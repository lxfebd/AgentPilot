"""Tests for the ZCode session_input delivery mechanism."""

from __future__ import annotations

import json
import sqlite3
from pathlib import Path

import pytest

from connector.runtime_protocol import RuntimeUnavailableError
from connector.runtimes.zcode.db import ZcodeDb
from connector.runtimes.zcode.session_input import (
    deliver_send_text,
    interrupt_by_queuing_delivery,
    read_delivery_status,
    session_input_payload,
)


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
        "insert into session (id, task_type, time_created, time_updated) "
        "values (?, ?, ?, ?)",
        ("sess_abc", "interactive", 1000, 1000),
    )
    connection.commit()
    connection.close()


def test_session_input_payload_shape() -> None:
    payload = session_input_payload("hello")
    assert payload["text"] == "hello"
    assert payload["sourceCommandType"] == "sendText"
    intent = payload["intent"]
    assert intent["kind"] == "sendText"
    assert intent["requestedDelivery"] == "startNow"
    assert intent["admittedDelivery"] == "startNow"
    assert intent["admissionSeq"] == 0
    assert intent["sourceCommandId"]
    assert payload["conversationInputIntent"]["text"] == "hello"


def test_deliver_send_text_writes_row(tmp_path: Path) -> None:
    db_path = tmp_path / "db.sqlite"
    _create_fake_db(db_path)
    db = ZcodeDb(db_path)
    receipt = deliver_send_text(
        db, external_session_id="sess_abc", text="hello zcode"
    )
    assert receipt.status == "admitted"
    assert receipt.admitted_sequence == 0
    row = db.query_one(
        "select kind, delivery, payload, status from session_input where id = ?",
        (receipt.input_id,),
    )
    assert row["kind"] == "sendText"
    assert row["delivery"] == "startNow"
    assert row["status"] == "admitted"
    payload = json.loads(row["payload"])
    assert payload["text"] == "hello zcode"


def test_deliver_send_text_sequences_increment(tmp_path: Path) -> None:
    db_path = tmp_path / "db.sqlite"
    _create_fake_db(db_path)
    db = ZcodeDb(db_path)
    first = deliver_send_text(db, external_session_id="sess_abc", text="one")
    second = deliver_send_text(db, external_session_id="sess_abc", text="two")
    assert first.admitted_sequence == 0
    assert second.admitted_sequence == 1


def test_deliver_send_text_requires_existing_session(tmp_path: Path) -> None:
    db_path = tmp_path / "db.sqlite"
    _create_fake_db(db_path)
    db = ZcodeDb(db_path)
    with pytest.raises(RuntimeUnavailableError, match="不存在"):
        deliver_send_text(db, external_session_id="sess_missing", text="hi")


def test_read_delivery_status_roundtrip(tmp_path: Path) -> None:
    db_path = tmp_path / "db.sqlite"
    _create_fake_db(db_path)
    db = ZcodeDb(db_path)
    receipt = deliver_send_text(
        db, external_session_id="sess_abc", text="hello"
    )
    status = read_delivery_status(
        db, input_id=receipt.input_id, external_session_id="sess_abc"
    )
    assert status is not None
    assert status.status == "admitted"
    assert status.promoted_sequence is None


def test_read_delivery_status_reports_promoted(tmp_path: Path) -> None:
    db_path = tmp_path / "db.sqlite"
    _create_fake_db(db_path)
    db = ZcodeDb(db_path)
    receipt = deliver_send_text(
        db, external_session_id="sess_abc", text="hello"
    )
    db.execute_write(
        "update session_input set status = 'promoted', promoted_sequence = 7, "
        "promoted_message_id = 'msg_x' where id = ?",
        (receipt.input_id,),
    )
    status = read_delivery_status(
        db, input_id=receipt.input_id, external_session_id="sess_abc"
    )
    assert status.status == "promoted"
    assert status.promoted_sequence == 7
    assert status.promoted_message_id == "msg_x"


def test_interrupt_queues_empty_guide(tmp_path: Path) -> None:
    db_path = tmp_path / "db.sqlite"
    _create_fake_db(db_path)
    db = ZcodeDb(db_path)
    receipt = interrupt_by_queuing_delivery(
        db, external_session_id="sess_abc"
    )
    row = db.query_one(
        "select kind, delivery, payload from session_input where id = ?",
        (receipt.input_id,),
    )
    assert row["kind"] == "queue"
    assert row["delivery"] == "queue"
    payload = json.loads(row["payload"])
    assert payload["kind"] == "queue"


def test_deliver_rejects_empty_text(tmp_path: Path) -> None:
    db_path = tmp_path / "db.sqlite"
    _create_fake_db(db_path)
    db = ZcodeDb(db_path)
    with pytest.raises(ValueError, match="non-empty"):
        deliver_send_text(db, external_session_id="sess_abc", text="   ")
