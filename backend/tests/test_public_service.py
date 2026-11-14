from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from types import SimpleNamespace
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app
from app.provider import ProviderOutcome
from app.store import digest


@pytest.fixture
def public_settings(tmp_path):
    return Settings(
        db_path=str(tmp_path / "public.sqlite3"),
        api_enabled=True,
        api_key="synthetic-public-service-key",
        public_enabled=True,
        daily_limit=50,
        user_daily_limit=5,
        public_registration_limit=1000,
        worker_enabled=False,
    )


def job_payload(key="public-job-0001", content="用户下周每天练习英语二十分钟"):
    return {
        "idempotency_key": key,
        "messages": [{"id": "synthetic:1", "role": "user", "content": content}],
    }


def new_session(client):
    identity = uuid4().hex
    body = client.app.state.store.accounts.register(
        "public_" + identity[:24], "synthetic-public-password", client_ip="synthetic-" + identity,
    )
    return {"Authorization": "Bearer " + body["token"]}, body


def quota(client, headers):
    response = client.get("/v1/quota", headers=headers)
    assert response.status_code == 200
    return response.json()


def submit(client, headers, key="public-job-0001"):
    response = client.post("/v1/jobs", headers=headers, json=job_payload(key))
    assert response.status_code == 200
    return response.json()


def test_anonymous_session_quota_remains_readable_but_new_public_jobs_require_registration(public_settings):
    app = create_app(public_settings)
    with TestClient(app) as client:
        response = client.post("/v1/sessions")
        assert response.status_code == 200
        body = response.json()
        token = body["token"]
        headers = {"Authorization": "Bearer " + token}
        current = quota(client, headers)
        assert len(token) >= 32
        assert current["access_mode"] == "public"
        assert current["enabled"] is True
        assert current["cost_known"] is False
        assert current["remaining_cny"] == 0
        assert current["per_job_limit_cny"] == 0
        assert current["daily_limit"] == current["daily_remaining"] == 50
        assert current["user_daily_limit"] == current["user_daily_remaining"] == 5
        assert current["daily_used"] == current["daily_reserved"] == 0
        reset = datetime.fromisoformat(current["reset_at"])
        assert reset.hour == reset.minute == reset.second == 0
        assert reset.utcoffset().total_seconds() == 8 * 3600
        with app.state.store.connection() as db:
            assert db.execute("SELECT token_hash FROM users").fetchone()[0] == digest(token)
        response = client.post("/v1/jobs", headers=headers, json=job_payload())
        assert response.status_code == 401
        current = quota(client, headers)
        assert current["daily_used"] == current["daily_reserved"] == 0
        assert current["daily_remaining"] == 50
        assert current["user_daily_remaining"] == 5
        with app.state.store.connection() as db:
            assert db.execute("SELECT COUNT(*) FROM jobs").fetchone()[0] == 0


def test_fifty_global_and_five_per_user_hold_under_concurrent_submissions(public_settings):
    app = create_app(public_settings)
    with TestClient(app) as client:
        users = [new_session(client)[0] for _ in range(12)]
        requests = [(owner, index) for owner in range(12) for index in range(6)]

        def send(item):
            owner, index = item
            return client.post(
                "/v1/jobs", headers=users[owner],
                json=job_payload(f"concurrent-{owner:02d}-{index:02d}"),
            )

        with ThreadPoolExecutor(max_workers=16) as pool:
            responses = list(pool.map(send, requests))
        assert sum(response.status_code == 200 for response in responses) == 50
        assert all(response.status_code in {200, 429} for response in responses)
        for headers in users:
            current = quota(client, headers)
            assert current["daily_used"] == 0
            assert current["daily_reserved"] == 50
            assert current["daily_remaining"] == 0
            assert 0 <= current["user_daily_reserved"] <= 5

        with ThreadPoolExecutor(max_workers=8) as pool:
            claimed = list(pool.map(lambda _: app.state.store.claim_next(), range(60)))
        ids = [row["id"] for row in claimed if row is not None]
        assert len(ids) == len(set(ids)) == 50
        current = quota(client, users[0])
        assert current["daily_used"] == 50
        assert current["daily_reserved"] == 0
        assert current["daily_remaining"] == 0


