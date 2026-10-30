from __future__ import annotations

import asyncio
from typing import Any

from connector.server.client import BackendRpcClient
from connector.server.notification_dedupe import (
    NotificationFingerprintGate,
    _dedupe_key,
    _fingerprint,
)


def _capability(session_id: str, *, revision: int = 1) -> dict[str, Any]:
    return {
        "sessionId": session_id,
        "metadata": {"runtimeId": "rti_test"},
        "protocolCapabilities": {"revision": revision, "entries": []},
    }


def test_identical_capability_snapshot_is_suppressed() -> None:
    gate = NotificationFingerprintGate()
    params = _capability("ses_1")

    assert gate.should_send("runtime.capability.updated", params) is True
    assert gate.should_send("runtime.capability.updated", params) is False
    # A dict with the same content in a different key order is the same state.
    assert gate.should_send("runtime.capability.updated", dict(reversed(list(params.items())))) is False


def test_changed_capability_snapshot_is_sent() -> None:
    gate = NotificationFingerprintGate()
    assert gate.should_send("runtime.capability.updated", _capability("ses_1", revision=1)) is True
    assert gate.should_send("runtime.capability.updated", _capability("ses_1", revision=2)) is True


def test_sessions_are_tracked_independently() -> None:
    gate = NotificationFingerprintGate()
    assert gate.should_send("session.capability.updated", _capability("ses_1")) is True
    assert gate.should_send("session.capability.updated", _capability("ses_2")) is True
    assert gate.should_send("session.capability.updated", _capability("ses_1")) is False
    assert gate.should_send("session.capability.updated", _capability("ses_2")) is False


def test_catalog_snapshots_are_keyed_by_catalog_type() -> None:
    model = {"runtimeId": "rti_test", "catalogType": "model", "items": []}
    permission = {"runtimeId": "rti_test", "catalogType": "permission", "items": []}

    assert _dedupe_key("runtime.catalog.updated", model) != _dedupe_key(
        "runtime.catalog.updated", permission
    )

    gate = NotificationFingerprintGate()
    assert gate.should_send("runtime.catalog.updated", model) is True
    assert gate.should_send("runtime.catalog.updated", permission) is True
    assert gate.should_send("runtime.catalog.updated", model) is False


def test_other_notifications_are_never_deduped() -> None:
    gate = NotificationFingerprintGate()
    params = {"sessionId": "ses_1", "text": "hello"}

    assert gate.should_send("session.notice", params) is True
    assert gate.should_send("session.notice", params) is True
    assert gate.should_send("timeline.itemUpsert", params) is True
    assert gate.should_send("timeline.itemUpsert", params) is True
    assert _dedupe_key("session.notice", params) is None


def test_reset_reallows_a_snapshot_after_reconnect() -> None:
    gate = NotificationFingerprintGate()
    params = _capability("ses_1")

    assert gate.should_send("runtime.capability.updated", params) is True
    assert gate.should_send("runtime.capability.updated", params) is False
    gate.reset()
    # A reconnected backend may have lost its view, so the snapshot goes out again.
    assert gate.should_send("runtime.capability.updated", params) is True


def test_unserializable_payload_is_always_sent() -> None:
    gate = NotificationFingerprintGate()

    class Opaque:
        def __repr__(self) -> str:
            return "<opaque>"

    assert _fingerprint({"sessionId": "ses_1", "value": Opaque()})
    assert gate.should_send("session.state.updated", {"sessionId": "ses_1", "value": Opaque()}) is True


class _FakeRpcChannel:
    connected = True

    def __init__(self) -> None:
        self.sent: list[tuple[str, dict[str, Any]]] = []

    async def send_notification(self, method: str, params: dict[str, Any]) -> None:
        self.sent.append((method, params))


class _FakeIngestClient:
    has_pending = False

    async def enqueue(self, method: str, params: dict[str, Any]) -> None:
        raise AssertionError("a deduped snapshot must not fall back to ingest")


def _send_path_client() -> BackendRpcClient:
    client = object.__new__(BackendRpcClient)
    client._rpc = _FakeRpcChannel()  # type: ignore[assignment]
    client._ingest = _FakeIngestClient()  # type: ignore[assignment]
    client._notification_dedupe = NotificationFingerprintGate()
    return client


def test_client_send_path_drops_repeated_capability_snapshot() -> None:
    client = _send_path_client()
    params = _capability("ses_1")

    asyncio.run(client._send_backend_notification_now("runtime.capability.updated", params))
    asyncio.run(client._send_backend_notification_now("runtime.capability.updated", params))
    asyncio.run(client._send_backend_notification_now("runtime.capability.updated", _capability("ses_2")))

    sent = client._rpc.sent
    assert [method for method, _ in sent] == [
        "runtime.capability.updated",
        "runtime.capability.updated",
    ]
    assert [params["sessionId"] for _, params in sent] == ["ses_1", "ses_2"]


def test_client_send_path_still_forwards_repeated_notices() -> None:
    client = _send_path_client()
    params = {"sessionId": "ses_1", "text": "hello"}

    asyncio.run(client._send_backend_notification_now("session.notice", params))
    asyncio.run(client._send_backend_notification_now("session.notice", params))

    assert len(client._rpc.sent) == 2
