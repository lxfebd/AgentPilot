"""ZCode model/permission catalog projection.

The catalog is derived from ``<data>/v2/provider_config.json`` (the profile the
desktop itself writes). Only model ids, provider ids and safe display metadata
leave this module — provider access/API keys live in the same file and are
never projected into the catalog, logs, or RPC payloads.
"""

from __future__ import annotations

import json
import time
from pathlib import Path
from typing import Any

from connector.runtime_protocol import (
    RuntimeModelCatalog,
    RuntimeModelItem,
    RuntimeReasoningItem,
    RuntimeUnavailableError,
)
from connector.runtimes.zcode.identity import model_selection_id

# The desktop updates the profile file each time settings are saved; using its
# mtime keeps the catalog revision moving without a separate counter.
_CATALOG_REVISION_FACTOR = 1000


def _provider_config_path(data_dir: Path) -> Path:
    return data_dir / "v2" / "provider_config.json"


def load_model_catalog(data_dir: Path) -> RuntimeModelCatalog:
    path = _provider_config_path(data_dir)
    if not path.is_file():
        raise RuntimeUnavailableError(
            "找不到 ZCode 模型配置（v2/provider_config.json）。"
        )
    try:
        with open(path, encoding="utf-8") as handle:
            document = json.load(handle)
    except (OSError, ValueError) as exc:
        raise RuntimeUnavailableError(
            "ZCode 模型配置无法读取或不是有效 JSON。"
        ) from exc

    config = _mapping(document.get("config"))
    provider_rules = _list(
        _mapping(config.get("providerConfigRules")).get("providerRules")
    )
    model_rules = _list(
        _mapping(config.get("modelConfigRules")).get("providerModelRules")
    )

    property_index: dict[tuple[str, str], dict[str, Any]] = {}
    for rule in model_rules:
        rule = _mapping(rule)
        provider_id = _optional_string(rule.get("providerId"))
        model_id = _optional_string(rule.get("modelId"))
        if not provider_id or not model_id:
            continue
        property_index[(provider_id, model_id)] = _mapping(
            _mapping(rule.get("config")).get("properties")
        )

    models: list[RuntimeModelItem] = []
    seen: set[str] = set()
    for rule in provider_rules:
        rule = _mapping(rule)
        provider_id = _optional_string(rule.get("providerId"))
        provider_name = _optional_string(rule.get("providerName"))
        if not provider_id:
            continue
        config_value = _mapping(rule.get("config"))
        model_ids = _list(config_value.get("modelOrder"))
        if not model_ids:
            model_ids = _list(config_value.get("personalModelIds"))
        for model_id in model_ids:
            model_id = _optional_string(model_id)
            if not model_id or model_id in seen:
                continue
            seen.add(model_id)
            properties = property_index.get((provider_id, model_id), {})
            model = _model_item(
                provider_id=provider_id,
                provider_name=provider_name,
                model_id=model_id,
                properties=properties,
            )
            models.append(model)

    revision = int(time.time() * 1000 // _CATALOG_REVISION_FACTOR)
    try:
        mtime = int(path.stat().st_mtime * 1000 // _CATALOG_REVISION_FACTOR)
    except OSError:
        mtime = revision
    return RuntimeModelCatalog(
        runtime="zcode",
        revision=max(revision, mtime),
        models=tuple(models),
    )


def _model_item(
    *,
    provider_id: str,
    provider_name: str | None,
    model_id: str,
    properties: dict[str, Any],
) -> RuntimeModelItem:
    reasoning_items: list[RuntimeReasoningItem] = []
    reasoning_level = _optional_string(properties.get("reasoningLevel"))
    if reasoning_level:
        reasoning_items.append(
            RuntimeReasoningItem(
                id=f"{model_id}:{reasoning_level}",
                title=_reasoning_title(reasoning_level),
                selection_id=model_selection_id(
                    provider_id, model_id, reasoning_level
                ),
                description=None,
                enabled=True,
                disabled_reason=None,
                metadata={},
            )
        )
    context_window = properties.get("contextWindow")
    supports_image = _mapping(properties.get("inputFormat")).get(
        "supportsImage"
    )
    metadata: dict[str, Any] = {
        "provider": provider_id,
        "providerName": provider_name or provider_id,
        "model": model_id,
        "source": "zcode.provider-config",
    }
    if isinstance(context_window, int) and context_window > 0:
        metadata["contextWindow"] = context_window
    if supports_image is True:
        metadata["supportsImage"] = True
    return RuntimeModelItem(
        id=model_id,
        title=_model_title(provider_name, model_id),
        selection_id=model_selection_id(provider_id, model_id, None),
        description=None,
        reasoning_items=tuple(reasoning_items),
        enabled=True,
        disabled_reason=None,
        metadata=metadata,
    )


def _model_title(provider_name: str | None, model_id: str) -> str:
    if provider_name and provider_name.strip():
        return f"{provider_name.strip()} · {model_id}"
    return model_id


def _reasoning_title(level: str) -> str:
    titles = {
        "off": "No reasoning",
        "enabled": "Reasoning",
        "high": "High reasoning",
    }
    return titles.get(level, level)


def _mapping(value: Any) -> dict[str, Any]:
    return value if isinstance(value, dict) else {}


def _list(value: Any) -> list[Any]:
    return value if isinstance(value, list) else []


def _optional_string(value: Any) -> str | None:
    return value if isinstance(value, str) and value else None
