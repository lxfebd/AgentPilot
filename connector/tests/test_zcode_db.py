"""Tests for the ZCode database adapter, session projection, and timeline."""

from __future__ import annotations

import json
import sqlite3
from pathlib import Path

import pytest

from connector.runtime_protocol import RuntimeTimelineSnapshot
from connector.runtimes.session_identity import stable_runtime_session_id
from connector.runtimes.zcode.db import ZcodeDb
from connector.runtimes.zcode.sessions import (
    list_complete_session_inventory,
    list_interactive_sessions,
    session_meta_from_row,
)
from connector.runtimes.zcode.timeline import build_timeline_snapshot


def _create_fake_db(path: Path) -> None:
    connection = sqlite3.connect(path)
    connection.executescript(
        """
        create table session (
            id text primary key,
            project_id text,
            directory text,
            path text,
            title text,
            task_type text,
            time_created integer,
            time_updated integer,
            time_archived integer
        );
        create table message (
            id text primary key,
            session_id text,
            data text,
            sequence integer,
            time_created integer,
            time_updated integer
        );
        create table part (
            id text primary key,
            message_id text,
            session_id text,
            data text,
            sequence integer
        );
        create table session_input (
            id text primary key,
            session_id text,
            kind text,
            delivery text,
            payload text,
            admitted_sequence integer,
            promoted_sequence integer,
            promoted_message_id text,
            status text,
            time_created integer,
            time_updated integer
        );
        """
    )
    connection.execute(
        "insert into session (id, project_id, directory, path, title, task_type, "
        "time_created, time_updated, time_archived) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        (
            "sess_abc",
            "proj_1",
            "/work/proj1",
            "/work/proj1",
            "Main chat",
            "interactive",
            1000,
            2000,
            None,
        ),
    )
    connection.execute(
        "insert into session (id, project_id, directory, path, title, task_type, "
        "time_created, time_updated, time_archived) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        (
            "sess_worktree",
            "proj_1",
            "/work/proj1",
            "/work/proj1/.agent",
            "subagent",
            "worktree",
            1000,
            1500,
            None,
        ),
    )
    connection.execute(
        "insert into message (id, session_id, data, sequence, time_created, time_updated) "
        "values (?, ?, ?, ?, ?, ?)",
        (
            "msg_1",
            "sess_abc",
            json.dumps(
                {
                    "role": "user",
                    "metadata": {
                        "conversationInputIntent": {"text": "list the files"},
                    },
                }
            ),
            1,
            1000,
            1100,
        ),
    )
    connection.execute(
        "insert into message (id, session_id, data, sequence, time_created, time_updated) "
        "values (?, ?, ?, ?, ?, ?)",
        (
            "msg_2",
            "sess_abc",
            json.dumps({"role": "assistant"}),
            2,
            1100,
            1200,
        ),
    )
    connection.execute(
        "insert into part (id, message_id, session_id, data, sequence) values (?, ?, ?, ?, ?)",
        (
            "part_text",
            "msg_2",
            "sess_abc",
            json.dumps({"type": "text", "text": "hello from zcode"}),
            1,
        ),
    )
    connection.execute(
        "insert into part (id, message_id, session_id, data, sequence) values (?, ?, ?, ?, ?)",
        (
            "part_tool",
            "msg_2",
            "sess_abc",
            json.dumps(
                {
                    "type": "tool",
                    "tool": "bash",
                    "state": {
                        "input": {"command": "ls"},
                        "output": "\x1b[32mok\x1b[0m",
                        "status": "completed",
                        "exitCode": 0,
                    },
                }
            ),
            2,
        ),
    )
    connection.commit()
    connection.close()


def test_zcode_db_reads_fake_database(tmp_path: Path) -> None:
    db_path = tmp_path / "db.sqlite"
    _create_fake_db(db_path)
    db = ZcodeDb(db_path)
    db.check_schema()
    rows = db.query("select count(*) as n from session")
    assert rows[0]["n"] == 2


