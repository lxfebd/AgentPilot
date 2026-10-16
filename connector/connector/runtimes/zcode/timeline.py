from __future__ import annotations

import re
from typing import Any

from connector.runtime_protocol import (
    RuntimeTimelineItem,
    RuntimeTimelineSnapshot,
    TimelineSource,
    timeline_content_hash,
)
from connector.runtime_protocol.timeline import (
    MessageTimelineContent,
    ToolTimelineContent,
)
from connector.runtimes.zcode import identity

_ANSI_ESCAPE = re.compile(r"\x1b\[[0-9;?]*[a-zA-Z]|\x1b\][^\x07]*\x07|\x1b[()][0-9A-Za-z]")
_WHITESPACE_COLLAPSE = re.compile(r"[ \t\r\f\v]+")

# Part types the desktop writes; anything else degrades to nothing rather
# than crashing the projection.
KNOWN_PART_TYPES = frozenset({"text", "reasoning", "step-start", "step-finish", "tool", "step"})

# Tool names -> platform tool content kind. Unknown tool names fall back to
# "tool_call" so the platform never receives an invalid kind.
_TOOL_KIND_BY_NAME = {
    "bash": "command",
    "terminal": "command",
    "shell": "command",
    "execute": "command",
    "read": "tool_call",
    "grep": "tool_call",
    "glob": "tool_call",
    "search": "tool_call",
    "listdir": "tool_call",
    "write": "file_change",
    "edit": "file_change",
    "notebookedit": "file_change",
    "patch": "file_change",
    "apply_patch": "file_change",
}


def build_timeline_snapshot(
    db: Any,
    *,
    external_session_id: str,
    connector_id: str,
    runtime_id: str | None,
    limit: int | None = None,
) -> RuntimeTimelineSnapshot:
    """Project the ZCode message/part store into a platform timeline snapshot.

    Ordering derives from the per-session message sequence and the per-message
    part sequence so the projection is stable across re-reads.
    """
    db.check_schema()
    messages = db.query(
        "select id, data, sequence, time_created, time_updated "
        "from message where session_id = ? order by sequence asc",
        (external_session_id,),
    )
    if not messages:
        return RuntimeTimelineSnapshot(
            session_id=_stable_id(connector_id, external_session_id),
            external_session_id=external_session_id,
            runtime="zcode",
            items=(),
            complete=True,
            runtime_id=runtime_id,
        )
    rows = [row for row in messages if isinstance(row.get("sequence"), int)]
    parts_by_message: dict[str, list[dict[str, Any]]] = {}
    part_rows = db.query(
        "select id, message_id, data, sequence from part "
        "where session_id = ? order by sequence asc",
        (external_session_id,),
    )
    for row in part_rows:
        parts_by_message.setdefault(row["message_id"], []).append(row)

    items: list[RuntimeTimelineItem] = []
    for message in rows:
        message_seq = int(message["sequence"])
        data = _loads(message.get("data"))
        if not isinstance(data, dict):
            continue
        parts = parts_by_message.get(message["id"], [])
        base_seq = message_seq * 10000
        items.extend(
            _project_message(
                external_session_id,
                message["id"],
                data,
                parts,
                base_seq,
                connector_id=connector_id,
            )
        )
    items.sort(key=lambda item: item.order_seq)
    if limit is not None:
        items = items[-limit:]
    return RuntimeTimelineSnapshot(
        session_id=_stable_id(connector_id, external_session_id),
        external_session_id=external_session_id,
        runtime="zcode",
        items=tuple(items),
        complete=True,
        metadata={"watermark": messages[-1]["sequence"] if messages else 0},
        runtime_id=runtime_id,
    )


def _project_message(
    external_session_id: str,
    message_id: str,
    data: dict[str, Any],
    parts: list[dict[str, Any]],
    base_seq: int,
    *,
    connector_id: str,
) -> list[RuntimeTimelineItem]:
    role = data.get("role")
    anchor = data.get("anchor")
    turn_id = None
    if isinstance(anchor, dict):
        value = anchor.get("turnId")
        turn_id = value if isinstance(value, str) else None

    items: list[RuntimeTimelineItem] = []
    if role == "user":
        text = _user_text(data)
        if text:
            items.append(
                _message_item(
                    external_session_id,
                    message_id,
                    base_seq,
                    role="user",
                    text=text,
                    turn_id=turn_id,
                    connector_id=connector_id,
                    kind="text",
                )
            )
        return items

    if role == "assistant":
        # Assistant text comes from text parts; the message JSON itself carries
        # metadata (tokens/finish) but no user-visible body.
        for part in parts:
            part_data = _loads(part.get("data"))
            if not isinstance(part_data, dict):
                continue
            part_type = part_data.get("type")
            if part_type not in KNOWN_PART_TYPES:
                continue
            if part_type == "text":
                text = part_data.get("text")
                if isinstance(text, str) and text.strip():
                    items.append(
                        _message_item(
                            external_session_id,
                            message_id,
                            base_seq + int(part.get("sequence") or 0),
                            role="assistant",
                            text=text,
                            turn_id=turn_id,
                            connector_id=connector_id,
                            kind="markdown",
                        )
                    )
            elif part_type == "tool":
                item = _tool_item(
                    external_session_id,
                    message_id,
                    part,
                    part_data,
                    base_seq + int(part.get("sequence") or 0),
                    turn_id=turn_id,
                    connector_id=connector_id,
                )
                if item is not None:
                    items.append(item)
    return items


