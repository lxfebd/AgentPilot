from __future__ import annotations

from typing import Any

from connector.runtimes.model_gateway import ModelGateway

CODEX_MODEL_GATEWAY_PROVIDER_ID = "agent_pilot_gateway"


def codex_model_gateway_config(gateway: ModelGateway) -> dict[str, Any]:
    return {
        "model_providers": {
            CODEX_MODEL_GATEWAY_PROVIDER_ID: {
                "name": "AgentPilot Model Gateway",
                "base_url": gateway.base_url,
                "experimental_bearer_token": gateway.api_key,
                "wire_api": "responses",
                "requires_openai_auth": False,
            }
        }
    }
