"""Tests for the device.launch (app launcher) RPC surface."""

from __future__ import annotations

import asyncio
from pathlib import Path
from unittest.mock import patch

import pytest

from connector.local.app_launcher import (
    AppLaunchError,
    AppLauncher,
    SUPPORTED_APPS,
    _dsh_bridge_endpoint,
    discover_dsh,
    discover_zcode,
)
from connector.local.ops import LocalOps
from connector.server.local_rpc import LocalRpcHandler


def _local_rpc_handler(apps: AppLauncher) -> LocalRpcHandler:
    ops = LocalOps(files=object(), shell=object(), terminal=object(), apps=apps)  # type: ignore[arg-type]

    async def noop(_params: dict) -> dict:
        return {}

    return LocalRpcHandler(
        local_ops=ops,
        upload_prepared_download=noop,
        start_terminal_relay=noop,
        schedule_background=lambda _coro: None,
    )


def test_supported_apps_only_dsh_and_zcode() -> None:
    assert SUPPORTED_APPS == frozenset({"dsh", "zcode"})


def test_discover_dsh_uses_env_first() -> None:
    with patch.dict("os.environ", {"DSH_EXE": "/usr/local/dsh/bin/dsh"}):
        with patch("pathlib.Path.is_file", return_value=True):
            assert discover_dsh() == ("env", "/usr/local/dsh/bin/dsh")


def test_discover_dsh_missing_returns_none() -> None:
    with patch.dict("os.environ", {}, clear=True):
        with patch("pathlib.Path.is_file", return_value=False):
            with patch("shutil.which", return_value=None):
                assert discover_dsh() is None


def test_discover_zcode_uses_env_first() -> None:
    with patch.dict("os.environ", {"ZCODE_EXE": r"C:\ZCode\ZCode.exe"}):
        with patch("pathlib.Path.is_file", return_value=True):
            assert discover_zcode() == ("env", r"C:\ZCode\ZCode.exe")


def test_discover_zcode_missing_returns_none() -> None:
    with patch.dict("os.environ", {}, clear=True):
        with patch(
            "connector.runtimes.zcode.provider_config.zcode_exe",
            return_value=None,
        ):
            with patch("pathlib.Path.is_file", return_value=False):
                with patch(
                    "connector.local.app_launcher._windows_app_paths",
                    return_value=[],
                ):
                    with patch("shutil.which", return_value=None):
                        assert discover_zcode() is None


def test_launch_rejects_unsupported_app() -> None:
    handler = _local_rpc_handler(AppLauncher())
    with pytest.raises(AppLaunchError, match="unsupported application"):
        asyncio.run(handler.dispatch("device.launch", {"app": "vim"}))


def test_launch_missing_app_reports_clear_error() -> None:
    handler = _local_rpc_handler(AppLauncher())
    with patch("connector.local.app_launcher.discover_dsh", return_value=None):
        with pytest.raises(AppLaunchError) as excinfo:
            asyncio.run(handler.dispatch("device.launch", {"app": "dsh"}))
    assert excinfo.value.code == "app_not_found"


def test_launch_already_running_short_circuits(tmp_path: Path) -> None:
    endpoint = _dsh_bridge_endpoint()
    record = {"version": 1, "host": "127.0.0.1", "port": 17891, "token": "t", "pid": 1234}
    with patch("connector.local.app_launcher._dsh_bridge_endpoint", return_value=tmp_path / "endpoint.json"):
        (tmp_path / "endpoint.json").write_text(
            __import__("json").dumps(record), encoding="utf-8"
        )
        with patch("os.name", "nt"):
            result = asyncio.run(
                _local_rpc_handler(AppLauncher()).dispatch(
                    "device.launch", {"app": "dsh"}
                )
            )
    assert result["alreadyRunning"] is True
    assert result["launched"] is False


def test_launch_zcode_already_running_short_circuits() -> None:
    handler = _local_rpc_handler(AppLauncher())
    with patch(
        "connector.local.app_launcher._zcode_already_running",
        return_value=True,
    ):
        result = asyncio.run(
            handler.dispatch("device.launch", {"app": "zcode"})
        )
    assert result["alreadyRunning"] is True
    assert result["launched"] is False


def test_launch_actually_starts_app(tmp_path: Path) -> None:
    launcher = AppLauncher()
    launcher._targets["dsh"] = ("test", str(tmp_path / "dsh"))

    handler = _local_rpc_handler(launcher)
    with patch("connector.local.app_launcher._dsh_already_running", return_value=False):
        with patch(
            "connector.local.app_launcher.subprocess.Popen"
        ) as popen:
            proc = popen.return_value
            proc.pid = 4242
            result = asyncio.run(
                handler.dispatch("device.launch", {"app": "dsh"})
            )
    assert result["launched"] is True
    assert result["alreadyRunning"] is False
    assert result["path"] == str(tmp_path / "dsh")
    assert result["pid"] == 4242


def test_launch_detaches_on_windows(tmp_path: Path) -> None:
    launcher = AppLauncher()
    launcher._targets["dsh"] = ("test", str(tmp_path / "dsh"))

    handler = _local_rpc_handler(launcher)
    with patch("connector.local.app_launcher._dsh_already_running", return_value=False):
        with patch("connector.local.app_launcher.sys.platform", "win32"):
            with patch(
                "connector.local.app_launcher.subprocess.Popen"
            ) as popen:
                proc = popen.return_value
                proc.pid = 5
                asyncio.run(handler.dispatch("device.launch", {"app": "dsh"}))
    kwargs = popen.call_args.kwargs
    assert kwargs.get("stdin") is not None  # DEVNULL
    assert kwargs.get("creationflags", 0) != 0
