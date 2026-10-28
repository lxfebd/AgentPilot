from __future__ import annotations

import json
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from datetime import UTC, date, datetime, time, timedelta
from typing import Any
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from agent_server.core.jsonutil import json_loads
from agent_server.core.models import (
    DashboardBreakdownItem,
    DashboardHistogramBucket,
    DashboardHistogramSettings,
    DashboardIntensitySettings,
    DashboardOverviewResponse,
    DashboardRange,
    DashboardSeriesPoint,
    DashboardSettingsUpdateRequest,
    DashboardSettingsView,
    DashboardSnapshotResponse,
    DashboardSummary,
    DashboardUserSegmentItem,
)
from agent_server.core.utc import utc_now
from agent_server.services.repository_ports import AdminDashboardRepository

DASHBOARD_SETTINGS_KEY = "settings"
DASHBOARD_SNAPSHOT_VERSION = 5
SNAPSHOT_REFRESH_SECONDS = 300
METRIC_KEYS = {
    "totalUsers": "users.total",
    "newUsers": "users.new",
    "dau": "users.dau",
    "activeUsers": "users.active_session_users",
    "wau": "users.wau",
    "mau": "users.mau",
    "totalMessages": "usage.messages",
    "activeSessions": "usage.active_sessions",
    "avgMessagesPerActiveUser": "usage.avg_messages_per_active_user",
    "avgActiveSessionsPerActiveUser": "usage.avg_active_sessions_per_active_user",
    "totalDevices": "devices.total",
    "avgDevicesPerUser": "devices.avg_per_user",
}
AGENT_LABELS = {
    "codex": "Codex",
    "claude": "Claude Code",
    "dsh": "DeepSeek Harness",
}
DEVICE_LABELS = {
    "macos": "macOS",
    "windows": "Windows",
    "linux": "Linux",
    "unknown": "Unknown",
}
SEGMENT_LABELS = {
    "light": "Light",
    "medium": "Medium",
    "heavy": "Heavy",
}


@dataclass
class UserDailyFact:
    user_id: str
    messages: int = 0
    active_sessions: set[str] = field(default_factory=set)
    created_sessions: int = 0
    last_activity_at: str | None = None
    devices: int = 0
    macos_devices: int = 0
    windows_devices: int = 0
    linux_devices: int = 0
    unknown_devices: int = 0
    codex_agents: int = 0
    claude_agents: int = 0
    dsh_agents: int = 0


@dataclass
class DeviceSnapshot:
    total_devices: int
    by_os: Counter[str]
    by_user: dict[str, dict[str, int]]
    agent_counts: Counter[str]


# Range summaries only need activity; device columns on the facts are unused.
_EMPTY_DEVICE_SNAPSHOT = DeviceSnapshot(total_devices=0, by_os=Counter(), by_user={}, agent_counts=Counter())


