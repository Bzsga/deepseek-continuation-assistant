import asyncio
import json
import sqlite3
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
from decimal import Decimal

import httpx
import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app
from app.provider import ProviderOutcome, normalize_entries, organize, snapshot_for
from app.schemas import JobRequest
from app.store import Store


@pytest.fixture
def settings(tmp_path):
    return Settings(
        db_path=str(tmp_path / "test.sqlite3"), mock_mode=True, public_enabled=False,
        global_budget_cny=Decimal("1"), monthly_budget_cny=Decimal("1"),
        user_budget_cny=Decimal("1"), per_job_limit_cny=Decimal("0.1"),
        input_price_per_million=Decimal("2"), output_price_per_million=Decimal("8"),
        worker_enabled=False, cleanup_interval_seconds=1,
    )


def payload(key="test-key-0001", content="用户计划下周继续学习 Kotlin"):
    return {"idempotency_key": key, "messages": [{"id": "conversation:source-1", "role": "user", "content": content}]}


def auth(client, app):
    code = app.state.store.create_invitation()
    response = client.post("/v1/invitations/redeem", json={"code": code})
    assert response.status_code == 200
    return {"Authorization": "Bearer " + response.json()["token"]}


def wait_job(client, headers, job_id, statuses={"completed", "failed", "cancelled", "uncertain"}):
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        response = client.get(f"/v1/jobs/{job_id}", headers=headers)
        assert response.status_code == 200
        if response.json()["status"] in statuses:
            return response.json()
        time.sleep(0.02)
    pytest.fail("任务未在预期时间内结束")


def test_default_paid_service_disabled_and_credentials_hashed(settings):
    disabled = replace(settings, mock_mode=False)
    app = create_app(disabled)
    with TestClient(app) as client:
        code = app.state.store.create_invitation()
        response = client.post("/v1/invitations/redeem", json={"code": code})
        token = response.json()["token"]
        headers = {"Authorization": f"Bearer {token}"}
        assert response.json()["quota"]["enabled"] is False
        assert client.post("/v1/jobs", headers=headers, json=payload()).status_code == 503
        assert client.post("/v1/invitations/redeem", json={"code": code}).status_code == 400
        with app.state.store.connection() as db:
            assert db.execute("SELECT code_hash FROM invitations").fetchone()[0] != code
            assert db.execute("SELECT token_hash FROM users").fetchone()[0] != token
            assert db.execute("SELECT COUNT(*) FROM jobs").fetchone()[0] == 0
        assert token.encode() not in open(settings.db_path, "rb").read()
        assert code.encode() not in open(settings.db_path, "rb").read()


@pytest.mark.parametrize("field", ["global_budget_cny", "monthly_budget_cny", "user_budget_cny", "per_job_limit_cny", "input_price_per_million", "output_price_per_million"])
def test_every_budget_and_price_must_be_explicit(settings, field):
    assert not replace(settings, **{field: Decimal(0)}).enabled


def test_idempotency_owner_isolation_and_cancellation(settings):
    app = create_app(settings)
    with TestClient(app) as client:
        headers, other = auth(client, app), auth(client, app)
        first = client.post("/v1/jobs", headers=headers, json=payload()).json()
        duplicate = client.post("/v1/jobs", headers=headers, json=payload()).json()
        assert first["id"] == duplicate["id"]
        assert client.post("/v1/jobs", headers=headers, json=payload(content="改变内容")).status_code == 409
        assert client.get(f"/v1/jobs/{first['id']}", headers=other).status_code == 404
        assert client.post(f"/v1/jobs/{first['id']}/cancel", headers=other).status_code == 404
        assert client.get("/v1/quota").status_code == 401
        before = client.get("/v1/quota", headers=headers).json()["remaining_cny"]
        assert before < 1
        cancelled = client.post(f"/v1/jobs/{first['id']}/cancel", headers=headers).json()
        assert cancelled["status"] == "cancelled"
        assert cancelled["reserved_cny"] == 0
        assert client.get("/v1/quota", headers=headers).json()["remaining_cny"] == 1


def test_simultaneous_jobs_cannot_overreserve(settings):
    app = create_app(settings)
    with TestClient(app) as client:
        headers = auth(client, app)
        _, reservation = snapshot_for(settings, payload()["messages"])
        app.state.store.set_cap("global", 3 * reservation)
        def submit(index):
            return client.post("/v1/jobs", headers=headers, json=payload(f"parallel-{index:04d}"))
        with ThreadPoolExecutor(max_workers=12) as pool:
            responses = list(pool.map(submit, range(16)))
        assert sum(response.status_code == 200 for response in responses) == 3
        assert all(response.status_code in {200, 402} for response in responses)
        with app.state.store.connection() as db:
            assert db.execute("SELECT SUM(cost+reserved) FROM jobs").fetchone()[0] == 3 * reservation


