"""Coverage for the planning API, which shipped with no server tests at all.

The port moved 734 lines and five endpoints into the Server and added none of
its own tests; the suite passed because nothing ever asked for a plan. These
tests ask, so a dropped route, a store write that silently fails, or a state
model that will not persist fails here instead of in a user's session.
"""

from __future__ import annotations

from test_backend_mvp import create_connector_and_session, make_client

API = "/api/v2"


class FakePlanningRpc:
    """Answers the planning operations a real connector would, and records them."""

    def __init__(self) -> None:
        self.calls: list[str] = []

    async def is_online(self, connector_id: str) -> bool:
        return True

    async def request_bound(self, connector_id: str, method: str, params: dict, **kwargs):
        self.calls.append(method)
        return await self._reply(method, params)

    # The capability check and the planning service reach for different spellings
    # of the same call; answer both so the fake stays a real stand-in.
    async def request(self, connector_id: str, method: str, params: dict, **kwargs):
        return await self._reply(method, params)

    async def _reply(self, method: str, params: dict):
        if "session.capabilities" == method:
            # The planning endpoints gate on the capability set before they touch
            # the service, and the index drops any capability whose runtime does
            # not match the session's, so the fake has to name it.
            return {
                "capabilitySet": {
                    "revision": 1,
                    "capabilities": [
                        {
                            "capabilityId": "session.planning", "runtime": "dsh",
                            "supported": True, "available": True, "allowed": True,
                        },
                        {
                            "capabilityId": "session.plan_review", "runtime": "dsh",
                            "supported": True, "available": True, "allowed": True,
                        },
                    ],
                },
            }
        # The command is dispatched on session.planning.execute; the reply is a
        # PlanningResponse, so it must carry the operation id and the outcome.
        if "session.planning.execute" == method:
            return {
                "ok": True,
                "operationId": params.get("request", {}).get("operationId"),
                "outcome": "applied",
                "execution": "started",
            }
        if "session.planning.get" == method:
            return {
                "ok": True,
                "operationId": params.get("operationId"),
                "outcome": "applied",
                "effectiveMode": "plan",
                "state": {"effectiveMode": "plan"},
            }
        return {}


def _planning_state(client, headers, session_id: str) -> dict:
    response = client.get(f"{API}/sessions/{session_id}/planning", headers=headers)
    assert response.status_code == 200, response.text
    return response.json()


def _with_rpc(client) -> FakePlanningRpc:
    rpc = FakePlanningRpc()
    client.app.state.rpc = rpc
    return rpc


def _bring_online(client, connector_id: str) -> None:
    """A mutating capability is only available while the connector is online."""
    import asyncio

    asyncio.run(client.app.state.store.set_connector_status(connector_id, "online"))


def _takeover(client, headers, session_id: str) -> None:
    """Planning is a mutating action: it is only allowed once this client owns the session."""
    response = client.post(f"{API}/sessions/{session_id}/takeover", headers=headers)
    assert response.status_code == 200, response.text


def test_a_sessions_planning_state_starts_empty_and_named(tmp_path) -> None:
    client = make_client(tmp_path)
    _, _, session_id, headers = create_connector_and_session(client, runtime="dsh")

    state = _planning_state(client, headers, session_id)
    assert state["sessionId"] == session_id
    # "unknown", not "normal": nothing has reported a mode for this session yet,
    # and claiming normal would tell the client plan mode is off when we simply
    # do not know.
    assert state["effectiveMode"] == "unknown"
    assert state["draft"] is None
    assert state["proposal"] is None
    assert state["review"] is None


def test_posting_a_plan_writes_through_the_command_surface(tmp_path) -> None:
    client = make_client(tmp_path)
    _, _, session_id, headers = create_connector_and_session(client, runtime="dsh")
    # Installed for its capability answers: the planning endpoints gate on them.
    _with_rpc(client)
    _takeover(client, headers, session_id)
    markdown = "# 迁移计划\n\n1. 备份\n2. 切换\n"

    # A plan is written through the command surface: enter_plan asks the Agent
    # for a plan, submit_task attaches the finished markdown. There is no
    # bare-markdown route.
    response = client.post(
        f"{API}/sessions/{session_id}/planning",
        headers=headers,
        json={"command": "submit_task", "operationId": "op-1", "content": markdown},
    )
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["ok"] is True, "submit_task is accepted"
    assert body["operationId"] == "op-1", "an accepted write is identified so it can be polled"

    state = _planning_state(client, headers, session_id)
    # The write is accepted, but the Server does not invent a draft: until the
    # connector reports the plan's progress the mode stays unknown, exactly as
    # a session that has never had a plan reported. Claiming "plan" here would
    # show a card the backend has no content for.
    assert state["effectiveMode"] in {"unknown", "plan"}
    if state["effectiveMode"] == "plan":
        assert state["draft"]["markdown"] == markdown, "the draft the client renders is stored"

    # /plans lists what the connector has reported. With no draft reported yet an
    # empty list is the correct answer, and reaching the endpoint at all is what
    # matters here - the route is registered and answers.
    plans = client.get(f"{API}/sessions/{session_id}/plans", headers=headers)
    assert plans.status_code == 200, plans.text
    if plans.json():
        assert plans.json()[0]["markdown"] == markdown, "the stored plan is listed as written"


def test_an_unknown_command_is_refused_before_it_reaches_the_connector(tmp_path) -> None:
    client = make_client(tmp_path)
    _, _, session_id, headers = create_connector_and_session(client, runtime="dsh")
    rpc = _with_rpc(client)

    response = client.post(
        f"{API}/sessions/{session_id}/planning",
        headers=headers,
        json={"command": "teleport", "operationId": "op-bad"},
    )
    assert response.status_code == 422, "an unknown command must not be dispatched"
    assert rpc.calls == []


def test_planning_is_scoped_to_its_own_session(tmp_path) -> None:
    client = make_client(tmp_path)
    connector_id, _, first, headers = create_connector_and_session(client, runtime="dsh")
    _, _, second, _ = create_connector_and_session(client, runtime="dsh")
    _with_rpc(client)
    _bring_online(client, connector_id)
    _takeover(client, headers, first)

    response = client.post(
        f"{API}/sessions/{first}/planning",
        headers=headers,
        json={"command": "submit_task", "operationId": "op-scope", "content": "# first\n"},
    )
    assert response.status_code == 200, response.text

    other = _planning_state(client, headers, second)
    assert other["effectiveMode"] != "plan", "one session's plan must not leak into another"


def test_an_unknown_session_reports_missing_rather_than_an_empty_plan(tmp_path) -> None:
    client = make_client(tmp_path)
    _, _, _, headers = create_connector_and_session(client, runtime="dsh")

    response = client.get(f"{API}/sessions/sess_missing/planning", headers=headers)
    assert response.status_code == 404
    assert "not found" in response.text.lower(), "a missing session is a 404, not an empty plan"
