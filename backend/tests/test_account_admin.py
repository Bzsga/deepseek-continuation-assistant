import json
import secrets
import time
import uuid

import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app
from app.store import digest, local_day


@pytest.fixture
def account_admin(tmp_path):
    token = secrets.token_urlsafe(32)
    settings = Settings(db_path=str(tmp_path / "account-admin.sqlite3"), worker_enabled=False,
                        api_enabled=True, api_key="synthetic-provider-key", public_enabled=True,
                        daily_limit=50, user_daily_limit=5, admin_token=token)
    app = create_app(settings)
    with TestClient(app) as client:
        yield app.state.store, client, {"Authorization": "Bearer " + token}


def add_user(store, username=None, *, created_at=None, registration_kind="public", daily_limit=None):
    created_at = time.time() if created_at is None else created_at
    user_id, token = str(uuid.uuid4()), secrets.token_urlsafe(32)
    with store.connection(write=True) as db:
        db.execute("""INSERT INTO users(id,token_hash,budget_limit,created_at,access_mode,last_seen)
                      VALUES(?,?,0,?,'public',?)""", (user_id, digest(token), created_at, created_at))
        if username is not None:
            db.execute("""INSERT INTO registered_accounts(user_id,username,username_key,password_hash,
                          registration_kind,created_at,daily_limit_override) VALUES(?,?,?,?,?,?,?)""",
                       (user_id, username, username.lower(), "private-password-hash", registration_kind,
                        created_at, daily_limit))
            if registration_kind == "public":
                db.execute("UPDATE registration_counter SET public_registered_count=public_registered_count+1 WHERE id=1")
        db.execute("INSERT INTO user_activity(user_id,day,last_seen) VALUES(?,?,?)",
                   (user_id, local_day(), created_at))
    return user_id, token


def add_job(store, owner_id, *, reserved=False, fallback=False):
    job_id, now = str(uuid.uuid4()), time.time()
    with store.connection(write=True) as db:
        db.execute("""INSERT INTO jobs(id,owner_id,idempotency_key,fingerprint,status,message,payload,provider,
                      created_at,month,count_state,count_day) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)""",
                   (job_id, owner_id, job_id, digest(job_id), "queued" if reserved else "completed",
                    "private-job-message", "private-transcript", json.dumps({"mock_mode": False}),
                    now, time.strftime("%Y-%m", time.gmtime(now)), "reserved" if reserved else "used", local_day()))
        if fallback:
            db.execute("""INSERT INTO provider_attempts(id,job_id,ordinal,route_id,base_url,model,status,
                          started_at,count_day) VALUES(?,?,1,'synthetic-fallback',
                          'https://synthetic.invalid','synthetic-model','completed',?,?)""",
                       (str(uuid.uuid4()), job_id, now, local_day()))
    return job_id


def users(client, headers):
    response = client.get("/v1/admin/users", headers=headers)
    assert response.status_code == 200, response.text
    return {user["id"]: user for user in response.json()["users"]}


def test_registered_user_list_and_population_statistics_exclude_legacy_identities(account_admin):
    store, client, headers = account_admin
    first, _ = add_user(store, "SyntheticOne")
    second, _ = add_user(store, "SyntheticTwo", registration_kind="invitation", daily_limit=2)
    legacy, _ = add_user(store)
    add_job(store, first, fallback=True)
    add_job(store, first, reserved=True)
    add_job(store, legacy)

    response = client.get("/v1/admin/users?limit=1&offset=0", headers=headers)
    assert response.status_code == 200
    result = response.json()
    assert result["total"] == 2 and result["limit"] == 1 and len(result["users"]) == 1
    assert result["day"] == local_day() and result["reset_at"].endswith("+08:00")
    assert result["users"][0]["id"] == second
    current = users(client, headers)
    assert set(current) == {first, second}
    assert current[first]["username"] == "SyntheticOne"
    assert current[first]["registration_kind"] == "public"
    assert current[first]["daily_limit_override"] is None
    assert current[first]["daily_used"] == 2  # The fallback is a separate real dispatch.
    assert current[first]["daily_reserved"] == 1
    assert current[first]["daily_remaining"] == 2
    assert current[second]["registration_kind"] == "invitation"
    assert current[second]["daily_limit"] == current[second]["daily_remaining"] == 2
    assert "private-password-hash" not in response.text
    assert "token_hash" not in response.text and "private-transcript" not in response.text

    statistics = client.get("/v1/admin/stats?days=1", headers=headers).json()
    assert statistics["totals"]["users"] == 2
    assert statistics["totals"]["new_users"] == 2
    assert statistics["totals"]["active_users"] == 2
    assert statistics["totals"]["requests"] == 3  # Anonymous historical usage remains in service totals.
    assert statistics["today"]["daily_reserved"] == 1
    config = client.get("/v1/admin/config", headers=headers).json()
    assert config["public_registered_count"] == 1  # Invited registrations do not consume the public cap.