def test_concurrent_duplicate_creates_only_one_reservation(settings):
    app = create_app(settings)
    with TestClient(app) as client:
        headers = auth(client, app)
        with ThreadPoolExecutor(max_workers=8) as pool:
            responses = list(pool.map(lambda _: client.post("/v1/jobs", headers=headers, json=payload()), range(12)))
        assert all(response.status_code == 200 for response in responses)
        assert len({response.json()["id"] for response in responses}) == 1
        with app.state.store.connection() as db:
            assert db.execute("SELECT COUNT(*) FROM jobs").fetchone()[0] == 1


def test_monthly_user_and_per_job_limits(settings):
    app = create_app(settings)
    with TestClient(app) as client:
        headers = auth(client, app)
        _, reserve = snapshot_for(settings, payload()["messages"])
        for cap in ("monthly", "user", "per_job"):
            app.state.store.set_cap(cap, reserve - 1)
            assert client.post("/v1/jobs", headers=headers, json=payload()).status_code == 402
            app.state.store.set_cap(cap, 1_000_000)


def test_running_restart_is_uncertain_queue_resumes(settings):
    app = create_app(settings)
    with TestClient(app) as client:
        headers = auth(client, app)
        job = client.post("/v1/jobs", headers=headers, json=payload()).json()
        app.state.store.claim_next()
        queued = client.post("/v1/jobs", headers=headers, json=payload("next-key-0002")).json()
    restarted = create_app(replace(settings, worker_enabled=True))
    with TestClient(restarted) as client:
        uncertain = client.get(f"/v1/jobs/{job['id']}", headers=headers).json()
        assert uncertain["status"] == "uncertain"
        assert uncertain["reserved_cny"] == job["reserved_cny"]
        completed = wait_job(client, headers, queued["id"])
        assert completed["status"] == "completed"
        assert completed["mock_mode"] is True
        assert completed["entries"][0]["text"].startswith("[模拟结果]")
        replay = client.post("/v1/jobs", headers=headers, json=payload()).json()
        assert replay["status"] == "uncertain"
        restarted.state.store.reconcile(job["id"], 1000)
        reconciled = client.get(f"/v1/jobs/{job['id']}", headers=headers).json()
        assert reconciled["cost_cny"] == 0.001
        assert reconciled["reserved_cny"] == 0
        assert reconciled["status"] == "failed"


def test_cancel_in_flight_accounts_response_without_saving_entries(settings, monkeypatch):
    entered, release = threading.Event(), threading.Event()
    calls = []
    async def provider(*args, trace=None):
        calls.append(1)
        entered.set()
        while not release.is_set():
            await asyncio.sleep(0.01)
        return ProviderOutcome("completed", "done", [{"text": "不应保留"}], 10, 10, 100, usage_known=True)
    monkeypatch.setattr("app.runner.organize", provider)
    app = create_app(replace(settings, worker_enabled=True))
    with TestClient(app) as client:
        headers = auth(client, app)
        job = client.post("/v1/jobs", headers=headers, json=payload()).json()
        assert entered.wait(3)
        pending = client.post(f"/v1/jobs/{job['id']}/cancel", headers=headers).json()
        assert pending["cancel_requested"] is True
        assert pending["reserved_cny"] > 0
        release.set()
        result = wait_job(client, headers, job["id"])
        assert result["status"] == "cancelled"
        assert result["entries"] == []
        assert result["cost_cny"] == 0.0001
        assert result["reserved_cny"] == 0
        assert len(calls) == 1


def test_shutdown_during_call_preserves_uncertain_reservation(settings, monkeypatch):
    entered = threading.Event()
    async def provider(*args, trace=None):
        entered.set()
        await asyncio.sleep(60)
    monkeypatch.setattr("app.runner.organize", provider)
    app = create_app(replace(settings, worker_enabled=True))
    with TestClient(app) as client:
        headers = auth(client, app)
        job = client.post("/v1/jobs", headers=headers, json=payload()).json()
        assert entered.wait(3)
    with app.state.store.connection() as db:
        row = db.execute("SELECT * FROM jobs WHERE id=?", (job["id"],)).fetchone()
        assert row["status"] == "uncertain"
        assert row["reserved"] > 0


