from __future__ import annotations

from collections.abc import Awaitable, Callable
from typing import Any

from agent_server.core.models import ConnectorView
from agent_server.infra.repositories.store_support import *


def _between(column: Any, start_utc: str, end_utc: str) -> Any:
    return and_(column.is_not(None), column >= start_utc, column < end_utc)


def _platform_user_message_filter() -> Any:
    return and_(
        timeline_items_t.c.type == "message",
        timeline_items_t.c.role == "user",
        timeline_items_t.c.payload_json.like('%"clientMessageId"%'),
    )


class AdminDashboardRepositoryImpl:
    """Data access for the admin dashboard.

    ``services.admin_dashboard`` depends on the ``AdminDashboardRepository``
    port only; every ORM table reference stays in the infra layer.
    """

    def __init__(
        self,
        engine: AsyncEngine,
        list_connectors: Callable[..., Awaitable[list[ConnectorView]]],
    ) -> None:
        self._engine = engine
        self._list_connectors = list_connectors

    async def list_connectors(self, *, user_id: str | None = None) -> list[ConnectorView]:
        return await self._list_connectors(user_id=user_id)

    async def load_settings_value(self, key: str) -> str | None:
        async with self._engine.connect() as conn:
            row = (
                await conn.execute(
                    select(dashboard_settings_t.c.value_json).where(
                        dashboard_settings_t.c.key == key
                    )
                )
            ).first()
        return None if row is None else row.value_json

    async def upsert_settings(self, *, key: str, value_json: str, updated_at: str) -> None:
        async with self._engine.begin() as conn:
            existing = (
                await conn.execute(
                    select(dashboard_settings_t.c.key).where(dashboard_settings_t.c.key == key)
                )
            ).first()
            if existing is None:
                await conn.execute(
                    insert(dashboard_settings_t).values(
                        key=key,
                        value_json=value_json,
                        updated_at=updated_at,
                    )
                )
            else:
                await conn.execute(
                    dashboard_settings_t.update()
                    .where(dashboard_settings_t.c.key == key)
                    .values(value_json=value_json, updated_at=updated_at)
                )

    async def latest_snapshot_computed_at(self, target_date: str) -> str | None:
        async with self._engine.connect() as conn:
            row = (
                await conn.execute(
                    select(func.max(dashboard_daily_metrics_t.c.computed_at)).where(
                        dashboard_daily_metrics_t.c.date == target_date
                    )
                )
            ).first()
        return str(row[0]) if row is not None and row[0] else None

    async def snapshot_version(self, target_date: str) -> int | None:
        async with self._engine.connect() as conn:
            row = (
                await conn.execute(
                    select(dashboard_daily_metrics_t.c.value).where(
                        dashboard_daily_metrics_t.c.date == target_date,
                        dashboard_daily_metrics_t.c.metric_key == "snapshot.version",
                        dashboard_daily_metrics_t.c.dimension_key == "",
                        dashboard_daily_metrics_t.c.dimension_value == "",
                    )
                )
            ).first()
        return int(row[0]) if row is not None and row[0] is not None else None

    async def metrics_between(self, from_date: str, to_date: str) -> list[Any]:
        async with self._engine.connect() as conn:
            rows = (
                await conn.execute(
                    select(dashboard_daily_metrics_t).where(
                        dashboard_daily_metrics_t.c.date >= from_date,
                        dashboard_daily_metrics_t.c.date <= to_date,
                    )
                )
            ).mappings().all()
        return list(rows)

    async def facts_between(self, from_date: str, to_date: str) -> list[Any]:
        async with self._engine.connect() as conn:
            rows = (
                await conn.execute(
                    select(dashboard_user_daily_facts_t).where(
                        dashboard_user_daily_facts_t.c.date >= from_date,
                        dashboard_user_daily_facts_t.c.date <= to_date,
                    )
                )
            ).mappings().all()
        return list(rows)

    async def users_before(self, end_utc: str) -> list[Any]:
        async with self._engine.connect() as conn:
            rows = (
                await conn.execute(select(users_t).where(users_t.c.created_at < end_utc))
            ).mappings().all()
        return list(rows)

    async def device_runtimes(self) -> list[Any]:
        async with self._engine.connect() as conn:
            rows = (
                await conn.execute(
                    select(
                        device_runtimes_t.c.connector_id,
                        device_runtimes_t.c.runtime_type,
                    ).where(device_runtimes_t.c.config_json.is_not(None))
                )
            ).all()
        return list(rows)

    async def activity_bundle(
        self,
        *,
        start_utc: str,
        end_utc: str,
    ) -> tuple[list[Any], list[Any], list[Any], list[Any]]:
        async with self._engine.connect() as conn:
            activity_rows = (
                await conn.execute(
                    select(
                        platform_user_activity_t.c.user_id,
                        func.max(platform_user_activity_t.c.last_seen_at).label("last_activity_at"),
                    )
                    .where(_between(platform_user_activity_t.c.last_seen_at, start_utc, end_utc))
                    .group_by(platform_user_activity_t.c.user_id)
                )
            ).mappings().all()
            session_rows = (
                await conn.execute(
                    select(
                        sessions_t.c.id,
                        sessions_t.c.created_at,
                        connectors_t.c.user_id,
                    )
                    .join(connectors_t, connectors_t.c.id == sessions_t.c.connector_id)
                    .where(
                        sessions_t.c.origin == "platform",
                        _between(sessions_t.c.created_at, start_utc, end_utc),
                    )
                )
            ).mappings().all()
            timeline_session_rows = (
                await conn.execute(
                    select(
                        timeline_items_t.c.session_id,
                        connectors_t.c.user_id,
                        func.max(timeline_items_t.c.item_time).label("last_activity_at"),
                    )
                    .join(sessions_t, sessions_t.c.id == timeline_items_t.c.session_id)
                    .join(connectors_t, connectors_t.c.id == sessions_t.c.connector_id)
                    .where(
                        _platform_user_message_filter(),
                        _between(timeline_items_t.c.item_time, start_utc, end_utc),
                    )
                    .group_by(timeline_items_t.c.session_id, connectors_t.c.user_id)
                )
            ).mappings().all()
            message_rows = (
                await conn.execute(
                    select(
                        connectors_t.c.user_id,
                        func.count(func.distinct(timeline_items_t.c.id)).label("messages"),
                        func.max(timeline_items_t.c.item_time).label("last_activity_at"),
                    )
                    .join(sessions_t, sessions_t.c.id == timeline_items_t.c.session_id)
                    .join(connectors_t, connectors_t.c.id == sessions_t.c.connector_id)
                    .where(
                        _platform_user_message_filter(),
                        _between(timeline_items_t.c.item_time, start_utc, end_utc),
                    )
                    .group_by(connectors_t.c.user_id)
                )
            ).mappings().all()
        return list(activity_rows), list(session_rows), list(timeline_session_rows), list(message_rows)

    async def agent_count_rows(
        self,
        *,
        start_utc: str,
        end_utc: str,
    ) -> tuple[list[Any], list[Any]]:
        async with self._engine.connect() as conn:
            session_rows = (
                await conn.execute(
                    select(sessions_t.c.id, sessions_t.c.runtime).where(
                        sessions_t.c.origin == "platform",
                        _between(sessions_t.c.created_at, start_utc, end_utc),
                    )
                )
            ).mappings().all()
            timeline_rows = (
                await conn.execute(
                    select(sessions_t.c.id, sessions_t.c.runtime)
                    .join(timeline_items_t, timeline_items_t.c.session_id == sessions_t.c.id)
                    .where(
                        _platform_user_message_filter(),
                        _between(timeline_items_t.c.item_time, start_utc, end_utc),
                    )
                    .group_by(sessions_t.c.id, sessions_t.c.runtime)
                )
            ).mappings().all()
        return list(session_rows), list(timeline_rows)

    async def active_user_rows(
        self,
        *,
        start_utc: str,
        end_utc: str,
    ) -> tuple[list[Any], list[Any], list[Any]]:
        async with self._engine.connect() as conn:
            activity_users = (
                await conn.execute(
                    select(platform_user_activity_t.c.user_id)
                    .where(_between(platform_user_activity_t.c.last_seen_at, start_utc, end_utc))
                    .group_by(platform_user_activity_t.c.user_id)
                )
            ).all()
            session_users = (
                await conn.execute(
                    select(connectors_t.c.user_id)
                    .join(sessions_t, sessions_t.c.connector_id == connectors_t.c.id)
                    .where(
                        sessions_t.c.origin == "platform",
                        _between(sessions_t.c.created_at, start_utc, end_utc),
                    )
                    .group_by(connectors_t.c.user_id)
                )
            ).all()
            timeline_users = (
                await conn.execute(
                    select(connectors_t.c.user_id)
                    .join(sessions_t, sessions_t.c.connector_id == connectors_t.c.id)
                    .join(timeline_items_t, timeline_items_t.c.session_id == sessions_t.c.id)
                    .where(
                        _platform_user_message_filter(),
                        _between(timeline_items_t.c.item_time, start_utc, end_utc),
                    )
                    .group_by(connectors_t.c.user_id)
                )
            ).all()
        return list(activity_users), list(session_users), list(timeline_users)

    async def replace_snapshot(
        self,
        *,
        target_date: str,
        metrics: list[dict[str, Any]],
        fact_rows: list[dict[str, Any]],
    ) -> None:
        async with self._engine.begin() as conn:
            await conn.execute(
                delete(dashboard_daily_metrics_t).where(
                    dashboard_daily_metrics_t.c.date == target_date
                )
            )
            await conn.execute(
                delete(dashboard_user_daily_facts_t).where(
                    dashboard_user_daily_facts_t.c.date == target_date
                )
            )
            if metrics:
                await conn.execute(insert(dashboard_daily_metrics_t), metrics)
            if fact_rows:
                await conn.execute(insert(dashboard_user_daily_facts_t), fact_rows)
