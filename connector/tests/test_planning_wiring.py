"""The planning application is only reachable if every runtime binds it.

This batch ported the unified planning host: three runtime providers bind it,
the capability set is rewritten from what the bridge reports, and stop closes
it. Each of those is a one-line call site in a file that type-checks whether
or not the call is there, so a dropped bind is invisible to everything except
a test that looks for it.

These assertions read the source and check the wiring exists. They are not a
substitute for the integration tests that drive the behaviour; they are the
cheap guard that keeps the behaviour reachable.
"""

import ast
from pathlib import Path

PACKAGE_ROOT = Path(__file__).parents[1]
PROVIDERS = ("claude", "codex", "dsh")


def _source(relative: str) -> str:
    return (PACKAGE_ROOT / relative).read_text(encoding="utf-8")


def _calls(relative: str, function: str) -> set[str]:
    """Every callee name that appears inside `function`'s body.

    Both plain calls and attribute calls are collected, because the same
    action can be spelled either way: `bind_planning(...)` here, and
    `await planning.close()` for the host's own shutdown.
    """
    tree = ast.parse(_source(relative))
    found: set[str] = set()
    for node in ast.walk(tree):
        if not isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) or node.name != function:
            continue
        for child in ast.walk(node):
            if not isinstance(child, ast.Call):
                continue
            if isinstance(child.func, ast.Name):
                found.add(child.func.id)
            elif isinstance(child.func, ast.Attribute):
                found.add(child.func.attr)
    return found


def test_every_runtime_provider_binds_the_planning_host() -> None:
    # A provider that forgets this returns a runtime with no planning service:
    # no plan mode, no observations, and capabilities that report unsupported.
    for runtime in PROVIDERS:
        relative = f"connector/runtimes/{runtime}/provider.py"
        calls = _calls(relative, "create_runtime")
        assert "bind_planning" in calls, f"{runtime}'s create_runtime no longer binds the planning host"


def test_the_capability_set_is_rewritten_from_the_bridge() -> None:
    # Without both call sites the three planning capabilities never reach the
    # server, so every client's plan gating reads as unsupported.
    source = _source("connector/runtime_protocol/instance_binding.py")
    assert source.count("with_planning_capabilities(") >= 3, (
        "the capability fill is missing one of its call sites"
    )


def test_stop_closes_the_planning_host() -> None:
    # A stopped instance that keeps the host open leaks its store connection.
    calls = _calls("connector/runtime_protocol/instance_binding.py", "stop")
    assert any("close" in call for call in calls), "stop no longer closes the planning host"
