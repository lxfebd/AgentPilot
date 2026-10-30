from __future__ import annotations

import asyncio

import pytest

from connector.core.config import ConnectorConfig
from connector.server import client as client_module
from connector.server.client import BackendRpcClient


class _AbortConnectionError(Exception):
    """Raised by the fake transport so run_once() stops right after connect()."""


def test_backend_websocket_keepalive_tolerates_a_busy_event_loop(tmp_path, monkeypatch):
    """The connector shares one event loop with local runtime work.

    websockets defaults to ping_interval=20 / ping_timeout=20, so any 20 s
    stall of our own loop makes the client drop the backend link even though
    the server answered every ping. Keep the same ping cadence but allow a
    much longer pong wait.
    """
    captured: dict[str, object] = {}

    class _Context:
        async def __aenter__(self):
            raise _AbortConnectionError

        async def __aexit__(self, *args):
            return False

    def fake_connect(url, **kwargs):
        captured["url"] = url
        captured.update(kwargs)
        return _Context()

    async def authenticate(**_kwargs):
        return "fixture-access-token"

    client = BackendRpcClient(
        ConnectorConfig(
            "http://127.0.0.1:8000",
            "connector",
            "test-only",
            state_path=str(tmp_path / "sync.json"),
        ),
        agent_runtime_providers=(),
    )
    monkeypatch.setattr(client, "ensure_access_token", authenticate)
    monkeypatch.setattr(client_module.websockets, "connect", fake_connect)

    async def exercise():
        with pytest.raises(_AbortConnectionError):
            await client.run_once()

    asyncio.run(exercise())

    assert captured["ping_interval"] == 20.0
    assert captured["ping_timeout"] == 60.0
    # Two missed pongs must not be enough to tear the link down.
    assert captured["ping_timeout"] >= 3 * captured["ping_interval"]