class AdminDashboardService:
    def __init__(self, store: AdminDashboardRepository) -> None:
        self._store = store

    async def get_settings(self) -> DashboardSettingsView:
        settings, _customized = await self._load_settings()
        return settings.model_copy(update={"serverTime": utc_now()})

    async def _load_settings(self) -> tuple[DashboardSettingsView, bool]:
        raw = await self._store.load_settings_value(DASHBOARD_SETTINGS_KEY)
        if raw is None:
            settings = DashboardSettingsView()
            customized = False
        else:
            settings = DashboardSettingsView.model_validate(json_loads(raw) or {})
            customized = True
        return _normalized_settings(settings), customized

    async def update_settings(
        self,
        payload: DashboardSettingsUpdateRequest,
    ) -> DashboardSettingsView:
        current = await self.get_settings()
        merged = current.model_copy(
            update={
                "intensity": payload.intensity or current.intensity,
                "histogramBins": payload.histogramBins or current.histogramBins,
                "serverTime": None,
            }
        )
        settings = _normalized_settings(merged)
        now = utc_now()
        values = {
            "key": DASHBOARD_SETTINGS_KEY,
            "value_json": json.dumps(
                settings.model_dump(exclude_none=True, exclude={"serverTime"}),
                ensure_ascii=False,
                sort_keys=True,
                separators=(",", ":"),
            ),
            "updated_at": now,
        }
        await self._store.upsert_settings(
            key=DASHBOARD_SETTINGS_KEY,
            value_json=values["value_json"],
            updated_at=now,
        )
        return settings.model_copy(update={"serverTime": utc_now()})

    async def get_overview(
        self,
        *,
        from_date: date,
        to_date: date,
        timezone: str,
    ) -> DashboardOverviewResponse:
        if from_date > to_date:
            raise ValueError("from date must be before or equal to to date")
        tz = _timezone(timezone)
        settings, customized_settings = await self._load_settings()
        today = datetime.now(tz).date()
        for day in _date_range(from_date, to_date):
            await self.ensure_snapshot(day, timezone=timezone, refresh_today=day == today)

        metrics = await self._read_metrics(from_date, to_date)
        facts = await self._read_facts(from_date, to_date)
        series = [_series_point(day, metrics.get(day.isoformat(), {})) for day in _date_range(from_date, to_date)]
        range_start_utc, _ = _day_bounds_utc(from_date, timezone)
        _, range_end_utc = _day_bounds_utc(to_date, timezone)
        range_user_facts = await self._compute_user_facts(
            start_utc=range_start_utc,
            end_utc=range_end_utc,
            device_snapshot=_EMPTY_DEVICE_SNAPSHOT,
        )
        range_session_agent_counts = await self._active_session_agent_counts(
            start_utc=range_start_utc,
            end_utc=range_end_utc,
        )
        summary = _range_summary(series, range_user_facts)
        range_facts = list(facts.values())
        effective_settings = settings if customized_settings else _settings_for_facts(range_facts)
        message_histogram = _histogram(
            [int(row["messages"] or 0) for row in range_facts],
            effective_settings.histogramBins.messages,
        )
        session_histogram = _histogram(
            [int(row["active_sessions"] or 0) for row in range_facts],
            effective_settings.histogramBins.sessions,
        )
        user_segments = _segment_counts(range_facts, effective_settings)
        latest_metrics = metrics.get(to_date.isoformat(), {})
        return DashboardOverviewResponse(
            range=DashboardRange(
                fromDate=from_date.isoformat(),
                toDate=to_date.isoformat(),
                timezone=timezone,
            ),
            summary=summary,
            series=series,
            messageHistogram=message_histogram,
            sessionHistogram=session_histogram,
            userSegments=user_segments,
            deviceBreakdown=_breakdown(latest_metrics, "devices.by_os", DEVICE_LABELS),
            agentBreakdown=_breakdown(latest_metrics, "agents.installed", AGENT_LABELS),
            sessionAgentBreakdown=_counter_breakdown(range_session_agent_counts, AGENT_LABELS),
            settings=effective_settings.model_copy(update={"serverTime": utc_now()}),
            serverTime=utc_now(),
        )

    async def refresh_snapshot(
        self,
        target_date: date,
        *,
        timezone: str,
    ) -> DashboardSnapshotResponse:
        return await self._compute_snapshot(target_date, timezone=timezone)

    async def ensure_snapshot(
        self,
        target_date: date,
        *,
        timezone: str,
        refresh_today: bool,
    ) -> None:
        latest = await self._latest_snapshot_at(target_date)
        if latest is None:
            await self._compute_snapshot(target_date, timezone=timezone)
            return
        version = await self._snapshot_version(target_date)
        if version != DASHBOARD_SNAPSHOT_VERSION:
            await self._compute_snapshot(target_date, timezone=timezone)
            return
        if refresh_today and _snapshot_age_seconds(latest) > SNAPSHOT_REFRESH_SECONDS:
            await self._compute_snapshot(target_date, timezone=timezone)

    async def _compute_snapshot(
        self,
        target_date: date,
        *,
        timezone: str,
    ) -> DashboardSnapshotResponse:
        settings, customized_settings = await self._load_settings()
        start_utc, end_utc = _day_bounds_utc(target_date, timezone)
        users = await self._load_users(end_utc=end_utc)
        device_snapshot = await self._load_device_snapshot()
        facts = await self._compute_user_facts(
            start_utc=start_utc,
            end_utc=end_utc,
            device_snapshot=device_snapshot,
        )
        fact_rows_for_settings = [_fact_row(fact) for fact in facts.values()]
        effective_settings = settings if customized_settings else _settings_for_facts(fact_rows_for_settings)
        total_users = len(users)
        new_users = sum(1 for row in users.values() if _in_range(row["created_at"], start_utc, end_utc))
        dau = len(facts)
        active_users = sum(1 for fact in facts.values() if fact.active_sessions)
        wau = await self._count_active_users_between(
            start_utc=_period_start_utc(target_date, timezone, days=7),
            end_utc=end_utc,
        )
        mau = await self._count_active_users_between(
            start_utc=_period_start_utc(target_date, timezone, days=30),
            end_utc=end_utc,
        )
        total_messages = sum(fact.messages for fact in facts.values())
        active_sessions = len({sid for fact in facts.values() for sid in fact.active_sessions})
        session_agent_counts = await self._active_session_agent_counts(start_utc=start_utc, end_utc=end_utc)
        avg_messages = _ratio(total_messages, dau)
        avg_sessions = _ratio(sum(len(f.active_sessions) for f in facts.values()), dau)
        avg_devices = _ratio(device_snapshot.total_devices, total_users)
        computed_at = utc_now()
        metrics: list[dict[str, Any]] = [
            _metric(target_date, "snapshot.version", DASHBOARD_SNAPSHOT_VERSION, computed_at),
            _metric(target_date, "users.total", total_users, computed_at),
            _metric(target_date, "users.new", new_users, computed_at),
            _metric(target_date, "users.dau", dau, computed_at),
            _metric(target_date, "users.active_session_users", active_users, computed_at),
            _metric(target_date, "users.wau", wau, computed_at),
            _metric(target_date, "users.mau", mau, computed_at),
            _metric(target_date, "usage.messages", total_messages, computed_at),
            _metric(target_date, "usage.active_sessions", active_sessions, computed_at),
            _metric(target_date, "usage.avg_messages_per_active_user", avg_messages, computed_at),
            _metric(target_date, "usage.avg_active_sessions_per_active_user", avg_sessions, computed_at),
            _metric(target_date, "devices.total", device_snapshot.total_devices, computed_at),
            _metric(target_date, "devices.avg_per_user", avg_devices, computed_at),
        ]
        for key in ("macos", "windows", "linux", "unknown"):
            metrics.append(
                _metric(
                    target_date,
                    "devices.by_os",
                    device_snapshot.by_os.get(key, 0),
                    computed_at,
                    dimension_key="device_os",
                    dimension_value=key,
                )
            )
        for key in ("codex", "claude", "dsh"):
            metrics.append(
                _metric(
                    target_date,
                    "agents.installed",
                    device_snapshot.agent_counts.get(key, 0),
                    computed_at,
                    dimension_key="agent",
                    dimension_value=key,
                )
            )
            metrics.append(
                _metric(
                    target_date,
                    "sessions.by_agent",
                    session_agent_counts.get(key, 0),
                    computed_at,
                    dimension_key="agent",
                    dimension_value=key,
                )
            )
        for item in _segment_counts(fact_rows_for_settings, effective_settings):
            metrics.append(
                _metric(
                    target_date,
                    "usage.intensity",
                    item.count,
                    computed_at,
                    dimension_key="segment",
                    dimension_value=item.segment,
                )
            )
        for bucket in _histogram([fact.messages for fact in facts.values()], effective_settings.histogramBins.messages):
            metrics.append(
                _metric(
                    target_date,
                    "usage.message_histogram",
                    bucket.count,
                    computed_at,
                    dimension_key="bucket",
                    dimension_value=bucket.key,
                )
            )
        for bucket in _histogram(
            [len(fact.active_sessions) for fact in facts.values()],
            effective_settings.histogramBins.sessions,
        ):
            metrics.append(
                _metric(
                    target_date,
                    "usage.session_histogram",
                    bucket.count,
                    computed_at,
                    dimension_key="bucket",
                    dimension_value=bucket.key,
                )
            )

        fact_rows = [
            {
                "date": target_date.isoformat(),
                "user_id": fact.user_id,
                "messages": fact.messages,
                "active_sessions": len(fact.active_sessions),
                "created_sessions": fact.created_sessions,
                "devices": fact.devices,
                "macos_devices": fact.macos_devices,
                "windows_devices": fact.windows_devices,
                "linux_devices": fact.linux_devices,
                "unknown_devices": fact.unknown_devices,
                "codex_agents": fact.codex_agents,
                "claude_agents": fact.claude_agents,
                "dsh_agents": fact.dsh_agents,
                "last_activity_at": fact.last_activity_at,
                "computed_at": computed_at,
            }
            for fact in sorted(facts.values(), key=lambda value: value.user_id)
        ]
        await self._store.replace_snapshot(
            target_date=target_date.isoformat(),
            metrics=metrics,
            fact_rows=fact_rows,
        )
        return DashboardSnapshotResponse(
            date=target_date.isoformat(),
            computedAt=computed_at,
            metrics=len(metrics),
            users=len(fact_rows),
            serverTime=utc_now(),
        )

    async def _latest_snapshot_at(self, target_date: date) -> str | None:
        return await self._store.latest_snapshot_computed_at(target_date.isoformat())

    async def _snapshot_version(self, target_date: date) -> int | None:
        return await self._store.snapshot_version(target_date.isoformat())

    async def _read_metrics(
        self,
        from_date: date,
        to_date: date,
    ) -> dict[str, dict[tuple[str, str, str], float]]:
        rows = await self._store.metrics_between(from_date.isoformat(), to_date.isoformat())
        result: dict[str, dict[tuple[str, str, str], float]] = defaultdict(dict)
        for row in rows:
            result[row["date"]][
                (row["metric_key"], row["dimension_key"] or "", row["dimension_value"] or "")
            ] = float(row["value"])
        return result

    async def _read_facts(
        self,
        from_date: date,
        to_date: date,
    ) -> dict[str, dict[str, Any]]:
        rows = await self._store.facts_between(from_date.isoformat(), to_date.isoformat())
        by_user: dict[str, dict[str, Any]] = {}
        for row in rows:
            user_id = row["user_id"]
            item = by_user.setdefault(
                user_id,
                {
                    "user_id": user_id,
                    "messages": 0,
                    "active_sessions": 0,
                    "created_sessions": 0,
                    "active_days": 0,
                    "last_activity_at": None,
                },
            )
            item["messages"] += int(row["messages"] or 0)
            item["active_sessions"] += int(row["active_sessions"] or 0)
            item["created_sessions"] += int(row["created_sessions"] or 0)
            item["active_days"] += 1
            item["last_activity_at"] = _max_iso(item["last_activity_at"], row["last_activity_at"])
        return by_user

    async def _load_users(self, *, end_utc: str) -> dict[str, dict[str, Any]]:
        rows = await self._store.users_before(end_utc)
        return {row["id"]: dict(row) for row in rows}

    async def _load_device_snapshot(self) -> DeviceSnapshot:
        connectors = await self._store.list_connectors()
        runtime_rows = await self._store.device_runtimes()
        runtimes_by_connector: dict[str, set[str]] = defaultdict(set)
        for connector_id, runtime_type in runtime_rows:
            runtimes_by_connector[str(connector_id)].add(str(runtime_type))
        by_os: Counter[str] = Counter()
        by_user: dict[str, dict[str, int]] = defaultdict(
            lambda: {
                "devices": 0,
                "macos_devices": 0,
                "windows_devices": 0,
                "linux_devices": 0,
                "unknown_devices": 0,
                "codex_agents": 0,
                "claude_agents": 0,
                "dsh_agents": 0,
            }
        )
        agent_counts: Counter[str] = Counter()
        for connector in connectors:
            os_key = connector.deviceOs if connector.deviceOs in {"macos", "windows", "linux"} else "unknown"
            by_os[os_key] += 1
            item = by_user[connector.userId]
            item["devices"] += 1
            item[f"{os_key}_devices"] += 1
            for agent in ("codex", "claude", "dsh"):
                if agent in runtimes_by_connector.get(connector.id, set()):
                    item[f"{agent}_agents"] += 1
                    agent_counts[agent] += 1
        return DeviceSnapshot(
            total_devices=len(connectors),
            by_os=by_os,
            by_user=dict(by_user),
            agent_counts=agent_counts,
        )

    async def _compute_user_facts(
        self,
        *,
        start_utc: str,
        end_utc: str,
        device_snapshot: DeviceSnapshot,
    ) -> dict[str, UserDailyFact]:
        facts: dict[str, UserDailyFact] = {}

        def fact_for(user_id: str) -> UserDailyFact:
            fact = facts.get(user_id)
            if fact is None:
                fact = UserDailyFact(user_id=user_id)
                device_counts = device_snapshot.by_user.get(user_id, {})
                for key, value in device_counts.items():
                    setattr(fact, key, int(value or 0))
                facts[user_id] = fact
            return fact

        activity_rows, session_rows, timeline_session_rows, message_rows = await self._store.activity_bundle(
            start_utc=start_utc,
            end_utc=end_utc,
        )

        for row in activity_rows:
            fact = fact_for(row["user_id"])
            fact.last_activity_at = _max_iso(fact.last_activity_at, row["last_activity_at"])
        for row in session_rows:
            user_id = row["user_id"]
            fact = fact_for(user_id)
            fact.active_sessions.add(row["id"])
            fact.created_sessions += 1
            fact.last_activity_at = _max_iso(fact.last_activity_at, row["created_at"])
        for row in timeline_session_rows:
            fact = fact_for(row["user_id"])
            fact.active_sessions.add(row["session_id"])
            fact.last_activity_at = _max_iso(fact.last_activity_at, row["last_activity_at"])
        for row in message_rows:
            fact = fact_for(row["user_id"])
            fact.messages = int(row["messages"] or 0)
            fact.last_activity_at = _max_iso(fact.last_activity_at, row["last_activity_at"])
        return facts

    async def _active_session_agent_counts(self, *, start_utc: str, end_utc: str) -> Counter[str]:
        active: dict[str, str] = {}
        session_rows, timeline_rows = await self._store.agent_count_rows(
            start_utc=start_utc,
            end_utc=end_utc,
        )
        for row in [*session_rows, *timeline_rows]:
            runtime = row["runtime"]
            if runtime in {"codex", "claude", "dsh"}:
                active[row["id"]] = runtime
        return Counter(active.values())

    async def _count_active_users_between(self, *, start_utc: str, end_utc: str) -> int:
        active: set[str] = set()
        activity_users, session_users, timeline_users = await self._store.active_user_rows(
            start_utc=start_utc,
            end_utc=end_utc,
        )
        active.update(str(row[0]) for row in activity_users if row[0])
        active.update(str(row[0]) for row in session_users if row[0])
        active.update(str(row[0]) for row in timeline_users if row[0])
        return len(active)

