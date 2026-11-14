import asyncio
import json
import secrets
from dataclasses import replace
from decimal import Decimal

import httpx
import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app
from app.provider import ProviderOutcome, organize, parse_usage, snapshot_for
from app.schemas import JobRequest
from app.store import Store
from account_fixtures import registered_job, registered_session, register_owner


PRIVATE = "SYNTHETIC_PRIVATE_RESPONSE_VALUE"


@pytest.fixture
def settings(tmp_path):
    return Settings(
        db_path=str(tmp_path / "usage.sqlite3"), api_enabled=True, api_key=secrets.token_urlsafe(32),
        public_enabled=True, daily_limit=500, user_daily_limit=20, context_tokens=64000,
        max_output_tokens=4096, worker_enabled=False, base_url="https://synthetic.invalid/v1",
        model="deepseek-v4.1-flash", admin_token=secrets.token_urlsafe(32),
    )


def messages():
    return [{"id": "original-source-id", "role": "user", "content": "合成旅行计划"}]


def envelope(usage=None, *, include_usage=True):
    value = {"choices": [{"finish_reason": "stop", "message": {
        "content": json.dumps({"entries": [{"category": "RECENT", "text": "合成摘要",
            "sourceIds": ["m1"], "kind": "USER_FACT"}]}), "reasoning_content": PRIVATE}}]}
    if include_usage:
        value["usage"] = usage
    return value


def mock_http(monkeypatch, response, status=200, raw=None, transport_error=None):
    requests = []
    original_client = httpx.AsyncClient

    def handler(request):
        requests.append(json.loads(request.content))
        if transport_error is not None:
            raise transport_error
        if raw is not None:
            return httpx.Response(status, content=raw)
        return httpx.Response(status, json=response)

    def client(**kwargs):
        return original_client(transport=httpx.MockTransport(handler), **kwargs)

    monkeypatch.setattr("app.provider.httpx.AsyncClient", client)
    return requests


def execute(settings, *, access_mode="public", snapshot=None):
    if snapshot is None:
        snapshot, _ = snapshot_for(settings, messages())
    snapshot["access_mode"] = access_mode
    return asyncio.run(organize(settings, snapshot, messages()))


@pytest.mark.parametrize("usage", [
    {"prompt_tokens": 100, "completion_tokens": 80},
    {"prompt_tokens": "100", "completion_tokens": "00080"},
    {"input_tokens": 100, "output_tokens": 80},
    {"input_tokens": "100", "output_tokens": "80"},
    {"prompt_tokens": "100", "completion_tokens": 80, "input_tokens": 100, "output_tokens": "80"},
    {"prompt_tokens": 100, "input_tokens": 100, "output_tokens": 80},
])
def test_supported_complete_usage_pairs_are_known(settings, monkeypatch, usage):
    requests = mock_http(monkeypatch, envelope(usage))
    priced = replace(settings, input_price_per_million=Decimal("2"), output_price_per_million=Decimal("8"))
    result = execute(priced)
    assert result.status == "completed"
    assert result.usage_known is True
    assert (result.input_tokens, result.output_tokens, result.cost_micros) == (100, 80, 840)
    assert len(requests) == 1
    wire = json.loads(requests[0]["messages"][1]["content"])["source_messages"]
    assert wire[0]["id"] == "m1"


@pytest.mark.parametrize("usage", [
    None, {}, "not-an-object", [], {"total_tokens": 180},
    {"prompt_tokens": 100}, {"completion_tokens": 80},
    {"prompt_tokens": 100, "output_tokens": 80},
    {"prompt_tokens": True, "completion_tokens": 80},
    {"prompt_tokens": 100.0, "completion_tokens": 80},
    {"prompt_tokens": -1, "completion_tokens": 80},
    {"prompt_tokens": "100.0", "completion_tokens": "80"},
    {"prompt_tokens": "1e2", "completion_tokens": "80"},
    {"prompt_tokens": 100, "completion_tokens": 80, "input_tokens": 101, "output_tokens": 80},
    {"prompt_tokens": 100, "completion_tokens": 80, "input_tokens": False, "output_tokens": 80},
])
def test_public_unpriced_valid_result_survives_unknown_usage(settings, monkeypatch, usage):
    requests = mock_http(monkeypatch, envelope(usage))
    result = execute(settings)
    assert result.status == "completed"
    assert result.entries[0]["sourceIds"] == [messages()[0]["id"]]
    assert result.usage_known is False
    assert (result.input_tokens, result.output_tokens, result.cost_micros) == (0, 0, 0)
    assert "[usage_unknown]" in result.message
    assert len(requests) == 1


