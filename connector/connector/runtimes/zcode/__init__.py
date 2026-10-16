"""ZCode runtime support for AgentPilot.

The ZCode desktop app keeps its sessions in a local SQLite database
(``<zcode home>/cli/db/db.sqlite``). This package treats that database as the
runtime data plane: sessions and timelines are read directly (read-only
connections), and user input is delivered through the ``session_input`` table
using the same ``sendText`` / ``startNow`` shape the desktop UI writes. No
bridge service or reverse-engineered protocol is involved.
"""