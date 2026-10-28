from __future__ import annotations

import posixpath
from pathlib import PurePosixPath, PureWindowsPath


class MissingWorkspaceError(ValueError):
    """Raised when a connector session cannot be assigned to a workspace."""


def clean_workspace_path(path: str, device_os: str | None) -> tuple[str, str]:
    """Return the display path and comparison key for a connector workspace.

    Windows paths are canonicalized with ``PureWindowsPath`` and case-folded so
    different spellings of one workspace compare equal; POSIX paths are
    normalized with :func:`posixpath.normpath`.
    """
    cleaned = path.strip()
    if not cleaned:
        raise ValueError("workspacePath must not be empty")

    looks_windows = (
        device_os == "windows"
        or (len(cleaned) >= 3 and cleaned[1] == ":" and cleaned[2] in ("/", "\\"))
        or cleaned.startswith("\\\\")
    )
    if looks_windows:
        windows_path = PureWindowsPath(cleaned)
        if not windows_path.is_absolute():
            raise ValueError("workspacePath must be an absolute path")
        display = str(windows_path)
        return display, windows_path.as_posix().casefold()

    posix_path = PurePosixPath(cleaned)
    if not posix_path.is_absolute():
        raise ValueError("workspacePath must be an absolute path")
    display = posixpath.normpath(cleaned)
    return display, display
