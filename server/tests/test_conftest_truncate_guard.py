"""Guards around the external-database truncation autouse fixture.

AGENT_SERVER_DB_URL can point anywhere, and the fixture is destructive, so the
guard must refuse unless the DB is explicitly confirmed and clearly disposable.
"""

from __future__ import annotations

import pytest
from conftest import _assert_truncate_allowed, _pg_database_name

_DEV_URL = "postgresql://agents:password@localhost:5432/agent_pilot"


def test_database_name_parses_asyncpg_variants() -> None:
    assert _pg_database_name(_DEV_URL) == "agent_pilot"
    assert (
        _pg_database_name("postgresql+asyncpg://u:p@host:5432/agent_pilot_test") == "agent_pilot_test"
    )
    assert _pg_database_name("postgresql://u:p@host:5432/agent_pilot?ssl=require") == "agent_pilot"
    assert _pg_database_name("postgresql://u:p@host:5432") == ""


def test_truncation_requires_the_explicit_switch(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("AGENT_SERVER_TEST_ALLOW_DB_TRUNCATE", raising=False)
    with pytest.raises(RuntimeError, match="AGENT_SERVER_TEST_ALLOW_DB_TRUNCATE"):
        _assert_truncate_allowed(_DEV_URL)


def test_truncation_rejects_a_non_disposable_database(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("AGENT_SERVER_TEST_ALLOW_DB_TRUNCATE", "1")
    with pytest.raises(RuntimeError, match="production"):
        _assert_truncate_allowed("postgresql://agents:password@prod-db:5432/production")
    with pytest.raises(RuntimeError, match="Refusing"):
        _assert_truncate_allowed("postgresql://agents:password@prod-db:5432")


def test_truncation_allows_the_dev_and_test_databases(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("AGENT_SERVER_TEST_ALLOW_DB_TRUNCATE", "true")
    _assert_truncate_allowed(_DEV_URL)
    _assert_truncate_allowed("postgresql://u:p@host:5432/agent_pilot_test")
