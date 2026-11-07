from __future__ import annotations

from abc import ABC, abstractmethod
from collections.abc import Mapping
from typing import Any
from connector.core.json_kv import JsonKeyValueStore

from connector.runtime_protocol.models import (
    RuntimeAttachmentContent,
    RuntimeCapabilitySet,
    RuntimeModelCatalog,
    RuntimePermissionCatalog,
    RuntimeStatus,
    RuntimeTimelineItem,
    SessionNotice,
    SessionSourceObservation,
)


class RuntimeHostClient(ABC):
    """Runtime -> Connector."""

    async def observe_planning(self, observation) -> None:
        """Hand a planning observation to the unified planning host, if one is bound.

        The host is installed by the composition root (bind_planning) as
        `_planning_observer`; without it the observation is dropped rather than
        failing the runtime, so a legacy host keeps working.
        """
        handler = getattr(self, "_planning_observer", None)
        if handler is not None:
            await handler(observation)

    async def validate_planning_document(self, session_id: str, text: str) -> bool:
        """Ask the planning host whether a document is worth accepting.

        Defaults to True: a host that has not bound a validator must not reject
        a plan the runtime considers valid.
        """
        callback = getattr(self, "_planning_validate", None)
        return callback(session_id, text) if callback else True

    async def prepare_planning_turn(self, session_id: str, external_id: str | None) -> bool:
        """Ask the planning host to arm a turn before the runtime starts it.

        False when no host is bound: a runtime without planning support must not
        be told a plan was demanded.
        """
        callback = getattr(self, "_planning_prepare", None)
        return await callback(session_id, external_id) if callback else False

    async def publish_planning(self, snapshot: dict, durable: bool = False) -> None:
        """Publish one planning snapshot. Real hosts forward this to the Server.

        The default raises because a host that cannot publish must not swallow
        the state: bind_planning checks for this method before binding anything.
        """
        raise NotImplementedError("Planning publication is unavailable")

    async def prepare_runtime_host(self, runtime_id: str) -> RuntimeHostClient:
        """Bind storage before a provider constructs its runtime; legacy hosts are unchanged."""
        return self

    @property
    def runtime_kv(self) -> JsonKeyValueStore:
        return JsonKeyValueStore.default()

    async def publish_runtime_notifications(
        self, runtime: str, notifications: list[dict[str, Any]], runtime_id: str | None = None
    ) -> None:
        """Await the existing Connector ingest path, without the fallback queue."""
        raise NotImplementedError("Synchronous notification ingestion is unavailable")

    @property
    @abstractmethod
    def connector_id(self) -> str:
        raise NotImplementedError

    @property
    def session_namespace(self) -> str:
        """Stable namespace used when deriving platform session identities."""

        return self.connector_id

    async def session_meta_upsert(
        self,
        session_id: str,
        runtime: str,
        external_session_id: str | None = None,
        title: str | None = None,
        cwd: str | None = None,
        ordering_time: str | None = None,
        metadata: Mapping[str, Any] | None = None,
    ) -> None:
        raise NotImplementedError

    async def session_state_update(
        self,
        session_id: str,
        runtime: str,
        status: RuntimeStatus | None = None,
        selections: Mapping[str, str | None] | None = None,
        external_session_id: str | None = None,
        status_reason: str | None = None,
        error: Mapping[str, Any] | None = None,
        metadata: Mapping[str, Any] | None = None,
    ) -> None:
        raise NotImplementedError

    async def session_source_update(
        self,
        observation: SessionSourceObservation,
    ) -> None:
        raise NotImplementedError

    async def session_turn_ended(
        self,
        session_id: str,
        runtime: str,
        external_session_id: str | None = None,
        turn_id: str | None = None,
        outcome: str = "completed",
        metadata: Mapping[str, Any] | None = None,
    ) -> None:
        raise NotImplementedError

    async def runtime_capabilities_update(
        self,
        capabilities: RuntimeCapabilitySet,
    ) -> None:
        raise NotImplementedError

    async def session_capabilities_update(
        self,
        capabilities: RuntimeCapabilitySet,
    ) -> None:
        raise NotImplementedError

    async def model_catalog_update(
        self,
        catalog: RuntimeModelCatalog,
    ) -> None:
        raise NotImplementedError

    async def permission_catalog_update(
        self,
        catalog: RuntimePermissionCatalog,
    ) -> None:
        raise NotImplementedError

    async def timeline_sync(
        self,
        session_id: str,
        runtime: str,
        items: tuple[RuntimeTimelineItem, ...],
        external_session_id: str | None = None,
        complete: bool = False,
        metadata: Mapping[str, Any] | None = None,
    ) -> None:
        raise NotImplementedError

    async def timeline_item_upsert(
        self,
        item: RuntimeTimelineItem,
    ) -> None:
        raise NotImplementedError

    async def notice_upsert(
        self,
        notice: SessionNotice,
    ) -> None:
        raise NotImplementedError

    async def runtime_error(
        self,
        runtime: str,
        code: str,
        message: str,
        session_id: str | None = None,
        external_session_id: str | None = None,
        details: Mapping[str, Any] | None = None,
    ) -> None:
        raise NotImplementedError

    async def runtime_health_update(
        self,
        status: str,
        error: Mapping[str, Any] | None = None,
    ) -> None:
        """Report a provider-owned health change for this runtime instance.

        The Connector routes this to the owning supervisor so runtime status
        stays one state machine. Hosts that are not bound to an instance ignore
        it.
        """

        return None

    async def attachment_download(
        self,
        session_id: str,
        file_id: str,
    ) -> RuntimeAttachmentContent:
        raise NotImplementedError

    async def sync_state_read(
        self,
        key: str,
    ) -> Mapping[str, Any] | None:
        raise NotImplementedError

    async def sync_state_write(
        self,
        key: str,
        value: Mapping[str, Any],
    ) -> None:
        raise NotImplementedError

    async def sync_state_delete(
        self,
        key: str,
    ) -> None:
        raise NotImplementedError


def runtime_kv_store(host: RuntimeHostClient) -> JsonKeyValueStore:
    """Support older structural Host implementations outside the Connector."""
    store = getattr(host, "runtime_kv", None)
    return store if store is not None else JsonKeyValueStore.default()
