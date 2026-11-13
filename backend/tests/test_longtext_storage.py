"""Reference context stays in the admitted job, count, expiry and replay boundary."""
import base64
import json
import time
from dataclasses import replace
from decimal import Decimal

import httpx
import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from app.config import Settings
from app.failure_artifacts import ArtifactError, replay_artifact
from app.main import create_app
from app.provider import ProviderOutcome, snapshot_for
from app.schemas import JobRequest
from app.store import digest


PRIMARY = [{"id": "current", "role": "user", "content": "我改主意了，小说里的阿遥最终没有离开。"}]
CONTEXT = [{"id": "previous", "role": "user", "content": "小说里的阿遥原本计划离开；不是我的经历。"}]


@pytest.fixture
def settings(tmp_path):
    return Settings(db_path=str(tmp_path / "reference-jobs.sqlite3"), mock_mode=True,
                    public_enabled=True, worker_enabled=False, context_tokens=64000,
                    max_output_tokens=2048, input_price_per_million=Decimal(2),
                    output_price_per_million=Decimal(8), cleanup_interval_seconds=1)


def payload(key="reference-job-0001", *, context=CONTEXT):
    return {"idempotency_key": key, "messages": PRIMARY, "context_messages": context}


def session(client):
    result = client.post("/v1/sessions").json()
    return {"Authorization": "Bearer " + result["token"]}


def wait_job(client, headers, job_id):
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        result = client.get(f"/v1/jobs/{job_id}", headers=headers).json()
        if result["status"] in {"completed", "failed", "uncertain", "cancelled"}:
            return result
        time.sleep(0.01)
    pytest.fail("synthetic reference job did not finish")


@pytest.mark.parametrize("change", [
    {"context_messages": [{"id": "ref", "role": "user", "content": "字" * 801}]},
    {"context_messages": [{"id": "current", "role": "assistant", "content": "角色冲突"}]},
    {"context_messages": [{"id": "ref", "role": "user", "content": "\ud800"}]},
    {"context_messages": [{"id": "ref", "role": "user", "content": " "}]},
    {"messages": [{"id": "current", "role": "user", "content": "字" * 40_000}]},
    {"messages": [{"id": "current", "role": "user", "content": "字"}] * 10_000},
])
def test_reference_and_primary_share_source_and_total_limits(change):
    with pytest.raises(ValidationError):
        JobRequest.model_validate({**payload(), **change})


