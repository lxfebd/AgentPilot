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


# ── DST correctness ─────────────────────────────────────────
#
# Rolling a recurring schedule forward must keep the wall-clock time. These
# cases pin that behaviour across spring-forward, fall-back, a southern-
# hemisphere transition, and a slot that the spring-forward gap skips. They are
# regression guards, not bug reproductions: the naive "add timedelta(days=1)"
# arithmetic already produces the same instants, because `ZoneInfo` re-resolves
# the offset when the result is converted back to UTC.


def test_daily_keeps_the_wall_clock_across_spring_forward() -> None:
    # America/New_York springs forward on 2026-03-08 at 02:00 local.
    # 15:00Z is 10:00 EST, so today's 09:00 slot has already passed and the
    # schedule rolls to tomorrow — the transition day.
    now = datetime(2026, 3, 7, 15, 0, tzinfo=UTC)
    result = compute_next_run(
        "daily", run_at=None, run_time="09:00", weekday=None,
        timezone_name="America/New_York", now=now,
    )
    # 2026-03-08 09:00 EDT is 13:00Z — not 14:00Z, which a drifted clock gives.
    assert result == "2026-03-08T13:00:00Z"


def test_daily_keeps_the_wall_clock_across_fall_back() -> None:
    # America/New_York falls back on 2026-11-01 at 02:00 local.
    # 15:00Z is 11:00 EDT, so today's 09:00 slot has already passed.
    now = datetime(2026, 10, 31, 15, 0, tzinfo=UTC)
    result = compute_next_run(
        "daily", run_at=None, run_time="09:00", weekday=None,
        timezone_name="America/New_York", now=now,
    )
    # 2026-11-01 09:00 EST is 14:00Z — an hour later in UTC than the EDT day,
    # but still 09:00 on the wall clock.
    assert result == "2026-11-01T14:00:00Z"


def test_weekly_keeps_the_wall_clock_across_spring_forward() -> None:
    # Saturday 2026-03-07, 15:00Z = 10:00 EST; the next Monday is 2026-03-09,
    # after the transition.
    now = datetime(2026, 3, 7, 15, 0, tzinfo=UTC)
    result = compute_next_run(
        "weekly", run_at=None, run_time="09:00", weekday=0,
        timezone_name="America/New_York", now=now,
    )
    assert result == "2026-03-09T13:00:00Z"


def test_southern_hemisphere_transitions_are_symmetric() -> None:
    # Australia/Sydney falls back on 2026-04-05 at 03:00 local (AEDT -> AEST).
    # 2026-04-04 15:00Z is 2026-04-05 02:00 AEDT, i.e. before today's 09:00 slot.
    now = datetime(2026, 4, 4, 15, 0, tzinfo=UTC)
    result = compute_next_run(
        "daily", run_at=None, run_time="09:00", weekday=None,
        timezone_name="Australia/Sydney", now=now,
    )
    # The slot is still 09:00 on the wall clock, but it now falls after the
    # transition, so it is AEST (UTC+10) rather than AEDT (UTC+11).
    assert result == "2026-04-04T23:00:00Z"


def test_a_slot_skipped_by_spring_forward_still_resolves() -> None:
    """A runTime inside the skipped hour must not crash or loop forever."""

    # 02:30 does not exist on 2026-03-08 in New York. The schedule is created
    # the day before, so the first candidate lands exactly on the gap.
    now = datetime(2026, 3, 7, 15, 0, tzinfo=UTC)
    result = compute_next_run(
        "daily", run_at=None, run_time="02:30", weekday=None,
        timezone_name="America/New_York", now=now,
    )
    assert result is not None
    # `fold=0` resolves the non-existent local time forward, i.e. 03:30 EDT.
    assert result == "2026-03-08T07:30:00Z"
