from __future__ import annotations

import sys
from collections.abc import Mapping
from typing import Any

from jsonschema import Draft202012Validator

from connector.runtime_protocol import (
    AgentRuntime,
    RuntimeConfig,
    RuntimeConfigSchema,
    RuntimeInvalidRequestError,
    RuntimeProvider,
    RuntimeResourceClaim,
    RuntimeSourceKey,
    RuntimeTypeDescriptor,
)
from connector.runtime_protocol.filesystem import filesystem_resource_key
from connector.runtime_protocol.host import RuntimeHostClient
from connector.runtimes.zcode import discovery, provider_config
from connector.runtimes.zcode.runtime import ZCodeRuntime

ZCODE_CONFIG_SCHEMA_REVISION = 1


class ZCodeProvider(RuntimeProvider):
    @property
    def runtime(self) -> str:
        return "zcode"

    @property
    def runtime_type(self) -> str:
        return "zcode"

    @property
    def implementation_type(self) -> str:
        return "local-service"

    @property
    def display_name(self) -> str:
        return "ZCode"

    @property
    def description(self) -> str:
        return "ZCode desktop session database runtime"

    async def discover(self) -> RuntimeTypeDescriptor:
        """Report the supported runtime type. Database reachability is a config check."""

        result = await discovery.discover(provider_config.default_config_values())
        metadata = dict(result.metadata or {})
        metadata.update(
            {
                "protocolVersion": "1.0",
                "configured": result.configured,
                "platform": sys.platform,
            }
        )
        return RuntimeTypeDescriptor(
            runtime_type=self.runtime_type,
            display_name=self.display_name,
            description=self.description,
            implementation_type=self.implementation_type,
            available=result.available,
            capabilities=provider_config.zcode_capabilities(),
            reason=result.reason,
            config_schema=await self.get_config_schema(),
            instance_policy=self.instance_policy,
            max_instances=self.max_instances,
            metadata=metadata,
        )

    async def get_config_schema(self) -> RuntimeConfigSchema:
        return self._config_schema()

    async def validate_config(self, values: Mapping[str, Any]) -> RuntimeConfig:
        raw = dict(values)
        errors = sorted(
            Draft202012Validator(
                provider_config.zcode_config_schema()
            ).iter_errors(raw),
            key=lambda error: list(error.absolute_path),
        )
        if errors:
            path = "/" + "/".join(str(part) for part in errors[0].absolute_path)
            raise RuntimeInvalidRequestError(
                f"zcode config is invalid at {path or '/'}: {errors[0].message}"
            )
        normalized = provider_config.normalized_config_values(raw)
        # Reachability is not validity: an offline ZCode just keeps the runtime
        # in a starting/retryable state until the desktop appears.
        result = await discovery.probe(normalized)
        schema_info = self._config_schema()
        metadata = dict(result.metadata or {})
        metadata.update(
            {
                "protocolVersion": "1.0",
                "configured": result.configured,
                "platform": sys.platform,
            }
        )
        return RuntimeConfig(
            runtime=self.runtime,
            revision=ZCODE_CONFIG_SCHEMA_REVISION,
            values=normalized,
            schema=schema_info.schema,
            ui_schema=schema_info.ui_schema,
            metadata=metadata,
        )

    def _config_schema(self) -> RuntimeConfigSchema:
        return RuntimeConfigSchema(
            runtime=self.runtime,
            revision=ZCODE_CONFIG_SCHEMA_REVISION,
            schema=provider_config.zcode_config_schema(),
            ui_schema={
                "order": [
                    "zcodeDataDir",
                    "zcodeExe",
                    "pollIntervalMs",
                    "requestTimeoutMs",
                ],
                "zcodeDataDir": {"component": "path"},
                "zcodeExe": {"component": "path"},
            },
            defaults=provider_config.default_config_values(),
            metadata={
                "storageMode": "zcode-native",
                "sameSessionWriterLimit": 1,
                "crossProcessWriterExclusion": False,
            },
        )

    async def create_runtime(
        self,
        config: RuntimeConfig,
        host: RuntimeHostClient,
    ) -> AgentRuntime:
        return ZCodeRuntime(config=config, host=host)

    def resource_claims(
        self,
        config: RuntimeConfig,
    ) -> tuple[RuntimeResourceClaim, ...]:
        values = dict(config.values)
        data_dir = provider_config.zcode_data_dir(values)
        return (
            RuntimeResourceClaim(
                kind="zcode_data_dir",
                key=filesystem_resource_key(data_dir),
                label=f"ZCode data directory {str(data_dir)!r}",
            ),
        )

    def session_source_key(self, config: RuntimeConfig) -> RuntimeSourceKey:
        # Keyed by the data directory so existing session ids stay stable across
        # connector restarts.
        return RuntimeSourceKey(
            kind="zcode_data_dir",
            key=filesystem_resource_key(provider_config.zcode_data_dir(dict(config.values))),
        )
