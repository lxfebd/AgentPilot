from __future__ import annotations

import uuid
from datetime import UTC, datetime, timedelta
from zoneinfo import ZoneInfo

from agent_server.core.models import (
    SCHEDULE_KIND_DAILY,
    SCHEDULE_KIND_ONCE,
    SCHEDULE_KIND_WEEKLY,
    ScheduleCreateRequest,
    SchedulePatchRequest,
    ScheduleView,
)
from agent_server.core.utc import utc_now

_RUN_TIME_FORMAT = "%H:%M"


def _parse_zone(name: str) -> ZoneInfo:
    try:
        return ZoneInfo(name)
    except Exception as error:
        raise ValueError(f"Unknown timezone: {name}") from error


def _parse_run_time(value: str) -> tuple[int, int]:
    try:
        hour, minute = value.split(":", 1)
        parsed = (int(hour), int(minute))
    except ValueError as error:
        raise ValueError("runTime must be HH:MM.") from error
    if not (0 <= parsed[0] <= 23 and 0 <= parsed[1] <= 59):
        raise ValueError("runTime must be HH:MM.")
    return parsed


def _parse_run_at(value: str, tz: ZoneInfo) -> datetime:
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError as error:
        raise ValueError("runAt must be an ISO-8601 timestamp.") from error
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=tz)
    return parsed.astimezone(UTC)


def _iso(moment: datetime) -> str:
    return moment.astimezone(UTC).isoformat().replace("+00:00", "Z")


def compute_next_run(
    kind: str,
    *,
    run_at: str | None,
    run_time: str | None,
    weekday: int | None,
    timezone_name: str,
    now: datetime,
) -> str | None:
    tz = _parse_zone(timezone_name)
    if kind == SCHEDULE_KIND_ONCE:
        if run_at is None:
            raise ValueError("runAt is required for one-time schedules.")
        return _iso(_parse_run_at(run_at, tz))
    if run_time is None:
        raise ValueError("runTime is required for recurring schedules.")
    hour, minute = _parse_run_time(run_time)
    local_now = now.astimezone(tz)
    if kind == SCHEDULE_KIND_DAILY:
        candidate = local_now.replace(hour=hour, minute=minute, second=0, microsecond=0)
        if candidate <= local_now:
            candidate = candidate + timedelta(days=1)
        return _iso(candidate)
    if kind == SCHEDULE_KIND_WEEKLY:
        if weekday is None:
            raise ValueError("weekday is required for weekly schedules.")
        candidate = local_now.replace(hour=hour, minute=minute, second=0, microsecond=0)
        for _ in range(8):
            if candidate.weekday() == weekday and candidate > local_now:
                break
            candidate = candidate + timedelta(days=1)
        return _iso(candidate)
    raise ValueError(f"Unknown schedule kind: {kind}")


def _to_view(row: dict) -> ScheduleView:
    return ScheduleView(
        id=row["id"],
        title=row["title"],
        prompt=row["prompt"],
        projectId=row["project_id"],
        connectorId=row["connector_id"],
        runtime=row["runtime"],
        cwd=row["cwd"],
        sessionId=row["session_id"],
        scheduleKind=row["schedule_kind"],
        runAt=row["run_at"],
        runTime=row["run_time"],
        weekday=row["weekday"],
        timezone=row["timezone"],
        enabled=bool(row["enabled"]),
        lastRunAt=row["last_run_at"],
        nextRunAt=row["next_run_at"],
        createdAt=row["created_at"],
        updatedAt=row["updated_at"],
    )


