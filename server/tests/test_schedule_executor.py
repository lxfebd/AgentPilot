import asyncio

import pytest

from agent_server.services.schedule_executor import ScheduleExecutor
from agent_server.services.session_run import (
    SessionRunConflictError,
    SessionRunNotFoundError,
)


class FakeSchedulesRepo:
    def __init__(self, due_rows, claim_results=None):
        self.due_rows = due_rows
        self.claim_results = claim_results or {}
        self.claimed = []
        self.updates = []

    async def claim_due(self, now, limit=8):
        return list(self.due_rows)

    async def claim(self, schedule_id, due_at):
        self.claimed.append((schedule_id, due_at))
        return self.claim_results.get(schedule_id, True)

    async def update(self, schedule_id, user_id, **values):
        self.updates.append((schedule_id, values))
        return True


class FakeStore:
    def __init__(self, repo):
        self.schedules = repo


def _row(schedule_id, *, kind="daily", next_run_at="2026-10-12T00:00:00Z"):
    return {
        "id": schedule_id,
        "user_id": "u1",
        "title": "t",
        "prompt": "p",
        "project_id": None,
        "connector_id": None,
        "runtime": None,
        "cwd": None,
        "session_id": "sess_1",
        "schedule_kind": kind,
        "run_at": None,
        "run_time": "09:00",
        "weekday": None,
        "timezone": "UTC",
        "enabled": 1,
        "last_run_at": None,
        "next_run_at": next_run_at,
        "created_at": "2026-10-01T00:00:00Z",
        "updated_at": "2026-10-01T00:00:00Z",
    }


def test_tick_skips_rows_another_worker_claimed() -> None:
    repo = FakeSchedulesRepo([_row("sch_a")], claim_results={"sch_a": False})
    executor = ScheduleExecutor(FakeStore(repo), rpc=None, device_runtimes=None)
    asyncio.run(executor.tick())
    assert repo.claimed == [("sch_a", "2026-10-12T00:00:00Z")]
    assert repo.updates == []  # never fired, never advanced


def test_stale_failure_advances_recurring_schedule() -> None:
    # Due more than an hour ago -> give up and push next_run_at forward.
    stale = _row("sch_b", kind="daily", next_run_at="2026-10-12T00:00:00Z")
    repo = FakeSchedulesRepo([stale])
    executor = ScheduleExecutor(FakeStore(repo), rpc=None, device_runtimes=None)
    # Bypass _fire entirely: drive the failure path directly.
    asyncio.run(executor._give_up_if_stale(stale, "2026-10-12T03:00:00Z"))
    assert len(repo.updates) == 1
    schedule_id, values = repo.updates[0]
    assert schedule_id == "sch_b"
    assert values["next_run_at"] is not None
    assert values["last_run_at"] is None  # a failed run never counts as a run


def test_recent_failure_retries_without_advancing() -> None:
    fresh = _row("sch_c", kind="daily", next_run_at="2026-10-12T02:55:00Z")
    repo = FakeSchedulesRepo([fresh])
    executor = ScheduleExecutor(FakeStore(repo), rpc=None, device_runtimes=None)
    asyncio.run(executor._give_up_if_stale(fresh, "2026-10-12T03:00:00Z"))
    assert repo.updates == []  # inside the 1h grace, keep retrying next tick


def test_once_schedule_advance_clears_next_run() -> None:
    row = _row("sch_d", kind="once", next_run_at="2026-10-12T02:00:00Z")
    repo = FakeSchedulesRepo([row])
    executor = ScheduleExecutor(FakeStore(repo), rpc=None, device_runtimes=None)
    asyncio.run(executor._advance(row, "2026-10-12T03:00:00Z", last_run_at="2026-10-12T03:00:00Z"))
    _, values = repo.updates[0]
    assert values["next_run_at"] is None  # dropped from the due scan forever
    assert values["last_run_at"] == "2026-10-12T03:00:00Z"


def _executor_failing_with(repo, error: Exception) -> ScheduleExecutor:
    executor = ScheduleExecutor(FakeStore(repo), rpc=None, device_runtimes=None)

    async def _fire(row, now):
        raise error

    executor._fire = _fire
    return executor


def test_tick_disables_schedule_whose_target_is_gone() -> None:
    # A deleted session / project / connector can never succeed on a later
    # tick, so the row is switched off instead of retrying every cycle forever.
    row = _row("sch_e", kind="daily", next_run_at="2026-10-12T02:59:00Z")
    repo = FakeSchedulesRepo([row])
    executor = _executor_failing_with(repo, SessionRunNotFoundError("session not found"))
    asyncio.run(executor.tick())

    assert len(repo.updates) == 1  # disabled once, never advanced as a run
    schedule_id, values = repo.updates[0]
    assert schedule_id == "sch_e"
    assert values["enabled"] == 0
    assert values["next_run_at"] is None  # leaves the due scan
    assert "last_run_at" not in values  # a failed run never counts as a run


