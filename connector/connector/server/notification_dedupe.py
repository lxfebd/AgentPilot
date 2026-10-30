from __future__ import annotations

import hashlib
import json
from typing import Any

# Notifications that carry a *full replacement* snapshot: the backend only ever
# needs the newest value, so a byte-identical repeat adds nothing and only costs
# websocket bandwidth and backend merge work.
DEDUPE_METHODS = frozenset(
    {
        "runtime.capability.updated",
        "session.capability.updated",
        "runtime.catalog.updated",
        "catalog.model.update",
        "catalog.permission.update",
        "protocol.capabilitiesUpdated",
        "session.state.updated",
        "session.source.updated",
        "connector.preferencesUpdated",
    }
)


def _dedupe_key(method: str, params: dict[str, Any]) -> str | None:
    if method not in DEDUPE_METHODS:
        return None
    scope = params.get("sessionId") or params.get("externalSessionId") or ""
    runtime_id = params.get("runtimeId") or ""
    catalog_type = params.get("catalogType") or ""
    return f"{method}|{scope}|{runtime_id}|{catalog_type}"


def _fingerprint(params: dict[str, Any]) -> str:
    try:
        encoded = json.dumps(params, sort_keys=True, default=str)
    except (TypeError, ValueError):
        return ""
    return hashlib.sha1(encoded.encode("utf-8")).hexdigest()[:16]


class NotificationFingerprintGate:
    """Drop repeated full-replacement notifications that carry no new state.

    A provider may re-publish the same capability or catalog snapshot many times
    a second (the DSH runtime re-emits one snapshot per known session on every
    capability change). Forwarding every copy saturates the single backend
    websocket, starves the keepalive ping and makes the connection drop, so only
    a payload that differs from the last one sent for the same scope goes out.
    """

    def __init__(self) -> None:
        self._fingerprints: dict[str, str] = {}

    def reset(self) -> None:
        """Forget what was sent; called after the websocket reconnects."""
        self._fingerprints.clear()

    def should_send(self, method: str, params: dict[str, Any]) -> bool:
        key = _dedupe_key(method, params)
        if key is None:
            return True
        fingerprint = _fingerprint(params)
        if not fingerprint:
            return True
        if self._fingerprints.get(key) == fingerprint:
            return False
        self._fingerprints[key] = fingerprint
        return True