def test_known_zero_usage_remains_known(settings, monkeypatch):
    mock_http(monkeypatch, envelope({"prompt_tokens": "000", "completion_tokens": 0}))
    result = execute(settings)
    assert result.status == "completed" and result.usage_known is True
    assert result.input_tokens == result.output_tokens == 0


@pytest.mark.parametrize("access_mode,priced", [("invitation", True), ("public", True), ("invitation", False)])
def test_unknown_usage_outside_public_unpriced_mode_stays_uncertain(settings, monkeypatch, access_mode, priced):
    requests = mock_http(monkeypatch, envelope(include_usage=False))
    if priced:
        settings = replace(settings, input_price_per_million=Decimal("2"), output_price_per_million=Decimal("8"))
    result = execute(settings, access_mode=access_mode)
    assert result.status == "uncertain"
    assert result.entries == []
    assert result.usage_known is False
    assert "[usage]" in result.message
    assert len(requests) == 1


@pytest.mark.parametrize("case", ["partial", "conflict", "oversized_string", "oversized_integer"])
def test_any_valid_overbound_usage_still_pauses_calls(settings, monkeypatch, case):
    snapshot, _ = snapshot_for(settings, messages())
    if case == "partial":
        usage = {"input_tokens": str(snapshot["input_bound"] + 1)}
    elif case == "conflict":
        usage = {"prompt_tokens": 10, "completion_tokens": 10,
                 "input_tokens": snapshot["input_bound"] + 1, "output_tokens": 10}
    elif case == "oversized_string":
        usage = {"input_tokens": "9" * 100, "output_tokens": "10"}
    else:
        usage = {"input_tokens": 10**30, "output_tokens": 10}
    requests = mock_http(monkeypatch, envelope(usage))
    result = execute(settings, snapshot=snapshot)
    assert result.status == "uncertain" and result.budget_violation is True
    assert result.entries == []
    assert "[usage_bound]" in result.message
    assert len(requests) == 1


@pytest.mark.parametrize("invalid", ["error", "choices", "finish", "json", "source", "missing_field"])
def test_missing_usage_never_bypasses_response_validation(settings, monkeypatch, invalid):
    response = envelope(include_usage=False)
    if invalid == "error":
        response["error"] = {"message": PRIVATE + settings.api_key}
    elif invalid == "choices":
        response["choices"] = []
    elif invalid == "finish":
        response["choices"][0]["finish_reason"] = "length"
    elif invalid == "json":
        response["choices"][0]["message"]["content"] = PRIVATE
    elif invalid == "source":
        response["choices"][0]["message"]["content"] = json.dumps({"entries": [
            {"category": "RECENT", "text": PRIVATE, "sourceIds": ["m999"], "kind": "USER_FACT"}]})
    else:
        response["choices"][0]["message"]["content"] = '{"entries":[{"text":"synthetic"}]}'
    requests = mock_http(monkeypatch, response)
    result = execute(settings)
    assert result.status == "uncertain"
    assert result.entries == [] and result.usage_known is False
    assert PRIVATE not in result.message and settings.api_key not in result.message
    assert "diagnostic=" in result.message
    assert len(requests) == 1


