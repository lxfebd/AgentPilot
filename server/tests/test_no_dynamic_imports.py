from __future__ import annotations

import ast
from pathlib import Path

PACKAGE_ROOT = Path(__file__).parents[1] / "agent_server"


def test_no_dynamic_imports_bypass_static_analysis() -> None:
    """Static boundary checks can be bypassed via __import__/importlib.

    The architecture tests only see ast.Import/ImportFrom nodes; a dynamic
    import would smuggle dependencies past them, so the whole package must stay
    free of __import__ and importlib.import_module calls.
    """
    offenders: list[str] = []
    for path in sorted(PACKAGE_ROOT.rglob("*.py")):
        tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
        for node in ast.walk(tree):
            if not isinstance(node, ast.Call):
                continue
            func = node.func
            label = None
            if isinstance(func, ast.Name) and func.id == "__import__":
                label = "__import__"
            elif (
                isinstance(func, ast.Attribute)
                and isinstance(func.value, ast.Name)
                and func.value.id == "importlib"
                and func.attr == "import_module"
            ):
                label = "importlib.import_module"
            if label is not None:
                offenders.append(f"{path.relative_to(PACKAGE_ROOT)}:{node.lineno} {label}")
    assert offenders == []