def test_ttl_purges_without_a_read_and_keeps_accounting(settings):
    app = create_app(settings)
    with TestClient(app) as client:
        headers = auth(client, app)
        job = client.post("/v1/jobs", headers=headers, json=payload()).json()
        app.state.store.claim_next()
        app.state.store.finish(job["id"], ProviderOutcome("uncertain", "pending", []))
        with app.state.store.connection(write=True) as db:
            db.execute("UPDATE jobs SET entries=?,expires_at=? WHERE id=?", ('[{"text":"private result"}]', time.time() - 1, job["id"]))
        time.sleep(1.3)
        with app.state.store.connection() as db:
            row = db.execute("SELECT * FROM jobs WHERE id=?", (job["id"],)).fetchone()
            assert row["payload"] is None
            assert row["entries"] is None
            assert row["purged_at"] is not None
            assert row["reserved"] > 0
        result = client.get(f"/v1/jobs/{job['id']}", headers=headers)
        assert result.status_code == 410
        assert "保留期" in result.json()["detail"]
        assert client.post("/v1/jobs", headers=headers, json=payload()).status_code == 410
        app.state.store.reconcile(job["id"], 0)
        with app.state.store.connection() as db:
            assert db.execute("SELECT payload FROM jobs WHERE id=?", (job["id"],)).fetchone()[0] is None


def test_bad_and_oversize_requests_create_no_job(settings):
    app = create_app(settings)
    with TestClient(app) as client:
        headers = auth(client, app)
        assert client.post("/v1/jobs", headers=headers, json=payload(content="a" * 40001)).status_code == 422
        assert client.post("/v1/jobs", headers=headers, json=payload(content="   ")).status_code == 422
        invalid_unicode = json.dumps(payload(content="\ud800"))
        assert client.post("/v1/jobs", headers={**headers, "Content-Type": "application/json"}, content=invalid_unicode).status_code == 422
        assert client.post("/v1/jobs", headers=headers, content=b"x" * 65537).status_code == 413
        response = client.post("/v1/jobs", headers=headers, content='{"secret": "private transcript"}')
        assert response.status_code == 422
        assert "private transcript" not in response.text
        with app.state.store.connection() as db:
            assert db.execute("SELECT COUNT(*) FROM jobs").fetchone()[0] == 0


def test_single_worker_database_lease(settings):
    first, second = create_app(settings), create_app(settings)
    with TestClient(first):
        with pytest.raises(RuntimeError, match="单个"):
            with TestClient(second):
                pass


def test_https_and_money_settings_validate(settings):
    with pytest.raises(ValueError):
        replace(settings, mock_mode=False, api_enabled=True, base_url="http://example.com").validate()
    with pytest.raises(ValueError):
        replace(settings, global_budget_cny=Decimal("NaN")).validate()


def fake_provider(monkeypatch, envelope, status_code=200):
    calls = []
    class Response:
        headers = {}
        async def __aenter__(self):
            return self
        async def __aexit__(self, *args):
            return None
        async def aiter_bytes(self):
            yield json.dumps(envelope).encode()
    response = Response()
    response.status_code = status_code
    class Client:
        def __init__(self, **kwargs):
            assert kwargs["follow_redirects"] is False
            assert kwargs["trust_env"] is False
        async def __aenter__(self):
            return self
        async def __aexit__(self, *args):
            return None
        def stream(self, *args, **kwargs):
            calls.append(kwargs)
            return response
    monkeypatch.setattr("app.provider.httpx.AsyncClient", Client)
    return calls


def test_malformed_model_output_is_billed_once_without_retry(settings, monkeypatch):
    calls = fake_provider(monkeypatch, {"usage": {"prompt_tokens": 100, "completion_tokens": 20},
        "choices": [{"finish_reason": "stop", "message": {"content": "invalid json", "reasoning_content": "private thinking"}}]})
    paid = replace(settings, mock_mode=False, api_enabled=True, api_key="server-only")
    snapshot, _ = snapshot_for(paid, payload()["messages"])
    outcome = asyncio.run(organize(paid, snapshot, payload()["messages"]))
    assert outcome.status == "failed"
    assert outcome.cost_micros == 360
    assert len(calls) == 1
    assert calls[0]["headers"]["Authorization"] == "Bearer server-only"
    assert calls[0]["json"]["thinking"] == {"type": "disabled"}
    wire = json.loads(calls[0]["json"]["messages"][1]["content"])["source_messages"]
    assert wire[0]["id"] == "m1"
    assert wire[0]["content"] == payload()["messages"][0]["content"]
    assert outcome.entries == []


