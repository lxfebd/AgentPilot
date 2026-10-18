from __future__ import annotations

import asyncio
from collections.abc import Mapping
from typing import Any

from connector.logging import logger
from connector.runtime_protocol import (
    AgentRuntime,
    RuntimeAttachment,
    RuntimeCapabilitySet,
    RuntimeCommand,
    RuntimeCommandResult,
    RuntimeConfig,
    RuntimeIdentity,
    RuntimeInvalidRequestError,
    RuntimeModelCatalog,
    RuntimeOperationResult,
    RuntimePermissionCatalog,
    RuntimeTimelineSnapshot,
    RuntimeUnavailableError,
    RuntimeUnsupportedError,
    RuntimeUpstreamError,
    SessionMeta,
    SessionNotice,
    SessionState,
)
from connector.runtime_protocol import models as protocol_models
from connector.runtime_protocol.host import RuntimeHostClient
from connector.runtimes.zcode import (
    catalog,
    discovery,
    provider_config,
    sessions,
    timeline,
)
from connector.runtimes.zcode import (
    selections as zcode_selections,
)
from connector.runtimes.zcode.db import ZcodeDb
from connector.runtimes.zcode.session_input import (
    deliver_send_text,
    read_delivery_status,
)


class ZCodeRuntime(AgentRuntime):
    """ZCode database adapter.

    Reads project the ZCode SQLite store into platform session metadata and
    timelines; writes enqueue ``session_input`` rows exactly like the desktop
    input composer. The runtime defers all state conclusions to the connector's
    polling scanner.
    """

    def __init__(
        self,
        config: RuntimeConfig,
        host: RuntimeHostClient,
    ) -> None:
        self.config = config
        self.host = host
        self._db = ZcodeDb(provider_config.zcode_db_path(dict(config.values)))
        self._identity = RuntimeIdentity(
            runtime="zcode",
            runtime_version="desktop-db",
            display_name="ZCode",
        )
        self._read_lock = asyncio.Lock()
        self._poll_interval = (
            float(dict(config.values).get("pollIntervalMs", 5_000)) / 1000
        )
        self._stopping = False

    @property
    def sync_mode(self) -> str:
        return "polling"

    @property
    def identity(self) -> RuntimeIdentity:
        return self._identity

    @property
    def db(self) -> ZcodeDb:
        return self._db

    async def start(self) -> None:
        self._stopping = False
        await self.host.runtime_health_update("starting", {
            "code": "runtime_initializing",
            "message": "正在读取 ZCode 会话…",
            "retryable": True,
        })
        try:
            self._db.check_schema()
            await self.host.runtime_health_update("running")
        except (RuntimeUnavailableError, RuntimeUpstreamError) as exc:
            await self.host.runtime_health_update("starting", {
                "code": "runtime_unavailable",
                "message": "正在等待 ZCode 启动；请先启动 ZCode 桌面应用。",
                "retryable": True,
            })
            logger.warning(
                "zcode startup failed error_type={}",
                exc.__class__.__name__,
            )
            self._schedule_restart()

    async def stop(self) -> None:
        self._stopping = True

    def _schedule_restart(self) -> None:
        # The supervisor owns reconnect retry for polling runtimes; we only
        # keep the health state visible. Nothing to schedule here.
        return None

    async def get_config(self) -> RuntimeConfig:
        return self.config

    async def get_runtime_capabilities(self) -> RuntimeCapabilitySet:
        return self._capability_set()

    def _capability_set(self, session_id: str | None = None) -> RuntimeCapabilitySet:
        flags = provider_config.zcode_capabilities()
        capabilities = []
        for capability_id in (
            "catalog.model",
            "catalog.effort",
            "session.send_message",
            "session.steer",
            "session.interrupt",
            "session.interaction.approval",
            "runtime.attachment",
            "session.commands",
        ):
            capabilities.append(
                protocol_models.RuntimeCapability(
                    capability_id=capability_id,
                    scope="session" if session_id else "runtime",
                    runtime="zcode",
                    version="1",
                    session_id=session_id,
                    connector_id=self.host.connector_id,
                    supported=flags.get(_capability_flag(capability_id), False),
                    available=not self._stopping,
                    allowed=True,
                    metadata={"source": "zcode.db-adapter"},
                )
            )
        return protocol_models.RuntimeCapabilitySet(
            runtime="zcode",
            revision=1,
            capabilities=tuple(capabilities),
            session_id=session_id,
            connector_id=self.host.connector_id,
        )

    async def list_model_catalog(
        self, query: str | None = None, limit: int = 100
    ) -> RuntimeModelCatalog:
        async with self._read_lock:
            catalog_value = catalog.load_model_catalog(
                provider_config.zcode_data_dir(dict(self.config.values))
            )
        if query:
            needle = query.lower()
            catalog_value = protocol_models.RuntimeModelCatalog(
                runtime=catalog_value.runtime,
                revision=catalog_value.revision,
                runtime_id=catalog_value.runtime_id,
                models=tuple(
                    item
                    for item in catalog_value.models
                    if needle in item.title.lower()
                    or needle in str(item.metadata.get("model", "")).lower()
                ),
            )
        if limit > 0 and len(catalog_value.models) > limit:
            catalog_value = protocol_models.RuntimeModelCatalog(
                runtime=catalog_value.runtime,
                revision=catalog_value.revision,
                runtime_id=catalog_value.runtime_id,
                models=catalog_value.models[:limit],
            )
        return catalog_value

    async def list_permission_catalog(
        self, query: str | None = None, limit: int = 100
    ) -> RuntimePermissionCatalog:
        raise RuntimeUnsupportedError("zcode permission catalog")

    async def list_sessions(
        self,
        limit: int = 100,
        cursor: str | None = None,
        force: bool = False,
    ) -> tuple[SessionMeta, ...]:
        async with self._read_lock:
            metas, _ = sessions.list_interactive_sessions(
                self._db,
                connector_id=self.host.connector_id,
                runtime_id=None,
                limit=limit,
                cursor=cursor,
            )
            return tuple(metas)

    async def list_complete_session_inventory(
        self,
        page_size: int = 100,
        force: bool = False,
    ) -> tuple[SessionMeta, ...]:
        async with self._read_lock:
            metas = sessions.list_complete_session_inventory(
                self._db,
                connector_id=self.host.connector_id,
                runtime_id=None,
                page_size=page_size,
            )
            return tuple(metas)

    async def get_session_snapshot(
        self,
        session_id: str,
        external_session_id: str | None = None,
        limit: int | None = None,
    ) -> RuntimeTimelineSnapshot:
        external = _require_external(external_session_id)
        async with self._read_lock:
            return timeline.build_timeline_snapshot(
                self._db,
                external_session_id=external,
                connector_id=self.host.connector_id,
                runtime_id=None,
                limit=limit,
            )

    async def get_session_state(
        self,
        session_id: str,
        external_session_id: str | None = None,
    ) -> SessionState | None:
        external = _require_external(external_session_id)
        async with self._read_lock:
            row = self._db.query_one(
                "select id, time_created, time_updated, time_archived, task_type "
                "from session where id = ?",
                (external,),
            )
            if row is None:
                return None
            selected = zcode_selections.read_current_selections(
                self._db, external_session_id=external
            )
            return sessions.session_state_from_row(
                row,
                runtime_id=None,
                connector_id=self.host.connector_id,
                selections=selected,
            )

    async def get_session_notices(
        self,
        session_id: str,
        external_session_id: str | None = None,
    ) -> tuple[SessionNotice, ...]:
        return ()

    async def get_session_capabilities(
        self,
        session_id: str,
        external_session_id: str | None = None,
    ) -> RuntimeCapabilitySet:
        return self._capability_set(session_id=session_id)

    async def create_and_start_session(
        self,
        session_id: str,
        content: str,
        title: str | None = None,
        cwd: str | None = None,
        selections: Mapping[str, str | None] | None = None,
        attachments: tuple[RuntimeAttachment, ...] = (),
        client_message_id: str | None = None,
        *,
        runtime_options: Mapping[str, Any] | None = None,
    ) -> RuntimeOperationResult:
        if runtime_options:
            raise RuntimeUnsupportedError("runtimeOptions")
        if not cwd:
            raise RuntimeInvalidRequestError(
                "ZCode 新会话需要指定工作目录（cwd）。"
            )
        external_session_id = f"sess_{generate_session_uuid()}"
        _create_zcode_session(self._db, external_session_id, cwd, title)
        return await self._deliver(
            session_id=session_id,
            external_session_id=external_session_id,
            content=content,
            cwd=cwd,
        )

    async def start_turn(
        self,
        session_id: str,
        external_session_id: str | None,
        content: str,
        selections: Mapping[str, str | None] | None = None,
        attachments: tuple[RuntimeAttachment, ...] = (),
        client_message_id: str | None = None,
        cwd: str | None = None,
    ) -> RuntimeOperationResult:
        return await self._deliver(
            session_id=session_id,
            external_session_id=_require_external(external_session_id),
            content=content,
            cwd=cwd,
        )

    async def steer_turn(
        self,
        session_id: str,
        external_session_id: str | None,
        content: str,
        attachments: tuple[RuntimeAttachment, ...] = (),
        client_message_id: str | None = None,
    ) -> RuntimeOperationResult:
        # ZCode queues follow-up inputs while a turn runs; steering rides the
        # same sendText delivery path.
        return await self._deliver(
            session_id=session_id,
            external_session_id=_require_external(external_session_id),
            content=content,
        )

    async def interrupt_session(
        self,
        session_id: str,
        reason: str | None = None,
    ) -> RuntimeOperationResult:
        from connector.runtimes.zcode.session_input import (
            interrupt_by_queuing_delivery,
        )

        external = _external_for_session(
            self._db, session_id, self.host.connector_id
        )
        if external is None:
            raise RuntimeUnavailableError("找不到 ZCode 会话。")
        _ensure_zcode_running()
        receipt = interrupt_by_queuing_delivery(
            self._db, external_session_id=external
        )
        return RuntimeOperationResult(
            ok=True,
            code="interrupt_queued",
            message="已向 ZCode 排队中断信号。",
            result={"inputId": receipt.input_id, "status": receipt.status},
        )

    async def update_session_selections(
        self,
        session_id: str,
        external_session_id: str | None,
        selections: Mapping[str, str | None],
    ) -> RuntimeOperationResult:
        external = _require_external(external_session_id)
        if not selections:
            raise RuntimeInvalidRequestError("selections 不能为空。")
        async with self._read_lock:
            outcome = zcode_selections.apply_selections(
                self._db,
                external_session_id=external,
                selections=selections,
            )
        return RuntimeOperationResult(
            ok=True,
            code="selections_updated",
            message="会话选择已更新。",
            result=outcome,
        )

    async def list_commands(
        self,
        session_id: str,
        external_session_id: str | None = None,
        query: str | None = None,
        limit: int = 50,
    ) -> tuple[RuntimeCommand, ...]:
        return ()

    async def execute_command(
        self,
        session_id: str,
        command: str,
        external_session_id: str | None = None,
        raw: str | None = None,
        args: tuple[str, ...] = (),
    ) -> RuntimeCommandResult:
        return RuntimeCommandResult(
            command=command,
            ok=False,
            code="unknown_command",
            message="ZCode 数据库接入不支持会话命令。",
        )

    async def _deliver(
        self,
        *,
        session_id: str,
        external_session_id: str,
        content: str,
        cwd: str | None = None,
    ) -> RuntimeOperationResult:
        if not content.strip():
            raise RuntimeInvalidRequestError("消息内容不能为空。")
        _ensure_zcode_running()
        receipt = deliver_send_text(
            self._db, external_session_id=external_session_id, text=content
        )
        # The desktop promotes the row to 'promoted' once it dispatches the
        # message to the running agent. Until then the input is enqueued.
        monitored = read_delivery_status(
            self._db,
            input_id=receipt.input_id,
            external_session_id=external_session_id,
        )
        result = {
            "inputId": receipt.input_id,
            "status": monitored.status if monitored else receipt.status,
        }
        if monitored is not None and monitored.promoted_message_id:
            result["promotedMessageId"] = monitored.promoted_message_id
        return RuntimeOperationResult(
            ok=True,
            code="input_admitted" if result["status"] == "admitted" else "input_promoted",
            message="消息已投递给 ZCode。",
            result=result,
        )


