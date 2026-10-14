"""Schedule failure visibility.

A schedule that could not run used to look healthy: `next_run_at` kept
advancing, the row stayed enabled, and the only trace of the failure was a
server-log line the user never sees.  `lastError` / `lastErrorAt` now carry the
reason on the schedule itself, and an edit clears them so a stale failure is
never shown as if it were current.
"""

from __future__ import annotations

import asyncio

from tests.test_auth import admin_token, bearer, make_client, register


def _record_failure(client, schedule_id: str, message: str, at: str, **extra) -> None:
    """Write what ScheduleExecutor writes when a run cannot happen."""

    async def go() -> None:
        await client.app.state.store.schedules.update(
            schedule_id, None, last_error=message, last_error_at=at, **extra
        )

    asyncio.run(go())


def _client(tmp_path):
    client = make_client(tmp_path)
    admin = admin_token(client)
    client.patch("/admin/settings", json={"registrationOpen": True}, headers=bearer(admin))
    response = register(client, "viewer")
    assert response.status_code == 200, response.text
    return client, response.json()["accessToken"]


def _own_connector_and_project(client, token: str) -> tuple[str, str]:
    """Return (connectorId, projectId) owned by this user.

    A schedule must name a target — an existing session or a project +
    connector pair — or the server rejects it at create time.
    """

    connector = client.post("/connectors", json={"name": "laptop"}, headers=bearer(token))
    assert connector.status_code == 200, connector.text
    connector_id = connector.json()["connector"]["id"]
    project = client.post(
        "/projects",
        json={"name": "workspace", "workspacePath": "/repo", "connectorId": connector_id},
        headers=bearer(token),
    )
    assert project.status_code == 200, project.text
    return connector_id, project.json()["project"]["id"]


def _create(client, token: str) -> str:
    connector_id, project_id = _own_connector_and_project(client, token)
    response = client.post(
        "/schedules",
        json={
            "title": "Daily digest",
            "prompt": "Summarize yesterday.",
            "scheduleKind": "daily",
            "runTime": "08:00",
            "timezone": "UTC",
            "projectId": project_id,
            "connectorId": connector_id,
        },
        headers=bearer(token),
    )
    assert response.status_code == 201, response.text
    return response.json()["schedule"]["id"]


def test_created_schedule_has_no_failure(tmp_path) -> None:
    client, token = _client(tmp_path)
    schedule_id = _create(client, token)
    schedule = next(
        item
        for item in client.get("/schedules", headers=bearer(token)).json()["schedules"]
        if item["id"] == schedule_id
    )

    assert schedule["lastError"] is None
    assert schedule["lastErrorAt"] is None


def test_list_exposes_the_recorded_failure(tmp_path) -> None:
    client, token = _client(tmp_path)
    schedule_id = _create(client, token)

    # What ScheduleExecutor writes when a run cannot happen.
    _record_failure(client, schedule_id, "connector is offline", "2026-10-12T00:00:00Z")

    schedules = client.get("/schedules", headers=bearer(token)).json()["schedules"]
    row = next(item for item in schedules if item["id"] == schedule_id)
    assert row["lastError"] == "connector is offline"
    assert row["lastErrorAt"] == "2026-10-12T00:00:00Z"
    # The schedule is still enabled and still scheduled: the failure is a
    # report, not a state the client has to infer from missing fields.
    assert row["enabled"] is True
    assert row["nextRunAt"]


def test_editing_a_schedule_clears_the_previous_failure(tmp_path) -> None:
    client, token = _client(tmp_path)
    schedule_id = _create(client, token)

    async def break_it() -> None:
        await client.app.state.store.schedules.update(
            schedule_id,
            None,
            last_error="target no longer exists: session not found",
            last_error_at="2026-10-12T00:00:00Z",
            enabled=0,
            next_run_at=None,
        )

    asyncio.run(break_it())

    # The user fixes the target and switches it back on.  The old failure
    # describes settings that no longer exist, so it must not keep showing.
    patched = client.patch(
        f"/schedules/{schedule_id}",
        json={"enabled": True},
        headers=bearer(token),
    )
    assert patched.status_code == 200, patched.text
    schedule = patched.json()["schedule"]

    assert schedule["enabled"] is True
    assert schedule["lastError"] is None
    assert schedule["lastErrorAt"] is None

    listed = next(
        item
        for item in client.get("/schedules", headers=bearer(token)).json()["schedules"]
        if item["id"] == schedule_id
    )
    assert listed["lastError"] is None
