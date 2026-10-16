"""Device-level commands for a connector (launch whitelisted local apps)."""

from __future__ import annotations

from agent_server.services.connector_rpc import ConnectorRpcPort

SUPPORTED_LAUNCH_APPS = frozenset({"dsh", "zcode"})


class ConnectorDeviceService:
    def __init__(
        self,
        gateway: ConnectorRpcPort,
    ) -> None:
        self._gateway = gateway

    async def launch(
        self,
        *,
        connector_id: str,
        scope_id: str,
        app: str,
    ) -> object:
        return await self._gateway.request(
            connector_id,
            "device.launch",
            {
                "sessionId": scope_id,
                "app": app,
            },
            timeout=20,
        )