def _capability_flag(capability_id: str) -> str:
    mapping = {
        "catalog.model": "modelCatalog",
        "catalog.effort": "modelCatalog",
        "catalog.permission": "permissionCatalog",
        "session.send_message": "startTurn",
        "session.steer": "steerTurn",
        "session.interrupt": "interruptTurn",
        "session.interaction.approval": "interactions",
        "runtime.attachment": "attachments",
        "session.commands": "commands",
    }
    return mapping.get(capability_id, "startTurn")


def _require_external(external_session_id: str | None) -> str:
    if not external_session_id:
        raise RuntimeInvalidRequestError("缺少 ZCode 会话 ID。")
    return external_session_id


def _external_for_session(
    db: ZcodeDb, session_id: str, connector_id: str
) -> str | None:
    """Reverse-map a platform session id back to its ZCode ``sess_<uuid>``.

    ``session_id`` is a one-way hash of the external id, so the match has to
    recompute the stable id for each candidate row.
    """
    from connector.runtimes.session_identity import stable_runtime_session_id

    rows = db.query(
        "select id from session where task_type in ('interactive')"
    )
    for row in rows:
        if stable_runtime_session_id(
            connector_id, "zcode", row["id"]
        ) == session_id:
            return row["id"]
    return None


def _ensure_zcode_running() -> None:
    if not discovery.zcode_is_running():
        raise RuntimeUnavailableError(
            "ZCode 未运行，无法投递消息。请先启动 ZCode 桌面应用。"
        )


def generate_session_uuid() -> str:
    import uuid

    return str(uuid.uuid4())


def _create_zcode_session(
    db: ZcodeDb, external_session_id: str, cwd: str, title: str | None
) -> None:
    """Create a ZCode session row the desktop can pick up.

    The desktop only loads sessions it knows about; a session row with the
    proper shape and an empty timeline satisfies that. The session becomes
    visible once the desktop next scans its project directory.
    """
    import time

    from connector.runtime_protocol.filesystem import canonical_path

    now = int(time.time() * 1000)
    project_id = _project_id_for_path(cwd)
    db.execute_write(
        "insert into session "
        "(id, project_id, slug, directory, path, title, version, time_created, "
        " time_updated, task_type, title_source) "
        "values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'interactive', 'first_input')",
        (
            external_session_id,
            project_id,
            external_session_id,
            cwd,
            canonical_path(cwd),
            title or "AgentPilot 新会话",
            "0.0.0",
            now,
            now,
        ),
    )


def _project_id_for_path(cwd: str) -> str:
    import hashlib
    import os

    normalized = os.path.normcase(os.path.abspath(cwd))
    digest = hashlib.sha256(normalized.encode("utf-8")).hexdigest()[:14]
    return f"proj_{digest}"