def _timezone(name: str) -> ZoneInfo:
    try:
        return ZoneInfo(name)
    except ZoneInfoNotFoundError as exc:
        raise ValueError(f"unsupported timezone: {name}") from exc


def _day_bounds_utc(target_date: date, timezone: str) -> tuple[str, str]:
    tz = _timezone(timezone)
    start = datetime.combine(target_date, time.min, tzinfo=tz).astimezone(UTC)
    end = (datetime.combine(target_date, time.min, tzinfo=tz) + timedelta(days=1)).astimezone(UTC)
    return _iso_utc(start), _iso_utc(end)


def _period_start_utc(target_date: date, timezone: str, *, days: int) -> str:
    tz = _timezone(timezone)
    start_date = target_date - timedelta(days=days - 1)
    start = datetime.combine(start_date, time.min, tzinfo=tz).astimezone(UTC)
    return _iso_utc(start)


def _iso_utc(value: datetime) -> str:
    return value.astimezone(UTC).isoformat().replace("+00:00", "Z")


def _date_range(from_date: date, to_date: date) -> list[date]:
    days = (to_date - from_date).days
    return [from_date + timedelta(days=offset) for offset in range(days + 1)]


def _in_range(value: str | None, start_utc: str, end_utc: str) -> bool:
    return isinstance(value, str) and start_utc <= value < end_utc


