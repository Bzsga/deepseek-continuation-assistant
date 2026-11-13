import json
import os
from dataclasses import replace

import pytest

from app.config import ProviderRoute, Settings, load_fallback_configuration


def private_file(tmp_path, document):
    path = tmp_path / "providers.json"
    path.write_text(json.dumps(document), encoding="utf-8")
    path.chmod(0o600)
    return path


def document():
    return {
        "providers": [{"id": "alternate", "base_url": "https://alternate.invalid/v1",
                       "model": "deepseek-v4.1-flash", "api_key": "synthetic-private-route-key"}],
        "order": ["alternate", "primary"], "slow_seconds": 30, "cooldown_seconds": 300,
    }


def test_private_file_loads_measured_order_without_repr_secrets(tmp_path, monkeypatch):
    path = private_file(tmp_path, document())
    monkeypatch.setenv("FALLBACK_PROVIDERS_FILE", str(path))
    settings = Settings.from_env()
    assert settings.provider_route_order == ("alternate", "primary")
    assert settings.fallback_providers[0].api_key == "synthetic-private-route-key"
    assert "synthetic-private-route-key" not in repr(settings) + repr(settings.fallback_providers)


@pytest.mark.parametrize("change", [
    {"order": ["alternate", "alternate"]}, {"order": [["alternate"], "primary"]},
    {"slow_seconds": True}, {"cooldown_seconds": float("inf")},
    {"providers": [{"id": "primary", "base_url": "https://alternate.invalid/v1", "model": "terra", "api_key": "secret"}]},
])
def test_invalid_route_file_fails_before_any_api_work(tmp_path, monkeypatch, change):
    path = private_file(tmp_path, {**document(), **change})
    monkeypatch.setenv("FALLBACK_PROVIDERS_FILE", str(path))
    with pytest.raises(ValueError) as error:
        Settings.from_env()
    assert "synthetic-private-route-key" not in str(error.value)


@pytest.mark.skipif(os.name == "nt", reason="POSIX owner/mode protection")
def test_world_readable_credentials_are_refused(tmp_path):
    path = private_file(tmp_path, document())
    path.chmod(0o644)
    with pytest.raises(ValueError, match="私有配置"):
        load_fallback_configuration(path)


def test_duplicate_fallback_ids_are_rejected():
    route = ProviderRoute("alternate", "https://alternate.invalid", "deepseek-v4.1-flash", "secret")
    with pytest.raises(ValueError):
        replace(Settings(), fallback_providers=(route, route)).validate()
