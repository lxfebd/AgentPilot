from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Any

import psutil

from connector.runtimes.zcode import provider_config

ZCODE_PROCESS_NAMES = frozenset({"ZCode.exe", "zcode.exe"})


@dataclass(frozen=True, slots=True)
class ZcodeDiscovery:
    available: bool
    configured: bool
    database: Path | None = None
    exe: Path | None = None
    running: bool = False
    reason: str | None = None
    metadata: dict[str, Any] | None = None


def _static_metadata(values: dict[str, object]) -> dict[str, object]:
    return {
        "database": str(provider_config.zcode_db_path(values)),
        "storageMode": "zcode-native",
        "sameSessionWriterLimit": 1,
        "crossProcessWriterExclusion": False,
    }


async def discover(values: dict[str, object]) -> ZcodeDiscovery:
    """Report that this connector supports the ZCode runtime type.

    The device list only answers "which runtime types does this connector
    support", so discovery never touches the ZCode database. Whether the app
    and its database are actually reachable is a configuration/start concern
    and belongs to probe(); ``configured`` and ``database`` are only meaningful
    there.
    """
    return ZcodeDiscovery(
        True,
        True,
        None,
        reason=None,
        metadata=_static_metadata(values),
    )


async def probe(values: dict[str, object]) -> ZcodeDiscovery:
    """Check whether the ZCode database is reachable. Configuration/start only."""
    path = provider_config.zcode_db_path(values)
    if not path.is_file():
        return ZcodeDiscovery(
            False,
            False,
            None,
            reason=f"ZCode 数据库不存在：{path}。请先启动 ZCode 桌面应用。",
        )
    try:
        # Opening read-only both validates accessibility and avoids creating
        # files. WAL and SHM sidecars are expected alongside a live database.
        import sqlite3

        connection = sqlite3.connect(f"file:{path.as_posix()}?mode=ro", uri=True)
        try:
            connection.execute("select 1 from session limit 1").fetchone()
        finally:
            connection.close()
    except (OSError, ValueError, sqlite3.Error):
        return ZcodeDiscovery(
            False,
            False,
            None,
            reason="ZCode 数据库不可读，连接失败。",
        )
    return ZcodeDiscovery(
        True,
        True,
        path,
        exe=provider_config.zcode_exe(values),
        running=zcode_is_running(),
        metadata=_static_metadata(values),
    )


def zcode_is_running() -> bool:
    """True when a ZCode desktop process is currently running."""
    for process in psutil.process_iter(["name"]):
        try:
            if process.info.get("name") in ZCODE_PROCESS_NAMES:
                return True
        except (psutil.NoSuchProcess, psutil.AccessDenied):
            continue
    return False