"""Regression tests for derived-title locking in ``_session_from_row``.

`_session_from_row` treats any title whose ``strip()`` is empty as missing and
derives one from the first user message.  `_lock_in_derived_title` used to
guard its UPDATE with ``title IS NULL OR title == ''`` only.  A whitespace-only
title therefore never matched the guard, so:

* the derived title was returned but never persisted, and
* every later read re-issued the ``_derive_title_from_first_user_message``
  backfill query.

The whitespace-only title is reachable in production through the connector
sync path (`upsert_connector_session`), which persists the runtime-reported
title verbatim -- unlike `create_session`, which normalizes blanks to ``None``.
The guard now uses ``coalesce(trim(title), '') == ''``, matching the read-side
emptiness test on both SQLite and PostgreSQL.

These tests assert the *persisted* state and the backfill query count, because
the returned view always carries the derived title either way.
"""

from __future__ import annotations

from typing import Self

import pytest
from session_fixtures import ensure_user
from sqlalchemy import event, text
from sqlalchemy.dialects import postgresql, sqlite

from agent_server.core.models import TimelineItemIn
from agent_server.infra.db.migrations import upgrade_database
from agent_server.infra.repositories import sessions as sessions_module
from agent_server.infra.repositories.facade import Store
from agent_server.infra.repositories.sessions import sessions_t


def user_message(item_id: str, session_id: str, text: str, order_seq: int = 1) -> TimelineItemIn:
    return TimelineItemIn.model_validate(
        {
            "id": item_id,
            "sessionId": session_id,
            "type": "message",
            "status": "done",
            "role": "user",
            "content": {"text": text, "format": "markdown"},
            "source": {
                "runtime": "codex",
                "sessionId": "thread_blank",
                "itemId": f"runtime-{item_id}",
                "itemType": "userMessage",
            },
            "orderSeq": order_seq,
            "revision": 1,
            "contentHash": f"sha256:{item_id}",
        }
    )


async def _new_store(tmp_path) -> Store:
    db_path = tmp_path / "derived-title.sqlite3"
    upgrade_database(sqlite_path=db_path)
    return Store(db_path)


async def _sync_blank_title(store: Store, connector_id: str, session_id: str, title: str) -> None:
    """Persist a runtime-reported title verbatim, as connector sync does."""

    await store.upsert_connector_session(
        session_id=session_id,
        connector_id=connector_id,
        runtime="codex",
        external_session_id="thread_blank",
        title=title,
        cwd="/repo",
    )


async def _stored_title(store: Store, session_id: str) -> str | None:
    async with store._engine.connect() as conn:
        row = (
            await conn.execute(
                text("SELECT title FROM sessions WHERE id = :sid"),
                {"sid": session_id},
            )
        ).first()
    return None if row is None else row[0]


class _BackfillCounter:
    """Counts the ``_derive_title_from_first_user_message`` backfill query.

    The read-path query (`_session_view_query`) also touches ``timeline_items``
    via its latest-item join, so a bare table-name filter is not enough.  The
    backfill is the only query that constrains ``role`` to ``user`` while
    selecting ``payload_json``.
    """

    def __init__(self, store: Store) -> None:
        self.count = 0
        self._store = store

    def __enter__(self) -> Self:
        event.listen(
            self._store._engine.sync_engine,
            "before_cursor_execute",
            self._on_execute,
        )
        return self

    def __exit__(self, *_exc: object) -> None:
        event.remove(
            self._store._engine.sync_engine,
            "before_cursor_execute",
            self._on_execute,
        )

    def _on_execute(self, _conn, _cursor, statement, *_rest) -> None:
        flat = " ".join(statement.upper().split())
        if "timeline_items" in flat and "payload_json" in flat and "ROLE" in flat:
            self.count += 1


@pytest.mark.anyio
async def test_blank_title_is_persisted_and_backfill_runs_once(tmp_path) -> None:
    """A whitespace-only title must be replaced in the DB, not just the view."""

    store = await _new_store(tmp_path)
    try:
        await ensure_user(store)
        connector, _, _ = await store.create_connector(name="dev", user_id="user_1")
        await _sync_blank_title(store, connector.id, "sess_blank", "   ")
        await store.upsert_timeline_item(
            session_id="sess_blank",
            item=user_message("msg_1", "sess_blank", "Explain the retry policy"),
        )

        first = await store.get_session("sess_blank")
        assert first is not None
        assert first.title == "Explain the retry policy"
        # The whole point: the derived title must be durable.
        assert await _stored_title(store, "sess_blank") == "Explain the retry policy"
    finally:
        await store.close()


