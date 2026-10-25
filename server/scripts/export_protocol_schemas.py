from __future__ import annotations

import argparse
import hashlib
import json
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Literal

from pydantic import BaseModel

from agent_server.core.protocol import (
    PROTOCOL_VERSION_1,
    ProtocolCapabilitiesResponse,
    ProtocolCapabilitySet,
    ProtocolEventEnvelope,
    ProtocolEventRecoveryResponse,
    ProtocolHandshakeRequest,
    ProtocolHandshakeResponse,
    ProtocolModelCatalog,
    ProtocolModelCatalogResponse,
    ProtocolPermissionCatalog,
    ProtocolPermissionCatalogResponse,
    ProtocolSessionSnapshotResponse,
    ProtocolWsTicketRequest,
    ProtocolWsTicketResponse,
)

JSON_SCHEMA_DRAFT = "https://json-schema.org/draft/2020-12/schema"
SCHEMA_ID_PREFIX = "urn:agent-pilot:protocol"

SchemaDirection = Literal["ingress", "egress", "bidirectional"]
SchemaMode = Literal["validation", "serialization"]


@dataclass(frozen=True)
class ProtocolSchema:
    slug: str
    model: type[BaseModel]
    direction: SchemaDirection
    mode: SchemaMode


SCHEMAS = (
    ProtocolSchema("handshake-request", ProtocolHandshakeRequest, "ingress", "validation"),
    ProtocolSchema("handshake-response", ProtocolHandshakeResponse, "egress", "serialization"),
    ProtocolSchema("capability-set", ProtocolCapabilitySet, "bidirectional", "validation"),
    ProtocolSchema("capabilities-response", ProtocolCapabilitiesResponse, "egress", "serialization"),
    ProtocolSchema("model-catalog", ProtocolModelCatalog, "bidirectional", "validation"),
    ProtocolSchema("model-catalog-response", ProtocolModelCatalogResponse, "egress", "serialization"),
    ProtocolSchema("permission-catalog", ProtocolPermissionCatalog, "bidirectional", "validation"),
    ProtocolSchema(
        "permission-catalog-response",
        ProtocolPermissionCatalogResponse,
        "egress",
        "serialization",
    ),
    ProtocolSchema(
        "session-snapshot-response",
        ProtocolSessionSnapshotResponse,
        "egress",
        "serialization",
    ),
    ProtocolSchema("ws-ticket-request", ProtocolWsTicketRequest, "ingress", "validation"),
    ProtocolSchema("ws-ticket-response", ProtocolWsTicketResponse, "egress", "serialization"),
    ProtocolSchema("event-envelope", ProtocolEventEnvelope, "egress", "serialization"),
    ProtocolSchema(
        "event-recovery-response",
        ProtocolEventRecoveryResponse,
        "egress",
        "serialization",
    ),
)


def _json_bytes(value: object) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    ).encode()


def _schema_document(artifact: ProtocolSchema) -> dict[str, object]:
    schema = artifact.model.model_json_schema(by_alias=True, mode=artifact.mode)
    schema["$schema"] = JSON_SCHEMA_DRAFT
    schema["$id"] = (
        f"{SCHEMA_ID_PREFIX}:{PROTOCOL_VERSION_1}:{artifact.slug}"
    )
    schema["x-protocol-version"] = PROTOCOL_VERSION_1
    schema["x-direction"] = artifact.direction
    schema["x-pydantic-mode"] = artifact.mode
    return schema


def export_contract(output_dir: Path) -> None:
    schema_dir = output_dir / "schemas"
    schema_dir.mkdir(parents=True, exist_ok=True)
    artifacts: list[dict[str, str]] = []

    for artifact in SCHEMAS:
        relative_path = f"schemas/{artifact.slug}.schema.json"
        schema_bytes = _json_bytes(_schema_document(artifact))
        (output_dir / relative_path).write_bytes(schema_bytes)
        artifacts.append(
            {
                "direction": artifact.direction,
                "model": artifact.model.__name__,
                "path": relative_path,
                "pydanticMode": artifact.mode,
                "sha256": hashlib.sha256(schema_bytes).hexdigest(),
                "slug": artifact.slug,
            }
        )

    manifest = {
        "artifacts": artifacts,
        "jsonSchemaDraft": JSON_SCHEMA_DRAFT,
        "protocolVersion": PROTOCOL_VERSION_1,
        "source": "server/agent_server/core/protocol.py",
    }
    (output_dir / "manifest.json").write_bytes(_json_bytes(manifest))


def check_contract(output_dir: Path) -> int:
    """Regenerate the contract in a temp dir and compare it with the committed bytes.

    Byte-for-byte comparison is what CI relies on, so a schema edit that skips a
    regeneration fails here instead of silently drifting from server models.
    CRLF is normalized because Windows checkouts may store the committed files
    with autocrlf conversions while the exporter always writes LF.
    """
    if not output_dir.is_dir():
        print(f"missing contract directory: {output_dir}")
        return 1
    with tempfile.TemporaryDirectory() as tmp:
        generated_dir = Path(tmp)
        export_contract(generated_dir)
        # Only the generated artifacts are compared; README.md and the
        # curated fixtures/ directory legitimately coexist next to them.
        expected = {
            path.relative_to(generated_dir).as_posix()
            for path in generated_dir.rglob("*")
            if path.is_file()
        }
        problems = []
        for rel in sorted(expected):
            committed_path = output_dir / rel
            if not committed_path.is_file():
                problems.append(f"missing: {committed_path}")
                continue
            fresh = (generated_dir / rel).read_bytes()
            on_disk = committed_path.read_bytes().replace(b"\r\n", b"\n")
            if fresh != on_disk:
                problems.append(f"stale: {committed_path}")
        if problems:
            print("\n".join(problems))
            print(
                "Protocol contract is out of date; run "
                "`uv run python -m scripts.export_protocol_schemas`."
            )
            return 1
    print(f"Protocol contract is up to date ({len(expected)} files).")
    return 0


def main() -> None:
    repository_root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(
        description="Export the canonical AgentPilot protocol JSON Schemas."
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=repository_root / "contracts" / "protocol" / PROTOCOL_VERSION_1,
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="Verify the committed contract matches the server models instead of writing it.",
    )
    args = parser.parse_args()
    if args.check:
        raise SystemExit(check_contract(args.output.resolve()))
    export_contract(args.output.resolve())


if __name__ == "__main__":
    main()
