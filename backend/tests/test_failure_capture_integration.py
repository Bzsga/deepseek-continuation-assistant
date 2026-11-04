import asyncio
import base64
import json
import sqlite3
import threading
import time
import uuid
from dataclasses import replace
from decimal import Decimal

import httpx
import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.failure_artifacts import ArtifactError
from app.main import create_app
from app.provider import ProviderOutcome, snapshot_for
from app.runner import Runner


PRIVATE = "SYNTHETIC_FAILURE_BODY_DO_NOT_EXPOSE"
PARTIAL = b'{"choices":[{"message":{"content":"partial synthetic'


@pytest.fixture
def settings(tmp_path):
    return Settings(
        db_path=str(tmp_path / "service.sqlite3"), api_enabled=True, api_key="synthetic-base-api-key",
        admin_token="synthetic-admin-token", admin_password_hash="synthetic-password-hash",
        feedback_worker_token="synthetic-independent-worker-token-0001",
        base_url="https://synthetic.invalid/v1", model="deepseek-v4.1-flash",
        context_tokens=64000, max_output_tokens=4096, provider_timeout_seconds=2,
        input_price_per_million=Decimal(2), output_price_per_million=Decimal(8),
        worker_enabled=True, cleanup_interval_seconds=1,
    )


def envelope(content):
    return {"usage": {"prompt_tokens": 100, "completion_tokens": 80},
            "choices": [{"finish_reason": "stop", "message": {"content": content}}]}


def install_http(monkeypatch, responder):
    requests, real_client = [], httpx.AsyncClient

    def handler(request):
        assert request.url == "https://synthetic.invalid/v1/chat/completions"
        requests.append(json.loads(request.content))
        return responder(request)

    def client(**kwargs):
        return real_client(transport=httpx.MockTransport(handler), **kwargs)

    monkeypatch.setattr("app.provider.httpx.AsyncClient", client)
    return requests


def submit(client, *, text="合成来源资料", key="failure-capture-case"):
    headers = {"Authorization": "Bearer " + client.post("/v1/sessions").json()["token"]}
    body = {"idempotency_key": key, "messages": [{"id": "original-source", "role": "user", "content": text}]}
    response = client.post("/v1/jobs", headers=headers, json=body)
    assert response.status_code == 200
    return headers, response.json()["id"]


def wait_job(client, headers, job_id):
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        result = client.get(f"/v1/jobs/{job_id}", headers=headers).json()
        if result["status"] in {"completed", "failed", "uncertain", "cancelled"}:
            return result
        time.sleep(0.01)
    pytest.fail("synthetic job did not finish")


def diagnostic(result):
    return json.loads(result["message"].split(" diagnostic=", 1)[1])


def assert_counted_once(client, headers, result, requests):
    quota = client.get("/v1/quota", headers=headers).json()
    assert quota["daily_used"] == quota["user_daily_used"] == 1
    assert result["request_counted"] is True and len(requests) == 1


def test_runner_failure_saves_exact_wire_and_body_but_exposes_only_metadata(settings, monkeypatch, caplog):
    raw = json.dumps(envelope(PRIVATE), ensure_ascii=False).encode("utf-8")
    requests = install_http(monkeypatch, lambda _: httpx.Response(200, content=raw))
    app = create_app(settings)
    with TestClient(app) as client:
        headers, job_id = submit(client, text="私有合成来源")
        result = wait_job(client, headers, job_id)
        info = diagnostic(result)
        assert result["status"] == "failed" and "[json]" in result["message"]
        assert result["cost_cny"] == 0.00084 and result["usage_known"] is True
        assert info["failure_capture_state"] == "saved"
        assert str(uuid.UUID(info["failure_capture_id"])) == info["failure_capture_id"]
        assert type(info["failure_capture_expires_at"]) is int
        artifact = app.state.runner.artifacts.load(info["failure_capture_id"])
        assert artifact["request"] == requests[0]
        assert artifact["source_id_map"] == {"m1": "original-source"}
        assert artifact["messages"][0]["content"] == "私有合成来源"
        assert base64.b64decode(artifact["response"]["body_base64"]) == raw
        assert artifact["response"]["http_status"] == 200
        assert artifact["response"]["body_read_complete"] and artifact["complete_original"]
        assert "headers" not in artifact["request"] and "api_key" not in artifact["snapshot"]
        assert PRIVATE not in result["message"] and "私有合成来源" not in result["message"]
        assert PRIVATE not in caplog.text and settings.api_key not in caplog.text
        assert_counted_once(client, headers, result, requests)


