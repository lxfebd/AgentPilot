from __future__ import annotations

import asyncio
import json

import httpx
import pytest

from connector.server.errors import ConnectorNetworkError
from connector.server.ingest import ConnectorIngestClient
from connector.server.ingest_dedupe import IngestFingerprintGate

OK_BODY = {"accepted": 0, "rejected": []}

def _snapshot(title: str = "title") -> list[dict]:
    return [
        {"method": "session.meta.upsert", "params": {"sessionId": "s1", "title": title}},
        {
            "method": "timeline.sync",
            "params": {
                "sessionId": "s1",
                "complete": True,
                "items": [{"id": "i1", "text": "hello"}],
            },
        },
        {
            "method": "session.state.updated",
            "params": {"sessionId": "s1", "status": "running"},
        },
    ]

def _item(item_id: str, text: str) -> dict:
    return {
        "method": "timeline.itemUpsert",
        "params": {"sessionId": "s1", "item": {"id": item_id, "text": text}},
    }

def test_repeated_identical_snapshot_is_sent_once():
    async def run():
        batches: list[list[dict]] = []

        async def token(force: bool) -> str:
            return "token"

        async def transport(request):
            batch = json.loads(request.content)["notifications"]
            batches.append(batch)
            return httpx.Response(200, json={"accepted": len(batch), "rejected": []})

        async with httpx.AsyncClient(transport=httpx.MockTransport(transport)) as http:
            ingest = ConnectorIngestClient(
                "https://server.test", token, lambda: http, lambda timeout: http
            )
            await ingest.ingest_notifications(_snapshot())
            await ingest.ingest_notifications(_snapshot())
            await ingest.ingest_notifications(_snapshot())
            assert len(batches) == 1
            assert [row["method"] for row in batches[0]] == [
                "session.meta.upsert",
                "timeline.sync",
                "session.state.updated",
            ]
            assert not ingest.has_pending

    asyncio.run(run())

def test_only_changed_notifications_are_sent_again():
    async def run():
        batches: list[list[dict]] = []

        async def token(force: bool) -> str:
            return "token"

        async def transport(request):
            batch = json.loads(request.content)["notifications"]
            batches.append(batch)
            return httpx.Response(200, json={"accepted": len(batch), "rejected": []})

        async with httpx.AsyncClient(transport=httpx.MockTransport(transport)) as http:
            ingest = ConnectorIngestClient(
                "https://server.test", token, lambda: http, lambda timeout: http
            )
            await ingest.ingest_notifications(_snapshot())
            await ingest.ingest_notifications(_snapshot(title="renamed"))
            assert len(batches) == 2
            assert [row["method"] for row in batches[1]] == ["session.meta.upsert"]
            assert batches[1][0]["params"]["title"] == "renamed"
            # The unchanged timeline.sync never goes out a second time.
            assert [row["method"] for row in batches[0]].count("timeline.sync") == 1

    asyncio.run(run())

def test_failed_batch_is_not_remembered_and_is_sent_again():
    async def run():
        statuses = [503, 200]
        batches: list[list[dict]] = []

        async def token(force: bool) -> str:
            return "token"

        async def transport(request):
            batch = json.loads(request.content)["notifications"]
            batches.append(batch)
            return httpx.Response(statuses.pop(0), json=OK_BODY)

        async with httpx.AsyncClient(transport=httpx.MockTransport(transport)) as http:
            ingest = ConnectorIngestClient(
                "https://server.test", token, lambda: http, lambda timeout: http
            )
            with pytest.raises(ConnectorNetworkError):
                await ingest.ingest_notifications(_snapshot())
            await ingest.ingest_notifications(_snapshot())
            assert len(batches) == 2
            # A transient failure must not swallow the retry even when the
            # payload is byte-identical to the failed attempt.
            assert batches[0] == batches[1]

    asyncio.run(run())

def test_item_upserts_are_deduped_per_item():
    async def run():
        batches: list[list[dict]] = []

        async def token(force: bool) -> str:
            return "token"

        async def transport(request):
            batch = json.loads(request.content)["notifications"]
            batches.append(batch)
            return httpx.Response(200, json={"accepted": len(batch), "rejected": []})

        async with httpx.AsyncClient(transport=httpx.MockTransport(transport)) as http:
            ingest = ConnectorIngestClient(
                "https://server.test", token, lambda: http, lambda timeout: http
            )
            await ingest.ingest_notifications([_item("i1", "a"), _item("i2", "b")])
            await ingest.ingest_notifications([_item("i1", "a"), _item("i2", "b")])
            assert len(batches) == 1
            await ingest.ingest_notifications([_item("i1", "changed")])
            assert len(batches) == 2
            assert batches[1][0]["params"]["item"]["text"] == "changed"

    asyncio.run(run())

def test_event_notifications_are_never_dropped():
    async def run():
        batches: list[list[dict]] = []

        async def token(force: bool) -> str:
            return "token"

        async def transport(request):
            batch = json.loads(request.content)["notifications"]
            batches.append(batch)
            return httpx.Response(200, json={"accepted": len(batch), "rejected": []})

        approval = {"method": "approval.request", "params": {"sessionId": "s1"}}
        async with httpx.AsyncClient(transport=httpx.MockTransport(transport)) as http:
            ingest = ConnectorIngestClient(
                "https://server.test", token, lambda: http, lambda timeout: http
            )
            await ingest.ingest_notifications([approval])
            await ingest.ingest_notifications([approval])
            assert len(batches) == 2

    asyncio.run(run())

def test_unscoped_notifications_are_not_deduped():
    async def run():
        batches: list[list[dict]] = []

        async def token(force: bool) -> str:
            return "token"

        async def transport(request):
            batch = json.loads(request.content)["notifications"]
            batches.append(batch)
            return httpx.Response(200, json={"accepted": len(batch), "rejected": []})

        async with httpx.AsyncClient(transport=httpx.MockTransport(transport)) as http:
            ingest = ConnectorIngestClient(
                "https://server.test", token, lambda: http, lambda timeout: http
            )
            unscoped = {"method": "session.state.updated", "params": {"status": "idle"}}
            await ingest.ingest_notifications([unscoped])
            await ingest.ingest_notifications([unscoped])
            assert len(batches) == 2

    asyncio.run(run())

def test_gate_evicts_old_fingerprints():
    gate = IngestFingerprintGate(max_fingerprints=1)
    first = {"method": "session.meta.upsert", "params": {"sessionId": "s1", "title": "a"}}
    second = {"method": "session.meta.upsert", "params": {"sessionId": "s2", "title": "a"}}
    assert gate.filter([first]) == [first]
    gate.remember([first])
    assert gate.filter([first]) == []
    assert gate.filter([second]) == [second]
    gate.remember([second])
    assert len(gate) == 1
    # Evicted scope is sent once more instead of being lost forever.
    assert gate.filter([first]) == [first]

def test_gate_keeps_first_of_identical_copies_inside_one_batch():
    gate = IngestFingerprintGate()
    first = {"method": "session.source.updated", "params": {"sessionId": "s1", "x": 1}}
    duplicate = {"method": "session.source.updated", "params": {"sessionId": "s1", "x": 1}}
    assert gate.filter([first, duplicate]) == [first]
