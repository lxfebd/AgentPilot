from __future__ import annotations

import asyncio
from collections.abc import Mapping
from typing import Any

import pytest

from connector.runtime_protocol import (
    AgentRuntime,
    RuntimeConfig,
    RuntimeConfigSchema,
    RuntimeHostClient,
    RuntimeIdentity,
    RuntimeInstancePolicy,
    RuntimeInstanceSpec,
    RuntimeProvider,
    RuntimeSupervisor,
    RuntimeTypeDescriptor,
    RuntimeUnavailableError,
)
from connector.runtime_protocol import supervisor as supervisor_module


class FakeHost(RuntimeHostClient):
    @property
    def connector_id(self) -> str:
        return "conn_test"


class FakeRuntime(AgentRuntime):
    def __init__(self, host: RuntimeHostClient, *, degraded: bool, message: str | None) -> None:
        self.host = host
        self.degraded = degraded
        self.message = message

    @property
    def identity(self) -> RuntimeIdentity:
        return RuntimeIdentity(runtime="fake", runtime_version="test")

    async def start(self) -> None:
        if self.degraded:
            error: dict[str, Any] | None = None
            if self.message is not None:
                error = {
                    "code": "runtime_unavailable",
                    "message": self.message,
                    "retryable": True,
                }
            await self.host.runtime_health_update("starting", error)
            return
        await self.host.runtime_health_update("running")

    async def stop(self) -> None:
        return None


class FakeProvider(RuntimeProvider):
    runtime = "fake"
    runtime_type = "fake"
    display_name = "Fake Runtime"

    def __init__(self, *, degraded: bool = False, message: str | None = None) -> None:
        self.degraded = degraded
        self.message = message
        self.hosts: list[RuntimeHostClient] = []

    @property
    def instance_policy(self) -> RuntimeInstancePolicy:
        return "multiple"

    async def discover(self) -> RuntimeTypeDescriptor:
        return RuntimeTypeDescriptor(
            runtime_type=self.runtime_type,
            display_name=self.display_name,
            available=True,
            instance_policy=self.instance_policy,
            config_schema=RuntimeConfigSchema(runtime=self.runtime, revision=1, schema={"type": "object"}),
        )

    async def get_config_schema(self) -> RuntimeConfigSchema:
        return RuntimeConfigSchema(runtime=self.runtime, revision=1, schema={"type": "object"})

    async def validate_config(self, values: Mapping[str, Any]) -> RuntimeConfig:
        return RuntimeConfig(runtime=self.runtime, revision=1, values=dict(values))

    async def create_runtime(self, config: RuntimeConfig, host: RuntimeHostClient) -> AgentRuntime:
        self.hosts.append(host)
        return FakeRuntime(host, degraded=self.degraded, message=self.message)


def _spec(runtime_id: str = "rti_wd") -> RuntimeInstanceSpec:
    return RuntimeInstanceSpec(runtime_id=runtime_id, runtime_type="fake", name="W")


def test_unavailable_runtime_surfaces_provider_message(monkeypatch):
    async def run():
        monkeypatch.setattr(supervisor_module, "STARTUP_WATCHDOG_SECONDS", 0.3)
        monkeypatch.setattr(supervisor_module, "STARTUP_WATCHDOG_POLL_SECONDS", 0.05)
        provider = FakeProvider(
            degraded=True,
            message="正在等待本地 DSH Bridge；请启动 DSH 并启用手机连接插件。",
        )
        supervisor = RuntimeSupervisor((provider,), FakeHost())
        await supervisor.start(_spec(), {})
        entry = supervisor.entry("rti_wd")
        assert entry.status == "starting"
        assert entry.error is not None
        assert entry.error["message"] == "正在等待本地 DSH Bridge；请启动 DSH 并启用手机连接插件。"
        with pytest.raises(RuntimeUnavailableError) as excinfo:
            supervisor.resolve_runtime("rti_wd")
        # Clients show this text, so the provider's actionable message must be in it.
        assert "正在等待本地 DSH Bridge" in str(excinfo.value)
        await supervisor.stop("rti_wd")
    asyncio.run(run())


def test_startup_watchdog_fills_missing_error(monkeypatch):
    async def run():
        monkeypatch.setattr(supervisor_module, "STARTUP_WATCHDOG_SECONDS", 0.3)
        monkeypatch.setattr(supervisor_module, "STARTUP_WATCHDOG_POLL_SECONDS", 0.05)
        provider = FakeProvider(degraded=True, message=None)
        supervisor = RuntimeSupervisor((provider,), FakeHost())
        await supervisor.start(_spec(), {})
        entry = supervisor.entry("rti_wd")
        assert entry.status == "starting"
        assert entry.error is None
        await asyncio.sleep(0.6)
        entry = supervisor.entry("rti_wd")
        # A provider that never leaves 'starting' must not block sessions with a
        # bare status; the watchdog attaches a retryable, readable error.
        assert entry.status == "starting"
        assert entry.error is not None
        assert entry.error["code"] == "runtime_unavailable"
        assert entry.error["retryable"] is True
        assert "did not become ready" in entry.error["message"]
        with pytest.raises(RuntimeUnavailableError) as excinfo:
            supervisor.resolve_runtime("rti_wd")
        assert "did not become ready" in str(excinfo.value)
        await supervisor.stop("rti_wd")
    asyncio.run(run())


def test_late_health_promotes_degraded_instance(monkeypatch):
    async def run():
        monkeypatch.setattr(supervisor_module, "STARTUP_WATCHDOG_SECONDS", 0.3)
        monkeypatch.setattr(supervisor_module, "STARTUP_WATCHDOG_POLL_SECONDS", 0.05)
        provider = FakeProvider(degraded=True, message="正在等待本地 DSH Bridge")
        supervisor = RuntimeSupervisor((provider,), FakeHost())
        await supervisor.start(_spec(), {})
        assert supervisor.entry("rti_wd").status == "starting"
        await asyncio.sleep(0.5)
        assert supervisor.entry("rti_wd").error is not None
        # The provider recovers later (bridge comes up) and reports running.
        await provider.hosts[0].runtime_health_update("running")
        entry = supervisor.entry("rti_wd")
        assert entry.status == "running"
        assert entry.error is None
        assert supervisor.resolve_runtime("rti_wd") is not None
        await supervisor.stop("rti_wd")
    asyncio.run(run())


def test_healthy_start_skips_watchdog(monkeypatch):
    async def run():
        monkeypatch.setattr(supervisor_module, "STARTUP_WATCHDOG_SECONDS", 0.3)
        monkeypatch.setattr(supervisor_module, "STARTUP_WATCHDOG_POLL_SECONDS", 0.05)
        provider = FakeProvider(degraded=False)
        supervisor = RuntimeSupervisor((provider,), FakeHost())
        await supervisor.start(_spec(), {})
        assert supervisor.entry("rti_wd").status == "running"
        await asyncio.sleep(0.5)
        assert supervisor.entry("rti_wd").status == "running"
        assert supervisor.entry("rti_wd").error is None
        await supervisor.stop("rti_wd")
    asyncio.run(run())