class ScheduleService:
    def __init__(self, store) -> None:
        self._store = store

    async def list(self, user_id: str) -> list[ScheduleView]:
        rows = await self._store.schedules.list_for_user(user_id)
        return [_to_view(row) for row in rows]

    async def create(self, user_id: str, payload: ScheduleCreateRequest) -> ScheduleView:
        await self._validate_target(user_id, payload.sessionId)
        now = datetime.now(UTC)
        next_run_at = compute_next_run(
            payload.scheduleKind,
            run_at=payload.runAt,
            run_time=payload.runTime,
            weekday=payload.weekday,
            timezone_name=payload.timezone,
            now=now,
        )
        if payload.scheduleKind == SCHEDULE_KIND_ONCE and _parse_run_at(payload.runAt, _parse_zone(payload.timezone)) <= now:
            raise ValueError("runAt must be in the future.")
        moment = utc_now()
        row = {
            "id": f"sch_{uuid.uuid4().hex}",
            "user_id": user_id,
            "title": payload.title,
            "prompt": payload.prompt,
            "project_id": payload.projectId,
            "connector_id": payload.connectorId,
            "runtime": payload.runtime,
            "cwd": payload.cwd,
            "session_id": payload.sessionId,
            "schedule_kind": payload.scheduleKind,
            "run_at": payload.runAt,
            "run_time": payload.runTime,
            "weekday": payload.weekday,
            "timezone": payload.timezone,
            "enabled": 1 if payload.enabled else 0,
            "last_run_at": None,
            "next_run_at": next_run_at if payload.enabled else None,
            "created_at": moment,
            "updated_at": moment,
        }
        await self._store.schedules.insert(**row)
        return _to_view(row)

    async def patch(
        self, user_id: str, schedule_id: str, payload: SchedulePatchRequest
    ) -> ScheduleView:
        current = await self._store.schedules.get(schedule_id, user_id)
        if current is None:
            raise KeyError(schedule_id)
        merged = {
            "title": payload.title if payload.title is not None else current["title"],
            "prompt": payload.prompt if payload.prompt is not None else current["prompt"],
            "project_id": payload.projectId if payload.projectId is not None else current["project_id"],
            "connector_id": payload.connectorId if payload.connectorId is not None else current["connector_id"],
            "runtime": payload.runtime if payload.runtime is not None else current["runtime"],
            "cwd": payload.cwd if payload.cwd is not None else current["cwd"],
            "session_id": payload.sessionId if payload.sessionId is not None else current["session_id"],
            "schedule_kind": payload.scheduleKind or current["schedule_kind"],
            "run_at": payload.runAt if payload.runAt is not None else current["run_at"],
            "run_time": payload.runTime if payload.runTime is not None else current["run_time"],
            "weekday": payload.weekday if payload.weekday is not None else current["weekday"],
            "timezone": payload.timezone or current["timezone"],
            "enabled": current["enabled"] if payload.enabled is None else (1 if payload.enabled else 0),
        }
        await self._validate_target(user_id, merged["session_id"])
        next_run_at = compute_next_run(
            merged["schedule_kind"],
            run_at=merged["run_at"],
            run_time=merged["run_time"],
            weekday=merged["weekday"],
            timezone_name=merged["timezone"],
            now=datetime.now(UTC),
        )
        merged["next_run_at"] = next_run_at if merged["enabled"] else None
        merged["updated_at"] = utc_now()
        await self._store.schedules.update(schedule_id, user_id, **merged)
        stored = {**current, **merged, "id": schedule_id}
        return _to_view(stored)

    async def delete(self, user_id: str, schedule_id: str) -> bool:
        return await self._store.schedules.delete(schedule_id, user_id)

    async def mark_run(
        self, schedule_id: str, *, last_run_at: str, next_run_at: str | None
    ) -> None:
        await self._store.schedules.update(
            schedule_id,
            None,
            last_run_at=last_run_at,
            next_run_at=next_run_at,
            updated_at=last_run_at,
        )

    async def _validate_target(self, user_id: str, session_id: str | None) -> None:
        if session_id is None:
            return
        try:
            await self._store.get_session(session_id, user_id=user_id)
        except KeyError as error:
            raise ValueError("sessionId does not reference one of your sessions.") from error