def test_same_registered_user_can_only_reserve_five_concurrently(public_settings):
    app = create_app(public_settings)
    with TestClient(app) as client:
        headers, _ = new_session(client)
        with ThreadPoolExecutor(max_workers=12) as pool:
            responses = list(pool.map(
                lambda index: client.post("/v1/jobs", headers=headers, json=job_payload(f"user-limit-{index:03d}")),
                range(18),
            ))
        assert sum(response.status_code == 200 for response in responses) == 5
        assert all(response.status_code in {200, 429} for response in responses)
        current = quota(client, headers)
        assert current["user_daily_reserved"] == 5
        assert current["user_daily_remaining"] == 0
        assert current["daily_remaining"] == 45


def test_idempotent_request_reserves_once_and_conflicting_body_is_rejected(public_settings):
    app = create_app(public_settings)
    with TestClient(app) as client:
        headers, _ = new_session(client)
        with ThreadPoolExecutor(max_workers=10) as pool:
            responses = list(pool.map(
                lambda _: client.post("/v1/jobs", headers=headers, json=job_payload()), range(12),
            ))
        assert all(response.status_code == 200 for response in responses)
        assert len({response.json()["id"] for response in responses}) == 1
        assert quota(client, headers)["user_daily_reserved"] == 1
        changed = job_payload(content="新的内容")
        assert client.post("/v1/jobs", headers=headers, json=changed).status_code == 409
        row = app.state.store.claim_next()
        app.state.store.finish(row["id"], ProviderOutcome("uncertain", "test uncertainty", []))
        replay = client.post("/v1/jobs", headers=headers, json=job_payload())
        assert replay.status_code == 200
        assert replay.json()["status"] == "uncertain"
        current = quota(client, headers)
        assert current["user_daily_used"] == 1
        assert current["user_daily_reserved"] == 0


def test_session_isolation_preserves_private_tasks(public_settings):
    app = create_app(public_settings)
    with TestClient(app) as client:
        owner, owner_body = new_session(client)
        other, other_body = new_session(client)
        assert owner_body["token"] != other_body["token"]
        job = submit(client, owner)
        assert client.get(f"/v1/jobs/{job['id']}", headers=other).status_code == 404
        assert client.post(f"/v1/jobs/{job['id']}/cancel", headers=other).status_code == 404
        assert client.get("/v1/quota").status_code == 401
        assert quota(client, owner)["user_daily_reserved"] == 1
        assert quota(client, other)["user_daily_reserved"] == 0


def test_queued_cancel_releases_only_its_reservation(public_settings):
    app = create_app(public_settings)
    with TestClient(app) as client:
        headers, _ = new_session(client)
        first = submit(client, headers)
        second = submit(client, headers, "public-job-0002")
        response = client.post(f"/v1/jobs/{first['id']}/cancel", headers=headers)
        assert response.status_code == 200
        assert response.json()["status"] == "cancelled"
        current = quota(client, headers)
        assert current["daily_used"] == 0
        assert current["daily_reserved"] == 1
        assert current["user_daily_remaining"] == 4
        assert client.post(f"/v1/jobs/{first['id']}/cancel", headers=headers).status_code == 200
        assert quota(client, headers)["daily_reserved"] == 1
        row = app.state.store.claim_next()
        assert row["id"] == second["id"]


@pytest.mark.parametrize("status", ["completed", "failed", "uncertain"])
def test_started_request_is_not_refunded_for_any_outcome(public_settings, status):
    app = create_app(public_settings)
    with TestClient(app) as client:
        headers, _ = new_session(client)
        app.state.store.update_config({"user_daily_limit": 1})
        job = submit(client, headers)
        row = app.state.store.claim_next()
        assert row["id"] == job["id"]
        app.state.store.finish(job["id"], ProviderOutcome(status, "synthetic result", [], 12, 7, 0))
        current = quota(client, headers)
        assert current["daily_used"] == current["user_daily_used"] == 1
        assert current["daily_reserved"] == current["user_daily_reserved"] == 0
        assert current["user_daily_remaining"] == 0
        assert client.post("/v1/jobs", headers=headers, json=job_payload("another-public-job")).status_code == 429


