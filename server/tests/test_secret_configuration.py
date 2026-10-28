"""The token-signing secret must be configured explicitly.

A hardcoded fallback used to sign every access token when the environment
variable was missing, so the fallback now needs an explicit opt-in and the
default is a startup error.
"""

from __future__ import annotations

import pytest

from agent_server.core.secret import (
    DEV_SECRET,
    DEV_SECRET_ENV,
    SECRET_ENV,
    MissingSecretError,
    resolve_secret,
    validate_secret_configuration,
)


@pytest.fixture(autouse=True)
def _clean_secret_env(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv(SECRET_ENV, raising=False)
    monkeypatch.delenv(DEV_SECRET_ENV, raising=False)


def test_configured_secret_is_used(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv(SECRET_ENV, "unit-test-secret")
    assert resolve_secret() == b"unit-test-secret"


def test_blank_secret_is_not_a_secret(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv(SECRET_ENV, "   ")
    with pytest.raises(MissingSecretError):
        resolve_secret()


def test_missing_secret_fails_closed() -> None:
    with pytest.raises(MissingSecretError):
        resolve_secret()
    with pytest.raises(MissingSecretError):
        validate_secret_configuration()


def test_dev_secret_requires_explicit_opt_in(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv(DEV_SECRET_ENV, "1")
    assert resolve_secret() == DEV_SECRET.encode("utf-8")

    monkeypatch.setenv(DEV_SECRET_ENV, "")
    with pytest.raises(MissingSecretError):
        resolve_secret()


def test_configured_secret_wins_over_dev_flag(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv(DEV_SECRET_ENV, "1")
    monkeypatch.setenv(SECRET_ENV, "preferred-secret")
    assert resolve_secret() == b"preferred-secret"