def test_tick_keeps_retrying_transient_failure() -> None:
    # The permanent path must not swallow ordinary conflicts (connector offline,
    # session busy): those stay on the existing retry / grace behaviour.
    row = _row("sch_f", kind="daily", next_run_at="2026-10-12T02:59:00Z")
    repo = FakeSchedulesRepo([row])
    executor = _executor_failing_with(repo, SessionRunConflictError("connector is offline"))
    asyncio.run(executor.tick())

    # Two writes: the failure is recorded, then the grace path advances it.
    assert len(repo.updates) == 2
    recorded = repo.updates[0][1]
    assert recorded["last_error"] == "connector is offline"
    assert recorded["last_error_at"]
    advanced = repo.updates[1][1]
    assert advanced["next_run_at"] is not None
    assert "enabled" not in advanced  # still enabled, still scheduled


def test_failure_is_recorded_where_the_user_can_see_it() -> None:
    # Before this, a failed run left only a server-log line: next_run_at kept
    # advancing and the schedule looked healthy while nothing ever ran.
    row = _row("sch_h", kind="daily", next_run_at="2026-10-12T02:59:00Z")
    repo = FakeSchedulesRepo([row])
    executor = _executor_failing_with(repo, SessionRunConflictError("session is running"))
    asyncio.run(executor.tick())

    recorded = repo.updates[0][1]
    assert recorded["last_error"] == "session is running"
    assert recorded["last_error_at"]  # when it failed, not just that it did

    advanced = repo.updates[1][1]
    # An advance from the retry grace is not a run: the failure stays visible.
    assert "last_error" not in advanced
    assert "last_error_at" not in advanced


def test_disabled_target_failure_names_the_cause() -> None:
    row = _row("sch_i", kind="daily", next_run_at="2026-10-12T02:59:00Z")
    repo = FakeSchedulesRepo([row])
    executor = _executor_failing_with(repo, SessionRunNotFoundError("project not found"))
    asyncio.run(executor.tick())

    _, values = repo.updates[0]
    assert values["enabled"] == 0
    assert values["last_error"] == "target no longer exists: project not found"
    assert values["last_error_at"]


def test_error_text_is_single_line_and_bounded() -> None:
    from agent_server.services.schedule_executor import _error_text

    assert _error_text(ValueError("first line\n  second   line")) == "first line second line"
    assert _error_text(ValueError("")) == "ValueError"
    long = _error_text(ValueError("x" * 5000))
    assert len(long) == 400
    assert long.endswith("…")


def test_successful_run_clears_the_previous_failure() -> None:
    row = _row("sch_j", kind="daily", next_run_at="2026-10-12T02:00:00Z")
    row["last_error"] = "connector is offline"
    row["last_error_at"] = "2026-10-12T02:00:00Z"
    repo = FakeSchedulesRepo([row])
    executor = ScheduleExecutor(FakeStore(repo), rpc=None, device_runtimes=None)
    asyncio.run(
        executor._advance(row, "2026-10-12T03:00:00Z", last_run_at="2026-10-12T03:00:00Z")
    )

    _, values = repo.updates[0]
    assert values["last_error"] is None
    assert values["last_error_at"] is None


def test_grace_advance_keeps_the_failure() -> None:
    # Advancing after the retry grace is not a run; hiding the failure here is
    # exactly the blind spot this change exists to close.
    row = _row("sch_k", kind="daily", next_run_at="2026-10-12T01:00:00Z")
    row["last_error"] = "connector is offline"
    repo = FakeSchedulesRepo([row])
    executor = ScheduleExecutor(FakeStore(repo), rpc=None, device_runtimes=None)
    asyncio.run(executor._advance(row, "2026-10-12T03:00:00Z", last_run_at=None))

    _, values = repo.updates[0]
    assert values["next_run_at"] is not None
    assert "last_error" not in values


def test_fire_without_any_target_is_a_missing_target() -> None:
    # Rows written before target validation existed: report them as a missing
    # target so tick() disables them instead of retrying forever.
    row = _row("sch_g", next_run_at="2026-10-12T02:00:00Z")
    row["session_id"] = None
    repo = FakeSchedulesRepo([row])
    executor = ScheduleExecutor(FakeStore(repo), rpc=None, device_runtimes=None)

    with pytest.raises(SessionRunNotFoundError):
        asyncio.run(executor._fire(row, "2026-10-12T03:00:00Z"))
    assert repo.updates == []