def test_diagnostics_emit_only_safe_field_names_types_and_counts(settings, monkeypatch):
    response = envelope({"prompt_tokens": True, "completion_tokens": "80", PRIVATE: settings.api_key})
    response["choices"][0]["finish_reason"] = PRIVATE
    requests = mock_http(monkeypatch, response)
    result = execute(settings)
    diagnostic = json.loads(result.message.split("diagnostic=", 1)[1])
    assert diagnostic["usage_fields"] == {"completion_tokens": "string", "prompt_tokens": "bool"}
    assert diagnostic["usage_other_fields"] == 1
    assert diagnostic["choices_count"] == 1 and diagnostic["finish_reason"] == "other"
    assert diagnostic["has_error"] is False
    assert diagnostic["stage"] == "response_validation"
    assert type(diagnostic["elapsed_ms"]) is int and 0 <= diagnostic["elapsed_ms"] <= 1_000_000_000
    assert PRIVATE not in result.message and settings.api_key not in result.message
    assert len(requests) == 1


@pytest.mark.parametrize("kind", ["transport", "http", "envelope_json"])
def test_outer_failures_have_safe_diagnostics_and_no_retry(settings, monkeypatch, kind):
    if kind == "transport":
        requests = mock_http(monkeypatch, None, transport_error=httpx.ReadError(PRIVATE + settings.api_key))
    elif kind == "http":
        requests = mock_http(monkeypatch, {"error": PRIVATE}, status=503)
    else:
        requests = mock_http(monkeypatch, None, raw=PRIVATE.encode())
    result = execute(settings)
    assert result.status == "uncertain" and not result.usage_known
    assert "diagnostic=" in result.message
    diagnostic = json.loads(result.message.split("diagnostic=", 1)[1])
    assert diagnostic["stage"] == ("envelope_json" if kind == "envelope_json" else "transport")
    assert type(diagnostic["elapsed_ms"]) is int and 0 <= diagnostic["elapsed_ms"] <= 1_000_000_000
    assert PRIVATE not in result.message and settings.api_key not in result.message
    assert len(requests) == 1


def test_gateway_502_remains_uncertain_counted_once_without_reading_error_body(settings, monkeypatch):
    calls = []
    real_client = httpx.AsyncClient

    class UnreadErrorBody(httpx.AsyncByteStream):
        async def __aiter__(self):
            raise AssertionError("error response body must not be read")
            yield b"unreachable"

    def handler(request):
        calls.append(request)
        return httpx.Response(502, stream=UnreadErrorBody())

    def client(**kwargs):
        return real_client(transport=httpx.MockTransport(handler), **kwargs)

    monkeypatch.setattr("app.provider.httpx.AsyncClient", client)
    app = create_app(settings)
    with TestClient(app) as client:
        token = registered_session(client)["token"]
        headers = {"Authorization": "Bearer " + token}
        body = {"idempotency_key": "upstream-502-once", "messages": messages()}
        job = client.post("/v1/jobs", headers=headers, json=body).json()
        claimed = app.state.store.claim_next()
        result = asyncio.run(organize(claimed["execution_settings"], json.loads(claimed["provider"]), messages()))
        app.state.store.finish(job["id"], result)
        response = client.get(f"/v1/jobs/{job['id']}", headers=headers).json()
        assert response["status"] == "uncertain" and response["request_counted"] is True
        assert "HTTP 502" in response["message"] and "上游网关异常" in response["message"]
        diagnostic = json.loads(response["message"].split(" diagnostic=", 1)[1])
        assert diagnostic["body_read_complete"] is False and diagnostic["response_bytes"] == 0
        quota = client.get("/v1/quota", headers=headers).json()
        assert quota["daily_used"] == quota["user_daily_used"] == 1
        assert client.post("/v1/jobs", headers=headers, json=body).json()["id"] == job["id"]
    assert len(calls) == 1


