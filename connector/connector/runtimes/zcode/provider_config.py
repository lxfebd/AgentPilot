from __future__ import annotations

import os
import re
from pathlib import Path

from connector.core import runtime_owner
from connector.runtime_protocol import RuntimeInvalidRequestError
from connector.runtime_protocol.filesystem import canonical_path

DEFAULT_POLL_INTERVAL_MS = 5_000
DEFAULT_REQUEST_TIMEOUT_MS = 60_000

_ZCODE_HOME_ENV = "ZCODE_HOME"
_WINDOWS_EXE_NAME = "ZCode.exe"


def zcode_config_schema() -> dict[str, object]:
    positive_timeout = {"type": "integer", "minimum": 100, "maximum": 600_000}
    return {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "type": "object",
        "properties": {
            "zcodeDataDir": {
                "type": "string",
                "minLength": 1,
                "title": "ZCode data directory",
                "description": "Directory that stores the CLI database (db/db.sqlite) and profile. Defaults to ~/.zcode or $ZCODE_HOME.",
            },
            "zcodeExe": {
                "type": "string",
                "minLength": 1,
                "title": "ZCode executable",
                "description": "Optional absolute path to the ZCode desktop executable used by the launch button. Discovered automatically when empty.",
            },
            "pollIntervalMs": {
                **positive_timeout,
                "default": DEFAULT_POLL_INTERVAL_MS,
                "title": "Poll interval",
                "description": "How often the connector re-reads the ZCode database for new sessions and timeline items.",
            },
            "requestTimeoutMs": {
                **positive_timeout,
                "default": DEFAULT_REQUEST_TIMEOUT_MS,
            },
        },
        "additionalProperties": False,
    }


def default_config_values() -> dict[str, object]:
    return {
        "pollIntervalMs": DEFAULT_POLL_INTERVAL_MS,
        "requestTimeoutMs": DEFAULT_REQUEST_TIMEOUT_MS,
    }


def normalized_config_values(raw: dict[str, object]) -> dict[str, object]:
    values = {**default_config_values(), **raw}
    data_dir = values.get("zcodeDataDir")
    if data_dir is not None:
        if (
            not isinstance(data_dir, str)
            or not Path(data_dir).expanduser().is_absolute()
        ):
            raise RuntimeInvalidRequestError(
                "zcodeDataDir must be an absolute path"
            )
        values["zcodeDataDir"] = canonical_path(data_dir)
    exe = values.get("zcodeExe")
    if exe is not None:
        if not isinstance(exe, str) or not Path(exe).expanduser().is_absolute():
            raise RuntimeInvalidRequestError("zcodeExe must be an absolute path")
        values["zcodeExe"] = canonical_path(exe)
    for key in ("pollIntervalMs", "requestTimeoutMs"):
        value = values.get(key)
        if (
            not isinstance(value, int)
            or isinstance(value, bool)
            or not 100 <= value <= 600_000
        ):
            raise RuntimeInvalidRequestError(
                f"{key} must be an integer between 100 and 600000"
            )
    return values


def zcode_data_dir(values: dict[str, object]) -> Path:
    """Resolve the ZCode data root from config, $ZCODE_HOME, or the user home."""
    configured = values.get("zcodeDataDir")
    if isinstance(configured, str):
        return Path(canonical_path(configured))
    env = os.environ.get(_ZCODE_HOME_ENV)
    if env:
        return Path(canonical_path(env))
    return Path(canonical_path(runtime_owner.system_home() / ".zcode"))


def zcode_db_path(values: dict[str, object]) -> Path:
    return zcode_data_dir(values) / "cli" / "db" / "db.sqlite"


def zcode_exe(values: dict[str, object]) -> Path | None:
    """Resolve the ZCode executable, if any is configured or discoverable."""
    configured = values.get("zcodeExe")
    if isinstance(configured, str):
        path = Path(configured)
        return path if path.is_file() else None
    # Common per-user locations on Windows; the desktop app installs here.
    candidates = (
        Path(os.environ.get("PROGRAMFILES", r"C:\Program Files")) / "ZCode" / _WINDOWS_EXE_NAME,
        Path(os.environ.get("LOCALAPPDATA", Path.home() / "AppData" / "Local"))
        / "Programs" / "ZCode" / _WINDOWS_EXE_NAME,
    )
    return next((path for path in candidates if path.is_file()), None)


def database_uri(values: dict[str, object]) -> str:
    """Read-only SQLite URI for the ZCode database (WAL-safe, immutable reader)."""
    path = zcode_db_path(values)
    return f"file:{path.as_posix()}?mode=ro"


def zcode_capabilities() -> dict[str, bool]:
    """Capability flags advertised by the ZCode provider.

    Delivery rides the ``session_input`` table, so sending is always supported
    as long as the database is reachable; the model catalog is projected from
    the profile file the desktop maintains. Command/interaction surfaces are
    not exposed.
    """
    return {
        "modelCatalog": True,
        "permissionCatalog": False,
        "sessionDiscovery": True,
        "sessionSnapshot": True,
        "sessionState": True,
        "sessionNotices": False,
        "createAndStartSession": True,
        "startTurn": True,
        "steerTurn": True,
        # Interrupt is best-effort: it queues a delivery row exactly like
        # start_turn, and ZCode consumes it when the session is active.
        "interruptTurn": True,
        "commands": False,
        "interactions": False,
        "attachments": False,
        "ipc": False,
    }


_KNOWN_MODEL_PROVIDER = re.compile(r"^[A-Za-z0-9][A-Za-z0-9:._-]*$")


def model_provider_is_known(provider: str) -> bool:
    return bool(_KNOWN_MODEL_PROVIDER.fullmatch(provider or ""))