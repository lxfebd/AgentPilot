"""The three planning capabilities reach the capability set the server reads.

The batch-level gap was that the set carried only a static session.planning
entry: clients gating on session.plan_document or session.plan_review saw
nothing, so the plan feature stayed invisible even on DSH - the one runtime
that runs plan mode. These tests drive RuntimeInstance itself, the layer the
Server talks to, rather than the helper in isolation.
"""

import asyncio
from dataclasses import replace

from connector.runtime_protocol.instance_binding import (
    RuntimeInstance,
    RuntimeInstanceSpec,
)
from connector.runtime_protocol.models import (
    RuntimeCapability,
    RuntimeCapabilitySet,
    RuntimeIdentity,
)

INSTANCE = RuntimeInstanceSpec(runtime_id="rti_pi-main", runtime_type="pi", name="Pi 主实例")


def capability_set(planning_metadata: dict | None) -> RuntimeCapabilitySet:
    return RuntimeCapabilitySet(
        runtime="pi",
        session_id="s1",
        revision=1,
        capabilities=(
            RuntimeCapability(
                capability_id="session.send_message", scope="session", runtime="pi",
                session_id="s1", version="1", supported=True, available=True, allowed=True,
            ),
            RuntimeCapability(
                capability_id="session.planning", scope="session", runtime="pi",
                session_id="s1", version="1", supported=False, available=False, allowed=True,
                metadata=planning_metadata,
            ),
        ),
    )


class Port:
    content_streaming = "native_delta"

    def __init__(self, available: bool = True) -> None:
        self.consulted = 0
        self._available = available

    async def available(self, session_id, external_id):
        self.consulted += 1
        return self._available


class PlanningHost:
    def __init__(self, port: Port) -> None:
        self.port = port
        self.closed = False

    async def close(self) -> None:
        self.closed = True


class NativeRuntime:
    """A minimal AgentRuntime carrying a bound planning host."""

    @property
    def identity(self) -> RuntimeIdentity:
        return RuntimeIdentity(runtime="pi", runtime_version="1.0", runtime_id="rti_pi-main")

    @property
    def sync_mode(self) -> str:
        return "polling"

    def __init__(self, metadata: dict | None, available: bool = True) -> None:
        self.port = Port(available)
        self.native = capability_set(metadata)
        self.__dict__["_planning"] = PlanningHost(self.port)

    async def get_runtime_capabilities(self) -> RuntimeCapabilitySet:
        return self.native

    async def get_session_capabilities(self, session_id, external_session_id=None):
        return replace(self.native, session_id=session_id)

    async def stop(self) -> None:
        pass


def test_bridge_reported_capabilities_reach_the_server() -> None:
    native = NativeRuntime({"nativePlanningAvailable": True})
    instance = RuntimeInstance(instance=INSTANCE, native_runtime=native)
    result = asyncio.run(instance.get_runtime_capabilities())
    found = {item.capability_id: item for item in result.capabilities}
    for name in ("session.planning", "session.plan_document", "session.plan_review"):
        assert name in found, f"{name} missing from the set the server reads"
        assert found[name].supported is True, name
        assert found[name].available is True, name
        assert found[name].metadata["contentStreaming"] == "native_delta", name
    assert found["session.send_message"].supported is True, "unrelated capabilities stay untouched"
    assert native.port.consulted == 0, "the bridge already answered; the port must not be consulted"


def test_unreported_capabilities_fall_back_to_the_port() -> None:
    native = NativeRuntime(None, available=True)
    instance = RuntimeInstance(instance=INSTANCE, native_runtime=native)
    result = asyncio.run(instance.get_runtime_capabilities())
    found = {item.capability_id: item for item in result.capabilities}
    for name in ("session.planning", "session.plan_document", "session.plan_review"):
        assert found[name].supported is True, name
    assert native.port.consulted == 1, "without a bridge answer the port decides"


def test_runtime_without_a_planning_host_is_left_alone() -> None:
    native = NativeRuntime(None)
    del native.__dict__["_planning"]
    instance = RuntimeInstance(instance=INSTANCE, native_runtime=native)
    result = asyncio.run(instance.get_runtime_capabilities())
    ids = {item.capability_id for item in result.capabilities}
    assert "session.plan_document" not in ids, "a runtime without a host must not gain capabilities"
    assert "session.planning" in ids, "the runtime's own entry stays"


def test_stopping_the_instance_closes_the_planning_host() -> None:
    native = NativeRuntime({"nativePlanningAvailable": True})
    instance = RuntimeInstance(instance=INSTANCE, native_runtime=native)
    asyncio.run(instance.stop())
    assert native.__dict__["_planning"].closed is True, "stop must close the host"


def test_session_capabilities_carry_the_same_three() -> None:
    native = NativeRuntime({"nativePlanningAvailable": True})
    instance = RuntimeInstance(instance=INSTANCE, native_runtime=native)
    result = asyncio.run(instance.get_session_capabilities("s1", "ext-1"))
    ids = {item.capability_id for item in result.capabilities}
    assert {"session.planning", "session.plan_document", "session.plan_review"} <= ids
