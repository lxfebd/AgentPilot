"""The connector accepts and routes every workflow RPC the server sends.

Builds a RuntimeRpcHandler over a stub supervisor whose provider returns a
runtime exposing execute_workflow, then dispatches the exact methods the
server sends for preparation, creation, tree, position, close, recover and
agent operations. Without the WORKFLOW_RPC table these are rejected as
unsupported before ever reaching the runtime, which is the gap the port of
upstream Agents Anywhere closed.
"""

import asyncio
from types import SimpleNamespace

from connector.runtime_protocol.workflows import (
    RuntimeWorkflowRequest,
    RuntimeWorkflowResult,
)
from connector.server.runtime_rpc import WORKFLOW_RPC, RuntimeRpcHandler


class StubRuntime:
    runtime_type = "pi"
    planning = None

    def __init__(self) -> None:
        self.calls: list[str] = []

    @property
    def identity(self):
        return SimpleNamespace(runtime_id="rti_pi-main", runtime="pi", runtime_type="pi")

    async def execute_workflow(self, request: RuntimeWorkflowRequest) -> RuntimeWorkflowResult:
        self.calls.append(request.operation)
        return RuntimeWorkflowResult({"ok": True, "operation": request.operation})


class StubSupervisor:
    def __init__(self) -> None:
        self._runtime = StubRuntime()

    def provider(self, runtime_type):
        return self._runtime

    def resolve_runtime(self, runtime_id, runtime_type):
        return self._runtime


def test_workflow_rpc_methods_are_supported_and_routed() -> None:
    handler = RuntimeRpcHandler(StubSupervisor(), None)
    runtime = handler.agent_runtime_supervisor.provider("pi")

    cases = [
        ("workspace.prepare", {"sessionId": "s1"}),
        ("workspace.preparation.get", {"sessionId": "s1", "preparationId": "p1"}),
        ("workspace.preparation.respond", {"sessionId": "s1", "preparationId": "p1"}),
        ("session.creation.begin", {"sessionId": "s1", "preparationId": "p1"}),
        ("session.creation.get", {"sessionId": "s1", "creationId": "c1"}),
        ("session.tree", {"sessionId": "s1"}),
        ("session.position.select", {"sessionId": "s1", "position": "end"}),
        ("session.close", {"sessionId": "s1"}),
        ("session.recover", {"sessionId": "s1"}),
        ("session.agent.operate", {"sessionId": "s1", "operation": "queue.get"}),
    ]

    async def run() -> dict:
        out = {}
        for method, params in cases:
            out[method] = await handler.dispatch(
                method, {**params, "runtime": "pi", "runtimeId": "pi"}
            )
        return out

    results = asyncio.run(run())

    assert set(results) == {method for method, _ in cases}
    assert runtime.calls == [WORKFLOW_RPC[method] for method, _ in cases]
    for method, result in results.items():
        assert result["ok"] is True, method
        assert result["operation"] == WORKFLOW_RPC[method]


def test_planning_rpc_degrades_when_the_runtime_has_no_plan_mode() -> None:
    handler = RuntimeRpcHandler(StubSupervisor(), None)
    result = asyncio.run(
        handler.dispatch("session.planning.get", {"sessionId": "s1", "runtime": "pi"})
    )
    assert result["capabilities"]["supported"] is False
    assert result["freshness"] == "unknown"
