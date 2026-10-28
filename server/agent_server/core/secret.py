"""The single token-signing secret of this process.

User access tokens, connector access tokens, setup tokens and email-code
hashes all use this value. A hardcoded fallback let a deployment that forgot
one environment variable sign tokens with a publicly known key, so the missing
configuration is now a startup error: set AGENT_SERVER_SECRET, or opt into the
development secret with AGENT_SERVER_ALLOW_DEV_SECRET=1.
"""

from __future__ import annotations

import os

SECRET_ENV = "AGENT_SERVER_SECRET"
DEV_SECRET_ENV = "AGENT_SERVER_ALLOW_DEV_SECRET"
DEV_SECRET = "agent-server-dev-secret"


class MissingSecretError(RuntimeError):
    """No token-signing secret is configured."""


def _flag(name: str) -> bool:
    return os.environ.get(name, "").strip().lower() in {"1", "true", "yes", "on"}


def resolve_secret() -> bytes:
    """Return the signing secret, or raise when none is configured."""
    value = os.environ.get(SECRET_ENV, "").strip()
    if value:
        return value.encode("utf-8")
    if _flag(DEV_SECRET_ENV):
        return DEV_SECRET.encode("utf-8")
    raise MissingSecretError(
        f"{SECRET_ENV} is required: set it to a long random value, or set "
        f"{DEV_SECRET_ENV}=1 to use the development secret."
    )


def validate_secret_configuration() -> None:
    """Fail fast at startup when no signing secret is configured."""
    resolve_secret()
