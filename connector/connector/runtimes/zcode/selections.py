"""Persist ZCode model/permission selections.

Model selections ride the ``session_entry`` table exactly like the desktop's
composer: ``type='runtime/model_selection'`` with
``data={"modelSelection": {providerId, modelId, options}}``.
"""

from __future__ import annotations

import json
import time
from collections.abc import Mapping
from typing import Any

from connector.runtime_protocol import RuntimeInvalidRequestError
from connector.runtimes.zcode.db import ZcodeDb
from connector.runtimes.zcode.identity import (
    decode_model_selection_id,
    decode_permission_selection_id,
)

_SUPPORTED_SCOPES = frozenset({"model", "permission"})


def apply_selections(
    db: ZcodeDb,
    *,
    external_session_id: str,
    selections: Mapping[str, str | None],
) -> dict[str, Any]:
    """Write selection updates into the ZCode session_entry stream.

    Returns the resulting per-scope state that should be reported back as the
    runtime's current selections.
    """
    updated: dict[str, Any] = {}
    now_ms = int(time.time() * 1000)
    for scope, selection_id in selections.items():
        if scope not in _SUPPORTED_SCOPES:
            raise RuntimeInvalidRequestError(
                f"ZCode 不支持的 selection 作用域: {scope}"
            )
        if selection_id is None:
            continue
        if not selection_id:
            raise RuntimeInvalidRequestError(
                f"selection {scope} 不能为空"
            )
        if scope == "model":
            entry_id = _write_model_selection(
                db, external_session_id=external_session_id,
                selection_id=selection_id, now_ms=now_ms,
            )
        else:
            entry_id = _write_permission_selection(
                db, external_session_id=external_session_id,
                selection_id=selection_id, now_ms=now_ms,
            )
        updated[scope] = {"entryId": entry_id, "selectionId": selection_id}
    return {"selections": updated}


def read_current_selections(
    db: ZcodeDb,
    *,
    external_session_id: str,
) -> dict[str, str | None]:
    """Read the latest persisted model selection for a session.

    Scopes without a persisted entry are absent from the mapping, matching how
    other runtimes report "not selected yet".
    """
    row = db.query_one(
        "select data from session_entry "
        "where session_id = ? and type = 'runtime/model_selection' "
        "order by time_created desc limit 1",
        (external_session_id,),
    )
    if row is None or not isinstance(row.get("data"), str):
        return {}
    try:
        payload = json.loads(row["data"])
    except ValueError:
        return {}
    model_selection = payload.get("modelSelection")
    if not isinstance(model_selection, dict):
        return {}
    provider_id = model_selection.get("providerId")
    model_id = model_selection.get("modelId")
    if not isinstance(provider_id, str) or not isinstance(model_id, str):
        return {}
    options = model_selection.get("options")
    reasoning = isinstance(options, dict) and options.get("reasoningLevel")
    selection_id = model_selection_id_or_none(
        provider_id, model_id, reasoning if isinstance(reasoning, str) else None
    )
    return {"model": selection_id} if selection_id else {}


def model_selection_id_or_none(
    provider_id: str, model_id: str, reasoning: str | None
) -> str | None:
    from connector.runtimes.zcode.identity import model_selection_id

    try:
        return model_selection_id(provider_id, model_id, reasoning)
    except ValueError:
        return None


def _write_model_selection(
    db: ZcodeDb,
    *,
    external_session_id: str,
    selection_id: str,
    now_ms: int,
) -> str:
    try:
        provider_id, model_id, reasoning = decode_model_selection_id(
            selection_id
        )
    except ValueError as exc:
        raise RuntimeInvalidRequestError(str(exc)) from exc
    options: dict[str, Any] = {}
    if reasoning:
        options["reasoningLevel"] = reasoning
    data = {
        "modelSelection": {
            "providerId": provider_id,
            "modelId": model_id,
            "options": options,
        }
    }
    entry_id = f"{external_session_id}:runtime-model-selection"
    db.execute_write(
        "insert into session_entry "
        "(id, session_id, type, time_created, time_updated, data) "
        "values (?, ?, 'runtime/model_selection', ?, ?, ?) "
        "on conflict(id) do update set time_updated = excluded.time_updated, "
        "data = excluded.data",
        (entry_id, external_session_id, now_ms, now_ms,
         json.dumps(data, ensure_ascii=False)),
    )
    return entry_id


def _write_permission_selection(
    db: ZcodeDb,
    *,
    external_session_id: str,
    selection_id: str,
    now_ms: int,
) -> str:
    try:
        preset = decode_permission_selection_id(selection_id)
    except ValueError as exc:
        raise RuntimeInvalidRequestError(str(exc)) from exc
    data = {"permissionSelection": {"preset": preset}}
    entry_id = f"{external_session_id}:runtime-permission-selection"
    db.execute_write(
        "insert into session_entry "
        "(id, session_id, type, time_created, time_updated, data) "
        "values (?, ?, 'runtime/permission_selection', ?, ?, ?) "
        "on conflict(id) do update set time_updated = excluded.time_updated, "
        "data = excluded.data",
        (entry_id, external_session_id, now_ms, now_ms,
         json.dumps(data, ensure_ascii=False)),
    )
    return entry_id