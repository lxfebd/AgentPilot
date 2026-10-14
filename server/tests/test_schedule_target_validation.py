"""Target-validation tests for the schedules service.

Regression context: `ScheduleService.create` only validated `sessionId`.  A
new-session target (`projectId` + `connectorId`) was stored verbatim, so a
schedule could be created pointing at a project or connector the user does not
own — or that does not exist.  The create call returned 201 and the failure only
surfaced later inside `ScheduleExecutor._fire`, long after the user left the
form.  `_validate_new_session_target` now rejects those at create/patch time.
"""

from __future__ import annotations

from tests.test_auth import admin_token, bearer, login, make_client, register


def _create_schedule(client, token: str, **overrides):
    body = {
        "title": "Daily digest",
        "prompt": "Summarize yesterday.",
        "scheduleKind": "daily",
        "runTime": "08:00",
        "timezone": "UTC",
    }
    body.update(overrides)
    return client.post("/schedules", json=body, headers=bearer(token))


def _user_token(client, name: str) -> str:
    """Register a second user.

    The bootstrap user is an admin, so they open registration for everyone else;
    without this the server answers 403 "registration is closed".  The admin is
    logged in again rather than re-registered, because the bootstrap register
    call only succeeds on a fresh database.
    """

    admin = login(client, "user1").json()["accessToken"]
    settings = client.patch(
        "/admin/settings",
        json={"registrationOpen": True},
        headers=bearer(admin),
    )
    assert settings.status_code == 200, settings.text
    response = register(client, name)
    assert response.status_code == 200, response.text
    return response.json()["accessToken"]


def _own_project(client, token: str) -> tuple[str, str]:
    """Create the connector + project a user owns and return their ids."""

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


def test_new_session_target_accepts_owned_project_and_connector(tmp_path):
    client = make_client(tmp_path)
    token = admin_token(client)
    connector_id, project_id = _own_project(client, token)

    created = _create_schedule(
        client,
        token,
        projectId=project_id,
        connectorId=connector_id,
    )

    assert created.status_code == 201, created.text
    assert created.json()["schedule"]["projectId"] == project_id
    assert created.json()["schedule"]["connectorId"] == connector_id


def test_new_session_target_rejects_foreign_project(tmp_path):
    client = make_client(tmp_path)
    owner = admin_token(client)
    _owner_connector, owner_project = _own_project(client, owner)

    # A second, unrelated user tries to schedule against the first user's project.
    intruder = _user_token(client, "user2")
    intruder_connector = client.post(
        "/connectors", json={"name": "other"}, headers=bearer(intruder)
    ).json()["connector"]["id"]

    created = _create_schedule(
        client,
        intruder,
        projectId=owner_project,
        connectorId=intruder_connector,
    )

    assert created.status_code == 400, created.text
    assert "projectId" in created.json()["detail"]


def test_new_session_target_rejects_foreign_connector(tmp_path):
    client = make_client(tmp_path)
    owner = admin_token(client)
    owner_connector, _owner_project = _own_project(client, owner)

    intruder = _user_token(client, "user2")
    _intruder_connector, intruder_project = _own_project(client, intruder)

    # Own project, someone else's connector.
    created = _create_schedule(
        client,
        intruder,
        projectId=intruder_project,
        connectorId=owner_connector,
    )

    assert created.status_code == 400, created.text
    assert "connectorId" in created.json()["detail"]


def test_new_session_target_rejects_unknown_project(tmp_path):
    client = make_client(tmp_path)
    token = admin_token(client)
    connector_id, _project = _own_project(client, token)

    created = _create_schedule(
        client,
        token,
        projectId="proj_does_not_exist",
        connectorId=connector_id,
    )

    assert created.status_code == 400, created.text
    assert "projectId" in created.json()["detail"]


def test_new_session_target_rejects_unknown_connector(tmp_path):
    client = make_client(tmp_path)
    token = admin_token(client)
    _connector_id, project_id = _own_project(client, token)

    created = _create_schedule(
        client,
        token,
        projectId=project_id,
        connectorId="conn_does_not_exist",
    )

    assert created.status_code == 400, created.text
    assert "connectorId" in created.json()["detail"]


def test_new_session_target_requires_both_fields(tmp_path):
    client = make_client(tmp_path)
    token = admin_token(client)
    connector_id, project_id = _own_project(client, token)

    project_only = _create_schedule(client, token, projectId=project_id)
    assert project_only.status_code == 400, project_only.text
    assert "projectId and connectorId" in project_only.json()["detail"]

    connector_only = _create_schedule(client, token, connectorId=connector_id)
    assert connector_only.status_code == 400, connector_only.text
    assert "projectId and connectorId" in connector_only.json()["detail"]


def test_patch_rejects_retargeting_to_a_foreign_project(tmp_path):
    client = make_client(tmp_path)
    owner = admin_token(client)
    owner_connector, owner_project = _own_project(client, owner)
    created = _create_schedule(
        client,
        owner,
        projectId=owner_project,
        connectorId=owner_connector,
    )
    assert created.status_code == 201, created.text
    schedule_id = created.json()["schedule"]["id"]

    intruder = _user_token(client, "user2")
    _intruder_connector, intruder_project = _own_project(client, intruder)

    patched = client.patch(
        f"/schedules/{schedule_id}",
        json={"projectId": intruder_project},
        headers=bearer(intruder),
    )
    # The schedule belongs to the owner, so the patch is a 404 before validation.
    assert patched.status_code == 404, patched.text


def test_patch_by_owner_rejects_foreign_project(tmp_path):
    client = make_client(tmp_path)
    owner = admin_token(client)
    owner_connector, owner_project = _own_project(client, owner)
    created = _create_schedule(
        client,
        owner,
        projectId=owner_project,
        connectorId=owner_connector,
    )
    schedule_id = created.json()["schedule"]["id"]

    intruder = _user_token(client, "user2")
    _intruder_connector, intruder_project = _own_project(client, intruder)

    # The owner patches their own schedule but aims it at someone else's project.
    patched = client.patch(
        f"/schedules/{schedule_id}",
        json={"projectId": intruder_project},
        headers=bearer(owner),
    )

    assert patched.status_code == 400, patched.text
    assert "projectId" in patched.json()["detail"]


def test_session_target_still_validates_ownership(tmp_path):
    client = make_client(tmp_path)
    owner = admin_token(client)
    owner_connector, owner_project = _own_project(client, owner)

    # Create the session straight through the store: `create-and-start` needs a
    # live connector, which this test does not have.
    import asyncio

    session_id = asyncio.run(
        client.app.state.store.create_session(
            connector_id=owner_connector,
            project_id=owner_project,
            user_id=None,
            runtime="codex",
            external_session_id="thread-1",
            title="Owned session",
            cwd="/repo",
        )
    ).id

    intruder = _user_token(client, "user2")
    stolen = _create_schedule(client, intruder, sessionId=session_id)

    assert stolen.status_code == 400, stolen.text
    assert "sessionId" in stolen.json()["detail"]

    # The owner can still target their own session.
    own = _create_schedule(client, owner, sessionId=session_id)
    assert own.status_code == 201, own.text
    assert own.json()["schedule"]["sessionId"] == session_id


def test_no_target_at_all_is_rejected_by_create(tmp_path):
    """A schedule with neither target cannot ever fire; reject it up front."""

    client = make_client(tmp_path)
    token = admin_token(client)

    created = _create_schedule(client, token)

    assert created.status_code == 400, created.text
    assert "either sessionId or projectId" in created.json()["detail"]
