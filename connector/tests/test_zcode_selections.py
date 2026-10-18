"""Tests for the ZCode model/permission selection persistence."""

from __future__ import annotations

import sqlite3
from pathlib import Path

import pytest

from connector.runtime_protocol import RuntimeInvalidRequestError
from connector.runtimes.zcode.db import ZcodeDb
from connector.runtimes.zcode.identity import (
    model_selection_id,
    permission_selection_id,
)
from connector.runtimes.zcode.selections import (
    apply_selections,
    read_current_selections,
)

_PROVIDER_ID = "c87cb8f0-6a37-403a-bd6f-15633d784751"


def _create_fake_db(path: Path) -> None:
    connection = sqlite3.connect(path)
    connection.executescript(
        """
        create table session (
            id text primary key,
            task_type text,
            time_created integer,
            time_updated integer
        );
        create table session_entry (
            id text primary key,
            session_id text,
            type text,
            time_created integer,
            time_updated integer,
            data text
        );
        """
    )
    connection.execute(
        "insert into session (id, task_type, time_created, time_updated) "
        "values ('sess_abc', 'interactive', 1000, 1000)"
    )
    connection.commit()
    connection.close()


def test_apply_model_selection_writes_entry(tmp_path: Path) -> None:
    db_path = tmp_path / "db.sqlite"
    _create_fake_db(db_path)
    db = ZcodeDb(db_path)
    selection_id = model_selection_id(
        _PROVIDER_ID, "deepseekv4-flash", "enabled"
    )

    outcome = apply_selections(
        db,
        external_session_id="sess_abc",
        selections={"model": selection_id},
    )

    assert "model" in outcome["selections"]
    row = db.query_one(
        "select id, type, data from session_entry "
        "where session_id = 'sess_abc'"
    )
    assert row is not None
    assert row["type"] == "runtime/model_selection"
    assert f'"providerId": "{_PROVIDER_ID}"' in row["data"]
    assert '"modelId": "deepseekv4-flash"' in row["data"]
    assert '"reasoningLevel": "enabled"' in row["data"]


def test_apply_model_selection_round_trip(tmp_path: Path) -> None:
    db_path = tmp_path / "db.sqlite"
    _create_fake_db(db_path)
    db = ZcodeDb(db_path)
    selection_id = model_selection_id(_PROVIDER_ID, "deepseekv4-flash", None)

    apply_selections(
        db,
        external_session_id="sess_abc",
        selections={"model": selection_id},
    )
    current = read_current_selections(db, external_session_id="sess_abc")

    assert current == {"model": selection_id}


def test_read_current_selections_missing_is_empty(tmp_path: Path) -> None:
    db_path = tmp_path / "db.sqlite"
    _create_fake_db(db_path)
    db = ZcodeDb(db_path)

    assert read_current_selections(db, external_session_id="sess_abc") == {}


def test_apply_rejects_unknown_scope(tmp_path: Path) -> None:
    db_path = tmp_path / "db.sqlite"
    _create_fake_db(db_path)
    db = ZcodeDb(db_path)

    with pytest.raises(RuntimeInvalidRequestError):
        apply_selections(
            db,
            external_session_id="sess_abc",
            selections={"bogus": "zcode:permission:whatever"},
        )


def test_apply_rejects_garbage_selection(tmp_path: Path) -> None:
    db_path = tmp_path / "db.sqlite"
    _create_fake_db(db_path)
    db = ZcodeDb(db_path)

    with pytest.raises(RuntimeInvalidRequestError):
        apply_selections(
            db,
            external_session_id="sess_abc",
            selections={"model": "zcode:model:not-base64!"},
        )


def test_apply_permission_selection(tmp_path: Path) -> None:
    db_path = tmp_path / "db.sqlite"
    _create_fake_db(db_path)
    db = ZcodeDb(db_path)
    selection_id = permission_selection_id("auto")

    apply_selections(
        db,
        external_session_id="sess_abc",
        selections={"permission": selection_id},
    )
    row = db.query_one(
        "select type, data from session_entry "
        "where session_id = 'sess_abc'"
    )
    assert row["type"] == "runtime/permission_selection"
    assert '"preset": "auto"' in row["data"]


def test_apply_model_selection_updates_existing(tmp_path: Path) -> None:
    db_path = tmp_path / "db.sqlite"
    _create_fake_db(db_path)
    db = ZcodeDb(db_path)
    first = model_selection_id(_PROVIDER_ID, "deepseekv4-flash", None)
    second = model_selection_id(_PROVIDER_ID, "eye", None)

    apply_selections(
        db,
        external_session_id="sess_abc",
        selections={"model": first},
    )
    apply_selections(
        db,
        external_session_id="sess_abc",
        selections={"model": second},
    )

    rows = db.query(
        "select data from session_entry where session_id = 'sess_abc'"
    )
    assert len(rows) == 1
    assert '"modelId": "eye"' in rows[0]["data"]
