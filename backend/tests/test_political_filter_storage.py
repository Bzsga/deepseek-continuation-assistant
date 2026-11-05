"""Optional fragment screening shares existing reservations and expiring job storage."""
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
from app.runner import source_payload
from app.schemas import JobRequest
from app.store import digest


SOURCES = [{"id": "quoted-fragment-1", "role": "user", "content": "合成片段：关于某项现实公共政策的争议。"}]


@pytest.fixture
def settings(tmp_path):
    return Settings(db_path=str(tmp_path / "filter-jobs.sqlite3"), mock_mode=True,
                    public_enabled=True, worker_enabled=False, context_tokens=64000,
                    max_output_tokens=2048, input_price_per_million=Decimal(2),
                    output_price_per_million=Decimal(8), cleanup_interval_seconds=1)


def payload(key="political-filter-job-0001", operation="political_filter"):
    return {"idempotency_key": key, "messages": SOURCES, "operation": operation}


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
    pytest.fail("synthetic screening job did not finish")


@pytest.mark.parametrize("change", [
    {"context_messages": SOURCES},
    {"messages": SOURCES * 13},
    {"messages": [{**SOURCES[0], "content": "字" * 6001}]},
    {"messages": [{**SOURCES[0], "role": "assistant"}]},
    {"messages": [{**SOURCES[0], "role": "system"}]},
    {"operation": "unrecognized_operation"},
])
def test_filter_rejects_out_of_scope_requests(change):
    with pytest.raises(ValidationError):
        JobRequest.model_validate({**payload(), **change})


def test_filter_bounds_do_not_restrict_ordinary_organization():
    messages = [{"id": str(index), "role": "assistant", "content": "字" * 500} for index in range(13)]
    legacy = {"idempotency_key": "legacy-task-0001", "messages": messages}
    assert JobRequest.model_validate(legacy).operation == "organize"
    assert len(JobRequest.model_validate({**legacy, "operation": "organize"}).messages) == 13


def test_restore_rejects_mismatched_payload_operation():
    screening = json.dumps({"operation": "political_filter", "messages": SOURCES})
    with pytest.raises(ValueError):
        source_payload(screening)
    with pytest.raises(ValueError):
        source_payload(json.dumps(SOURCES), operation="political_filter")
    assert source_payload(screening, operation="political_filter") == (SOURCES, [])


