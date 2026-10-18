"""Tests for the ZCode model catalog projection."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from connector.runtime_protocol import RuntimeUnavailableError
from connector.runtimes.zcode.catalog import load_model_catalog
from connector.runtimes.zcode.identity import (
    decode_model_selection_id,
    model_selection_id,
)

_PROVIDER_ID = "c87cb8f0-6a37-403a-bd6f-15633d784751"
_OTHER_PROVIDER_ID = "91c75199-6a18-4159-8beb-a37f2da9b058"

# A value that must never appear in the projected catalog; stands in for the
# credential material the real profile carries.
_SECRET_SENTINEL = "REDACTED-PROVIDER-SECRET"


def _write_profile(
    data_dir: Path,
    *,
    providers: list[dict],
    model_rules: list[dict],
) -> None:
    path = data_dir / "v2" / "provider_config.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(
            {
                "schemaVersion": 1,
                "config": {
                    "providerConfigRules": {"providerRules": providers},
                    "modelConfigRules": {"providerModelRules": model_rules},
                },
            }
        ),
        encoding="utf-8",
    )


def _provider_rule(
    provider_id: str,
    model_ids: list[str],
    *,
    provider_name: str | None = None,
) -> dict:
    return {
        "providerId": provider_id,
        "providerName": provider_name or provider_id,
        "config": {
            "group": "local",
            "access": {
                "type": "apiKey",
                "secret": _SECRET_SENTINEL,
            },
            "api": {"type": "openai", "baseUrl": "http://127.0.0.1:8000"},
            "personalModelIds": list(model_ids),
            "modelOrder": list(model_ids),
        },
    }


def _model_rule(provider_id: str, model_id: str, **properties) -> dict:
    return {
        "providerId": provider_id,
        "modelId": model_id,
        "config": {"properties": properties},
    }


def test_catalog_maps_models_without_exposing_keys(tmp_path: Path) -> None:
    model_rules = [
        _model_rule(_PROVIDER_ID, "deepseekv4-flash", contextWindow=256000),
        _model_rule(
            _PROVIDER_ID,
            "eye",
            contextWindow=256000,
            inputFormat={"supportsImage": True},
        ),
    ]
    providers = [
        _provider_rule(
            _PROVIDER_ID,
            ["deepseekv4-flash", "eye"],
            provider_name="deepseek",
        )
    ]
    _write_profile(tmp_path, providers=providers, model_rules=model_rules)

    catalog = load_model_catalog(tmp_path)

    assert catalog.runtime == "zcode"
    assert catalog.revision >= 0
    assert [model.id for model in catalog.models] == [
        "deepseekv4-flash",
        "eye",
    ]
    flash = catalog.models[0]
    assert flash.title == "deepseek · deepseekv4-flash"
    assert flash.selection_id == model_selection_id(
        _PROVIDER_ID, "deepseekv4-flash", None
    )
    assert flash.metadata["provider"] == _PROVIDER_ID
    assert flash.metadata["model"] == "deepseekv4-flash"
    assert flash.metadata["contextWindow"] == 256000
    assert _SECRET_SENTINEL not in json.dumps(
        {
            "title": flash.title,
            "selectionId": flash.selection_id,
            "metadata": flash.metadata,
        }
    )
    eye = catalog.models[1]
    assert eye.metadata["supportsImage"] is True

    provider, model, effort = decode_model_selection_id(flash.selection_id)
    assert provider == _PROVIDER_ID
    assert model == "deepseekv4-flash"
    assert effort is None


def test_catalog_reasoning_items_and_order(tmp_path: Path) -> None:
    providers = [
        _provider_rule(
            _PROVIDER_ID,
            ["flash", "amd"],
            provider_name="deepseek",
        ),
        _provider_rule(_OTHER_PROVIDER_ID, ["qwen"], provider_name="QWEN"),
    ]
    model_rules = [
        _model_rule(
            _PROVIDER_ID, "flash", reasoningLevel="enabled",
        ),
        _model_rule(_PROVIDER_ID, "amd"),
        _model_rule(_OTHER_PROVIDER_ID, "qwen"),
    ]
    _write_profile(tmp_path, providers=providers, model_rules=model_rules)

    catalog = load_model_catalog(tmp_path)

    assert [model.id for model in catalog.models] == ["flash", "amd", "qwen"]
    flash = catalog.models[0]
    assert len(flash.reasoning_items) == 1
    reasoning = flash.reasoning_items[0]
    assert reasoning.id == "flash:enabled"
    assert reasoning.selection_id == model_selection_id(
        _PROVIDER_ID, "flash", "enabled"
    )
    assert flash.selection_id != reasoning.selection_id


def test_catalog_missing_profile_raises(tmp_path: Path) -> None:
    with pytest.raises(RuntimeUnavailableError):
        load_model_catalog(tmp_path)


def test_catalog_ignores_malformed_rules(tmp_path: Path) -> None:
    providers = [
        {
            "providerId": _PROVIDER_ID,
            "config": {"modelOrder": ["flash", "flash", ""]},
        },
        {"config": {"modelOrder": ["no-provider-id"]}},
        "not-a-dict",
    ]
    _write_profile(tmp_path, providers=providers, model_rules=[])

    catalog = load_model_catalog(tmp_path)

    assert [model.id for model in catalog.models] == ["flash"]