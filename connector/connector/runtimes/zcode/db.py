from __future__ import annotations

import sqlite3
from collections.abc import Iterator
from contextlib import contextmanager
from pathlib import Path
from typing import Any

from connector.runtime_protocol import RuntimeUnavailableError, RuntimeUpstreamError


class ZcodeDb:
    """Access to the ZCode CLI database.

    Reads use read-only connections per operation so WAL checkpointing by the
    desktop never blocks them. Writes (input delivery) go through a separate
    writable connection with a busy timeout, so an active desktop writer and
    our one-shot ``session_input`` insert serialize instead of failing.
    """

    _REQUIRED_TABLES = frozenset({"session", "message", "part", "session_input"})

    def __init__(self, path: str | Path) -> None:
        self._path = str(Path(path).expanduser())
        self._checked = False

    @property
    def path(self) -> str:
        return self._path

    def check_schema(self) -> None:
        """Raise when the database shape is not one we can project from.

        Columns are accessed defensively by the projection modules, so a
        missing optional column degrades instead of crashing; a missing core
        table is fatal.
        """
        if self._checked:
            return
        try:
            with self._read() as connection:
                tables = {
                    row[0]
                    for row in connection.execute(
                        "select name from sqlite_master where type='table'"
                    )
                }
        except sqlite3.Error as exc:
            raise RuntimeUnavailableError("ZCode 数据库读取失败") from exc
        missing = self._REQUIRED_TABLES - tables
        if missing:
            raise RuntimeUpstreamError(
                f"ZCode 数据库缺少表: {', '.join(sorted(missing))}"
            )
        self._checked = True

    @contextmanager
    def _read(self) -> Iterator[sqlite3.Connection]:
        connection = sqlite3.connect(
            f"file:{self._path}?mode=ro", uri=True, timeout=15
        )
        try:
            connection.execute("PRAGMA busy_timeout = 15000")
            yield connection
        finally:
            connection.close()

    @contextmanager
    def _write(self) -> Iterator[sqlite3.Connection]:
        connection = sqlite3.connect(self._path, timeout=15)
        try:
            connection.execute("PRAGMA busy_timeout = 15000")
            yield connection
            connection.commit()
        except BaseException:
            connection.rollback()
            raise
        finally:
            connection.close()

    def query(self, sql: str, params: tuple[Any, ...] = ()) -> list[dict[str, Any]]:
        with self._read() as connection:
            connection.row_factory = sqlite3.Row
            rows = connection.execute(sql, params).fetchall()
            return [dict(row) for row in rows]

    def query_one(
        self, sql: str, params: tuple[Any, ...] = ()
    ) -> dict[str, Any] | None:
        rows = self.query(sql, params)
        return rows[0] if rows else None

    def execute_write(self, sql: str, params: tuple[Any, ...] = ()) -> None:
        with self._write() as connection:
            connection.execute(sql, params)