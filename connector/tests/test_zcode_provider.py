"""Tests for the ZCode runtime provider identity, schema, and claims."""

from __future__ import annotations

import asyncio
import sys
from pathlib import Path

import pytest

from connector.runtime_protocol import (
    RuntimeConfig,
    RuntimeInvalidRequestError,
    RuntimeSourceKey,
)
from connector.runtime_protocol.filesystem import filesystem_resource_key
from connector.runtimes.zcode import provider_config
from connector.runtimes.zcode.provider import (
    ZCODE_CONFIG_SCHEMA_REVISION,
    ZCodeProvider,
)
from connector.runtimes.zcode.runtime import ZCodeRuntime


def test_zcode_provider_identity() -> None:
    provider = ZCodeProvider()
    assert provider.runtime == "zcode"
    assert provider.runtime_type == "zcode"
    assert provider.implementation_type == "local-service"
    assert provider.display_name == "ZCode"
    assert provider.instance_policy == "single"


def test_zcode_provider_discover_reports_available() -> None:
    provider = ZCodeProvider()
    descriptor = asyncio.run(provider.discover())
    assert descriptor.runtime_type == "zcode"
    assert descriptor.available is True
    assert descriptor.reason is None
    assert descriptor.metadata["configured"] is True
    assert descriptor.metadata["platform"] == sys.platform
    assert descriptor.capabilities["sessionDiscovery"] is True
    assert descriptor.capabilities["startTurn"] is True
    assert descriptor.capabilities["interruptTurn"] is True
    assert descriptor.capabilities["ipc"] is False


def test_zcode_config_schema_shape() -> None:
    schema = provider_config.zcode_config_schema()
    assert schema["type"] == "object"
    assert "zcodeDataDir" in schema["properties"]
    assert "zcodeExe" in schema["properties"]
    assert "pollIntervalMs" in schema["properties"]
    assert schema["additionalProperties"] is False


def test_zcode_provider_config_schema_revision() -> None:
    provider = ZCodeProvider()
    schema_info = asyncio.run(provider.get_config_schema())
    assert schema_info.runtime == "zcode"
    assert schema_info.revision == ZCODE_CONFIG_SCHEMA_REVISION
    assert "zcodeDataDir" in schema_info.schema["properties"]
    assert schema_info.defaults["pollIntervalMs"] == 5_000


def test_zcode_validate_config_accepts_defaults(tmp_path: Path) -> None:
    provider = ZCodeProvider()
    values = {
        "zcodeDataDir": str(tmp_path / "data"),
        "pollIntervalMs": 5000,
        "requestTimeoutMs": 60000,
    }
    config = asyncio.run(provider.validate_config(values))
    assert isinstance(config, RuntimeConfig)
    assert config.runtime == "zcode"
    assert config.revision == ZCODE_CONFIG_SCHEMA_REVISION
    assert config.values["pollIntervalMs"] == 5000
    # Reachability is a separate concern: validate_config must succeed even
    # when the desktop has not yet created the database.
    assert config.metadata["configured"] is False


def test_zcode_validate_config_rejects_bad_path(tmp_path: Path) -> None:
    provider = ZCodeProvider()
    with pytest.raises(RuntimeInvalidRequestError, match="absolute"):
        asyncio.run(provider.validate_config({"zcodeDataDir": "relative/dir"}))


def test_zcode_validate_config_rejects_bad_poll_interval(tmp_path: Path) -> None:
    provider = ZCodeProvider()
    with pytest.raises(RuntimeInvalidRequestError, match="pollIntervalMs"):
        asyncio.run(
            provider.validate_config(
                {
                    "zcodeDataDir": str(tmp_path / "data"),
                    "pollIntervalMs": 42,
                }
            )
        )


def test_zcode_provider_resource_claims(tmp_path: Path) -> None:
    provider = ZCodeProvider()
    config = RuntimeConfig(
        runtime="zcode",
        revision=ZCODE_CONFIG_SCHEMA_REVISION,
        values={
            "zcodeDataDir": str(tmp_path / "data"),
            "pollIntervalMs": 5000,
            "requestTimeoutMs": 60000,
        },
        schema={},
        ui_schema={},
        metadata={},
    )
    claims = provider.resource_claims(config)
    assert len(claims) == 1
    claim = claims[0]
    assert claim.kind == "zcode_data_dir"
    assert claim.key == filesystem_resource_key(tmp_path / "data")


def test_zcode_provider_session_source_key(tmp_path: Path) -> None:
    provider = ZCodeProvider()
    config = RuntimeConfig(
        runtime="zcode",
        revision=ZCODE_CONFIG_SCHEMA_REVISION,
        values={
            "zcodeDataDir": str(tmp_path / "data"),
            "pollIntervalMs": 5000,
            "requestTimeoutMs": 60000,
        },
        schema={},
        ui_schema={},
        metadata={},
    )
    key = provider.session_source_key(config)
    assert isinstance(key, RuntimeSourceKey)
    assert key.kind == "zcode_data_dir"
    # The key must be stable: re-resolving the same config yields the same key.
    assert key.key == filesystem_resource_key(tmp_path / "data")


def test_zcode_provider_create_runtime(tmp_path: Path) -> None:
    provider = ZCodeProvider()
    config = RuntimeConfig(
        runtime="zcode",
        revision=ZCODE_CONFIG_SCHEMA_REVISION,
        values={
            "zcodeDataDir": str(tmp_path / "data"),
            "pollIntervalMs": 5000,
            "requestTimeoutMs": 60000,
        },
        schema={},
        ui_schema={},
        metadata={},
    )
    runtime = asyncio.run(provider.create_runtime(config, host=None))  # type: ignore[arg-type]
    assert isinstance(runtime, ZCodeRuntime)
    assert runtime.sync_mode == "polling"