def test_empty_context_keeps_legacy_fingerprint_and_request_identity(settings):
    app = create_app(settings)
    with TestClient(app) as client:
        headers = session(client)
        legacy = payload(context=[])
        legacy.pop("context_messages")
        first = client.post("/v1/jobs", headers=headers, json=legacy).json()
        repeated = client.post("/v1/jobs", headers=headers, json=payload(context=[])).json()
        assert first["id"] == repeated["id"]
        expected = json.dumps(PRIMARY, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        with app.state.store.connection() as db:
            row = db.execute("SELECT payload,fingerprint FROM jobs WHERE id=?", (first["id"],)).fetchone()
            assert tuple(row) == (expected, digest(expected))


def test_reference_context_is_reserved_and_persisted_without_extra_count(settings):
    app = create_app(settings)
    with TestClient(app) as client:
        headers = session(client)
        before = client.get("/v1/quota", headers=headers).json()
        assert before["organization_protocol_version"] == 10
        response = client.post("/v1/jobs", headers=headers, json=payload())
        assert response.status_code == 200
        job = response.json()
        duplicate = client.post("/v1/jobs", headers=headers, json=payload()).json()
        assert duplicate["id"] == job["id"]
        changed = [{**CONTEXT[0], "content": "另一个前段"}]
        assert client.post("/v1/jobs", headers=headers, json=payload(context=changed)).status_code == 409
        assert client.post("/v1/jobs", headers=headers, json=payload(context=[])).status_code == 409
        with app.state.store.connection() as db:
            row = db.execute("SELECT payload,provider,reserved FROM jobs WHERE id=?", (job["id"],)).fetchone()
        assert json.loads(row["payload"]) == {"messages": PRIMARY, "context_messages": CONTEXT}
        snapshot = json.loads(row["provider"])
        expected, reserve = snapshot_for(settings, PRIMARY, context_messages=CONTEXT)
        without_reference, _ = snapshot_for(settings, PRIMARY)
        assert snapshot["input_bound"] == expected["input_bound"] > without_reference["input_bound"]
        assert row["reserved"] == reserve
        assert CONTEXT[0]["content"] not in row["provider"]
        quota = client.get("/v1/quota", headers=headers).json()
        assert quota["daily_reserved"] == quota["user_daily_reserved"] == 1
        assert quota["daily_used"] == quota["user_daily_used"] == 0


def test_queued_reference_job_recovers_exact_input_once_and_expires_together(settings, monkeypatch):
    app = create_app(settings)
    with TestClient(app) as client:
        headers = session(client)
        response = client.post("/v1/jobs", headers=headers, json=payload())
        assert response.status_code == 200
        job = response.json()
    calls = []

    async def provider(call_settings, snapshot, messages, *, context_messages=(), trace=None):
        calls.append((messages, context_messages, snapshot["protocol_version"]))
        return ProviderOutcome("completed", "done", [])

    monkeypatch.setattr("app.runner.organize", provider)
    resumed = create_app(replace(settings, worker_enabled=True))
    with TestClient(resumed) as client:
        result = wait_job(client, headers, job["id"])
        assert result["status"] == "completed"
        assert calls == [(PRIMARY, CONTEXT, 10)]
        assert client.post("/v1/jobs", headers=headers, json=payload()).json()["id"] == job["id"]
        quota = client.get("/v1/quota", headers=headers).json()
        assert quota["daily_used"] == quota["user_daily_used"] == 1
        assert quota["daily_reserved"] == 0
        with resumed.state.store.connection(write=True) as db:
            db.execute("UPDATE jobs SET expires_at=? WHERE id=?", (time.time() - 1, job["id"]))
        resumed.state.store.purge_expired()
        with resumed.state.store.connection() as db:
            row = db.execute("SELECT payload,entries,provider FROM jobs WHERE id=?", (job["id"],)).fetchone()
        assert row["payload"] is None and row["entries"] is None
        assert PRIMARY[0]["content"] not in row["provider"] and CONTEXT[0]["content"] not in row["provider"]
        assert client.get(f"/v1/jobs/{job['id']}", headers=headers).status_code == 410


def test_failed_reference_job_capture_replays_joint_sources_and_context_only_filter(settings, monkeypatch):
    body = {"entries": [
        {"category": "RECENT", "text": "阿遥的结局已改为留下，原先曾计划离开。",
         "sourceIds": ["m1", "m2"], "kind": "USER_FACT", "continuityCue": "CORRECTION"},
        {"category": "RECENT", "text": "早先小说设定", "sourceIds": ["m2"], "kind": "USER_FACT"},
    ]}
    envelope = {"choices": [{"finish_reason": "length", "message": {"content": json.dumps(body)}}],
                "usage": {"prompt_tokens": 100, "completion_tokens": 50}}
    raw = json.dumps(envelope).encode()
    requests = []
    real_client = httpx.AsyncClient

    def handle(request):
        requests.append(json.loads(request.content))
        return httpx.Response(200, content=raw)

    monkeypatch.setattr("app.provider.httpx.AsyncClient",
                        lambda **kwargs: real_client(transport=httpx.MockTransport(handle), **kwargs))
    paid = replace(settings, mock_mode=False, worker_enabled=True, api_enabled=True,
                   api_key="synthetic-key", base_url="https://synthetic.invalid/v1")
    app = create_app(paid)
    with TestClient(app) as client:
        headers = session(client)
        response = client.post("/v1/jobs", headers=headers, json=payload())
        assert response.status_code == 200
        result = wait_job(client, headers, response.json()["id"])
        assert result["status"] == "failed"
        diagnostic = json.loads(result["message"].split(" diagnostic=", 1)[1])
        assert diagnostic["prompt_version"] == 10 and diagnostic["failure_capture_state"] == "saved"
        artifact = app.state.runner.artifacts.load(diagnostic["failure_capture_id"])
        assert artifact["messages"] == PRIMARY and artifact["context_messages"] == CONTEXT
        assert artifact["source_id_map"] == {"m1": "current", "m2": "previous"}
        assert artifact["request"] == requests[0]
        assert replay_artifact(artifact)["error_code"] == "output_limit"
        # Offline parser check with the captured source scope; no model resubmission.
        envelope["choices"][0]["finish_reason"] = "stop"
        artifact["response"]["body_base64"] = base64.b64encode(json.dumps(envelope).encode()).decode()
        replay = replay_artifact(artifact)
        assert replay["ok"] and replay["entries_count"] == 1
        quota = client.get("/v1/quota", headers=headers).json()
        assert quota["daily_used"] == quota["user_daily_used"] == len(requests) == 1
        listed = app.state.runner.artifacts.list_recent()
        assert listed[0]["source_count"] == 2 and listed[0]["context_source_count"] == 1
        assert CONTEXT[0]["content"] not in json.dumps(listed, ensure_ascii=False)
        app.state.runner.artifacts.purge(artifact["expires_at"] + 1)
        with pytest.raises(ArtifactError):
            app.state.runner.artifacts.load(artifact["id"])
