from __future__ import annotations

import json
from typing import Any


def json_loads(value: str | None) -> Any:
    """Decode a stored JSON column; empty values load as None."""
    if not value:
        return None
    return json.loads(value)