def _max_iso(left: str | None, right: str | None) -> str | None:
    if not left:
        return right
    if not right:
        return left
    return max(left, right)


def _metric(
    target_date: date,
    metric_key: str,
    value: float,
    computed_at: str,
    *,
    dimension_key: str = "",
    dimension_value: str = "",
) -> dict[str, Any]:
    return {
        "date": target_date.isoformat(),
        "metric_key": metric_key,
        "dimension_key": dimension_key,
        "dimension_value": dimension_value,
        "value": float(value),
        "computed_at": computed_at,
    }


def _fact_row(fact: UserDailyFact) -> dict[str, Any]:
    return {
        "messages": fact.messages,
        "active_sessions": len(fact.active_sessions),
    }


def _normalized_settings(settings: DashboardSettingsView) -> DashboardSettingsView:
    light_max = max(0, settings.intensity.lightMax)
    medium_max = max(light_max, settings.intensity.mediumMax)
    return DashboardSettingsView(
        intensity=settings.intensity.model_copy(
            update={"basis": "messages", "lightMax": light_max, "mediumMax": medium_max}
        ),
        histogramBins=settings.histogramBins.model_copy(
            update={
                "messages": _normalized_bins(settings.histogramBins.messages),
                "sessions": _normalized_bins(settings.histogramBins.sessions),
            }
        ),
    )


