import json
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
from datetime import datetime
from decimal import Decimal

import pytest

from app.admin_access import _statistics, _users
from app.config import ProviderRoute, Settings
from app.provider import ProviderOutcome
from app.schemas import JobRequest
from app.store import SHANGHAI, Store, configuration_fingerprint, local_day


@pytest.fixture
def settings(tmp_path):
    return Settings(
        db_path=str(tmp_path / "attempts.sqlite3"), worker_enabled=False,
        api_enabled=True, api_key="synthetic-primary-secret", model="deepseek-primary",
        base_url="https://primary.example.invalid/v1", daily_limit=20, user_daily_limit=10,
        fallback_providers=(
            ProviderRoute(id="alternate", base_url="https://alternate.example.invalid/v1",
                          model="deepseek-alternate", api_key="synthetic-alternate-secret"),
            ProviderRoute(id="gpt", base_url="https://gpt.example.invalid/v1",
                          model="gpt-6.1-terra", api_key="synthetic-gpt-secret"),
        ),
        provider_route_order=("alternate", "gpt", "primary"),
    )


def prepare(settings):
    store = Store(settings)
    store.initialize()
    token, _ = store.create_session()
    return store, store.authenticate(token)


def submit(store, owner, key="ledger-job-0001"):
    return store.create_job(owner, JobRequest.model_validate({
        "idempotency_key": key,
        "messages": [{"id": "synthetic-source-1", "role": "user", "content": "Synthetic private source"}],
    }))


def route(settings, ordinal, route_id="primary"):
    provider = settings if route_id == "primary" else next(item for item in settings.fallback_providers if item.id == route_id)
    return {"ordinal": ordinal, "id": route_id, "base_url": provider.base_url, "model": provider.model}


def done(input_tokens=0, output_tokens=0, known=False, status="completed"):
    return ProviderOutcome(status, "Synthetic result", [], input_tokens, output_tokens, usage_known=known)


def test_fastest_route_can_be_first_and_every_extra_dispatch_counts(settings):
    store, owner = prepare(settings)
    job = submit(store, owner)
    assert store.quota(owner)["daily_reserved"] == 1
    assert store.claim_next()["id"] == job["id"]
    first = store.begin_provider_attempt(job["id"], route(settings, 0, "alternate"))
    assert first
    assert store.quota(owner)["daily_used"] == 1
    assert store.begin_provider_attempt(job["id"], route(settings, 0, "alternate")) is None
    second = store.begin_provider_attempt(job["id"], route(settings, 1, "gpt"))
    assert second
    third = store.begin_provider_attempt(job["id"], route(settings, 2, "primary"))
    assert third
    current = store.quota(owner)
    assert current["daily_used"] == current["user_daily_used"] == 3
    assert current["daily_reserved"] == current["user_daily_reserved"] == 0
    assert [row["route_id"] for row in store.provider_attempts(job["id"])] == ["alternate", "gpt", "primary"]
    encoded = json.dumps(store.provider_attempts(job["id"]))
    assert "Synthetic private source" not in encoded
    for secret in (settings.api_key, *(item.api_key for item in settings.fallback_providers)):
        assert secret not in encoded


def test_last_global_slot_is_atomic_across_workers(settings):
    settings = replace(settings, daily_limit=3, user_daily_limit=3)
    store, first_owner = prepare(settings)
    token, _ = store.create_session()
    second_owner = store.authenticate(token)
    jobs = [submit(store, first_owner, "ledger-global-0001"), submit(store, second_owner, "ledger-global-0002")]
    for job in jobs:
        assert store.claim_next()
        assert store.begin_provider_attempt(job["id"], route(settings, 0))
    with ThreadPoolExecutor(max_workers=2) as pool:
        ids = list(pool.map(lambda job: store.begin_provider_attempt(job["id"], route(settings, 1, "alternate")), jobs))
    assert sum(item is not None for item in ids) == 1
    assert store.quota(first_owner)["daily_used"] == 3


def test_user_limit_and_queued_reservations_prevent_extra_dispatch(settings):
    settings = replace(settings, user_daily_limit=2)
    store, owner = prepare(settings)
    first = submit(store, owner)
    submit(store, owner, "ledger-reserved-0002")
    assert store.claim_next()["id"] == first["id"]
    assert store.begin_provider_attempt(first["id"], route(settings, 0))
    assert store.begin_provider_attempt(first["id"], route(settings, 1, "alternate")) is None
    current = store.quota(owner)
    assert current["user_daily_used"] == 1 and current["user_daily_reserved"] == 1


@pytest.mark.parametrize("change", ["cancel", "config", "fingerprint", "route", "skip", "repeat"])
def test_cancelled_changed_or_invalid_attempts_never_dispatch(settings, change):
    store, owner = prepare(settings)
    job = submit(store, owner)
    store.claim_next()
    assert store.begin_provider_attempt(job["id"], route(settings, 0))
    metadata = route(settings, 1, "alternate")
    if change == "cancel":
        store.cancel(owner, job["id"])
    elif change == "config":
        store.update_config({"model": "deepseek-changed"})
    elif change == "fingerprint":
        metadata["configuration_fingerprint"] = "stale-fingerprint"
    elif change == "route":
        metadata["model"] = "unconfigured-model"
    elif change == "skip":
        metadata["ordinal"] = 2
    else:
        metadata = route(settings, 1)
    assert store.begin_provider_attempt(job["id"], metadata) is None
    assert len(store.provider_attempts(job["id"])) == 1
    assert store.quota(owner)["daily_used"] == 1


