from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, status
from starlette.requests import HTTPConnection

from agent_server.core.models import (
    ScheduleCreateRequest,
    ScheduleListResponse,
    SchedulePatchRequest,
    ScheduleResponse,
    UserView,
)
from agent_server.core.utc import utc_now
from agent_server.deps import current_user
from agent_server.services.schedules import ScheduleService

router = APIRouter(tags=["schedules"])


def get_service(conn: HTTPConnection) -> ScheduleService:
    return ScheduleService(conn.app.state.store)


SchedulesServiceDep = Annotated[ScheduleService, Depends(get_service)]
CurrentUserDep = Annotated[UserView, Depends(current_user)]


@router.get("/schedules", response_model=ScheduleListResponse)
async def list_schedules(
    service: SchedulesServiceDep, user: CurrentUserDep
) -> ScheduleListResponse:
    schedules = await service.list(user.userId)
    return ScheduleListResponse(schedules=schedules, serverTime=utc_now())


@router.post("/schedules", response_model=ScheduleResponse, status_code=status.HTTP_201_CREATED)
async def create_schedule(
    payload: ScheduleCreateRequest,
    service: SchedulesServiceDep,
    user: CurrentUserDep,
) -> ScheduleResponse:
    try:
        schedule = await service.create(user.userId, payload)
    except ValueError as error:
        raise HTTPException(status_code=400, detail=str(error)) from error
    return ScheduleResponse(schedule=schedule, serverTime=utc_now())


@router.patch("/schedules/{schedule_id}", response_model=ScheduleResponse)
async def patch_schedule(
    schedule_id: str,
    payload: SchedulePatchRequest,
    service: SchedulesServiceDep,
    user: CurrentUserDep,
) -> ScheduleResponse:
    try:
        schedule = await service.patch(user.userId, schedule_id, payload)
    except KeyError as error:
        raise HTTPException(status_code=404, detail="schedule not found") from error
    except ValueError as error:
        raise HTTPException(status_code=400, detail=str(error)) from error
    return ScheduleResponse(schedule=schedule, serverTime=utc_now())


@router.delete("/schedules/{schedule_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_schedule(
    schedule_id: str, service: SchedulesServiceDep, user: CurrentUserDep
) -> None:
    if not await service.delete(user.userId, schedule_id):
        raise HTTPException(status_code=404, detail="schedule not found")