def _normalized_bins(values: list[int]) -> list[int]:
    result = sorted({0, *(max(0, int(value)) for value in values)})
    return result or [0]


def _settings_for_facts(facts: list[dict[str, Any]]) -> DashboardSettingsView:
    messages = [max(0, int(row.get("messages") or 0)) for row in facts]
    sessions = [max(0, int(row.get("active_sessions") or 0)) for row in facts]
    light_max, medium_max = _auto_intensity_bounds(messages)
    return _normalized_settings(
        DashboardSettingsView(
            intensity=DashboardIntensitySettings(lightMax=light_max, mediumMax=medium_max),
            histogramBins=DashboardHistogramSettings(
                messages=_auto_bins(messages),
                sessions=_auto_bins(sessions),
            ),
        )
    )


def _auto_intensity_bounds(values: list[int]) -> tuple[int, int]:
    positive = sorted(value for value in values if value > 0)
    if not positive:
        return 0, 0
    light_max = _percentile_nearest_rank(positive, 0.5)
    medium_max = _percentile_nearest_rank(positive, 0.8)
    return light_max, max(light_max, medium_max)


def _auto_bins(values: list[int]) -> list[int]:
    positive = sorted(value for value in values if value > 0)
    if not positive:
        return [0]
    candidates = [0]
    for percentile in (0.5, 0.8, 0.95):
        candidates.append(_percentile_lower(positive, percentile))
    return _normalized_bins(candidates)


