"""The RPC surface shared with Desktop must stay in one declared manifest."""

from __future__ import annotations

import ast
import json
import re
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
MANIFEST = REPO_ROOT / "contracts" / "local-machine" / "2.0" / "rpc.json"
CLI = REPO_ROOT / "connector" / "connector" / "cli.py"
JSON_RPC = REPO_ROOT / "connector" / "connector" / "core" / "json_rpc.py"
DESKTOP_SOURCE = REPO_ROOT / "desktop-workbench" / "electron" / "connector-supervisor.ts"


def _manifest() -> dict:
    return json.loads(MANIFEST.read_text(encoding="utf-8"))


def _registered_handlers() -> set[str]:
    tree = ast.parse(CLI.read_text(encoding="utf-8"))
    for node in ast.walk(tree):
        if not isinstance(node, ast.Assign):
            continue
        target = node.targets[0]
        if isinstance(target, ast.Name) and target.id == "handlers" and isinstance(node.value, ast.Dict):
            return {key.value for key in node.value.keys if isinstance(key, ast.Constant)}
    raise AssertionError("connector/cli.py no longer builds the RPC handlers table")


def test_declared_methods_match_the_rpc_handlers() -> None:
    assert _registered_handlers() == set(_manifest()["methods"])


def test_desktop_only_calls_declared_methods() -> None:
    manifest = _manifest()
    desktop = manifest["hosts"]["desktop-workbench"]
    assert set(desktop["methods"]) <= set(manifest["methods"])
    if not DESKTOP_SOURCE.exists():
        return
    called = set(re.findall(r'request\("(connector\.[A-Za-z]+)"', DESKTOP_SOURCE.read_text(encoding="utf-8")))
    assert called == set(desktop["methods"])


def test_declared_notifications_are_emitted() -> None:
    manifest = _manifest()
    assert set(manifest["notifications"]) == {"connector/state", "connector/log"}
    emitted = "\n".join(
        path.read_text(encoding="utf-8")
        for path in (REPO_ROOT / "connector" / "connector").rglob("*.py")
    )
    for name in manifest["notifications"]:
        assert f'"{name}"' in emitted, f"{name} is declared but no module emits it"


def test_framing_claims_match_the_stdio_server() -> None:
    source = JSON_RPC.read_text(encoding="utf-8")
    assert "ensure_ascii=False" in source
    assert 'separators=(",", ":")' in source
