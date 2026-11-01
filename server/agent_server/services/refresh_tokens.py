from __future__ import annotations

import asyncio
import math
import time

from loguru import logger

from agent_server.core.auth import USER_REFRESH_EXPIRES_IN
from agent_server.infra.redis_coordinator import RedisCoordinator


class RefreshTokenService:
    """Single-use tracking for refresh-token JTIs with theft detection.

    When Redis is configured (distributed), consumed-jti and revoked-family
    markers live in Redis with a TTL equal to the refresh-token lifetime so
    they expire naturally.  When running single-process (tests, dev without
    Redis), an in-memory dict with monotonic-expiry purge is used instead.
    """

    def __init__(self, coordinator: RedisCoordinator | None = None) -> None:
        self._coordinator = coordinator or RedisCoordinator()
        self._ttl = USER_REFRESH_EXPIRES_IN
        # Local (non-distributed) state
        self._consumed: dict[str, float] = {}  # jti -> monotonic expiry
        self._revoked_families: dict[str, float] = {}  # family_id -> monotonic expiry
        self._lock = asyncio.Lock()

    # ------------------------------------------------------------------ public

    async def consume_jti(self, jti: str, family_id: str) -> bool:
        """Atomically claim *jti* for single-use.

        Returns True when the jti was fresh (first use).  Returns False when
        the jti was already consumed — a replay / theft signal.
        """
        if self._coordinator.distributed:
            key = self._coordinator.key("refresh-jti", jti)
            ttl_ms = max(1, math.ceil(self._ttl * 1000))
            claimed = await self._coordinator.client.set(key, family_id, nx=True, px=ttl_ms)
            return bool(claimed)
        async with self._lock:
            self._purge_expired()
            if jti in self._consumed:
                return False
            self._consumed[jti] = time.monotonic() + self._ttl
            return True

    async def is_family_revoked(self, family_id: str) -> bool:
        """Return True when the token family has been revoked (theft detected)."""
        if not family_id:
            return False
        if self._coordinator.distributed:
            key = self._coordinator.key("refresh-family-revoked", family_id)
            return bool(await self._coordinator.client.exists(key))
        async with self._lock:
            self._purge_expired()
            return family_id in self._revoked_families

    async def revoke_family(self, family_id: str) -> None:
        """Revoke every refresh token that belongs to *family_id*."""
        if not family_id:
            return
        logger.warning(
            "Refresh-token family {} revoked (reuse / theft detected)", family_id
        )
        if self._coordinator.distributed:
            key = self._coordinator.key("refresh-family-revoked", family_id)
            ttl_ms = max(1, math.ceil(self._ttl * 1000))
            await self._coordinator.client.set(key, "1", px=ttl_ms)
        else:
            async with self._lock:
                self._revoked_families[family_id] = time.monotonic() + self._ttl

    # ----------------------------------------------------------------- private

    def _purge_expired(self) -> None:
        now = time.monotonic()
        for d in (self._consumed, self._revoked_families):
            expired = [k for k, exp in d.items() if now > exp]
            for k in expired:
                d.pop(k, None)