def _percentile_nearest_rank(values: list[int], percentile: float) -> int:
    if not values:
        return 0
    index = max(0, min(len(values) - 1, int(len(values) * percentile + 0.999999) - 1))
    return values[index]


def _percentile_lower(values: list[int], percentile: float) -> int:
    if not values:
        return 0
    index = max(0, min(len(values) - 1, int((len(values) - 1) * percentile)))
    return values[index]


def _segment_for_messages(messages: int, settings: DashboardSettingsView) -> str:
    if messages <= settings.intensity.lightMax:
        return "light"
    if messages <= settings.intensity.mediumMax:
        return "medium"
    return "heavy"


def _segment_counts(
    facts: list[dict[str, Any]],
    settings: DashboardSettingsView,
) -> list[DashboardUserSegmentItem]:
    counts = Counter({"light": 0, "medium": 0, "heavy": 0})
    for row in facts:
        messages = int(row.get("messages") or 0)
        if messages <= 0:
            continue
        counts[_segment_for_messages(messages, settings)] += 1
    return [
        DashboardUserSegmentItem(segment="light", label=SEGMENT_LABELS["light"], count=counts["light"]),
        DashboardUserSegmentItem(segment="medium", label=SEGMENT_LABELS["medium"], count=counts["medium"]),
        DashboardUserSegmentItem(segment="heavy", label=SEGMENT_LABELS["heavy"], count=counts["heavy"]),
    ]


def _histogram(values: list[int], bins: list[int]) -> list[DashboardHistogramBucket]:
    normalized = _normalized_bins(bins)
    counts = Counter()
    buckets: list[tuple[str, str, int | None, int | None]] = []
    for index, start in enumerate(normalized):
        end = normalized[index + 1] if index + 1 < len(normalized) else None
        if end is None:
            lower = start if index == 0 else start + 1
            key = f"{lower}+"
            label = key
            min_value = lower
            max_value = None
        elif start == 0:
            key = f"{start}-{end}"
            label = key
            min_value = start
            max_value = end
        else:
            key = f"{start + 1}-{end}"
            label = key
            min_value = start + 1
            max_value = end
        buckets.append((key, label, min_value, max_value))
    for value in values:
        matched = False
        for index, start in enumerate(normalized):
            end = normalized[index + 1] if index + 1 < len(normalized) else None
            if end is None and (value >= start if index == 0 else value > start):
                lower = start if index == 0 else start + 1
                counts[f"{lower}+"] += 1
                matched = True
                break
            if end is not None and start <= value <= end:
                counts[f"{start}-{end}" if start == 0 else f"{start + 1}-{end}"] += 1
                matched = True
                break
        if not matched and normalized:
            counts[f"{normalized[0]}-{normalized[1]}" if len(normalized) > 1 else f"{normalized[0]}+"] += 1
    return [
        DashboardHistogramBucket(
            key=key,
            label=label,
            count=int(counts[key]),
            min=min_value,
            max=max_value,
        )
        for key, label, min_value, max_value in buckets
    ]


