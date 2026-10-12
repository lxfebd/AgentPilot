from __future__ import annotations

from typing import Any

from sqlalchemy import delete, insert, select, update
from sqlalchemy.ext.asyncio import AsyncEngine

from agent_server.infra.db import session_schedules as schedules_t


class ScheduleRepository:
    def __init__(self, engine: AsyncEngine) -> None:
        self._engine = engine

    async def list_for_user(self, user_id: str) -> list[dict[str, Any]]:
        async with self._engine.connect() as conn:
            rows = (
                await conn.execute(
                    select(schedules_t)
                    .where(schedules_t.c.user_id == user_id)
                    .order_by(schedules_t.c.created_at.desc())
                )
            ).mappings()
            return [dict(row) for row in rows]

    async def get(self, schedule_id: str, user_id: str) -> dict[str, Any] | None:
        async with self._engine.connect() as conn:
            row = (
                await conn.execute(
                    select(schedules_t).where(
                        schedules_t.c.id == schedule_id,
                        schedules_t.c.user_id == user_id,
                    )
                )
            ).mappings().first()
            return dict(row) if row is not None else None

    async def insert(self, **values: Any) -> None:
        async with self._engine.begin() as conn:
            await conn.execute(insert(schedules_t).values(**values))

    async def update(
        self, schedule_id: str, user_id: str | None = None, **values: Any
    ) -> bool:
        conditions = [schedules_t.c.id == schedule_id]
        if user_id is not None:
            conditions.append(schedules_t.c.user_id == user_id)
        async with self._engine.begin() as conn:
            result = await conn.execute(
                update(schedules_t).where(*conditions).values(**values),
            )
            return result.rowcount > 0

    async def delete(self, schedule_id: str, user_id: str) -> bool:
        async with self._engine.begin() as conn:
            result = await conn.execute(
                delete(schedules_t).where(
                    schedules_t.c.id == schedule_id,
                    schedules_t.c.user_id == user_id,
                )
            )
            return result.rowcount > 0

    async def claim_due(self, now: str, limit: int = 8) -> list[dict[str, Any]]:
        """Rows whose next_run_at is due at or before `now` (UTC ISO-8601)."""
        async with self._engine.connect() as conn:
            rows = (
                await conn.execute(
                    select(schedules_t)
                    .where(
                        schedules_t.c.enabled == 1,
                        schedules_t.c.next_run_at.is_not(None),
                        schedules_t.c.next_run_at <= now,
                    )
                    .order_by(schedules_t.c.next_run_at)
                    .limit(limit)
                )
            ).mappings()
            return [dict(row) for row in rows]

    async def claim(self, schedule_id: str, due_at: str) -> bool:
        """CAS-take one due row so concurrent workers fire it only once."""
        async with self._engine.begin() as conn:
            result = await conn.execute(
                update(schedules_t)
                .where(
                    schedules_t.c.id == schedule_id,
                    schedules_t.c.enabled == 1,
                    schedules_t.c.next_run_at == due_at,
                )
                .values(updated_at=due_at),
            )
            return result.rowcount > 0
