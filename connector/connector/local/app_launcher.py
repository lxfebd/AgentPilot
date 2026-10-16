"""Device-level application launching for the connector.

Only whitelisted applications can be launched; ``device.launch`` is a
device command, not a general shell. The supported targets are DeepSeek
Harness (DSH) and ZCode, discovered from well-known install locations so the
phone can start them even when they are not running.
"""

from __future__ import annotations

import os
import shutil
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from connector.launch import launch_target

SUPPORTED_APPS: frozenset[str] = frozenset({"dsh", "zcode"})


class AppLaunchError(RuntimeError):
    """A device.launch request could not be satisfied."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


@dataclass(frozen=True, slots=True)
class AppLaunchResult:
    launched: bool
    already_running: bool
    app: str
    path: str | None
    source: str | None
    pid: int | None
    reason: str | None = None

    def view(self) -> dict[str, Any]:
        return {
            "launched": self.launched,
            "alreadyRunning": self.already_running,
            "app": self.app,
            "path": self.path,
            "source": self.source,
            "pid": self.pid,
            "reason": self.reason,
        }


def _dsh_candidate_paths() -> list[tuple[str, str]]:
    """Candidate (source, raw path) pairs for the DSH executable, most
    specific first. Raw paths may contain environment variables."""
    candidates: list[tuple[str, str]] = []

    configured = os.environ.get("DSH_EXE")
    if configured:
        candidates.append(("env", configured))

    if sys.platform == "win32":
        local = os.environ.get("LOCALAPPDATA")
        if local:
            candidates.append(
                (
                    "common",
                    str(Path(local) / "Programs" / "DeepSeek Harness" / "DeepSeek Harness.exe"),
                )
            )
        # Registry App Paths: HKCU and HKLM mirror the standard per-user
        # Electron installer layout.
        registry_paths = _windows_app_paths("DeepSeek Harness.exe")
        candidates.extend(("registry", path) for path in registry_paths)
        resolved = shutil.which("dsh")
        if resolved:
            candidates.append(("path", resolved))
    else:
        for name in ("dsh", "DeepSeek Harness", "deepseek-harness"):
            resolved = shutil.which(name)
            if resolved:
                candidates.append(("path", resolved))
        applications = Path.home() / "Applications"
        app = applications / "DeepSeek Harness.app" / "Contents" / "MacOS"
        candidates.extend(
            [
                ("common", str(app / "DeepSeek Harness")),
                ("common", "/Applications/DeepSeek Harness.app/Contents/MacOS/DeepSeek Harness"),
            ]
        )
    return candidates


def _windows_app_paths(name: str) -> list[str]:
    """Resolve %PATH%-style executable locations registered under App Paths."""
    import winreg  # Windows only; imported lazily.

    roots = (winreg.HKEY_CURRENT_USER, winreg.HKEY_LOCAL_MACHINE)
    key_path = rf"Software\Microsoft\Windows\CurrentVersion\App Paths\{name}"
    found: list[str] = []
    for root in roots:
        try:
            with winreg.OpenKey(root, key_path) as key:
                value, _ = winreg.QueryValueEx(key, None)
            if isinstance(value, str) and value:
                found.append(value)
        except OSError:
            continue
    return found


def discover_dsh() -> tuple[str, str] | None:
    """Return the first existing DSH executable as (source, path)."""
    seen: set[str] = set()
    for source, raw in _dsh_candidate_paths():
        path = os.path.expandvars(os.path.expanduser(raw))
        if not path or path in seen:
            continue
        seen.add(path)
        if Path(path).is_file():
            return source, path
        if shutil.which(path) is not None:
            return source, path
    return None


def _dsh_bridge_endpoint() -> Path:
    from connector.runtimes.dsh import provider_config

    return provider_config.endpoint_path()


def _dsh_already_running() -> bool:
    """A live bridge endpoint means the DSH plugin is up, so DSH is running.

    The connector only learns about DSH through its bridge endpoint file; a
    running DSH without the plugin enabled would not be visible here, but in
    that case the user has chosen not to pair the phone with DSH at all.
    """
    endpoint = _dsh_bridge_endpoint()
    if not endpoint.is_file():
        return False
    try:
        raw = endpoint.read_text(encoding="utf-8")
    except OSError:
        return False
    try:
        import json

        record = json.loads(raw)
    except ValueError:
        return False
    if not isinstance(record, dict):
        return False
    pid = record.get("pid")
    if not isinstance(pid, int) or pid <= 0:
        return False
    if os.name != "nt":
        try:
            os.kill(pid, 0)
        except ProcessLookupError:
            return False
        except PermissionError:
            pass
        except OSError:
            return False
    return True


def discover_zcode() -> tuple[str, str] | None:
    """Return the first existing ZCode executable as (source, path)."""
    configured = os.environ.get("ZCODE_EXE")
    if configured:
        path = os.path.expandvars(os.path.expanduser(configured))
        if path and Path(path).is_file():
            return "env", path

    from connector.runtimes.zcode import provider_config

    well_known = provider_config.zcode_exe({})
    if well_known is not None:
        return "common", str(well_known)

    if sys.platform == "win32":
        for path in _windows_app_paths("ZCode.exe"):
            if path and Path(path).is_file():
                return "registry", path
        resolved = shutil.which("zcode")
        if resolved:
            return "path", resolved
    else:
        resolved = shutil.which("zcode")
        if resolved:
            return "path", resolved
        app = Path.home() / "Applications" / "ZCode.app" / "Contents" / "MacOS"
        candidates = [
            str(app / "ZCode"),
            "/Applications/ZCode.app/Contents/MacOS/ZCode",
        ]
        for path in candidates:
            if Path(path).is_file():
                return "common", path
    return None


def _zcode_already_running() -> bool:
    """True when a ZCode desktop process is currently running."""
    from connector.runtimes.zcode.discovery import zcode_is_running

    return zcode_is_running()


class AppLauncher:
    """Launch whitelisted applications on this machine."""

    def __init__(self) -> None:
        self._targets: dict[str, tuple[str, str]] = {}

    def supported(self, app: str) -> bool:
        return app in SUPPORTED_APPS

    def resolve(self, app: str) -> tuple[str, str]:
        """Return (source, path) for an app, raising AppLaunchError when missing."""
        if app not in SUPPORTED_APPS:
            raise AppLaunchError("unsupported_app", f"unsupported application: {app}")
        cached = self._targets.get(app)
        if cached is not None:
            return cached
        discovered: tuple[str, str] | None
        if app == "dsh":
            discovered = discover_dsh()
        elif app == "zcode":
            discovered = discover_zcode()
        else:  # pragma: no cover - guarded by SUPPORTED_APPS
            discovered = None
        if discovered is None:
            raise AppLaunchError(
                "app_not_found",
                f"未找到 {app} 的安装位置，请先在本机安装并启动一次。",
            )
        self._targets[app] = discovered
        return discovered

    async def launch(self, app: str) -> dict[str, Any]:
        """Launch ``app`` (whitelisted) unless it is already running."""
        if app not in SUPPORTED_APPS:
            raise AppLaunchError("unsupported_app", f"unsupported application: {app}")

        if app == "dsh" and _dsh_already_running():
            return AppLaunchResult(
                launched=False,
                already_running=True,
                app=app,
                path=str(_dsh_bridge_endpoint()),
                source="bridge",
                pid=None,
                reason="DSH 已在运行",
            ).view()

        if app == "zcode" and _zcode_already_running():
            return AppLaunchResult(
                launched=False,
                already_running=True,
                app=app,
                path=None,
                source="process",
                pid=None,
                reason="ZCode 已在运行",
            ).view()

        source, path = self.resolve(app)
        target = launch_target(app, path)
        argv = target.command()

        if sys.platform == "win32":
            creationflags = (
                subprocess.CREATE_NEW_PROCESS_GROUP
                | getattr(subprocess, "DETACHED_PROCESS", 0)
            )
            process = subprocess.Popen(
                argv,
                close_fds=True,
                creationflags=creationflags,
                cwd=str(Path(path).parent),
                stdin=subprocess.DEVNULL,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
        else:
            process = subprocess.Popen(
                argv,
                cwd=str(Path(path).parent),
                stdin=subprocess.DEVNULL,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                start_new_session=True,
            )

        return AppLaunchResult(
            launched=True,
            already_running=False,
            app=app,
            path=path,
            source=source,
            pid=getattr(process, "pid", None),
        ).view()