def _series_point(
    target_date: date,
    metrics: dict[tuple[str, str, str], float],
) -> DashboardSeriesPoint:
    values = {field: metrics.get((metric, "", ""), 0) for field, metric in METRIC_KEYS.items()}
    return DashboardSeriesPoint(
        date=target_date.isoformat(),
        totalUsers=int(values["totalUsers"]),
        newUsers=int(values["newUsers"]),
        dau=int(values["dau"]),
        activeUsers=int(values["activeUsers"]),
        wau=int(values["wau"]),
        mau=int(values["mau"]),
        totalMessages=int(values["totalMessages"]),
        activeSessions=int(values["activeSessions"]),
        avgMessagesPerActiveUser=round(float(values["avgMessagesPerActiveUser"]), 2),
        avgActiveSessionsPerActiveUser=round(float(values["avgActiveSessionsPerActiveUser"]), 2),
        totalDevices=int(values["totalDevices"]),
        avgDevicesPerUser=round(float(values["avgDevicesPerUser"]), 2),
    )


def _range_summary(
    series: list[DashboardSeriesPoint],
    range_facts: dict[str, UserDailyFact],
) -> DashboardSummary:
    """Summarize the whole selected range, not just its last day.

    Flow metrics (messages, active users, active sessions) are counted over the
    range with de-duplication; DAU is the daily average; stock metrics (users,
    devices) and rolling windows (WAU/MAU) are taken as of the end date.
    """
    if not series:
        return DashboardSummary()
    latest = series[-1]
    range_users = len(range_facts)
    total_messages = sum(fact.messages for fact in range_facts.values())
    user_sessions = sum(len(fact.active_sessions) for fact in range_facts.values())
    return DashboardSummary(
        totalUsers=latest.totalUsers,
        newUsers=sum(point.newUsers for point in series),
        dau=int(sum(point.dau for point in series) / len(series) + 0.5),
        activeUsers=sum(1 for fact in range_facts.values() if fact.active_sessions),
        wau=latest.wau,
        mau=latest.mau,
        totalMessages=total_messages,
        activeSessions=len({sid for fact in range_facts.values() for sid in fact.active_sessions}),
        avgMessagesPerActiveUser=round(_ratio(total_messages, range_users), 2),
        avgActiveSessionsPerActiveUser=round(_ratio(user_sessions, range_users), 2),
        totalDevices=latest.totalDevices,
        avgDevicesPerUser=latest.avgDevicesPerUser,
    )


def _counter_breakdown(counts: Counter[str], labels: dict[str, str]) -> list[DashboardBreakdownItem]:
    total = sum(counts.get(key, 0) for key in labels)
    return [
        DashboardBreakdownItem(
            key=key,
            label=label,
            value=float(value),
            percent=round(_ratio(value * 100, total), 2) if total else 0,
        )
        for key, label in labels.items()
        for value in [counts.get(key, 0)]
    ]


def _breakdown(
    metrics: dict[tuple[str, str, str], float],
    metric_key: str,
    labels: dict[str, str],
) -> list[DashboardBreakdownItem]:
    values = {key: metrics.get((metric_key, next(iter(labels_name(metric_key))), key), 0) for key in labels}
    total = sum(values.values())
    return [
        DashboardBreakdownItem(
            key=key,
            label=label,
            value=value,
            percent=round(_ratio(value * 100, total), 2) if total else 0,
        )
        for key, label in labels.items()
        for value in [values[key]]
    ]


def labels_name(metric_key: str) -> tuple[str]:
    if metric_key == "devices.by_os":
        return ("device_os",)
    return ("agent",)


def _ratio(numerator: float, denominator: float) -> float:
    if not denominator:
        return 0
    return numerator / denominator


def _snapshot_age_seconds(computed_at: str) -> float:
    try:
        parsed = datetime.fromisoformat(computed_at)
    except ValueError:
        return float("inf")
    return (datetime.now(UTC) - parsed.astimezone(UTC)).total_seconds()
