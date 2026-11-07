"""The planning notification path the plan panels depend on.

Neither end tested this before, which is how a chain whose pieces existed could
still carry no plans: the host could not publish, the server had no channel, and
the events builder had no planning branch. These tests drive the server half -
the notification that arrives from the connector and the event the client waits
for - so the transport cannot silently stop again.
"""

from __future__ import annotations

import asyncio
from types import SimpleNamespace

from agent_server.core.events import events_from_invalidation
from agent_server.services.connector_notifications import (
    NotificationValidationError,
    PlanningNotificationHandler,
)
from agent_server.services.ingest_effects import IngestEffect


class FakePlanningStore:
    """The two repository calls the handler makes."""

    def __init__(self, *, bound: bool = True) -> None:
        self._bound = bound
        self.saved: list[str] = []

    async def get_session(self, session_id):
        """A session whose binding either matches the notification or does not."""
        if not self._bound:
            raise KeyError(session_id)
        return SimpleNamespace(
            connectorId="conn_1", runtime="dsh", runtimeId="rti_1",
        )

    async def save_planning(self, session_id, payload) -> bool:
        self.saved.append(session_id)
        return True


def _draft() -> dict:
    """A plan mid-writing: the state the panel renders while it streams."""
    return {
        "draftId": "d1", "planId": "p1", "revision": 1, "seq": 0,
        "content": "# 迁移计划", "contentState": "drafting",
    }


def _state(**overrides) -> dict:
    state = {
        "sessionId": "sess_1", "runtime": "dsh", "runtimeId": "rti_1",
        "effectiveMode": "plan", "freshness": "fresh", "epoch": "e1", "revision": 1,
    }
    state.update(overrides)
    return state


def _apply(handler, method="session.planning.updated", params=None):
    return asyncio.run(
        handler.apply(connector_id="conn_1", method=method, params=params or _state()),
    )


def test_a_plan_producing_state_is_carried_as_an_ingest_effect() -> None:
    store = FakePlanningStore()
    handler = PlanningNotificationHandler(store)

    effect = _apply(handler, params=_state(draft=_draft()))
    assert isinstance(effect, IngestEffect)
    assert effect.session_id == "sess_1"
    assert effect.planning_state is not None
    # A drafting session must be published even without a durable write, because
    # the panel is mid-render and the draft is what it is showing.
    assert store.saved == []


def test_a_state_without_a_draft_is_persisted() -> None:
    store = FakePlanningStore()
    handler = PlanningNotificationHandler(store)

    effect = _apply(handler)
    assert effect.planning_state is not None
    assert store.saved == ["sess_1"], "a quiet state still has to be stored to survive a reload"


def test_a_state_bound_to_another_runtime_is_refused() -> None:
    store = FakePlanningStore(bound=False)
    handler = PlanningNotificationHandler(store)

    try:
        _apply(handler)
    except NotificationValidationError as exc:
        assert exc.code == "planning_binding_invalid"
    else:
        raise AssertionError("a plan bound to another runtime must not be ingested")
    assert store.saved == []


def test_other_notifications_are_left_to_their_own_handlers() -> None:
    handler = PlanningNotificationHandler(FakePlanningStore())
    assert _apply(handler, method="session.turnEnded", params={}) is None


def test_a_planning_snapshot_becomes_an_event_for_the_clients() -> None:
    planning = {"sessionId": "sess_1", "effectiveMode": "plan"}

    # The envelope is the ingest batch the connector's notification lands in.
    envelope = {"sessionId": "sess_1", "nextSeq": 7, "planningState": planning}
    events = events_from_invalidation(envelope)
    planning_events = [event for event in events if event.type == "planning.state.updated"]
    assert len(planning_events) == 1, "the clients only refresh when this event is emitted"
    assert planning_events[0].payload["state"] == planning
    assert planning_events[0].sequence == 7, "the event rides the same sequence as its batch"


def test_a_batch_without_a_plan_emits_no_planning_event() -> None:
    events = events_from_invalidation({"sessionId": "sess_1", "nextSeq": 1})
    assert [event for event in events if event.type == "planning.state.updated"] == []
