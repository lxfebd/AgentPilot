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

# A due schedule that keeps failing (connector offline, session busy, …) is
# retried every tick; give up after this many seconds so one broken row does
# not retry forever.
_RETRY_GRACE_SECONDS = 3600


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
            except Exception as error:  # noqa: BLE001 - isolate row failures
                logger.warning(
                    "schedule {} failed: {}", row.get("id"), error
                )
                await self._give_up_if_stale(row, now)

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
                raise ValueError(
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
        await self._store.schedules.update(
            row["id"],
            None,
            last_run_at=last_run_at if last_run_at is not None else row["last_run_at"],
            next_run_at=next_run_at,
            updated_at=now,
        )

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
