from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException
from starlette.requests import HTTPConnection

from agent_server.api.connector_common import require_owned_connector
from agent_server.core.protocol import ProtocolWsTicketRequest, ProtocolWsTicketResponse
from agent_server.core.utc import utc_now
from agent_server.deps import current_user_id, get_store
from agent_server.infra.repositories.facade import Store
from agent_server.infra.ws_tickets import ClientWsTicketManager

router = APIRouter(tags=["client-ws"])


def get_ws_tickets(conn: HTTPConnection) -> ClientWsTicketManager:
    return conn.app.state.ws_tickets


@router.post("/ws-ticket", response_model=ProtocolWsTicketResponse)
async def create_ws_ticket(
    payload: ProtocolWsTicketRequest,
    user_id: Annotated[str, Depends(current_user_id)],
    db: Annotated[Store, Depends(get_store)],
    tickets: Annotated[ClientWsTicketManager, Depends(get_ws_tickets)],
) -> ProtocolWsTicketResponse:
    if payload.scope.dashboard:
        scope = "dashboard"
    elif payload.scope.sessionId is not None:
        try:
            await db.get_session(payload.scope.sessionId, user_id=user_id)
        except KeyError:
            raise HTTPException(status_code=404, detail="session not found") from None
        scope = payload.scope.sessionId
    else:
        connector_id = payload.scope.connectorId or ""
        terminal_id = payload.scope.terminalId or ""
        if not connector_id or not terminal_id:
            raise HTTPException(
                status_code=422,
                detail="connectorId and terminalId are required for a terminal scope",
            )
        try:
            await require_owned_connector(connector_id, user_id, db)
        except HTTPException:
            raise HTTPException(status_code=404, detail="connector not found") from None
        scope = f"terminal:{connector_id}:{terminal_id}"
    ticket, expires_at = await tickets.issue(
        user_id=user_id,
        client_id=payload.clientId,
        scope=scope,
    )
    return ProtocolWsTicketResponse(ticket=ticket, expiresAt=expires_at, serverTime=utc_now())