def test_runner_success_does_not_create_failure_artifact(settings, monkeypatch):
    content = json.dumps({"entries": [{"category": "RECENT", "text": "有效的合成摘要", "sourceIds": ["m1"], "kind": "USER_FACT"}]})
    requests = install_http(monkeypatch, lambda _: httpx.Response(200, json=envelope(content)))
    app = create_app(settings)
    with TestClient(app) as client:
        headers, job_id = submit(client)
        result = wait_job(client, headers, job_id)
        assert result["status"] == "completed" and "failure_capture" not in result["message"]
        assert app.state.runner.artifacts.list_recent() == []
        assert_counted_once(client, headers, result, requests)


@pytest.mark.parametrize("failure", ["save", "initialize"])
def test_capture_failure_does_not_replace_business_error_or_accounting(settings, monkeypatch, caplog, failure):
    requests = install_http(monkeypatch, lambda _: httpx.Response(200, json=envelope(PRIVATE)))

    def broken(*args, **kwargs):
        raise RuntimeError(PRIVATE)

    if failure == "initialize":
        monkeypatch.setattr("app.runner.ArtifactStore", broken)
    app = create_app(settings)
    with TestClient(app) as client:
        if failure == "save":
            monkeypatch.setattr(app.state.runner.artifacts, "save", broken)
        headers, job_id = submit(client)
        result = wait_job(client, headers, job_id)
        info = diagnostic(result)
        assert result["status"] == "failed" and "[json]" in result["message"]
        assert result["cost_cny"] == 0.00084 and result["usage_known"] is True
        assert info["failure_capture_state"] == "unavailable" and "failure_capture_id" not in info
        assert PRIVATE not in result["message"] and PRIVATE not in caplog.text
        assert_counted_once(client, headers, result, requests)
        assert client.get("/health").status_code == (503 if failure == "initialize" else 200)


def test_disabled_capture_is_visible_and_does_not_create_a_new_store(settings, monkeypatch):
    requests = install_http(monkeypatch, lambda _: httpx.Response(200, json=envelope(PRIVATE)))
    app = create_app(replace(settings, failure_artifacts_enabled=False))
    with TestClient(app) as client:
        headers, job_id = submit(client)
        result = wait_job(client, headers, job_id)
        assert diagnostic(result)["failure_capture_state"] == "disabled"
        assert app.state.runner.artifacts is None and not app.state.runner.artifact_path.exists()
        assert_counted_once(client, headers, result, requests)


def test_mock_failure_is_not_captured(settings, monkeypatch):
    async def fake_provider(*args, trace=None):
        return ProviderOutcome("failed", "synthetic mock failure", [])

    monkeypatch.setattr("app.runner.organize", fake_provider)
    app = create_app(replace(settings, mock_mode=True))
    with TestClient(app) as client:
        headers, job_id = submit(client)
        result = wait_job(client, headers, job_id)
        assert result["status"] == "failed" and "failure_capture" not in result["message"]
        assert app.state.runner.artifacts.list_recent() == []


@pytest.mark.parametrize("shutdown", [False, True])
def test_runner_timeout_and_shutdown_keep_partial_response(settings, monkeypatch, shutdown):
    entered = threading.Event()

    class SlowBody(httpx.AsyncByteStream):
        async def __aiter__(self):
            yield PARTIAL
            entered.set()
            await asyncio.sleep(60)

    requests = install_http(monkeypatch, lambda _: httpx.Response(200, stream=SlowBody()))
    app = create_app(replace(settings, provider_timeout_seconds=1))
    with TestClient(app) as client:
        headers, job_id = submit(client)
        assert entered.wait(3)
        if not shutdown:
            result = wait_job(client, headers, job_id)
            assert_counted_once(client, headers, result, requests)
    with app.state.store.connection() as database:
        row = dict(database.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone())
    assert row["status"] == "uncertain" and row["count_state"] == "used"
    assert row["cost"] == 0 and row["usage_known"] == 0 and row["reserved"] > 0
    info = diagnostic(row)
    assert info["failure_capture_state"] == "saved"
    artifact = app.state.runner.artifacts.load(info["failure_capture_id"])
    assert base64.b64decode(artifact["response"]["body_base64"]) == PARTIAL
    assert artifact["response"]["http_status"] == 200
    assert not artifact["response"]["body_read_complete"] and not artifact["complete_original"]
    assert info["network_stage"] == "reading_body" and info["response_headers_received"] is True
    assert info["exception_type"] == ("cancelled" if shutdown else "total_timeout")
    assert info["elapsed_ms"] >= 0 and info["body_read_complete"] is False
    assert artifact["technical"] == {key: info[key] for key in artifact["technical"]}
    if not shutdown:
        assert "[runner_timeout]" in row["message"]
    assert len(requests) == 1