def test_public_result_unknown_usage_counts_once_and_stats_exclude_tokens(settings, monkeypatch):
    requests = mock_http(monkeypatch, envelope(include_usage=False))
    app = create_app(settings)
    with TestClient(app) as client:
        token = registered_session(client)["token"]
        headers = {"Authorization": "Bearer " + token}
        body = {"idempotency_key": "usage-public-0001", "messages": messages()}
        job = client.post("/v1/jobs", headers=headers, json=body).json()
        claimed = app.state.store.claim_next()
        result = asyncio.run(organize(claimed["execution_settings"], json.loads(claimed["provider"]), messages()))
        app.state.store.finish(job["id"], result)
        response = client.get(f"/v1/jobs/{job['id']}", headers=headers).json()
        assert response["status"] == "completed" and response["entries"]
        assert response["usage_known"] is False and response["cost_known"] is False
        assert response["request_counted"] is True
        assert client.post("/v1/jobs", headers=headers, json=body).json()["id"] == job["id"]
        quota = client.get("/v1/quota", headers=headers).json()
        assert quota["daily_used"] == quota["user_daily_used"] == 1
        stats = client.get("/v1/admin/stats", headers={"Authorization": "Bearer " + settings.admin_token}).json()
        assert stats["totals"]["usage_unknown_requests"] == 1
        assert stats["totals"]["usage_known_requests"] == 0
        assert stats["totals"]["input_tokens"] == stats["totals"]["output_tokens"] == 0
    assert len(requests) == 1


def test_legacy_unknown_usage_keeps_money_reservation_until_reconciled(settings, monkeypatch):
    requests = mock_http(monkeypatch, envelope(include_usage=False))
    priced = replace(settings, public_enabled=False, input_price_per_million=Decimal("2"),
                     output_price_per_million=Decimal("8"), global_budget_cny=Decimal("10"),
                     monthly_budget_cny=Decimal("10"), user_budget_cny=Decimal("1"), per_job_limit_cny=Decimal("1"))
    store = Store(priced)
    store.initialize()
    token, _ = store.redeem(store.create_invitation())
    owner = store.authenticate(token)
    request = JobRequest.model_validate({"idempotency_key": "usage-legacy-0001", "messages": messages()})
    job = store.create_job(owner, request)
    claimed = store.claim_next()
    outcome = asyncio.run(organize(claimed["execution_settings"], json.loads(claimed["provider"]), messages()))
    store.finish(job["id"], outcome)
    saved = store.get_job(owner, job["id"])
    assert saved["status"] == "uncertain" and saved["usage_known"] is False
    assert saved["reserved_cny"] == job["reserved_cny"] > 0
    assert saved["cost_known"] is False
    assert store.quota(owner)["daily_used"] == 1
    store.reconcile(job["id"], 0)
    reconciled = store.get_job(owner, job["id"])
    assert reconciled["reserved_cny"] == 0 and reconciled["cost_known"] is True
    assert reconciled["usage_known"] is False
    assert len(requests) == 1


def test_usage_migration_is_conservative_and_only_runs_once(settings):
    store = Store(settings)
    store.initialize()
    owner, token = register_owner(store)
    jobs = []
    for index in range(2):
        request = JobRequest.model_validate({"idempotency_key": f"migration-usage-{index}", "messages": messages()})
        job = registered_job(store, owner, token, request)
        store.claim_next()
        store.finish(job["id"], ProviderOutcome("completed", "synthetic", [], index * 10, 0, usage_known=True))
        jobs.append(job["id"])
    with store.connection(write=True) as db:
        db.execute("ALTER TABLE jobs DROP COLUMN usage_known")
    store.initialize()
    assert store.get_job(owner, jobs[0])["usage_known"] is False
    assert store.get_job(owner, jobs[1])["usage_known"] is True
    with store.connection(write=True) as db:
        db.execute("UPDATE jobs SET usage_known=0 WHERE id=?", (jobs[1],))
    store.initialize()
    assert store.get_job(owner, jobs[1])["usage_known"] is False


def test_old_queued_cost_flag_is_cleared_at_dispatch(settings):
    store = Store(settings)
    store.initialize()
    owner, token = register_owner(store)
    request = JobRequest.model_validate({"idempotency_key": "old-queued-flags-0001", "messages": messages()})
    job = registered_job(store, owner, token, request)
    with store.connection(write=True) as db:
        db.execute("UPDATE jobs SET cost_known=1,usage_known=1 WHERE id=?", (job["id"],))
    claimed = store.claim_next()
    assert claimed["id"] == job["id"]
    assert claimed["cost_known"] == claimed["usage_known"] == 0
    current = store.get_job(owner, job["id"])
    assert current["status"] == "running"
    assert current["cost_known"] is False and current["usage_known"] is False
