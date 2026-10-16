from __future__ import annotations

import json
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any

from connector.runtime_protocol import RuntimeUnavailableError
from connector.runtimes.zcode.db import ZcodeDb


@dataclass(frozen=True, slots=True)
class DeliveryReceipt:
    input_id: str
    session_id: str
    status: str
    admitted_sequence: int | None = None
    promoted_sequence: int | None = None
    promoted_message_id: str | None = None


def _utcnow_ms() -> int:
    return int(datetime.now(UTC).timestamp() * 1000)


def session_input_payload(text: str) -> dict[str, Any]:
    """Build the sendText payload exactly as the ZCode desktop UI writes it."""
    source_command_id = uuid.uuid4().hex
    queue_item_id = f"queue_{uuid.uuid4().hex}"
    client_id = f"client-{uuid.uuid4().hex}"
    now = _utcnow_ms()
    intent = {
        "sourceCommandId": source_command_id,
        "queueItemId": queue_item_id,
        "clientId": client_id,
        "kind": "sendText",
        "admissionSeq": 0,
        "admittedAt": now,
        "requestedDelivery": "startNow",
        "admittedDelivery": "startNow",
        "attachmentRefs": [],
    }
    return {
        "text": text,
        "intent": intent,
        "conversationInputIntent": {
            "sourceCommandId": source_command_id,
            "queueItemId": queue_item_id,
            "clientId": client_id,
            "kind": "sendText",
            "text": text,
            "attachments": [],
            "delivery": {"requested": "startNow", "admitted": "startNow"},
            "order": {"admissionSeq": 0},
            "steer": {"state": "notRequested"},
            "dispatch": {"state": "admitted"},
            "admittedAt": now,
        },
        "attachments": [],
        "sourceCommandType": "sendText",
    }


def deliver_send_text(
    db: ZcodeDb,
    *,
    external_session_id: str,
    text: str,
) -> DeliveryReceipt:
    """Enqueue one user message into the ZCode session_input table.

    Delivery only makes sense into a session that the ZCode desktop knows
    about; the caller must have resolved ``external_session_id`` from an
    existing ZCode session row on the same connection.
    """
    if not text or not text.strip():
        raise ValueError("a non-empty message is required to deliver")
    session = db.query_one(
        "select id from session where id = ?", (external_session_id,)
    )
    if session is None:
        raise RuntimeUnavailableError(
            f"ZCode 会话 {external_session_id} 不存在，无法投递消息。"
        )
    admitted_sequence = _next_admitted_sequence(db, external_session_id)
    payload = json.dumps(
        session_input_payload(text), ensure_ascii=False, separators=(",", ":")
    )
    input_id = f"queue_{uuid.uuid4().hex}"
    now = _utcnow_ms()
    db.execute_write(
        "insert into session_input "
        "(id, session_id, kind, delivery, payload, admitted_sequence, status, "
        " time_created, time_updated) values (?, ?, ?, ?, ?, ?, 'admitted', ?, ?)",
        (input_id, external_session_id, "sendText", "startNow", payload,
         admitted_sequence, now, now),
    )
    return DeliveryReceipt(
        input_id=input_id,
        session_id=external_session_id,
        status="admitted",
        admitted_sequence=admitted_sequence,
    )


def _next_admitted_sequence(db: ZcodeDb, external_session_id: str) -> int:
    row = db.query_one(
        "select coalesce(max(admitted_sequence), -1) as next from session_input "
        "where session_id = ?",
        (external_session_id,),
    )
    value = row["next"] if row else -1
    return int(value) + 1


def read_delivery_status(
    db: ZcodeDb, *, input_id: str, external_session_id: str
) -> DeliveryReceipt | None:
    row = db.query_one(
        "select id, session_id, status, admitted_sequence, promoted_sequence, "
        "promoted_message_id from session_input where id = ? and session_id = ?",
        (input_id, external_session_id),
    )
    if row is None:
        return None
    return DeliveryReceipt(
        input_id=row["id"],
        session_id=row["session_id"],
        status=row["status"],
        admitted_sequence=row.get("admitted_sequence"),
        promoted_sequence=row.get("promoted_sequence"),
        promoted_message_id=row.get("promoted_message_id"),
    )


def interrupt_by_queuing_delivery(
    db: ZcodeDb,
    *,
    external_session_id: str,
) -> DeliveryReceipt:
    """Best-effort interrupt: queue an empty guide delivery.

    ZCode consumes ``queue`` deliveries as a signal to steer an in-flight
    session. This is a lighter-weight interrupt than killing the process and
    cannot cancel a finished turn.
    """
    session = db.query_one(
        "select id from session where id = ?", (external_session_id,)
    )
    if session is None:
        raise RuntimeUnavailableError(
            f"ZCode 会话 {external_session_id} 不存在，无法中断。"
        )
    admitted_sequence = _next_admitted_sequence(db, external_session_id)
    payload = json.dumps(
        {
            "text": "",
            "kind": "queue",
            "delivery": "queue",
        },
        ensure_ascii=False,
        separators=(",", ":"),
    )
    input_id = f"queue_{uuid.uuid4().hex}"
    now = _utcnow_ms()
    db.execute_write(
        "insert into session_input "
        "(id, session_id, kind, delivery, payload, admitted_sequence, status, "
        " time_created, time_updated) values (?, ?, ?, ?, ?, ?, 'admitted', ?, ?)",
        (input_id, external_session_id, "queue", "queue", payload,
         admitted_sequence, now, now),
    )
    return DeliveryReceipt(
        input_id=input_id,
        session_id=external_session_id,
        status="admitted",
        admitted_sequence=admitted_sequence,
    )


def perform_start_now_delivery_check(
    db: ZcodeDb,
    *,
    external_session_id: str,
    timeout_ms: int = 30_000,
) -> DeliveryReceipt:
    """Deliver a message and wait for the desktop consumer to promote it.

    This is the write-safety gate used by tests and the live integration
    check: it proves the desktop picked the row up, which is what the whole
    connectivity story depends on.
    """
    receipt = deliver_send_text(
        db, external_session_id=external_session_id, text="__agentpilot_probe__"
    )
    import time

    deadline = time.time() + timeout_ms / 1000
    while time.time() < deadline:
        current = read_delivery_status(
            db, input_id=receipt.input_id, external_session_id=external_session_id
        )
        if current is not None and current.status == "promoted":
            return current
        time.sleep(0.5)
    return receipt