def test_attempt_finish_is_idempotent_and_restart_never_replays(settings):
    store, owner = prepare(settings)
    job = submit(store, owner)
    store.claim_next()
    first = store.begin_provider_attempt(job["id"], route(settings, 0))
    store.finish_provider_attempt(first, done(), status_override="uncertain")
    store.finish_provider_attempt(first, done(100, 50, True))
    second = store.begin_provider_attempt(job["id"], route(settings, 1, "alternate"))
    assert second
    store.recover_running()
    attempts = store.provider_attempts(job["id"])
    assert [item["status"] for item in attempts] == ["uncertain", "uncertain"]
    assert attempts[0]["input_tokens"] == 0
    assert store.get_job(owner, job["id"])["status"] == "uncertain"
    assert store.begin_provider_attempt(job["id"], route(settings, 2, "gpt")) is None
    assert store.claim_next() is None
    assert store.quota(owner)["daily_used"] == 2


def test_statistics_count_attempt_usage_once_and_keep_job_outcomes_separate(settings):
    store, owner = prepare(settings)
    legacy = submit(store, owner, "ledger-legacy-0001")
    store.claim_next()
    store.finish(legacy["id"], done(7, 1, True))
    job = submit(store, owner)
    store.claim_next()
    first = store.begin_provider_attempt(job["id"], route(settings, 0))
    store.finish_provider_attempt(first, done(10, 2, True, "failed"))
    second = store.begin_provider_attempt(job["id"], route(settings, 1, "alternate"))
    store.finish_provider_attempt(second, done(20, 3, True))
    store.finish(job["id"], done(30, 5, True))
    stats = _statistics(store, 1)["totals"]
    assert stats["requests"] == stats["usage_known_requests"] == 3
    assert stats["input_tokens"] == 37 and stats["output_tokens"] == 6
    assert stats["completed"] == 2 and stats["failed"] == 0
    user = _users(store, 10, 0)["users"][0]
    assert user["total_requests"] == user["daily_used"] == 3
    assert user["completed"] == 2
    assert user["input_tokens"] == 37 and user["output_tokens"] == 6


def test_unknown_abandoned_attempt_remains_unknown_beside_valid_backup(settings):
    store, owner = prepare(settings)
    job = submit(store, owner)
    store.claim_next()
    first = store.begin_provider_attempt(job["id"], route(settings, 0))
    store.finish_provider_attempt(first, done(status="uncertain"))
    second = store.begin_provider_attempt(job["id"], route(settings, 1, "alternate"))
    store.finish_provider_attempt(second, done(20, 3, True))
    store.finish(job["id"], done(20, 3, False))
    stats = _statistics(store, 1)["totals"]
    assert stats["requests"] == 2
    assert stats["usage_known_requests"] == stats["usage_unknown_requests"] == 1
    assert stats["input_tokens"] == 20 and stats["output_tokens"] == 3
    assert store.get_job(owner, job["id"])["usage_known"] is False


def test_backup_counts_on_its_own_beijing_day(settings, monkeypatch):
    before = datetime(2026, 11, 13, 23, 59, 50, tzinfo=SHANGHAI).timestamp()
    after = before + 20
    monkeypatch.setattr("app.store.time.time", lambda: before)
    store, owner = prepare(settings)
    job = submit(store, owner)
    store.claim_next()
    assert store.begin_provider_attempt(job["id"], route(settings, 0))
    monkeypatch.setattr("app.store.time.time", lambda: after)
    assert store.begin_provider_attempt(job["id"], route(settings, 1, "alternate"))
    assert [item["count_day"] for item in store.provider_attempts(job["id"])] == [local_day(before), local_day(after)]
    assert store.quota(owner)["daily_used"] == 1


def test_priced_mode_stays_single_route(settings):
    settings = replace(settings, input_price_per_million=Decimal("1"), output_price_per_million=Decimal("2"))
    store, owner = prepare(settings)
    job = submit(store, owner)
    store.claim_next()
    assert store.begin_provider_attempt(job["id"], route(settings, 0, "alternate")) is None
    first = store.begin_provider_attempt(job["id"], route(settings, 0))
    assert first
    assert store.begin_provider_attempt(job["id"], route(settings, 1, "alternate")) is None
    assert store.quota(owner)["daily_used"] == 1


def test_route_order_and_credentials_change_the_nonsecret_fingerprint(settings):
    first = configuration_fingerprint(settings)
    reordered = replace(settings, provider_route_order=("primary", "alternate", "gpt"))
    assert first != configuration_fingerprint(reordered)
    changed_route = replace(settings.fallback_providers[0], api_key="synthetic-new-secret")
    assert first != configuration_fingerprint(replace(settings, fallback_providers=(changed_route, settings.fallback_providers[1])))
    assert settings.api_key not in first


@pytest.mark.parametrize("mode", ["normal", "priced", "mock", "single"])
def test_quota_discloses_bounded_automatic_attempts_without_changing_model(settings, mode):
    if mode == "priced":
        settings = replace(settings, input_price_per_million=Decimal("1"), output_price_per_million=Decimal("2"))
    elif mode == "mock":
        settings = replace(settings, mock_mode=True)
    elif mode == "single":
        settings = replace(settings, fallback_providers=(), provider_route_order=("primary",))
    store, owner = prepare(settings)
    quota = store.quota(owner)
    assert quota["automatic_fallback_enabled"] is (mode == "normal")
    assert quota["max_attempts_per_chunk"] == (3 if mode == "normal" else 1)
    assert quota["model"] == settings.display_model
    assert quota["context_tokens"] == settings.context_tokens
    assert quota["max_output_tokens"] == settings.max_output_tokens
