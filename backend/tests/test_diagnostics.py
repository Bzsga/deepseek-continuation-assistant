import json
import secrets
import time
import uuid
from concurrent.futures import ThreadPoolExecutor

import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.diagnostics import DiagnosticBatch, MAX_DIAGNOSTIC_BODY_BYTES, RETENTION_SECONDS
from app.main import create_app


def event(**changes):
    return {
        "event_id": str(uuid.uuid4()), "occurred_at": time.time(),
        "app_version_code": 9, "app_version_name": "1.0.0-preview.9", "android_sdk": 35,
        "manufacturer": "OPPO", "model": "Find X9", "kind": "import", "stage": "failed",
        "metrics": {"input_bytes": 11_300_000, "heap_max_bytes": 128 * 1024 * 1024},
        "exception_class": "java.lang.OutOfMemoryError",
        "stack_frames": ["app.banzhan.deepseekcontinuation.core.DeepSeekImporter$Reader.read(DeepSeekImporter.kt:123)",
                         "java.lang.Thread.run(Thread.java:923)",
                         "kotlinx.coroutines.JobSupport.<init>(JobSupport.kt:50)"],
        **changes,
    }


def auth(token):
    return {"Authorization": "Bearer " + token}


@pytest.fixture
def setup(tmp_path):
    settings = Settings(db_path=str(tmp_path / "diagnostics.sqlite3"), worker_enabled=False,
                        admin_token=secrets.token_urlsafe(32), feedback_worker_token=secrets.token_urlsafe(32),
                        daily_limit=0, user_daily_limit=0, public_enabled=False)
    app = create_app(settings)
    with TestClient(app) as client:
        owner = auth(client.post("/v1/sessions").json()["token"])
        other = auth(client.post("/v1/sessions").json()["token"])
        yield app, client, owner, other, auth(settings.admin_token), auth(settings.feedback_worker_token)


def test_auth_persistence_admin_isolation_and_no_model_quota(setup):
    app, client, owner, other, admin, worker = setup
    payload = {"events": [event()]}
    for headers in ({}, admin, worker):
        assert client.post("/v1/diagnostics", headers=headers, json=payload).status_code == 401
    before = client.get("/v1/quota", headers=owner).json()
    result = client.post("/v1/diagnostics", headers=owner, json=payload)
    assert result.status_code == 200
    assert result.json()["accepted_event_ids"] == [payload["events"][0]["event_id"]]
    for headers in ({}, owner, other, worker):
        assert client.get("/v1/admin/diagnostics", headers=headers).status_code == 401
    response = client.get("/v1/admin/diagnostics", headers=admin)
    assert response.status_code == 200
    assert response.headers["cache-control"] == "no-store"
    saved = response.json()["items"]
    assert len(saved) == 1 and saved[0]["metrics"]["input_bytes"] == 11_300_000
    assert saved[0]["owner_id"]
    assert client.get("/v1/quota", headers=owner).json() == before
    with app.state.store.connection() as db:
        assert db.execute("SELECT COUNT(*) FROM jobs").fetchone()[0] == 0
    assert client.get("/v1/diagnostics", headers=other).status_code == 405


def test_idempotent_batch_and_conflict_rollback(setup):
    app, client, owner, other, admin, _ = setup
    first = event()
    assert client.post("/v1/diagnostics", headers=owner, json={"events": [first, first]}).status_code == 200
    repeat = client.post("/v1/diagnostics", headers=owner, json={"events": [first]}).json()
    assert repeat["accepted_event_ids"] == repeat["duplicate_event_ids"] == [first["event_id"]]
    new = event()
    conflict = dict(first, stage="complete")
    assert client.post("/v1/diagnostics", headers=owner, json={"events": [new, conflict]}).status_code == 409
    assert len(client.get("/v1/admin/diagnostics", headers=admin).json()["items"]) == 1
    # Another anonymous user has an independent idempotency namespace.
    assert client.post("/v1/diagnostics", headers=other, json={"events": [conflict]}).status_code == 200
    assert len(client.get("/v1/admin/diagnostics", headers=admin).json()["items"]) == 2


