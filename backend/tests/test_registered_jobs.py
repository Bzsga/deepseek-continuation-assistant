"""Account admission wraps the existing job ledger without changing its identities."""
import json
from concurrent.futures import ThreadPoolExecutor

import pytest
from fastapi.testclient import TestClient

from app.config import ProviderRoute, Settings
from app.main import create_app
from app.provider import ProviderOutcome
from app.store import digest, local_day


PASSWORD = "synthetic password 123"


@pytest.fixture
def service(tmp_path):
    settings = Settings(db_path=str(tmp_path / "registered-jobs.sqlite3"), worker_enabled=False,
                        api_enabled=True, api_key="synthetic-primary-key", daily_limit=30,
                        user_daily_limit=10, admin_token="synthetic-admin-token",
                        fallback_providers=(ProviderRoute("backup", "https://backup.invalid/v1",
                                                           "synthetic-backup", "synthetic-backup-key"),))
    app = create_app(settings)
    with TestClient(app) as client:
        yield app.state.store, client


def register(client, username="synthetic_user"):
    response = client.post("/v1/accounts/register", json={"username": username, "password": PASSWORD})
    assert response.status_code == 200, response.text
    body = response.json()
    return body, {"Authorization": "Bearer " + body["token"]}


def payload(key="registered-job-0001"):
    return {"idempotency_key": key, "messages": [{"id": "source", "role": "user", "content": "合成原话"}]}


def test_logout_revokes_session_and_same_owner_login_recovers_existing_identities(service):
    store, client = service
    account, headers = register(client)
    assert client.get("/v1/accounts/me", headers=headers).json()["user"] == account["user"]
    response = client.post("/v1/jobs", headers=headers, json=payload())
    assert response.status_code == 200, response.text
    job = response.json()
    assert client.post("/v1/accounts/logout", headers=headers).json() == {}
    for method, path, body in (("get", "/v1/accounts/me", None),
                               ("get", f"/v1/jobs/{job['id']}", None),
                               ("post", "/v1/jobs", payload()),
                               ("post", f"/v1/jobs/{job['id']}/cancel", {})):
        kwargs = {"headers": headers}
        if body is not None:
            kwargs["json"] = body
        assert getattr(client, method)(path, **kwargs).status_code == 401
    login = client.post("/v1/accounts/login", json={"username": "synthetic_user", "password": PASSWORD})
    assert login.status_code == 200
    assert login.json()["user"]["id"] == account["user"]["id"]
    renewed = {"Authorization": "Bearer " + login.json()["token"]}
    assert client.post("/v1/jobs", headers=renewed, json=payload()).json()["id"] == job["id"]
    _, other = register(client, "synthetic_other")
    assert client.get(f"/v1/jobs/{job['id']}", headers=other).status_code == 404
    assert client.post(f"/v1/jobs/{job['id']}/cancel", headers=other).status_code == 404
    assert client.post(f"/v1/jobs/{job['id']}/cancel", headers=renewed).json()["status"] == "cancelled"
    assert store.quota(account["user"]["id"])["user_daily_reserved"] == 0


def test_legacy_anonymous_jobs_are_lookup_only_and_never_dispatch_a_new_fallback(service):
    store, client = service
    _, headers = register(client)
    response = client.post("/v1/jobs", headers=headers, json=payload())
    assert response.status_code == 200
    job = response.json()
    token, _ = store.create_session()
    legacy_owner = store.authenticate(token)
    legacy = {"Authorization": "Bearer " + token}
    # Represent a job already admitted before the account migration, preserving its original payload/key.
    with store.connection(write=True) as db:
        snapshot = json.loads(db.execute("SELECT provider FROM jobs WHERE id=?", (job["id"],)).fetchone()[0])
        snapshot.pop("account_session_hash")
        db.execute("UPDATE jobs SET owner_id=?,provider=? WHERE id=?", (legacy_owner, json.dumps(snapshot), job["id"]))
    assert client.post("/v1/jobs", headers=legacy, json=payload()).json()["id"] == job["id"]
    changed = payload()
    changed["messages"][0]["content"] = "不同原话"
    assert client.post("/v1/jobs", headers=legacy, json=changed).status_code == 409
    assert client.post("/v1/jobs", headers=legacy, json=payload("not-admitted-before")).status_code == 401
    assert store.claim_next()["id"] == job["id"]
    settings = store.current_settings()
    primary = {"ordinal": 0, "id": "primary", "base_url": settings.base_url, "model": settings.model}
    assert store.begin_provider_attempt(job["id"], primary)
    backup = {"ordinal": 1, "id": "backup", "base_url": settings.fallback_providers[0].base_url,
              "model": settings.fallback_providers[0].model}
    assert store.begin_provider_attempt(job["id"], backup) is None
    assert len(store.provider_attempts(job["id"])) == 1
    assert client.get(f"/v1/jobs/{job['id']}", headers=legacy).status_code == 200
    assert client.post(f"/v1/jobs/{job['id']}/cancel", headers=legacy).status_code == 200
    assert store.quota(legacy_owner)["user_daily_used"] == 1