def _message_item(
    external_session_id: str,
    message_id: str,
    order_seq: int,
    *,
    role: str,
    text: str,
    turn_id: str | None,
    connector_id: str,
    kind: str,
) -> RuntimeTimelineItem:
    content = MessageTimelineContent(kind=kind, text=text, format=kind).to_mapping()
    item_id = identity.timeline_item_id(
        external_session_id, "message", message_id
    )
    return RuntimeTimelineItem(
        id=item_id,
        session_id=_stable_id(connector_id, external_session_id),
        type="message",
        status="done",
        order_seq=order_seq,
        content_hash=timeline_content_hash(
            item_type="message", status="done", role=role, content=content  # type: ignore[arg-type]
        ),
        role=role,  # type: ignore[arg-type]
        turn_id=turn_id,
        content=content,
        source=TimelineSource(
            runtime="zcode",
            external_session_id=external_session_id,
            turn_id=turn_id,
            native_item_id=message_id,
            native_item_type="message",
        ).to_mapping(),
        metadata={},
    )


def _tool_item(
    external_session_id: str,
    message_id: str,
    part: dict[str, Any],
    part_data: dict[str, Any],
    order_seq: int,
    turn_id: str | None,
    *,
    connector_id: str,
) -> RuntimeTimelineItem | None:
    tool_name = part_data.get("tool")
    if not isinstance(tool_name, str) or not tool_name:
        return None
    state = part_data.get("state")
    if not isinstance(state, dict):
        state = {}
    status = "done" if state.get("status") in (None, "completed") else "failed"
    kind = _TOOL_KIND_BY_NAME.get(tool_name.casefold(), "tool_call")
    input_value = state.get("input")
    command: str | None = None
    if kind == "command" and isinstance(input_value, dict):
        raw = input_value.get("command")
        command = raw if isinstance(raw, str) else None
    output = state.get("output")
    if isinstance(output, str):
        output = _clean_ansi(output)
    error = state.get("error")
    if status == "failed" and error is not None:
        output = f"[error] {error}" if output in (None, "") else output
    content = ToolTimelineContent(
        kind=kind,
        title=tool_name,
        command=command,
        input=_simplify(input_value),
        output=output,
        exit_code=_exit_code(state),
        metadata={
            "callID": part_data.get("callID"),
            "status": state.get("status"),
        },
    ).to_mapping()
    item_id = identity.timeline_item_id(
        external_session_id, "tool", part["id"]
    )
    return RuntimeTimelineItem(
        id=item_id,
        session_id=_stable_id(connector_id, external_session_id),
        type="tool",
        status=status,
        order_seq=order_seq,
        content_hash=timeline_content_hash(
            item_type="tool", status=status, role="tool", content=content
        ),
        role="tool",
        turn_id=turn_id,
        content=content,
        source=TimelineSource(
            runtime="zcode",
            external_session_id=external_session_id,
            turn_id=turn_id,
            native_item_id=part["id"],
            native_item_type="tool",
        ).to_mapping(),
        metadata={"tool": tool_name},
    )


def _exit_code(state: dict[str, Any]) -> int | None:
    value = state.get("exitCode", state.get("exit_code"))
    if isinstance(value, int) and not isinstance(value, bool):
        return value
    return None


def _simplify(value: Any) -> Any:
    if isinstance(value, dict):
        return {str(key): _simplify(item) for key, item in value.items()}
    if isinstance(value, list):
        return [_simplify(item) for item in value]
    return value


def _user_text(data: dict[str, Any]) -> str:
    """Extract the user-visible prompt from a user message.

    The desktop mirrors the typed text into
    ``data.metadata.conversationInputIntent.text``; fall back to scanning text
    parts like the assistant path.
    """
    metadata = data.get("metadata")
    if isinstance(metadata, dict):
        intent = metadata.get("conversationInputIntent")
        if isinstance(intent, dict):
            text = intent.get("text")
            if isinstance(text, str) and text.strip():
                return text
        input_intent = metadata.get("inputIntent")
        if isinstance(input_intent, dict):
            text = input_intent.get("text")
            if isinstance(text, str) and text.strip():
                return text
    return ""


def _clean_ansi(value: str) -> str:
    value = _ANSI_ESCAPE.sub("", value)
    value = _WHITESPACE_COLLAPSE.sub(" ", value)
    return value.strip()


def _loads(value: Any) -> Any:
    if not isinstance(value, str):
        return None
    import json

    try:
        return json.loads(value)
    except (ValueError, TypeError):
        return None


def _stable_id(connector_id: str, external_session_id: str) -> str:
    from connector.runtimes.session_identity import stable_runtime_session_id

    return stable_runtime_session_id(connector_id, "zcode", external_session_id)