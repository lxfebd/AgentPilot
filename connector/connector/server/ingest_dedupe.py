from __future__ import annotations

import hashlib
import json
from collections.abc import Iterable
from typing import Any

# Methods that carry a whole value the backend *replaces* (or upserts by id):
# re-sending a byte-identical payload cannot change backend state, so the HTTP
# ingest path drops it instead of paying for another request. Methods that
# carry events or user-visible transitions (approvals, turn boundaries,
# heartbeats, inventory scans) are intentionally absent: dropping those would
# lose state or add latency.
INGEST_DEDUPE_METHODS = frozenset(
    {
        "timeline.sync",
        "session.meta.upsert",
        "session.state.updated",
        "session.source.updated",
        "timeline.itemUpsert",
        "notice.upsert",
    }
)

# Bound the bookkeeping so a long-running connector with many sessions and
# timeline items cannot grow it without limit. Evicted entries simply allow one
# extra resend, which is the pre-dedupe behaviour.
MAX_FINGERPRINTS = 4096

def _scope(params: dict[str, Any]) -> str | None:
    scope = params.get("sessionId") or params.get("externalSessionId")
    if isinstance(scope, str) and scope:
        return scope
    return None

def _dedupe_key(method: str, params: dict[str, Any]) -> str | None:
    if method not in INGEST_DEDUPE_METHODS:
        return None
    scope = _scope(params)
    if scope is None:
        return None
    if method == "timeline.itemUpsert":
        item = params.get("item")
        item_id = item.get("id") if isinstance(item, dict) else None
        if not isinstance(item_id, str) or not item_id:
            return None
        return f"{method}|{scope}|{item_id}"
    if method == "notice.upsert":
        notice_id = params.get("noticeId")
        if not isinstance(notice_id, str) or not notice_id:
            return None
        return f"{method}|{scope}|{notice_id}"
    return f"{method}|{scope}"

def _fingerprint(params: dict[str, Any]) -> str:
    try:
        encoded = json.dumps(params, sort_keys=True, default=str)
    except (TypeError, ValueError):
        return ""
    return hashlib.sha1(encoded.encode("utf-8")).hexdigest()[:16]

class IngestFingerprintGate:
    """Drop full-replacement notifications the backend already has.

    A scanner runtime re-reads its local history and re-pushes the same session
    snapshot on every sync tick, and every WebSocket reconnect forces another
    full re-push. Those repeats are byte-identical to what the ingest endpoint
    already accepted, so they only cost upload bandwidth and backend merge work.
    Fingerprints are recorded only after the HTTP call succeeded; a failed batch
    keeps its original contents and is retried verbatim.
    """

    def __init__(self, max_fingerprints: int = MAX_FINGERPRINTS) -> None:
        self._fingerprints: dict[str, str] = {}
        self._max_fingerprints = max(1, max_fingerprints)

    def reset(self) -> None:
        self._fingerprints.clear()

    def __len__(self) -> int:
        return len(self._fingerprints)

    def filter(
        self, notifications: Iterable[dict[str, Any]]
    ) -> list[dict[str, Any]]:
        """Return the notifications worth sending, preserving input order."""
        kept: list[dict[str, Any]] = []
        seen_in_batch: set[tuple[str, str]] = set()
        for notification in notifications:
            method = notification.get("method")
            params = notification.get("params")
            if not isinstance(method, str) or not isinstance(params, dict):
                kept.append(notification)
                continue
            key = _dedupe_key(method, params)
            if key is None:
                kept.append(notification)
                continue
            fingerprint = _fingerprint(params)
            if not fingerprint:
                kept.append(notification)
                continue
            marker = (key, fingerprint)
            if marker in seen_in_batch:
                continue
            seen_in_batch.add(marker)
            if self._fingerprints.get(key) == fingerprint:
                continue
            kept.append(notification)
        return kept

    def remember(self, notifications: Iterable[dict[str, Any]]) -> None:
        """Record notifications that the backend acknowledged."""
        for notification in notifications:
            method = notification.get("method")
            params = notification.get("params")
            if not isinstance(method, str) or not isinstance(params, dict):
                continue
            key = _dedupe_key(method, params)
            if key is None:
                continue
            fingerprint = _fingerprint(params)
            if not fingerprint:
                continue
            # Re-insert so FIFO eviction always drops the least recently sent.
            self._fingerprints.pop(key, None)
            self._fingerprints[key] = fingerprint
            while len(self._fingerprints) > self._max_fingerprints:
                self._fingerprints.pop(next(iter(self._fingerprints)))