def test_admin_can_override_daily_limit_and_set_today_remaining_without_rewriting_usage(account_admin):
    store, client, headers = account_admin
    owner, _ = add_user(store, "SyntheticQuota")
    inherited, _ = add_user(store, "SyntheticInherited")
    legacy, _ = add_user(store)
    add_job(store, owner, fallback=True)
    add_job(store, owner, reserved=True)
    with store.connection() as db:
        ledger_before = [tuple(row) for row in db.execute("SELECT * FROM provider_attempts ORDER BY id")]
        jobs_before = [tuple(row) for row in db.execute("SELECT * FROM jobs ORDER BY id")]

    endpoint = f"/v1/admin/users/{owner}/quota"
    response = client.patch(endpoint, headers=headers, json={"daily_limit": 8})
    assert response.status_code == 200, response.text
    assert users(client, headers)[owner]["daily_remaining"] == 5
    response = client.patch(endpoint, headers=headers, json={"today_remaining": 4, "day": local_day()})
    assert response.status_code == 200, response.text
    assert users(client, headers)[owner]["daily_remaining"] == 4
    assert client.patch(endpoint, headers=headers, json={"today_remaining": 4, "day": local_day()}).status_code == 200
    assert users(client, headers)[owner]["daily_remaining"] == 4
    assert client.patch(endpoint, headers=headers, json={"daily_limit": 1}).status_code == 200
    assert users(client, headers)[owner]["daily_remaining"] == 4
    assert client.patch("/v1/admin/config", headers=headers, json={"user_daily_limit": 9}).status_code == 200
    assert users(client, headers)[inherited]["daily_remaining"] == 9
    assert client.patch(endpoint, headers=headers, json={"daily_limit": None}).status_code == 200
    assert users(client, headers)[owner]["daily_limit_override"] is None
    assert users(client, headers)[owner]["daily_remaining"] == 4
    assert client.patch(f"/v1/admin/users/{legacy}/quota", headers=headers, json={"daily_limit": 1}).status_code == 404
    with store.connection() as db:
        assert ledger_before == [tuple(row) for row in db.execute("SELECT * FROM provider_attempts ORDER BY id")]
        assert jobs_before == [tuple(row) for row in db.execute("SELECT * FROM jobs ORDER BY id")]


@pytest.mark.parametrize("body", [
    {"daily_limit": -1}, {"daily_limit": 1_000_001}, {"daily_limit": True},
    {"today_remaining": None}, {"today_remaining": -1}, {"today_remaining": "4"},
    {"day": None}, {"day": "2026-02-30"}, {"day": "2026-1-01"}, {"unknown": 1},
])
def test_user_quota_request_is_strict(account_admin, body):
    store, client, headers = account_admin
    owner, _ = add_user(store, "SyntheticValidation")
    assert client.patch(f"/v1/admin/users/{owner}/quota", headers=headers, json=body).status_code == 422


def test_registration_invites_are_admin_only_and_unused_codes_remain_shareable(account_admin):
    store, client, headers = account_admin
    owner, user_token = add_user(store, "SyntheticProtected")
    for supplied in ({}, {"Authorization": "Bearer " + user_token}):
        assert client.get("/v1/admin/registration-invites", headers=supplied).status_code == 401
        assert client.post("/v1/admin/registration-invites", headers=supplied, json={"count": 2, "valid_days": 7}).status_code == 401
        assert client.patch(f"/v1/admin/users/{owner}/quota", headers=supplied, json={"daily_limit": 2}).status_code == 401
    for body in ({"count": 0, "valid_days": 7}, {"count": 101, "valid_days": 7},
                 {"count": 1, "valid_days": 0}, {"count": 1, "valid_days": 366},
                 {"count": True, "valid_days": 7}, {"count": 1, "valid_days": 7, "unknown": 1}):
        assert client.post("/v1/admin/registration-invites", headers=headers, json=body).status_code == 422
    response = client.post("/v1/admin/registration-invites", headers=headers, json={"count": 2, "valid_days": 7})
    assert response.status_code == 200, response.text
    created = response.json()["invitations"]
    assert len(created) == 2
    assert len({invite["id"] for invite in created}) == len({invite["code"] for invite in created}) == 2
    assert all(invite["code"] and invite["expires_at"] for invite in created)
    unavailable = store.accounts.create_invites(3, 7)["invitations"]
    with store.connection(write=True) as db:
        now = time.time()
        # Newer unavailable records must not occupy the first page of shareable invitations.
        db.execute("UPDATE registration_invites SET created_at=? WHERE id IN (?,?,?)",
                   (now + 10, *(invite["id"] for invite in unavailable)))
        db.execute("UPDATE registration_invites SET redeemed_at=?,redeemed_by=?,unused_plaintext=NULL WHERE id=?",
                   (now, owner, unavailable[0]["id"]))
        db.execute("UPDATE registration_invites SET expires_at=? WHERE id=?", (now - 1, unavailable[1]["id"]))
        db.execute("UPDATE registration_invites SET revoked_at=?,unused_plaintext=NULL WHERE id=?",
                   (now, unavailable[2]["id"]))
    response = client.get("/v1/admin/registration-invites?limit=1&offset=0", headers=headers)
    assert response.status_code == 200, response.text
    page = response.json()
    assert page["total"] == 2 and len(page["invitations"]) == 1
    second_page = client.get("/v1/admin/registration-invites?limit=1&offset=1", headers=headers).json()
    assert second_page["total"] == 2 and len(second_page["invitations"]) == 1
    assert {page["invitations"][0]["id"], second_page["invitations"][0]["id"]} == {invite["id"] for invite in created}
    listed = client.get("/v1/admin/registration-invites", headers=headers).json()["invitations"]
    assert {invite["id"]: invite["code"] for invite in listed} == {invite["id"]: invite["code"] for invite in created}
    assert "code_hash" not in response.text
    assert len(users(client, headers)) == 1  # Issuing an invite does not create a user or consume a seat.
