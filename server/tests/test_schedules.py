from datetime import UTC, datetime

import pytest

from agent_server.services.schedules import compute_next_run

NOW = datetime(2026, 10, 12, 1, 0, 0, tzinfo=UTC)  # Monday 09:00 in +08:00


def test_once_returns_utc_instant() -> None:
    result = compute_next_run(
        "once",
        run_at="2026-11-01T08:00:00Z",
        run_time=None,
        weekday=None,
        timezone_name="UTC",
        now=NOW,
    )
    assert result == "2026-11-01T08:00:00Z"


def test_once_accepts_local_timestamp_without_offset() -> None:
    result = compute_next_run(
        "once",
        run_at="2026-11-01T08:00:00",
        run_time=None,
        weekday=None,
        timezone_name="Asia/Shanghai",
        now=NOW,
    )
    # 08:00 in +08:00 is 00:00Z.
    assert result == "2026-11-01T00:00:00Z"


def test_daily_rolls_to_tomorrow_after_the_slot() -> None:
    # 09:00 local (Asia/Shanghai) now; the 08:30 slot already passed today.
    result = compute_next_run(
        "daily",
        run_at=None,
        run_time="08:30",
        weekday=None,
        timezone_name="Asia/Shanghai",
        now=NOW,
    )
    assert result == "2026-10-13T00:30:00Z"  # tomorrow 08:30 +08:00


def test_daily_keeps_today_before_the_slot() -> None:
    result = compute_next_run(
        "daily",
        run_at=None,
        run_time="23:15",
        weekday=None,
        timezone_name="Asia/Shanghai",
        now=NOW,
    )
    assert result == "2026-10-12T15:15:00Z"  # today 23:15 +08:00


def test_weekly_advances_to_the_next_matching_weekday() -> None:
    result = compute_next_run(
        "weekly",
        run_at=None,
        run_time="07:00",
        weekday=0,
        timezone_name="Asia/Shanghai",
        now=NOW,
    )
    # Today is Monday and 07:00 +08:00 already passed; next is 2026-10-19 07:00+08.
    assert result == "2026-10-18T23:00:00Z"


def test_weekly_same_day_after_slot_rolls_a_week() -> None:
    # Monday 09:00 local, weekly Sunday 08:00 -> next Sunday 2026-10-18 08:00+08.
    result = compute_next_run(
        "weekly",
        run_at=None,
        run_time="08:00",
        weekday=6,
        timezone_name="Asia/Shanghai",
        now=NOW,
    )
    assert result == "2026-10-18T00:00:00Z"


def test_unknown_kind_and_timezone_are_rejected() -> None:
    with pytest.raises(ValueError, match="Unknown schedule kind"):
        compute_next_run(
            "hourly", run_at=None, run_time="08:00", weekday=None,
            timezone_name="UTC", now=NOW,
        )
    with pytest.raises(ValueError, match="Unknown timezone"):
        compute_next_run(
            "daily", run_at=None, run_time="08:00", weekday=None,
            timezone_name="Mars/Olympus", now=NOW,
        )


def test_missing_fields_are_rejected() -> None:
    with pytest.raises(ValueError, match="runAt"):
        compute_next_run(
            "once", run_at=None, run_time=None, weekday=None,
            timezone_name="UTC", now=NOW,
        )
    with pytest.raises(ValueError, match="weekday"):
        compute_next_run(
            "weekly", run_at=None, run_time="08:00", weekday=None,
            timezone_name="UTC", now=NOW,
        )
    with pytest.raises(ValueError, match="runTime"):
        compute_next_run(
            "daily", run_at=None, run_time=None, weekday=None,
            timezone_name="UTC", now=NOW,
        )
