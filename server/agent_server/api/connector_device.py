from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException

from agent_server.api.connector_common import (
    connector_scope_id,
    raise_connector_service_error,
    require_owned_online_connector,
)
from agent_server.core.models import DeviceLaunchRequest, DeviceLaunchResponse
from agent_server.core.utc import utc_now
from agent_server.deps import (
    current_user_id,
    get_connector_device_service,
    get_rpc,
    get_store,
)
from agent_server.infra.connector_rpc import ConnectorRpcManager
from agent_server.infra.repositories.facade import Store
from agent_server.services.connector_device import (
    SUPPORTED_LAUNCH_APPS,
    ConnectorDeviceService,
)
from agent_server.services.connector_rpc import ConnectorServiceError

router = APIRouter(prefix="/connectors", tags=["connector-device"])


@router.post("/{connector_id}/device/launch", response_model=DeviceLaunchResponse)
async def connector_device_launch(
    connector_id: str,
    payload: DeviceLaunchRequest,
    user_id: str = Depends(current_user_id),
    store: Store = Depends(get_store),
    manager: ConnectorRpcManager = Depends(get_rpc),
    device: ConnectorDeviceService = Depends(get_connector_device_service),
) -> DeviceLaunchResponse:
    await require_owned_online_connector(connector_id, user_id, store, manager)
    if payload.app not in SUPPORTED_LAUNCH_APPS:
        raise HTTPException(status_code=400, detail=f"unsupported application: {payload.app}")
    try:
        result = await device.launch(
            connector_id=connector_id,
            scope_id=connector_scope_id(connector_id),
            app=payload.app,
        )
    except ConnectorServiceError as exc:
        raise_connector_service_error(exc)
    view = result if isinstance(result, dict) else {"launched": bool(result)}
    return DeviceLaunchResponse(
        launched=bool(view.get("launched", False)),
        alreadyRunning=bool(view.get("alreadyRunning", False)),
        app=str(view.get("app", payload.app)),
        path=view.get("path"),
        source=view.get("source"),
        pid=view.get("pid"),
        reason=view.get("reason"),
        serverTime=utc_now(),
    )