def test_non_deepseek_request_omits_thinking(settings, monkeypatch):
    calls = fake_provider(monkeypatch, {"usage": {"prompt_tokens": 100, "completion_tokens": 20},
        "choices": [{"finish_reason": "stop", "message": {"content": '{"entries":[]}'}}]})
    paid = replace(settings, mock_mode=False, api_enabled=True, api_key="server-only", model="another-model")
    snapshot, _ = snapshot_for(paid, payload()["messages"])
    outcome = asyncio.run(organize(paid, snapshot, payload()["messages"]))
    assert outcome.status == "completed"
    assert "thinking" not in calls[0]["json"]


def test_missing_usage_holds_reservation_without_retry(settings, monkeypatch):
    calls = fake_provider(monkeypatch, {"choices": []})
    paid = replace(settings, mock_mode=False, api_enabled=True, api_key="server-only")
    snapshot, _ = snapshot_for(paid, payload()["messages"])
    outcome = asyncio.run(organize(paid, snapshot, payload()["messages"]))
    assert outcome.status == "uncertain"
    assert len(calls) == 1


def test_unknown_source_rejected_and_role_conflict_downgraded():
    entry = {"category": "RECENT", "text": "a fact", "sourceIds": ["unknown"], "kind": "USER_FACT"}
    with pytest.raises(ValueError):
        normalize_entries(json.dumps({"entries": [entry]}), payload()["messages"])
    entry["sourceIds"] = ["m1"]
    messages = payload()["messages"]
    messages[0]["role"] = "assistant"
    downgraded = normalize_entries(json.dumps({"entries": [entry]}), messages)
    assert downgraded[0]["kind"] == "UNCONFIRMED"
    assert downgraded[0]["sourceIds"] == ["conversation:source-1"]
    entry["kind"] = "ASSISTANT_SUGGESTION"
    result = normalize_entries(json.dumps({"entries": [entry]}), messages)
    assert result[0]["sourceIds"] == ["conversation:source-1"]
    assert result[0]["kind"] == "ASSISTANT_SUGGESTION"


def test_previous_month_reservation_counts_against_current_month(settings):
    app = create_app(settings)
    with TestClient(app) as client:
        headers = auth(client, app)
        job = client.post("/v1/jobs", headers=headers, json=payload()).json()
        app.state.store.set_cap("monthly", round(job["reserved_cny"] * 1_000_000))
        with app.state.store.connection(write=True) as db:
            db.execute("UPDATE jobs SET month='2000-01' WHERE id=?", (job["id"],))
        assert client.post("/v1/jobs", headers=headers, json=payload("next-key-0002")).status_code == 402


def test_provider_limit_violation_halts_subsequent_calls(settings, monkeypatch):
    paid = replace(settings, mock_mode=False, api_enabled=True, api_key="server-only")
    snapshot, _ = snapshot_for(paid, payload()["messages"])
    fake_provider(monkeypatch, {"usage": {"prompt_tokens": snapshot["input_bound"] + 1000, "completion_tokens": snapshot["output_bound"]}})
    outcome = asyncio.run(organize(paid, snapshot, payload()["messages"]))
    assert outcome.budget_violation
    assert outcome.status == "uncertain"
    app = create_app(settings)
    with TestClient(app) as client:
        headers = auth(client, app)
        job = client.post("/v1/jobs", headers=headers, json=payload()).json()
        app.state.store.claim_next()
        app.state.store.finish(job["id"], outcome)
        current = client.get(f"/v1/jobs/{job['id']}", headers=headers).json()
        assert current["reserved_cny"] > job["reserved_cny"]
        assert not client.get("/v1/quota", headers=headers).json()["enabled"]
        assert client.post("/v1/jobs", headers=headers, json=payload("new-job-0002")).status_code == 503


@pytest.mark.parametrize("change", [{"mock_mode": True}, {"input_price_per_million": Decimal("4")}, {"output_price_per_million": Decimal("16")}, {"max_output_tokens": 256}])
def test_changed_configuration_never_dispatches_stale_paid_job(settings, monkeypatch, change):
    paid = replace(settings, mock_mode=False, api_enabled=True, api_key="server-only")
    app = create_app(paid)
    with TestClient(app) as client:
        headers = auth(client, app)
        job = client.post("/v1/jobs", headers=headers, json=payload()).json()
    async def forbidden_provider(*args, trace=None):
        pytest.fail("stale configuration caused a provider call")
    monkeypatch.setattr("app.runner.organize", forbidden_provider)
    restarted = create_app(replace(paid, worker_enabled=True, **change))
    with TestClient(restarted) as client:
        result = wait_job(client, headers, job["id"])
        assert result["status"] == "failed"
        assert result["reserved_cny"] == 0
        assert result["cost_cny"] == 0