def test_cancel_after_dispatch_preserves_used_count(public_settings):
    app = create_app(public_settings)
    with TestClient(app) as client:
        headers, _ = new_session(client)
        job = submit(client, headers)
        app.state.store.claim_next()
        cancelled = client.post(f"/v1/jobs/{job['id']}/cancel", headers=headers)
        assert cancelled.status_code == 200
        assert cancelled.json()["cancel_requested"] is True
        app.state.store.finish(job["id"], ProviderOutcome("completed", "synthetic result", []))
        assert client.get(f"/v1/jobs/{job['id']}", headers=headers).json()["status"] == "cancelled"
        current = quota(client, headers)
        assert current["user_daily_used"] == 1
        assert current["user_daily_reserved"] == 0
        assert current["user_daily_remaining"] == 4


def test_restart_preserves_used_and_queued_counts(public_settings):
    first_app = create_app(public_settings)
    with TestClient(first_app) as client:
        headers, _ = new_session(client)
        running = submit(client, headers)
        first_app.state.store.claim_next()
        queued = submit(client, headers, "queued-at-restart")
    second_app = create_app(public_settings)
    with TestClient(second_app) as client:
        assert client.get(f"/v1/jobs/{running['id']}", headers=headers).json()["status"] == "uncertain"
        assert client.get(f"/v1/jobs/{queued['id']}", headers=headers).json()["status"] == "queued"
        current = quota(client, headers)
        assert current["daily_used"] == 1
        assert current["daily_reserved"] == 1
        assert current["user_daily_remaining"] == 3
        row = second_app.state.store.claim_next()
        assert row["id"] == queued["id"]
        assert quota(client, headers)["daily_used"] == 2


def test_beijing_midnight_resets_used_and_carries_pending_reservations(public_settings, monkeypatch):
    clock = {"now": datetime(2026, 11, 3, 15, 59, 50, tzinfo=timezone.utc).timestamp()}
    monkeypatch.setattr("app.store.time", SimpleNamespace(time=lambda: clock["now"]))
    app = create_app(public_settings)
    with TestClient(app) as client:
        headers, _ = new_session(client)
        app.state.store.update_config({"daily_limit": 2, "user_daily_limit": 2})
        first = submit(client, headers)
        app.state.store.claim_next()
        app.state.store.finish(first["id"], ProviderOutcome("completed", "synthetic result", []))
        waiting = submit(client, headers, "waiting-over-midnight")
        before = quota(client, headers)
        assert before["daily_used"] == before["daily_reserved"] == 1
        assert before["daily_remaining"] == 0
        assert before["reset_at"] == "2026-11-04T00:00:00+08:00"

        clock["now"] += 20
        after = quota(client, headers)
        assert after["daily_used"] == 0
        assert after["daily_reserved"] == 1
        assert after["daily_remaining"] == 1
        assert after["reset_at"] == "2026-11-05T00:00:00+08:00"
        submit(client, headers, "new-day-reservation")
        claimed = app.state.store.claim_next()
        assert claimed["id"] == waiting["id"]
        with app.state.store.connection() as db:
            row = db.execute("SELECT count_day FROM jobs WHERE id=?", (waiting["id"],)).fetchone()
            assert row["count_day"] == "2026-11-04"
        current = quota(client, headers)
        assert current["daily_used"] == current["daily_reserved"] == 1
        assert current["daily_remaining"] == 0


