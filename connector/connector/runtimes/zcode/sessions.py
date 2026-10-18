from __future__ import annotations

from collections.abc import Mapping
from typing import Any

from connector.runtime_protocol import (
    RuntimeStatus,
    SessionMeta,
    SessionSourceObservation,
    SessionSourceState,
    SessionState,
)
from connector.runtime_protocol.models import SessionSourceAvailability
from connector.runtimes.zcode.db import ZcodeDb

# ZCode creates one main chat per project; task_type distinguishes the
# interactive main session from subagent worktrees we must not surface.
_SUPPORTED_TASK_TYPES = frozenset({"interactive"})


def session_meta_from_row(
    row: dict[str, Any],
    *,
    runtime_id: str | None,
    connector_id: str,
) -> SessionMeta:
    session_id = _stable_id(connector_id, row["id"])
    return SessionMeta(
        session_id=session_id,
        external_session_id=row["id"],
        runtime="zcode",
        title=_optional_string(row.get("title")),
        cwd=_optional_string(row.get("directory")),
        ordering_time=str(row.get("time_updated") or row.get("time_created") or ""),
        metadata={
            "taskType": row.get("task_type") or "interactive",
            "projectId": row.get("project_id"),
        },
        runtime_id=runtime_id,
    )


def session_state_from_row(
    row: dict[str, Any],
    *,
    runtime_id: str | None,
    connector_id: str,
    selections: Mapping[str, str | None] | None = None,
) -> SessionState:
    status = _status_from_activity(row)
    return SessionState(
        session_id=_stable_id(connector_id, row["id"]),
        external_session_id=row["id"],
        runtime="zcode",
        status=status,
        selections=dict(selections or {}),
        metadata={
            "taskType": row.get("task_type") or "interactive",
            "timeUpdated": row.get("time_updated"),
        },
        runtime_id=runtime_id,
    )


def session_source_observation(
    row: dict[str, Any],
    *,
    runtime_id: str | None,
    connector_id: str,
    origin: str = "inventory",
) -> SessionSourceObservation:
    availability: SessionSourceAvailability = "available"
    reason: str | None = None
    if row.get("time_archived") is not None:
        availability = "archived"
        reason = "ZCode session archived"
    return SessionSourceObservation(
        session_id=_stable_id(connector_id, row["id"]),
        external_session_id=row["id"],
        runtime="zcode",
        state=SessionSourceState(
            availability=availability,
            reason=reason,
            observed_at=str(row.get("time_updated") or row.get("time_created") or ""),
            observation_origin=origin,  # type: ignore[arg-type]
        ),
        runtime_id=runtime_id,
    )


def list_interactive_sessions(
    db: ZcodeDb,
    *,
    connector_id: str,
    runtime_id: str | None,
    limit: int = 100,
    cursor: str | None = None,
) -> tuple[list[SessionMeta], str | None]:
    db.check_schema()
    where = ["task_type in ('interactive')"]
    params: list[Any] = []
    if cursor:
        where.append("time_updated <= ?")
        params.append(int(cursor))
    sql = (
        "select id, project_id, directory, path, title, time_created, time_updated, "
        "time_archived, task_type from session "
        f"where {' and '.join(where)} order by time_updated desc limit ?"
    )
    params.append(limit + 1)
    rows = db.query(sql, tuple(params))
    has_more = len(rows) > limit
    rows = rows[:limit]
    metas = [
        session_meta_from_row(row, runtime_id=runtime_id, connector_id=connector_id)
        for row in rows
    ]
    next_cursor = None
    if has_more and rows:
        next_cursor = str(rows[-1]["time_updated"])
    return metas, next_cursor


def list_complete_session_inventory(
    db: ZcodeDb,
    *,
    connector_id: str,
    runtime_id: str | None,
    page_size: int = 100,
) -> list[SessionMeta]:
    db.check_schema()
    rows = db.query(
        "select id, project_id, directory, path, title, time_created, time_updated, "
        "time_archived, task_type from session "
        "where task_type in ('interactive') order by time_updated desc"
    )
    return [
        session_meta_from_row(row, runtime_id=runtime_id, connector_id=connector_id)
        for row in rows
    ]


def _stable_id(connector_id: str, external_session_id: str) -> str:
    from connector.runtimes.session_identity import stable_runtime_session_id

    return stable_runtime_session_id(connector_id, "zcode", external_session_id)


def _optional_string(value: Any) -> str | None:
    return value if isinstance(value, str) and value else None


def _status_from_activity(row: dict[str, Any]) -> RuntimeStatus:
    """Map ZCode session activity to a platform runtime status.

    ZCode does not persist an explicit running/paused flag per session, so we
    derive a stable fact from recency: sessions updated within the last few
    seconds are reported running, everything else idle. The connector's session
    state cache then evolves these facts like any polling runtime.
    """
    import time

    updated = row.get("time_updated")
    if not isinstance(updated, int):
        return "idle"
    now_ms = int(time.time() * 1000)
    return "running" if now_ms - updated < 8_000 else "idle"