def test_logout_prevents_additional_provider_dispatch_without_erasing_usage(service):
    store, client = service
    account, headers = register(client)
    job = client.post("/v1/jobs", headers=headers, json=payload()).json()
    store.claim_next()
    settings = store.current_settings()
    assert store.begin_provider_attempt(job["id"], {"ordinal": 0, "id": "primary",
        "base_url": settings.base_url, "model": settings.model})
    assert client.post("/v1/accounts/logout", headers=headers).status_code == 200
    assert store.begin_provider_attempt(job["id"], {"ordinal": 1, "id": "backup",
        "base_url": settings.fallback_providers[0].base_url, "model": settings.fallback_providers[0].model}) is None
    store.finish(job["id"], ProviderOutcome("uncertain", "synthetic unknown outcome", []))
    assert store.quota(account["user"]["id"])["user_daily_used"] == 1
    assert len(store.provider_attempts(job["id"])) == 1


def test_today_remaining_is_atomic_with_reservations_and_does_not_rewrite_actual_usage(service):
    store, client = service
    account, headers = register(client)
    owner = account["user"]["id"]
    store.update_user_quota(owner, {"daily_limit": 5})
    first = client.post("/v1/jobs", headers=headers, json=payload()).json()
    store.claim_next()
    store.finish(first["id"], ProviderOutcome("completed", "synthetic result", []))
    for index in range(3):
        assert client.post("/v1/jobs", headers=headers, json=payload(f"reserved-{index:03d}")).status_code == 200
    with ThreadPoolExecutor(max_workers=7) as pool:
        setting = pool.submit(store.update_user_quota, owner, {"today_remaining": 1, "day": local_day()})
        admissions = [pool.submit(client.post, "/v1/jobs", headers=headers, json=payload(f"racing-{index:03d}"))
                      for index in range(6)]
        assert setting.result()["daily_remaining"] == 1
        assert all(result.result().status_code in {200, 429} for result in admissions)
    quota = store.quota(owner)
    assert quota["user_daily_used"] == 1
    assert quota["user_daily_used"] + quota["user_daily_reserved"] <= quota["user_daily_limit"]
    assert quota["user_daily_remaining"] == 0
    with store.connection() as db:
        assert db.execute("SELECT COUNT(*) FROM jobs WHERE count_state='used'").fetchone()[0] == 1


def test_account_validation_does_not_echo_secrets_and_registration_limit_is_runtime_configurable(service):
    store, client = service
    invalid = client.post("/v1/accounts/register", json={"username": "abc", "password": {"secret": "hidden"}})
    assert invalid.status_code == 422 and "hidden" not in invalid.text
    store.update_config({"public_registration_limit": 0})
    denied = client.post("/v1/accounts/register", json={"username": "no_public_slot", "password": PASSWORD})
    assert denied.status_code == 403
    invite = store.accounts.create_invites(1, 7)["invitations"][0]["code"]
    response = client.post("/v1/accounts/register", json={"username": "invited_user", "password": PASSWORD,
                                                          "invitation_code": invite})
    assert response.status_code == 200
    assert response.json()["user"]["registration_kind"] == "invitation"
    assert store.admin_config()["public_registered_count"] == 0