def test_zcode_db_missing_core_table_raises(tmp_path: Path) -> None:
    db_path = tmp_path / "db.sqlite"
    connection = sqlite3.connect(db_path)
    connection.execute("create table session (id text)")
    connection.commit()
    connection.close()
    db = ZcodeDb(db_path)
    with pytest.raises(Exception, match="缺少表"):
        db.check_schema()


def test_sessions_filter_interactive_only(tmp_path: Path) -> None:
    db_path = tmp_path / "db.sqlite"
    _create_fake_db(db_path)
    db = ZcodeDb(db_path)
    metas, cursor = list_interactive_sessions(db, connector_id="conn_1", runtime_id=None)
    assert len(metas) == 1
    assert metas[0].external_session_id == "sess_abc"
    assert metas[0].runtime == "zcode"
    assert metas[0].session_id == stable_runtime_session_id(
        "conn_1", "zcode", "sess_abc"
    )
    assert cursor is None


def test_sessions_inventory_includes_runtime_id(tmp_path: Path) -> None:
    db_path = tmp_path / "db.sqlite"
    _create_fake_db(db_path)
    db = ZcodeDb(db_path)
    metas = list_complete_session_inventory(
        db, connector_id="conn_1", runtime_id="rti_zcode_1"
    )
    assert len(metas) == 1
    assert metas[0].runtime_id == "rti_zcode_1"


def test_session_meta_from_row_defaults(tmp_path: Path) -> None:
    meta = session_meta_from_row(
        {
            "id": "sess_x",
            "project_id": None,
            "directory": None,
            "path": None,
            "title": None,
            "task_type": "interactive",
            "time_created": 5,
            "time_updated": None,
            "time_archived": None,
        },
        runtime_id=None,
        connector_id="conn_1",
    )
    assert meta.title is None
    assert meta.cwd is None
    assert meta.ordering_time == "5"


def test_timeline_projects_messages_and_parts(tmp_path: Path) -> None:
    db_path = tmp_path / "db.sqlite"
    _create_fake_db(db_path)
    db = ZcodeDb(db_path)
    snapshot = build_timeline_snapshot(
        db,
        external_session_id="sess_abc",
        connector_id="conn_1",
        runtime_id=None,
    )
    assert isinstance(snapshot, RuntimeTimelineSnapshot)
    assert snapshot.external_session_id == "sess_abc"
    assert snapshot.complete is True
    assert snapshot.metadata["watermark"] == 2

    items = snapshot.items
    assert len(items) >= 3
    kinds = [(item.type, item.role) for item in items]
    assert ("message", "user") in kinds
    assert ("message", "assistant") in kinds
    assert ("tool", "tool") in kinds

    tool = next(item for item in items if item.type == "tool")
    assert tool.status == "done"
    assert tool.content["command"] == "ls"
    # ANSI escapes must have been stripped from the tool output.
    assert "\x1b" not in tool.content["output"]
    assert tool.content["output"] == "ok"


def test_timeline_empty_session_is_complete(tmp_path: Path) -> None:
    db_path = tmp_path / "db.sqlite"
    _create_fake_db(db_path)
    db = ZcodeDb(db_path)
    snapshot = build_timeline_snapshot(
        db,
        external_session_id="sess_none",
        connector_id="conn_1",
        runtime_id=None,
    )
    assert snapshot.items == ()
    assert snapshot.complete is True


def test_db_write_roundtrip(tmp_path: Path) -> None:
    db_path = tmp_path / "db.sqlite"
    _create_fake_db(db_path)
    db = ZcodeDb(db_path)
    db.execute_write(
        "insert into session_input (id, session_id, kind, delivery, payload, "
        "admitted_sequence, status, time_created, time_updated) "
        "values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ("queue_t1", "sess_abc", "sendText", "startNow", "{}", 0, "admitted", 1, 1),
    )
    row = db.query_one(
        "select id, status from session_input where id = ?", ("queue_t1",)
    )
    assert row is not None
    assert row["id"] == "queue_t1"
    assert row["status"] == "admitted"
