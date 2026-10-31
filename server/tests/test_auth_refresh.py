"""Tests for POST /auth/refresh and refresh-token issuance across auth flows.

The refresh chain is deliberately stateless: the token is an HMAC-signed blob
carrying the user id, audience and a fingerprint of the stored password hash.
These tests pin that contract because every client (desktop / web / android /
ios) fails hard without it.
"""

from __future__ import annotations

import asyncio
from datetime import UTC, datetime

from conftest import ApiV2TestClient as TestClient
from conftest import make_test_client


def make_client(tmp_path) -> TestClient:
    return make_test_client(tmp_path / "test.sqlite3")


def register(
    client: TestClient, user_id: str = "user1", password: str = "secret"
) -> dict:
    cfg = client.get("/auth/config").json()
    body: dict[str, object] = {
        "email": f"{user_id}@example.test",
        "displayName": user_id,
        "password": password,
    }
    if cfg["needsBootstrap"]:
        token = client.app.state.setup_token.peek()
        if token is not None:
            body["setupToken"] = token
    response = client.post("/auth/register", json=body)
    assert response.status_code == 200, response.text
    return response.json()


def login(client: TestClient, user_id: str = "user1", password: str = "secret"):
    return client.post(
        "/auth/login",
        json={"email": f"{user_id}@example.test", "password": password},
    )


def refresh(client: TestClient, token: str):
    return client.post("/auth/refresh", json={"refreshToken": token})


def bearer(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def parse_ts(value: str) -> datetime:
    return datetime.fromisoformat(value)


# ---------- issuance ---------------------------------------------------------


def test_register_issues_refresh_token_with_expiry(tmp_path):
    client = make_client(tmp_path)
    body = register(client)

    assert body["refreshToken"]
    assert body["refreshToken"] != body["accessToken"]
    # expiresAt describes the short-lived access token, not the refresh token.
    lifetime = parse_ts(body["expiresAt"]) - datetime.now(UTC)
    assert 6 * 24 * 3600 < lifetime.total_seconds() <= 7 * 24 * 3600


def test_login_issues_refresh_token(tmp_path):
    client = make_client(tmp_path)
    register(client)

    body = login(client).json()

    assert body["refreshToken"]
    assert body["expiresAt"]


def test_mobile_login_exchange_issues_the_unified_refresh_token(tmp_path):
    client = make_client(tmp_path)
    first = register(client)

    qr = client.post("/auth/mobile-login/qr", headers=bearer(first["accessToken"]))
    assert qr.status_code == 200, qr.text
    login_token = qr.json()["loginToken"]
    # The phone scans the QR: request records requested_at, then the desktop
    # user approves. Approval without a request is a no-op by design.
    requested = client.post(
        "/auth/mobile-login/request",
        json={
            "loginToken": login_token,
            "userId": first["userId"],
            "deviceName": "Test Phone",
        },
    )
    assert requested.status_code == 200, requested.text
    confirm = client.post(
        "/auth/mobile-login/confirm",
        headers=bearer(first["accessToken"]),
        json={"loginToken": login_token, "approved": True},
    )
    assert confirm.status_code == 200, confirm.text

    exchange = client.post(
        "/auth/mobile-login/exchange",
        json={"loginToken": login_token, "userId": first["userId"]},
    )
    assert exchange.status_code == 200, exchange.text
    body = exchange.json()
    # One token, reachable through both paths, accepted by /auth/refresh.
    assert body["refreshToken"] == body["auth"]["refreshToken"]
    assert refresh(client, body["refreshToken"]).status_code == 200


# ---------- /auth/refresh ----------------------------------------------------


def test_refresh_rotates_the_token_and_returns_a_usable_access_token(tmp_path):
    client = make_client(tmp_path)
    first = register(client)

    response = refresh(client, first["refreshToken"])

    assert response.status_code == 200, response.text
    second = response.json()
    assert second["refreshToken"] and second["refreshToken"] != first["refreshToken"]
    # The access token keeps working even if it is byte-identical (issued in
    # the same second); what matters is it authenticates with the new one.
    me = client.get("/auth/me", headers=bearer(second["accessToken"]))
    assert me.status_code == 200
    assert me.json()["userId"] == first["userId"]


def test_refresh_survives_repeated_rotation(tmp_path):
    client = make_client(tmp_path)
    token = register(client)["refreshToken"]

    for _ in range(3):
        response = refresh(client, token)
        assert response.status_code == 200, response.text
        token = response.json()["refreshToken"]


def test_refresh_rejects_garbage_and_access_tokens(tmp_path):
    client = make_client(tmp_path)
    first = register(client)

    assert refresh(client, "not-a-token").status_code == 401
    assert refresh(client, "").status_code == 401
    # An access token is signed with the same secret but the wrong token kind.
    assert refresh(client, first["accessToken"]).status_code == 401


# ---------- invalidation -----------------------------------------------------


def test_refresh_is_rejected_after_a_password_change(tmp_path):
    client = make_client(tmp_path)
    first = register(client)

    changed = client.post(
        "/auth/change-password",
        headers=bearer(first["accessToken"]),
        json={"newPassword": "secret2"},
    )
    assert changed.status_code == 204, changed.text

    stale = refresh(client, first["refreshToken"])
    assert stale.status_code == 401
    assert "password" in stale.json()["detail"]

    # A fresh login is the only way back in, and its token works.
    relogin = login(client, password="secret2")
    assert relogin.status_code == 200, relogin.text
    assert refresh(client, relogin.json()["refreshToken"]).status_code == 200


def test_refresh_is_rejected_for_a_disabled_user(tmp_path):
    client = make_client(tmp_path)
    register(client)  # bootstrap admin; the repository refuses to disable them

    member = asyncio.run(
        client.app.state.store.create_email_user(
            email="member@example.test",
            display_name="member",
            password="secret",
            password_hash=None,
            role="member",
            verification_code=None,
            require_verification=False,
        )
    )
    signed_in = login(client, "member").json()
    assert refresh(client, signed_in["refreshToken"]).status_code == 200

    asyncio.run(client.app.state.store.set_user_disabled(member.userId, True))

    assert refresh(client, signed_in["refreshToken"]).status_code == 401
