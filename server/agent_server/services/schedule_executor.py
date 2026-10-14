from __future__ import annotations

import asyncio
from datetime import UTC, datetime
from typing import Any

from loguru import logger

from agent_server.core.models import (
    MessageCreateRequest,
    SessionCreateAndStartRequest,
)
from agent_server.core.utc import utc_now
from agent_server.services.schedules import (
    SCHEDULE_KIND_ONCE,
    ScheduleService,
    compute_next_run,
)
from agent_server.services.session_run import SessionRunNotFoundError

# A due schedule that keeps failing (connector offline, session busy, …) is
# retried every tick; give up after this many seconds so one broken row does
# not retry forever.
_RETRY_GRACE_SECONDS = 3600

# The failure reason is shown verbatim in every client, so it is kept short and
# on one line.  Details belong in the server log, not in a list row.
_ERROR_TEXT_LIMIT = 400


def _error_text(error: BaseException) -> str:
    text = " ".join(str(error).split()) or type(error).__name__
    if len(text) > _ERROR_TEXT_LIMIT:
        text = text[: _ERROR_TEXT_LIMIT - 1] + "…"
    return text


class ScheduleExecutor:
    """Background loop that fires due schedules and advances their next run."""

    def __init__(self, store: Any, rpc: Any, device_runtimes: Any) -> None:
        # Imported lazily to keep module import order simple.
        from agent_server.services.session_run import SessionRunService

        self._store = store
        self._schedules = ScheduleService(store)
        self._runs = SessionRunService(store, rpc, device_runtimes)

    async def run(self, interval_seconds: float = 60.0) -> None:
        while True:
            await asyncio.sleep(interval_seconds)
            try:
                await self.tick()
            except asyncio.CancelledError:
                raise
            except Exception as error:  # noqa: BLE001 - the loop must survive
                logger.warning("schedule tick failed: {}", error)

    async def tick(self) -> None:
        now = utc_now()
        due = await self._store.schedules.claim_due(now)
        for row in due:
            # CAS-take the row first so parallel workers never double-fire.
            if not await self._store.schedules.claim(row["id"], row["next_run_at"]):
                continue
            try:
                await self._fire(row, now)
            except asyncio.CancelledError:
                raise
            except SessionRunNotFoundError as error:
                # The target itself is gone (session deleted, project removed,
                # connector forgotten).  Nothing a later tick can fix, so the
                # schedule is switched off instead of burning a retry window
                # every cycle forever.  `enabled` is an existing API field, so
                # every client already renders the row as off.
                await self._disable_missing_target(row, now, error)
            except Exception as error:  # noqa: BLE001 - isolate row failures
                logger.warning(
                    "schedule {} failed: {}", row.get("id"), error
                )
                await self._record_failure(row, now, error)
                await self._give_up_if_stale(row, now)

    async def _record_failure(
        self, row: dict[str, Any], now: str, error: Exception
    ) -> None:
        """Put the failure where the user can see it.

        Until now a failed run left only a server-log line: `next_run_at`
        advancing made the schedule look healthy while nothing ever ran.
        """
        await self._store.schedules.update(
            row["id"],
            None,
            last_error=_error_text(error),
            last_error_at=now,
            updated_at=now,
        )

    async def _disable_missing_target(
        self, row: dict[str, Any], now: str, error: Exception
    ) -> None:
        await self._store.schedules.update(
            row["id"],
            None,
            enabled=0,
            next_run_at=None,
            last_error=f"target no longer exists: {_error_text(error)}",
            last_error_at=now,
            updated_at=now,
        )
        logger.warning(
            "schedule {} ({}) disabled: target no longer exists ({})",
            row.get("id"),
            row.get("title"),
            error,
        )

    async def _fire(self, row: dict[str, Any], now: str) -> None:
        user_id = row["user_id"]
        client_message_id = f"schedule_{row['id']}_{row['next_run_at']}"
        if row["session_id"]:
            payload = MessageCreateRequest(
                content=row["prompt"], clientMessageId=client_message_id
            )
            await self._runs.send_message(
                row["session_id"], payload, user_id=user_id
            )
        else:
            if not row["project_id"] or not row["connector_id"]:
                # Rows created before target validation existed.  The target
                # can never be resolved, so treat it like a deleted one.
                raise SessionRunNotFoundError(
                    "schedule needs either sessionId or projectId+connectorId"
                )
            payload = SessionCreateAndStartRequest(
                connectorId=row["connector_id"],
                projectId=row["project_id"],
                runtime=row["runtime"] or "codex",
                title=row["title"],
                cwd=row["cwd"],
                content=row["prompt"],
                clientMessageId=client_message_id,
            )
            await self._runs.create_and_start_session(payload, user_id=user_id)
        await self._advance(row, now, last_run_at=now)

    async def _advance(
        self, row: dict[str, Any], now: str, *, last_run_at: str | None
    ) -> None:
        kind = row["schedule_kind"]
        if kind == SCHEDULE_KIND_ONCE:
            # next_run_at NULL removes the row from the due scan forever.
            next_run_at = None
        else:
            next_run_at = compute_next_run(
                kind,
                run_at=row["run_at"],
                run_time=row["run_time"],
                weekday=row["weekday"],
                timezone_name=row["timezone"],
                now=datetime.now(UTC),
            )
        values: dict[str, Any] = {
            "last_run_at": last_run_at if last_run_at is not None else row["last_run_at"],
            "next_run_at": next_run_at,
            "updated_at": now,
        }
        if last_run_at is not None:
            # A run that worked supersedes whatever failed before it.  When
            # last_run_at is None this advance came from the retry grace, so the
            # failure stays visible: the schedule moved on without ever running.
            values["last_error"] = None
            values["last_error_at"] = None
        await self._store.schedules.update(row["id"], None, **values)

    async def _give_up_if_stale(self, row: dict[str, Any], now: str) -> None:
        due_at = row.get("next_run_at")
        if not due_at:
            return
        try:
            due = datetime.fromisoformat(due_at)
            current = datetime.fromisoformat(now)
        except ValueError:
            return
        if (current - due).total_seconds() < _RETRY_GRACE_SECONDS:
            return
        logger.warning(
            "schedule {} exceeded retry grace; advancing", row.get("id")
        )
        await self._advance(row, now, last_run_at=None)