@pytest.mark.parametrize("partial_body", [False, True], ids=["waiting_headers", "reading_body"])
def test_real_loopback_runner_timeout_preserves_network_stage_and_frozen_capture(settings, partial_body):
    async def scenario():
        loop = asyncio.get_running_loop()
        release, finished = asyncio.Event(), asyncio.Event()
        requests, connections, handlers = [], [], []

        async def serve(reader, writer):
            handlers.append(asyncio.current_task())
            connections.append(True)
            try:
                headers = await reader.readuntil(b"\r\n\r\n")
                content_length = next(int(line.split(b":", 1)[1]) for line in headers.split(b"\r\n")
                                      if line.lower().startswith(b"content-length:"))
                requests.append(json.loads(await reader.readexactly(content_length)))
                if partial_body:
                    writer.write(b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 9999\r\n\r\n" + PARTIAL)
                    await writer.drain()
                await release.wait()
            finally:
                writer.close()
                await writer.wait_closed()

        server = await asyncio.start_server(serve, "127.0.0.1", 0)
        port = server.sockets[0].getsockname()[1]
        execution_settings = replace(settings, provider_timeout_seconds=1)
        messages = [{"id": "loopback-source", "role": "user", "content": "仅本机合成测试资料"}]
        snapshot, _ = snapshot_for(execution_settings, messages)
        snapshot["base_url"] = f"http://127.0.0.1:{port}/v1"
        row = {"id": str(uuid.uuid4()), "provider": json.dumps(snapshot), "payload": json.dumps(messages),
               "execution_settings": execution_settings}

        class Store:
            pending = row
            outcome = None

            def claim_next(self):
                pending, self.pending = self.pending, None
                return pending

            def finish(self, job_id, outcome):
                assert job_id == row["id"] and self.outcome is None
                self.outcome = outcome
                loop.call_soon_threadsafe(finished.set)

        store = Store()
        runner = Runner(execution_settings, store)
        runner._purge_artifacts()
        runner.tasks = [asyncio.create_task(runner.run())]
        try:
            await asyncio.wait_for(finished.wait(), timeout=5)
            result = store.outcome
            assert result.status == "uncertain" and "[runner_timeout]" in result.message
            info = json.loads(result.message.split(" diagnostic=", 1)[1])
            assert info["stage"] == "transport" and info["exception_type"] == "total_timeout"
            assert info["network_stage"] == ("reading_body" if partial_body else "waiting_headers")
            assert info["request_sent"] is True and info["response_headers_received"] is partial_body
            assert info["http_status"] == (200 if partial_body else None)
            assert info["body_read_complete"] is False
            assert info["response_bytes"] == (len(PARTIAL) if partial_body else 0)
            assert 900 <= info["elapsed_ms"] <= 4000
            assert 0 <= info["network_stage_elapsed_ms"] <= info["elapsed_ms"]
            assert "http11.send_request_body.complete" in info["network_events"]
            assert "http11.receive_response_headers.started" in info["network_events"]
            assert len(requests) == len(connections) == 1
            artifact = runner.artifacts.load(info["failure_capture_id"])
            assert artifact["technical"] == {key: info[key] for key in artifact["technical"]}
            assert artifact["request"] == requests[0]
            assert base64.b64decode(artifact["response"]["body_base64"]) == (PARTIAL if partial_body else b"")
            assert not artifact["complete_original"]
        finally:
            await runner.stop()
            release.set()
            server.close()
            await server.wait_closed()
            await asyncio.gather(*handlers)

    asyncio.run(scenario())


def test_preflight_failure_records_missing_response_without_fabricating_wire(settings, monkeypatch):
    requests = install_http(monkeypatch, lambda _: pytest.fail("preflight failure must not call provider"))
    app = create_app(settings)
    original_claim = app.state.store.claim_next

    def claim_with_incompatible_snapshot():
        row = original_claim()
        if row is not None:
            snapshot = json.loads(row["provider"])
            snapshot["protocol_version"] = 999
            row["provider"] = json.dumps(snapshot)
        return row

    monkeypatch.setattr(app.state.store, "claim_next", claim_with_incompatible_snapshot)
    with TestClient(app) as client:
        headers, job_id = submit(client)
        result = wait_job(client, headers, job_id)
        assert result["status"] == "failed" and "[content_shape]" in result["message"] and requests == []
        artifact = app.state.runner.artifacts.load(diagnostic(result)["failure_capture_id"])
        assert artifact["request"] is None and artifact["response"]["http_status"] is None
        assert artifact["response"]["body_base64"] == "" and not artifact["complete_original"]


def test_base_and_runtime_secrets_are_redacted_before_failure_storage(settings, monkeypatch):
    runtime_key = "synthetic-runtime-key"
    secrets = [settings.api_key, runtime_key, settings.admin_token, settings.admin_password_hash, settings.feedback_worker_token]
    raw = json.dumps(envelope(" ".join(secrets))).encode()
    requests = install_http(monkeypatch, lambda _: httpx.Response(200, content=raw))
    app = create_app(settings)
    with TestClient(app) as client:
        app.state.store.update_config({"api_key": runtime_key})
        headers, job_id = submit(client, text=" ".join(secrets))
        result = wait_job(client, headers, job_id)
        artifact = app.state.runner.artifacts.load(diagnostic(result)["failure_capture_id"])
        encoded = json.dumps(artifact) + base64.b64decode(artifact["response"]["body_base64"]).decode()
        assert all(secret not in encoded and secret not in result["message"] for secret in secrets)
        assert artifact["redacted"] and not artifact["complete_original"]
        assert_counted_once(client, headers, result, requests)


def test_disabling_new_capture_still_purges_existing_expired_artifacts(settings, monkeypatch):
    install_http(monkeypatch, lambda _: httpx.Response(200, json=envelope(PRIVATE)))
    app = create_app(settings)
    with TestClient(app) as client:
        headers, job_id = submit(client)
        capture_id = diagnostic(wait_job(client, headers, job_id))["failure_capture_id"]
    with sqlite3.connect(app.state.runner.artifact_path) as database:
        database.execute("UPDATE artifacts SET expires_at=0 WHERE id=?", (capture_id,))
    restarted = create_app(replace(settings, failure_artifacts_enabled=False))
    with TestClient(restarted) as client:
        assert client.get("/health").status_code == 200
        assert restarted.state.runner.artifacts.list_recent() == []
        with pytest.raises(ArtifactError, match="artifact_expired"):
            restarted.state.runner.artifacts.load(capture_id)
        with sqlite3.connect(restarted.state.runner.artifact_path) as database:
            assert database.execute("SELECT payload FROM artifacts WHERE id=?", (capture_id,)).fetchone()[0] is None


def test_artifact_cleanup_failure_and_recovery_are_reflected_in_health(settings, monkeypatch):
    app = create_app(replace(settings, worker_enabled=False))
    with TestClient(app) as client:
        real_purge = app.state.runner.artifacts.purge

        def broken_purge():
            raise RuntimeError(PRIVATE)

        monkeypatch.setattr(app.state.runner.artifacts, "purge", broken_purge)
        deadline = time.monotonic() + 3
        while client.get("/health").status_code == 200 and time.monotonic() < deadline:
            time.sleep(0.02)
        assert client.get("/health").status_code == 503
        monkeypatch.setattr(app.state.runner.artifacts, "purge", real_purge)
        deadline = time.monotonic() + 3
        while client.get("/health").status_code == 503 and time.monotonic() < deadline:
            time.sleep(0.02)
        assert client.get("/health").status_code == 200


def test_failure_capture_settings_have_explicit_bounded_ttl(settings):
    assert Settings().failure_artifacts_enabled is True and Settings().failure_artifacts_ttl_hours == 24
    for hours in (1, 168):
        replace(settings, failure_artifacts_ttl_hours=hours).validate()
    for hours in (0, 169, True, 1.5):
        with pytest.raises(ValueError, match="FAILURE_ARTIFACTS_TTL_HOURS"):
            replace(settings, failure_artifacts_ttl_hours=hours).validate()