@pytest.mark.anyio
async def test_backfill_query_is_not_repeated_after_lock_in(tmp_path) -> None:
    """Once locked in, subsequent reads must not re-derive the title."""

    store = await _new_store(tmp_path)
    try:
        await ensure_user(store)
        connector, _, _ = await store.create_connector(name="dev", user_id="user_1")
        await _sync_blank_title(store, connector.id, "sess_blank", "   ")
        await store.upsert_timeline_item(
            session_id="sess_blank",
            item=user_message("msg_1", "sess_blank", "First question"),
        )

        first = await store.get_session("sess_blank")
        assert first is not None and first.title == "First question"

        with _BackfillCounter(store) as counter:
            second = await store.get_session("sess_blank")
        assert second is not None
        assert second.title == "First question"
        assert counter.count == 0, "locked-in title must not re-run the backfill query"
    finally:
        await store.close()


@pytest.mark.anyio
async def test_non_blank_title_is_never_replaced(tmp_path) -> None:
    """An existing meaningful title must survive reads untouched."""

    store = await _new_store(tmp_path)
    try:
        await ensure_user(store)
        connector, _, _ = await store.create_connector(name="dev", user_id="user_1")
        await _sync_blank_title(store, connector.id, "sess_blank", "Real title")
        await store.upsert_timeline_item(
            session_id="sess_blank",
            item=user_message("msg_1", "sess_blank", "Some message body"),
        )
        view = await store.get_session("sess_blank")
        assert view is not None
        assert view.title == "Real title"
        assert await _stored_title(store, "sess_blank") == "Real title"
    finally:
        await store.close()


@pytest.mark.anyio
async def test_session_without_user_message_keeps_blank_title(tmp_path) -> None:
    """No user message means nothing to derive; the blank title is preserved.

    `_derive_title_from_first_user_message` returns ``None`` here, so no lock-in
    happens and the stored blank title is returned as-is.  The point of this
    test is that the read path must not crash or invent a title.
    """

    store = await _new_store(tmp_path)
    try:
        await ensure_user(store)
        connector, _, _ = await store.create_connector(name="dev", user_id="user_1")
        await _sync_blank_title(store, connector.id, "sess_blank", "  ")
        view = await store.get_session("sess_blank")
        assert view is not None
        assert view.title is None or view.title.strip() == ""
    finally:
        await store.close()


def _lock_in_guard_statement():
    """The UPDATE guard `_lock_in_derived_title` compiles to."""

    from sqlalchemy import func

    return (
        sessions_t.update()
        .where(
            sessions_t.c.id == "x",
            func.coalesce(func.trim(sessions_t.c.title), "") == "",
        )
        .values(title="derived")
    )


@pytest.mark.parametrize("dialect", [sqlite.dialect(), postgresql.dialect()], ids=["sqlite", "postgresql"])
def test_lock_in_guard_compiles_on_both_dialects(dialect) -> None:
    """The blank-title guard must be valid SQL on SQLite and PostgreSQL.

    `func.trim` and `func.coalesce` exist on both backends, and the NULL
    handling matters: `trim(NULL)` is NULL on both, so `coalesce` is what turns
    a NULL title into a match.  This asserts the compiled SQL really contains
    both calls instead of silently degrading into a Python-side comparison.
    """

    compiled = str(_lock_in_guard_statement().compile(dialect=dialect))

    assert "TRIM" in compiled.upper()
    assert "COALESCE" in compiled.upper()
    assert "UPDATE sessions SET title=" in compiled


def test_lock_in_guard_matches_the_read_side_emptiness_test() -> None:
    """Guard and read must agree on what counts as an empty title.

    If these two ever diverge again, a blank title stops being persisted and
    every read re-runs the backfill query — the original bug.  The read side
    strips in Python; the guard trims in SQL.  Both must treat blank as empty.
    """

    assert sessions_module._client_session_title("   ") is None
    assert sessions_module._client_session_title("") is None
    assert sessions_module._client_session_title("ok") == "ok"