def test_request_started_before_midnight_stays_in_original_day(public_settings, monkeypatch):
    clock = {"now": datetime(2026, 11, 3, 15, 59, 50, tzinfo=timezone.utc).timestamp()}
    monkeypatch.setattr("app.store.time", SimpleNamespace(time=lambda: clock["now"]))
    app = create_app(public_settings)
    with TestClient(app) as client:
        headers, _ = new_session(client)
        job = submit(client, headers)
        app.state.store.claim_next()
        clock["now"] += 20
        app.state.store.finish(job["id"], ProviderOutcome("uncertain", "synthetic uncertainty", []))
        current = quota(client, headers)
        assert current["daily_used"] == 0
        assert current["daily_reserved"] == 0
        assert current["user_daily_remaining"] == 5
        with app.state.store.connection() as db:
            assert db.execute("SELECT count_day FROM jobs WHERE id=?", (job["id"],)).fetchone()[0] == "2026-11-03"
        assert client.post("/v1/jobs", headers=headers, json=job_payload()).json()["id"] == job["id"]
        assert quota(client, headers)["daily_reserved"] == 0


@pytest.mark.parametrize("change", [{"daily_limit": 0}, {"user_daily_limit": 0}, {"public_enabled": False}])
def test_zero_daily_limit_or_public_switch_pauses_new_work(public_settings, change):
    app = create_app(public_settings)
    with TestClient(app) as client:
        app.state.store.update_config(change)
        headers, session = new_session(client)
        assert session["quota"]["enabled"] is False
        assert quota(client, headers)["enabled"] is False
        assert client.post("/v1/jobs", headers=headers, json=job_payload()).status_code == 503


def test_lowered_daily_limits_prevent_queued_jobs_exceeding_new_cap(public_settings):
    app = create_app(public_settings)
    with TestClient(app) as client:
        headers, _ = new_session(client)
        jobs = [submit(client, headers, f"lower-limit-{index:03d}") for index in range(5)]
        app.state.store.update_config({"daily_limit": 2, "user_daily_limit": 2})
        started = []
        while True:
            row = app.state.store.claim_next()
            if row is None:
                break
            started.append(row["id"])
            app.state.store.finish(row["id"], ProviderOutcome("completed", "synthetic result", []))
        assert len(started) == 2
        current = quota(client, headers)
        assert current["daily_used"] == 2
        assert current["daily_reserved"] == 0
        assert current["daily_remaining"] == 0
        states = [client.get(f"/v1/jobs/{job['id']}", headers=headers).json()["status"] for job in jobs]
        assert states.count("completed") == 2
        assert states.count("failed") == 3


def test_changed_provider_configuration_releases_queue_without_counting_call(public_settings):
    app = create_app(public_settings)
    with TestClient(app) as client:
        headers, _ = new_session(client)
        job = submit(client, headers)
        app.state.store.update_config({"model": "deepseek-synthetic-changed"})
        assert app.state.store.claim_next() is None
        assert client.get(f"/v1/jobs/{job['id']}", headers=headers).json()["status"] == "failed"
        current = quota(client, headers)
        assert current["daily_used"] == current["daily_reserved"] == 0
        assert current["daily_remaining"] == 50


def test_existing_invitation_identity_keeps_readable_public_quota_but_cannot_admit_new_public_jobs(public_settings):
    app = create_app(public_settings)
    with TestClient(app) as client:
        app.state.store.update_config({"public_enabled": False})
        code = app.state.store.create_invitation(code="test-public-upgrade", user_limit_micros=0)
        response = client.post("/v1/invitations/redeem", json={"code": code})
        assert response.status_code == 200
        token = response.json()["token"]
        headers = {"Authorization": "Bearer " + token}
        owner_id = app.state.store.authenticate(token)
        assert quota(client, headers)["access_mode"] == "invitation"
        assert quota(client, headers)["enabled"] is False
        app.state.store.update_config({"public_enabled": True})
        assert app.state.store.authenticate(token) == owner_id
        assert quota(client, headers)["access_mode"] == "public"
        assert quota(client, headers)["enabled"] is True
        assert client.post("/v1/jobs", headers=headers, json=job_payload()).status_code == 401
        with app.state.store.connection() as db:
            assert db.execute("SELECT budget_limit FROM users WHERE id=?", (owner_id,)).fetchone()[0] == 0
            assert db.execute("SELECT COUNT(*) FROM jobs WHERE owner_id=?", (owner_id,)).fetchone()[0] == 0
