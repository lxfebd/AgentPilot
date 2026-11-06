from __future__ import annotations

import argparse
import hashlib
import json
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Literal

from pydantic import BaseModel

from agent_server.core.planning.models import (
    PlanDocument,
    PlanningRequest,
    PlanningResponse,
    PlanningState,
)
from agent_server.core.protocol import (
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

    ProtocolSchema("plan-document", PlanDocument, "egress", "serialization"),
    ProtocolSchema("planning-request", PlanningRequest, "bidirectional", "validation"),
    ProtocolSchema("planning-state", PlanningState, "egress", "serialization"),
    ProtocolSchema("planning-response", PlanningResponse, "egress", "serialization"),    ProtocolSchema("handshake-request", ProtocolHandshakeRequest, "ingress", "validation"),
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


def _schema_document(artifact: ProtocolSchema, version: str = "1.0") -> dict[str, object]:
    schema = artifact.model.model_json_schema(by_alias=True, mode=artifact.mode)
    def project(value):
        if isinstance(value, dict):
            if version == "1.0" and value.get("enum") == ["1.0", "1.1"]:
                value.pop("enum"); value["const"] = "1.0"
            if version == "1.1" and value.get("enum") == ["codex", "claude", "opencode", "acp", "dsh"]:
                value.pop("enum"); value["pattern"] = r"^[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*$"
            for child in value.values(): project(child)
        elif isinstance(value, list):
            for child in value: project(child)
    project(schema)
    schema["$schema"] = JSON_SCHEMA_DRAFT
    schema["$id"] = (
        f"{SCHEMA_ID_PREFIX}:{version}:{artifact.slug}"
    )
    schema["x-protocol-version"] = version
    schema["x-direction"] = artifact.direction
    schema["x-pydantic-mode"] = artifact.mode
    return schema


def export_contract(output_dir: Path, version: str = "1.0") -> None:
    schema_dir = output_dir / "schemas"
    schema_dir.mkdir(parents=True, exist_ok=True)
    artifacts: list[dict[str, str]] = []

    for artifact in SCHEMAS:
        relative_path = f"schemas/{artifact.slug}.schema.json"
        schema_bytes = _json_bytes(_schema_document(artifact, version))
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
        "protocolVersion": version,
        "source": "server/agent_server/core/protocol.py",
    }
    (output_dir / "manifest.json").write_bytes(_json_bytes(manifest))



def check_contract(output_dir: Path, version: str) -> int:
    if not output_dir.is_dir():
        print(f"missing contract directory: {output_dir}")
        return 1
    with tempfile.TemporaryDirectory() as tmp:
        generated_dir = Path(tmp)
        export_contract(generated_dir, version)
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
            on_disk = committed_path.read_bytes().replace(bytes([13, 10]), bytes([10]))
            if fresh != on_disk:
                problems.append(f"stale: {committed_path}")
        if problems:
            for line in problems:
                print(line)
            hint = "uv run python -m scripts.export_protocol_schemas"
            if version != "1.0":
                hint += f" --version {version}"
            print(f"Protocol contract {version} is out of date; run `{hint}`.")
            return 1
    print(f"Protocol contract {version} is up to date ({len(expected)} files).")
    return 0


def main() -> None:
    repository_root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(
        description="Export the canonical AgentPilot protocol JSON Schemas."
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="Verify the committed contract matches the server models instead of writing it.",
    )
    parser.add_argument("--version", choices=["1.0", "1.1"], default="1.0")
    parser.add_argument(
        "--output",
        type=Path,
        default=None,
    )
    args = parser.parse_args()
    target = (args.output or repository_root / "contracts" / "protocol" / args.version).resolve()
    if args.check:
        raise SystemExit(check_contract(target, args.version))
    export_contract(target, args.version)


if __name__ == "__main__":
    main()