def test_default_operation_keeps_legacy_bytes_and_explicit_operation_identity(settings):
    app = create_app(settings)
    with TestClient(app) as client:
        headers = session(client)
        legacy = payload(operation="organize")
        legacy.pop("operation")
        response = client.post("/v1/jobs", headers=headers, json=legacy)
        assert response.status_code == 200
        first = response.json()
        repeated = client.post("/v1/jobs", headers=headers, json=payload(operation="organize")).json()
        assert first["id"] == repeated["id"]
        assert client.post("/v1/jobs", headers=headers, json=payload()).status_code == 409
        expected = json.dumps(SOURCES, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        with app.state.store.connection() as db:
            row = db.execute("SELECT payload,fingerprint,provider FROM jobs WHERE id=?", (first["id"],)).fetchone()
        assert (row["payload"], row["fingerprint"]) == (expected, digest(expected))
        assert "operation" not in json.loads(row["provider"])


def test_screening_budget_identity_and_one_reservation(settings):
    app = create_app(settings)
    with TestClient(app) as client:
        headers = session(client)
        quota = client.get("/v1/quota", headers=headers).json()
        assert quota["political_filter_supported"] is True
        first = client.post("/v1/jobs", headers=headers, json=payload())
        assert first.status_code == 200
        job = first.json()
        repeated = client.post("/v1/jobs", headers=headers, json={**payload(), "context_messages": []}).json()
        assert repeated["id"] == job["id"]
        assert client.post("/v1/jobs", headers=headers, json=payload(operation="organize")).status_code == 409
        expected, reservation = snapshot_for(settings, SOURCES, operation="political_filter")
        with app.state.store.connection() as db:
            row = db.execute("SELECT payload,provider,reserved FROM jobs WHERE id=?", (job["id"],)).fetchone()
        assert json.loads(row["payload"]) == {"operation": "political_filter", "messages": SOURCES}
        snapshot = json.loads(row["provider"])
        assert snapshot["operation"] == "political_filter" and snapshot["protocol_version"] == 1
        assert snapshot["input_bound"] == expected["input_bound"] and row["reserved"] == reservation
        assert SOURCES[0]["content"] not in row["provider"]
        quota = client.get("/v1/quota", headers=headers).json()
        assert quota["daily_reserved"] == quota["user_daily_reserved"] == 1
        assert quota["daily_used"] == 0


def test_queued_filter_recovers_operation_once_and_expires_sources(settings, monkeypatch):
    app = create_app(settings)
    with TestClient(app) as client:
        headers = session(client)
        response = client.post("/v1/jobs", headers=headers, json=payload())
        assert response.status_code == 200
        job = response.json()
    calls = []

    async def provider(call_settings, snapshot, messages, *, context_messages=(), trace=None):
        calls.append((snapshot["operation"], snapshot["protocol_version"], messages, list(context_messages)))
        return ProviderOutcome("completed", "done", [])

    monkeypatch.setattr("app.runner.organize", provider)
    resumed = create_app(replace(settings, worker_enabled=True))
    with TestClient(resumed) as client:
        result = wait_job(client, headers, job["id"])
        assert result["status"] == "completed"
        assert calls == [("political_filter", 1, SOURCES, [])]
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
        assert SOURCES[0]["content"] not in row["provider"]


def test_filter_failure_artifact_replays_screening_instead_of_archive_normalizer(settings, monkeypatch):
    body = {"entries": [{"category": "QUOTES", "kind": "UNCONFIRMED", "sourceIds": ["m1"],
                         "text": "涉及现实政治争议"}]}
    envelope = {"choices": [{"finish_reason": "length", "message": {"content": json.dumps(body)}}],
                "usage": {"prompt_tokens": 100, "completion_tokens": 50}}
    requests = []
    real_client = httpx.AsyncClient

    def handler(request):
        requests.append(json.loads(request.content))
        return httpx.Response(200, json=envelope)

    monkeypatch.setattr("app.provider.httpx.AsyncClient",
                        lambda **kwargs: real_client(transport=httpx.MockTransport(handler), **kwargs))
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
        assert diagnostic["prompt_version"] == 1 and diagnostic["failure_capture_state"] == "saved"
        artifact = app.state.runner.artifacts.load(diagnostic["failure_capture_id"])
        assert artifact["snapshot"]["operation"] == "political_filter"
        assert artifact["messages"] == SOURCES and artifact["request"] == requests[0]
        assert replay_artifact(artifact)["error_code"] == "output_limit"
        # A structurally complete captured result exercises the operation-specific offline parser.
        envelope["choices"][0]["finish_reason"] = "stop"
        artifact["response"]["body_base64"] = base64.b64encode(json.dumps(envelope).encode()).decode()

        def forbidden_archive_parser(*args, **kwargs):
            pytest.fail("screening artifact was replayed as an archive")

        monkeypatch.setattr("app.provider.normalize_entries", forbidden_archive_parser)
        replay = replay_artifact(artifact)
        assert replay["ok"] and replay["entries_count"] == 1
        quota = client.get("/v1/quota", headers=headers).json()
        assert quota["daily_used"] == quota["user_daily_used"] == len(requests) == 1
        assert SOURCES[0]["content"] not in json.dumps(app.state.runner.artifacts.list_recent(), ensure_ascii=False)
        app.state.runner.artifacts.purge(artifact["expires_at"] + 1)
        with pytest.raises(ArtifactError):
            app.state.runner.artifacts.load(artifact["id"])