def test_provider_total_deadline_holds_reservation(settings, monkeypatch):
    calls = []
    async def endless_provider(*args, trace=None):
        calls.append(1)
        while True:
            await asyncio.sleep(0.01)
    monkeypatch.setattr("app.runner.organize", endless_provider)
    app = create_app(replace(settings, worker_enabled=True, provider_timeout_seconds=1))
    with TestClient(app) as client:
        headers = auth(client, app)
        job = client.post("/v1/jobs", headers=headers, json=payload()).json()
        result = wait_job(client, headers, job["id"])
        assert result["status"] == "uncertain"
        assert result["reserved_cny"] == job["reserved_cny"]
        assert len(calls) == 1


def test_existing_sqlite_users_and_usage_migrate_without_reset(settings):
    token = "existing-synthetic-session-token"
    from app.store import digest, local_day
    with sqlite3.connect(settings.db_path) as db:
        db.executescript("""
            CREATE TABLE users(id TEXT PRIMARY KEY,token_hash TEXT NOT NULL UNIQUE,
                budget_limit INTEGER NOT NULL,created_at REAL NOT NULL);
            CREATE TABLE jobs(id TEXT PRIMARY KEY,owner_id TEXT NOT NULL,idempotency_key TEXT NOT NULL,
                fingerprint TEXT NOT NULL,status TEXT NOT NULL,message TEXT NOT NULL,payload TEXT,entries TEXT,
                provider TEXT NOT NULL,reserved INTEGER NOT NULL DEFAULT 0,cost INTEGER NOT NULL DEFAULT 0,
                input_tokens INTEGER NOT NULL DEFAULT 0,output_tokens INTEGER NOT NULL DEFAULT 0,
                created_at REAL NOT NULL,month TEXT NOT NULL,terminal_at REAL,expires_at REAL,purged_at REAL,
                cancel_requested INTEGER NOT NULL DEFAULT 0,UNIQUE(owner_id,idempotency_key));
        """)
        db.execute("INSERT INTO users VALUES(?,?,?,?)", ("older-user", digest(token), 765432, time.time()))
        db.execute("""INSERT INTO jobs(id,owner_id,idempotency_key,fingerprint,status,message,provider,
            cost,input_tokens,output_tokens,created_at,month) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)""",
                   ("older-job", "older-user", "older-key", "digest", "completed", "done", '{"mock_mode":true}',
                    123, 50, 20, time.time(), "2026-11"))
    store = Store(replace(settings, public_enabled=True))
    store.initialize()
    store.initialize()
    assert store.authenticate(token) == "older-user"
    quota = store.quota("older-user")
    assert quota["access_mode"] == "public"
    assert quota["daily_used"] == 1
    with store.connection() as db:
        assert db.execute("SELECT budget_limit FROM users WHERE id='older-user'").fetchone()[0] == 765432
        row = db.execute("SELECT count_state,count_day,cost FROM jobs WHERE id='older-job'").fetchone()
        assert tuple(row) == ("used", local_day(), 123)


def test_inflight_keeps_original_runtime_settings(settings, monkeypatch):
    entered, release = threading.Event(), threading.Event()
    observed = []
    async def provider(call_settings, snapshot, messages, *, trace=None):
        observed.append((call_settings.model, call_settings.api_key, snapshot["model"]))
        if len(observed) == 1:
            entered.set()
            while not release.is_set():
                await asyncio.sleep(0.01)
        return ProviderOutcome("completed", "done", [])
    monkeypatch.setattr("app.runner.organize", provider)
    app = create_app(replace(settings, public_enabled=True, worker_enabled=True, mock_mode=False,
                             api_enabled=True, api_key="old-synthetic-key"))
    with TestClient(app) as client:
        session = client.post("/v1/sessions").json()
        headers = {"Authorization": "Bearer " + session["token"]}
        first = client.post("/v1/jobs", headers=headers, json=payload()).json()
        assert entered.wait(3)
        app.state.store.update_config({"model": "replacement-model", "api_key": "new-synthetic-key"})
        release.set()
        assert wait_job(client, headers, first["id"])["status"] == "completed"
        second = client.post("/v1/jobs", headers=headers, json=payload("next-job-0002")).json()
        assert wait_job(client, headers, second["id"])["status"] == "completed"
        assert observed == [(settings.model, "old-synthetic-key", settings.model),
                            ("replacement-model", "new-synthetic-key", "replacement-model")]
        with app.state.store.connection() as db:
            snapshots = "".join(row[0] for row in db.execute("SELECT provider FROM jobs"))
            assert "old-synthetic-key" not in snapshots and "new-synthetic-key" not in snapshots
