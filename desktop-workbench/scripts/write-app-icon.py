#!/usr/bin/env python3
"""Regenerate desktop-workbench/build/icon.ico.

`build/icon.ico` is referenced three times and nothing in the build produces
it: `electron/main.ts` loads it as the Windows tray icon, `package.json` lists
it under `win.icon` and `extraResources`, and `main-lifecycle.test.ts` asserts
it is packaged.  A clone without it therefore has no tray icon, no installer
icon, and a failing test.

The source is the product's own app icon - the same artwork the iOS AppIcon
uses - rather than the Web favicon, because the favicon is a black silhouette
on transparency and disappears on a dark taskbar.

    python scripts/write-app-icon.py
"""

from pathlib import Path

from PIL import Image

PROJECT_ROOT = Path(__file__).resolve().parent.parent
SOURCE = (
    PROJECT_ROOT.parent
    / "ios/AgentPilot/AgentPilot/Assets.xcassets/AppIcon.appiconset/ios-dark-iOS-Dark-1024@1x.png"
)
DESTINATION = PROJECT_ROOT / "build" / "icon.ico"
# 256 is stored as an embedded PNG; the rest are classic BMP entries.  16/24/32
# cover the tray and small taskbar, 48/64 the large taskbar and Alt+Tab.
SIZES = [(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)]


def main() -> None:
    if not SOURCE.exists():
        raise SystemExit(f"app icon source not found: {SOURCE}")
    source = Image.open(SOURCE).convert("RGBA")
    DESTINATION.parent.mkdir(parents=True, exist_ok=True)
    source.save(DESTINATION, format="ICO", sizes=SIZES)
    print(f"wrote {DESTINATION} ({DESTINATION.stat().st_size} bytes) from {SOURCE.name}")


if __name__ == "__main__":
    main()