@pytest.mark.parametrize("changes", [
    {"message": "PRIVATE transcript API-secret"},
    {"exception_class": "java.lang.Error: PRIVATE transcript"},
    {"stack_frames": ["java.lang.Thread.run(/private/PRIVATE.java:2)"]},
    {"stack_frames": ["outside.package.Read.go(Test.kt:1)"]},
    {"stack_frames": ["java.lang.Thread.run(Thread.java:2) PRIVATE"]},
    {"metrics": {"transcript": 123}},
    {"metrics": {"input_bytes": -1}},
    {"metrics": {"input_bytes": True}},
    {"metrics": {"input_bytes": "123"}},
    {"metrics": {"input_bytes": 2**53 + 1}},
    {"manufacturer": "OPPO\nPRIVATE"},
    {"model": "https://private.example/PRIVATE"},
    {"app_version_name": "preview.9 PRIVATE"},
    {"kind": "PRIVATE"},
    {"stage": "PRIVATE"},
    {"event_id": "PRIVATE"},
    {"android_sdk": True},
])
def test_rejects_unsafe_fields_without_echo(setup, changes):
    app, client, owner, _, _, _ = setup
    response = client.post("/v1/diagnostics", headers=owner, json={"events": [event(**changes)]})
    assert response.status_code == 422
    assert "PRIVATE" not in response.text
    with app.state.store.connection() as db:
        assert db.execute("SELECT COUNT(*) FROM mobile_diagnostics").fetchone()[0] == 0


def test_body_and_batch_bounds(setup):
    _, client, owner, _, _, _ = setup
    for events in ([], [event() for _ in range(21)]):
        assert client.post("/v1/diagnostics", headers=owner, json={"events": events}).status_code == 422
    assert client.post("/v1/diagnostics", headers=owner,
                       content=b" " * (MAX_DIAGNOSTIC_BODY_BYTES + 1)).status_code == 413
    assert client.post("/v1/diagnostics", headers=owner,
                       json={"events": [event(stack_frames=["java.lang.Thread.run(Thread.java:1)"] * 31)]}).status_code == 422


def test_receipt_time_retention_and_expired_ack(setup):
    app, client, owner, _, admin, _ = setup
    now = time.time()
    first = event(occurred_at=now - 86400)
    expired = event(occurred_at=now - RETENTION_SECONDS - 1)
    result = client.post("/v1/diagnostics", headers=owner, json={"events": [first, expired]}).json()
    assert result["accepted_event_ids"] == [first["event_id"], expired["event_id"]]
    assert result["expired_event_ids"] == [expired["event_id"]]
    items = client.get("/v1/admin/diagnostics", headers=admin, params={"since": now - 1}).json()["items"]
    assert len(items) == 1 and items[0]["received_at"] > first["occurred_at"]
    with app.state.store.connection(write=True) as db:
        db.execute("UPDATE mobile_diagnostics SET received_at=?", (now - RETENTION_SECONDS - 1,))
    assert client.get("/v1/admin/diagnostics", headers=admin).json()["items"] == []
    assert app.state.diagnostics.purge_expired() == 1


def test_daily_quota_concurrency_and_duplicate_after_limit(setup, monkeypatch):
    app, client, owner, _, admin, _ = setup
    monkeypatch.setattr("app.diagnostics.DAILY_EVENT_LIMIT", 1)
    events = [event(), event()]
    owner_id = app.state.store.authenticate(owner["Authorization"].split(" ", 1)[1])

    def attempt(item):
        try:
            return app.state.diagnostics.accept(owner_id, DiagnosticBatch(events=[item]))
        except Exception as error:
            return getattr(error, "status", None)

    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(attempt, events))
    assert sum(isinstance(result, dict) for result in results) == 1
    assert 429 in results
    winner = next(item for item, result in zip(events, results) if isinstance(result, dict))
    duplicate = client.post("/v1/diagnostics", headers=owner, json={"events": [winner]})
    assert duplicate.status_code == 200
    assert duplicate.json()["duplicate_event_ids"] == [winner["event_id"]]


def test_admin_filters_and_limits(setup):
    _, client, owner, _, admin, _ = setup
    assert client.post("/v1/diagnostics", headers=owner, json={"events": [event(), event()]}).status_code == 200
    assert len(client.get("/v1/admin/diagnostics?limit=1", headers=admin).json()["items"]) == 1
    for query in ("limit=201", "limit=0", "since=-1", "since=nan"):
        assert client.get("/v1/admin/diagnostics?" + query, headers=admin).status_code == 422
