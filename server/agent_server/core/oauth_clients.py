from __future__ import annotations

from dataclasses import dataclass
import re


# Loopback redirect for the DSH plugin: the OS allocates the port, so the host,
# scheme and path must match exactly and only the port is free.  No credentials,
# queries, fragments or DNS names.
_LOOPBACK_REDIRECT = re.compile(r"http://127\.0\.0\.1:([1-9][0-9]{0,4})/oauth/callback")

_LOOPBACK_CLIENT_IDS = frozenset({"agent-pilot-dsh-plugin", "agents-anywhere-dsh-plugin"})


@dataclass(frozen=True)
class FirstPartyOAuthClient:
    client_id: str
    name: str
    redirect_uri: str

    def allows_redirect(self, uri: str) -> bool:
        if self.client_id not in _LOOPBACK_CLIENT_IDS:
            return uri == self.redirect_uri
        match = _LOOPBACK_REDIRECT.fullmatch(uri)
        return match is not None and 1024 <= int(match[1]) <= 65535


MOBILE_OAUTH_CLIENT = FirstPartyOAuthClient(
    client_id="agent-pilot-mobile",
    name="AgentPilot Mobile",
    redirect_uri="agent-pilot://oauth/callback",
)

DESKTOP_OAUTH_CLIENT = FirstPartyOAuthClient(
    client_id="agent-pilot-desktop",
    name="AgentPilot Desktop",
    redirect_uri="agent-pilot-desktop://oauth/callback",
)

DSH_PLUGIN_OAUTH_CLIENT = FirstPartyOAuthClient(
    client_id="agent-pilot-dsh-plugin",
    name="AgentPilot DSH Plugin",
    redirect_uri="http://127.0.0.1:{port}/oauth/callback",
)

# Pre-rebrand ids.  These are not decoration: the client id is compiled into
# every shipped build, so an install that predates the rebrand still sends
# `agents-anywhere-*` and would be rejected with "oauth client not found" at
# the authorize step.  They stay registered until no such install is left in
# the wild, which needs a coordinated client release - not a server change.
LEGACY_BRAND_OAUTH_CLIENTS = (
    FirstPartyOAuthClient(
        client_id="agents-anywhere-mobile",
        name="Agents Anywhere Mobile",
        redirect_uri="agents-anywhere://oauth/callback",
    ),
    FirstPartyOAuthClient(
        client_id="agents-anywhere-desktop",
        name="Agents Anywhere Desktop",
        redirect_uri="agents-anywhere-desktop://oauth/callback",
    ),
    FirstPartyOAuthClient(
        client_id="agents-anywhere-dsh-plugin",
        name="Agents Anywhere DSH Plugin",
        redirect_uri="http://127.0.0.1:{port}/oauth/callback",
    ),
)

FIRST_PARTY_OAUTH_CLIENTS = {
    client.client_id: client
    for client in (
        MOBILE_OAUTH_CLIENT,
        DESKTOP_OAUTH_CLIENT,
        DSH_PLUGIN_OAUTH_CLIENT,
        *LEGACY_BRAND_OAUTH_CLIENTS,
    )
}


def first_party_oauth_client(client_id: str) -> FirstPartyOAuthClient | None:
    return FIRST_PARTY_OAUTH_CLIENTS.get(client_id)
