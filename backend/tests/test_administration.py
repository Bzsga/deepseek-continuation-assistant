import base64
import hashlib
import json
import secrets
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
from datetime import datetime

import pytest
from fastapi import Depends
from fastapi.testclient import TestClient

from app.admin_access import LOGIN_WINDOW_SECONDS, PASSWORD_ITERATIONS, SESSION_SECONDS, admin_authorizer
from app.config import Settings
from app.main import create_app
from app.store import SHANGHAI, digest, local_day


def password_hash(password):
    salt = secrets.token_bytes(16)
    derived = hashlib.pbkdf2_hmac("sha256", password.encode(), salt, PASSWORD_ITERATIONS)
    encode = lambda value: base64.urlsafe_b64encode(value).decode().rstrip("=")
    return f"pbkdf2_sha256${PASSWORD_ITERATIONS}${encode(salt)}${encode(derived)}"


@pytest.fixture
def admin_settings(tmp_path):
    password = secrets.token_urlsafe(32)
    settings = Settings(
        db_path=str(tmp_path / "administration.sqlite3"), worker_enabled=False,
        api_enabled=True, api_key=secrets.token_urlsafe(32), public_enabled=True,
        daily_limit=50, user_daily_limit=5, admin_password_hash=password_hash(password),
    )
    return settings, password


def login(client, password, **kwargs):
    response = client.post("/v1/admin/login", json={"password": password}, **kwargs)
    assert response.status_code == 200, response.text
    return {"Authorization": "Bearer " + response.json()["token"]}


def test_login_session_hashed_and_admin_endpoints_protected(admin_settings):
    settings, password = admin_settings
    app = create_app(settings)
    with TestClient(app) as client:
        for path in ("config", "stats", "users"):
            assert client.get(f"/v1/admin/{path}").status_code == 401
        assert client.patch("/v1/admin/config", json={"daily_limit": 40}).status_code == 401
        assert client.post("/v1/admin/login", json={"password": secrets.token_urlsafe(32)}).status_code == 401
        before = time.time()
        response = client.post("/v1/admin/login", json={"password": password})
        assert response.status_code == 200
        assert response.headers["cache-control"] == "no-store"
        token = response.json()["token"]
        expires_at = datetime.fromisoformat(response.json()["expires_at"])
        assert expires_at.tzinfo is not None
        assert before + SESSION_SECONDS <= expires_at.timestamp() <= time.time() + SESSION_SECONDS
        with app.state.store.connection() as db:
            session = db.execute("SELECT * FROM admin_sessions").fetchone()
            assert session["token_hash"] == digest(token)
            assert session["token_hash"] != token
            assert session["expires_at"] - session["created_at"] == SESSION_SECONDS
            assert db.execute("SELECT COUNT(*) FROM admin_login_attempts").fetchone()[0] == 1
        headers = {"Authorization": "Bearer " + token}
        config = client.get("/v1/admin/config", headers=headers)
        assert config.status_code == 200
        assert config.headers["cache-control"] == "no-store"
        for value in (password, settings.admin_password_hash, settings.api_key, token):
            assert value not in config.text
        assert client.get("/v1/quota", headers=headers).status_code == 401
        user_token, _ = app.state.store.create_session()
        assert client.get("/v1/admin/config", headers={"Authorization": "Bearer " + user_token}).status_code == 401


def test_sessions_survive_restart_and_expire(admin_settings):
    settings, password = admin_settings
    app = create_app(settings)
    with TestClient(app) as client:
        headers = login(client, password)
    restarted = create_app(settings)
    with TestClient(restarted) as client:
        assert client.get("/v1/admin/config", headers=headers).status_code == 200
        with restarted.state.store.connection(write=True) as db:
            db.execute("UPDATE admin_sessions SET expires_at=?", (time.time() - 1,))
        assert client.get("/v1/admin/config", headers=headers).status_code == 401


def test_static_admin_token_compatibility_and_unconfigured_response(admin_settings):
    settings, _ = admin_settings
    static_token = secrets.token_urlsafe(32)
    app = create_app(replace(settings, admin_password_hash="", admin_token=static_token))
    with TestClient(app) as client:
        headers = {"Authorization": "Bearer " + static_token}
        assert client.get("/v1/admin/config", headers=headers).status_code == 200
        assert client.post("/v1/admin/login", json={"password": secrets.token_urlsafe(32)}).status_code == 503
    app = create_app(replace(settings, admin_password_hash="", admin_token=""))
    with TestClient(app) as client:
        assert client.get("/v1/admin/config").status_code == 503
        assert client.post("/v1/admin/login", json={"password": secrets.token_urlsafe(32)}).status_code == 503


def test_failed_login_limit_is_persistent_and_expires(admin_settings):
    settings, password = admin_settings
    wrong = secrets.token_urlsafe(32)
    app = create_app(settings)
    with TestClient(app) as client:
        login(client, password)
        for _ in range(5):
            assert client.post("/v1/admin/login", json={"password": wrong}).status_code == 401
        assert client.post("/v1/admin/login", json={"password": password}).status_code == 429
    restarted = create_app(settings)
    with TestClient(restarted) as client:
        assert client.post("/v1/admin/login", json={"password": password}).status_code == 429
        with restarted.state.store.connection(write=True) as db:
            rows = db.execute("SELECT * FROM admin_login_attempts").fetchall()
            assert len(rows) == 5
            assert all(len(row["ip_hash"]) == 64 for row in rows)
            db.execute("UPDATE admin_login_attempts SET created_at=?", (time.time() - LOGIN_WINDOW_SECONDS - 1,))
        login(client, password)
        with restarted.state.store.connection() as db:
            assert db.execute("SELECT COUNT(*) FROM admin_login_attempts").fetchone()[0] == 0


def test_concurrent_failures_cannot_bypass_login_limit(admin_settings):
    settings, _ = admin_settings
    wrong = secrets.token_urlsafe(32)
    app = create_app(settings)
    with TestClient(app) as client:
        with ThreadPoolExecutor(max_workers=8) as pool:
            responses = list(pool.map(lambda _: client.post("/v1/admin/login", json={"password": wrong}), range(8)))
        statuses = [response.status_code for response in responses]
        assert statuses.count(401) == 5
        assert statuses.count(429) == 3
        with app.state.store.connection() as db:
            assert db.execute("SELECT COUNT(*) FROM admin_login_attempts").fetchone()[0] == 5


def test_forwarded_ip_only_trusted_from_loopback(admin_settings):
    settings, password = admin_settings
    wrong = secrets.token_urlsafe(32)
    app = create_app(settings)
    with TestClient(app, client=("198.51.100.8", 12345)) as client:
        for index in range(5):
            response = client.post("/v1/admin/login", json={"password": wrong}, headers={"X-Real-IP": f"192.0.2.{index + 1}"})
            assert response.status_code == 401
        assert client.post("/v1/admin/login", json={"password": password}, headers={"X-Real-IP": "192.0.2.100"}).status_code == 429
    with TestClient(app, client=("127.0.0.1", 12345)) as client:
        for _ in range(5):
            assert client.post("/v1/admin/login", json={"password": wrong}, headers={"X-Real-IP": "203.0.113.8"}).status_code == 401
        assert client.post("/v1/admin/login", json={"password": password}, headers={"X-Real-IP": "203.0.113.8"}).status_code == 429
        login(client, password, headers={"X-Real-IP": "203.0.113.9"})


def test_admin_config_patch_keeps_secrets_private(admin_settings):
    settings, password = admin_settings
    app = create_app(settings)
    with TestClient(app) as client:
        headers = login(client, password)
        response = client.patch("/v1/admin/config", headers=headers, json={"daily_limit": 40, "user_daily_limit": 4})
        assert response.status_code == 200
        current = client.get("/v1/admin/config", headers=headers).json()
        assert current["daily_limit"] == 40
        assert current["user_daily_limit"] == 4
        replacement_key = secrets.token_urlsafe(32)
        response = client.patch("/v1/admin/config", headers=headers, json={"api_key": replacement_key})
        assert response.status_code == 200
        assert response.json()["api_key_configured"] is True
        assert "api_key" not in response.json()
        assert replacement_key not in response.text
        assert app.state.store.current_settings().api_key == replacement_key
        for field in ("admin_password_hash", "admin_token"):
            response = client.patch("/v1/admin/config", headers=headers, json={field: secrets.token_urlsafe(32)})
            assert response.status_code == 422
        assert client.patch("/v1/admin/config", headers=headers, json={"daily_limit": -1}).status_code == 422


def test_invalid_password_hash_is_unconfigured(admin_settings):
    settings, _ = admin_settings
    app = create_app(replace(settings, admin_password_hash=secrets.token_urlsafe(32)))
    with TestClient(app) as client:
        response = client.post("/v1/admin/login", json={"password": secrets.token_urlsafe(32)})
        assert response.status_code == 503
        with app.state.store.connection() as db:
            assert db.execute("SELECT COUNT(*) FROM admin_login_attempts").fetchone()[0] == 0


def add_user(db, created_at, access_mode="invitation", last_seen=None):
    user_id = str(uuid.uuid4())
    db.execute("INSERT INTO users(id,token_hash,budget_limit,created_at,access_mode,last_seen) VALUES(?,?,?,?,?,?)",
               (user_id, digest(secrets.token_urlsafe(32)), 0, created_at, access_mode, last_seen))
    return user_id


def add_job(db, owner_id, created_at, status, count_state, count_day, input_tokens=0, output_tokens=0, usage_known=False):
    job_id = str(uuid.uuid4())
    db.execute("""INSERT INTO jobs(id,owner_id,idempotency_key,fingerprint,status,message,payload,entries,provider,
        created_at,month,count_state,count_day,input_tokens,output_tokens,usage_known) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
        (job_id, owner_id, job_id, digest(job_id), status, "private message sentinel", "private transcript sentinel",
         "private result sentinel", json.dumps({"mock_mode": False}), created_at,
         datetime.fromtimestamp(created_at, SHANGHAI).strftime("%Y-%m"), count_state, count_day,
         input_tokens, output_tokens, int(usage_known)))


def populated_stats(store):
    today_date = datetime.now(SHANGHAI).date()
    today = datetime(today_date.year, today_date.month, today_date.day, 12, tzinfo=SHANGHAI).timestamp()
    yesterday, older = today - 86400, today - 3 * 86400
    with store.connection(write=True) as db:
        first = add_user(db, today, last_seen=today)
        second = add_user(db, yesterday, access_mode="public", last_seen=yesterday)
        third = add_user(db, older)
        db.executemany("INSERT INTO user_activity VALUES(?,?,?)", [
            (first, local_day(today), today), (first, local_day(yesterday), yesterday),
            (second, local_day(yesterday), yesterday),
        ])
        add_job(db, first, yesterday, "completed", "used", local_day(today), 10, 4, usage_known=True)
        add_job(db, first, today, "cancelled", "none", local_day(today))
        add_job(db, second, older, "queued", "reserved", local_day(yesterday))
        add_job(db, second, today, "running", "used", local_day(today))
        add_job(db, third, older, "failed", "used", local_day(older), 20, 5, usage_known=True)
    return first, second, third


def test_stats_daily_activity_usage_and_current_queue(admin_settings):
    settings, password = admin_settings
    app = create_app(settings)
    with TestClient(app) as client:
        headers = login(client, password)
        populated_stats(app.state.store)
        response = client.get("/v1/admin/stats?days=2", headers=headers)
        assert response.status_code == 200
        result = response.json()
        assert result["timezone"] == "Asia/Shanghai"
        assert result["days"] == 2
        assert result["end_date"] == local_day()
        assert result["generated_at"].endswith("+08:00")
        assert result["totals"] == {"users": 3, "new_users": 2, "active_users": 2, "requests": 2,
            "usage_known_requests": 1, "usage_unknown_requests": 1,
            "input_tokens": 10, "output_tokens": 4, "completed": 1, "failed": 0, "uncertain": 0,
            "cancelled": 1, "queued": 1, "running": 1}
        assert result["today"]["daily_limit"] == 50
        assert result["today"]["daily_used"] == 2
        assert result["today"]["daily_reserved"] == 1
        assert result["today"]["daily_remaining"] == 47
        previous, current = result["daily"]
        assert previous["active_users"] == 2 and current["active_users"] == 1
        assert previous["requests"] == 0 and current["requests"] == 2
        assert previous["usage_known_requests"] == 0 and current["usage_known_requests"] == 1
        assert previous["usage_unknown_requests"] == 0 and current["usage_unknown_requests"] == 1
        assert previous["completed"] == 1 and current["completed"] == 0
        assert previous["cancelled"] == 0 and current["cancelled"] == 1
        assert "private" not in response.text
        assert all(type(value) is int for value in result["totals"].values())
        for day in result["daily"]:
            assert all(type(value) is int for key, value in day.items() if key != "date")
        for days in (0, 91):
            assert client.get(f"/v1/admin/stats?days={days}", headers=headers).status_code == 422


def test_user_list_pagination_effective_access_and_private_data_exclusion(admin_settings):
    settings, password = admin_settings
    app = create_app(settings)
    with TestClient(app) as client:
        headers = login(client, password)
        first, second, third = populated_stats(app.state.store)
        response = client.get("/v1/admin/users?limit=2&offset=0", headers=headers)
        assert response.status_code == 200
        result = response.json()
        assert result["total"] == 3 and result["limit"] == 2 and result["offset"] == 0
        assert [user["id"] for user in result["users"]] == [first, second]
        one, two = result["users"]
        assert one["access_mode"] == "public"
        assert one["daily_used"] == 1 and one["daily_reserved"] == 0 and one["daily_remaining"] == 4
        assert one["total_requests"] == 1 and one["completed"] == 1 and one["cancelled"] == 1
        assert one["usage_known_requests"] == 1 and one["usage_unknown_requests"] == 0
        assert one["input_tokens"] == 10 and one["output_tokens"] == 4
        assert two["daily_used"] == 1 and two["daily_reserved"] == 1 and two["daily_remaining"] == 3
        assert two["usage_known_requests"] == 0 and two["usage_unknown_requests"] == 1
        page_two = client.get("/v1/admin/users?limit=2&offset=2", headers=headers).json()["users"]
        assert len(page_two) == 1 and page_two[0]["id"] == third
        assert page_two[0]["last_seen_at"] is None
        assert page_two[0]["failed"] == 1 and page_two[0]["total_requests"] == 1
        assert page_two[0]["input_tokens"] == 20
        assert "private" not in response.text
        assert "token_hash" not in response.text and "budget_limit" not in response.text
        assert client.patch("/v1/admin/config", headers=headers, json={"public_enabled": False}).status_code == 200
        users = client.get("/v1/admin/users", headers=headers).json()["users"]
        assert users[0]["access_mode"] == "invitation" and users[1]["access_mode"] == "public"
        for query in ("limit=0", "limit=201", "offset=-1"):
            assert client.get("/v1/admin/users?" + query, headers=headers).status_code == 422


def test_shared_admin_authorizer_preserves_session_static_token_and_user_isolation(admin_settings):
    settings, password = admin_settings
    static_token = secrets.token_urlsafe(32)
    app = create_app(replace(settings, admin_token=static_token))

    @app.get("/test/shared-admin", dependencies=[Depends(admin_authorizer(app.state.store))])
    def shared_admin_route():
        return {"ok": True}

    with TestClient(app) as client:
        assert client.get("/test/shared-admin").status_code == 401
        user_token, _ = app.state.store.create_session()
        assert client.get("/test/shared-admin", headers={"Authorization": "Bearer " + user_token}).status_code == 401
        headers = login(client, password)
        session_response = client.get("/test/shared-admin", headers=headers)
        assert session_response.status_code == 200 and session_response.json() == {"ok": True}
        assert session_response.headers["cache-control"] == "no-store"
        static_headers = {"Authorization": "Bearer " + static_token}
        assert client.get("/test/shared-admin", headers=static_headers).status_code == 200
        with app.state.store.connection(write=True) as db:
            db.execute("UPDATE admin_sessions SET expires_at=?", (time.time() - 1,))
        assert client.get("/test/shared-admin", headers=headers).status_code == 401
        assert client.get("/test/shared-admin", headers=static_headers).status_code == 200


def test_shared_admin_authorizer_unconfigured_is_unavailable(admin_settings):
    settings, _ = admin_settings
    app = create_app(replace(settings, admin_password_hash="", admin_token=""))

    @app.get("/test/shared-admin", dependencies=[Depends(admin_authorizer(app.state.store))])
    def shared_admin_route():
        return {"ok": True}

    with TestClient(app) as client:
        assert client.get("/test/shared-admin").status_code == 503


def test_known_and_unknown_usage_counts_exclude_unverified_tokens(admin_settings):
    settings, password = admin_settings
    app = create_app(settings)
    with TestClient(app) as client:
        headers = login(client, password)
        now = time.time()
        with app.state.store.connection(write=True) as db:
            owner = add_user(db, now)
            empty_owner = add_user(db, now - 1)
            add_job(db, owner, now, "completed", "used", local_day(now), 7, 3, usage_known=True)
            add_job(db, owner, now, "failed", "used", local_day(now), usage_known=True)
            add_job(db, owner, now, "uncertain", "used", local_day(now), 91, 42)
            add_job(db, owner, now, "running", "used", local_day(now))
            add_job(db, owner, now, "queued", "reserved", local_day(now))
            add_job(db, owner, now, "cancelled", "none", local_day(now))
            add_job(db, owner, now - 86400, "uncertain", "used", local_day(now - 86400), 9, 7)
        response = client.get("/v1/admin/stats?days=1", headers=headers)
        assert response.status_code == 200
        result = response.json()
        for bucket in (result["totals"], result["daily"][0]):
            assert bucket["requests"] == 4
            assert bucket["usage_known_requests"] == 2
            assert bucket["usage_unknown_requests"] == 2
            assert bucket["requests"] == bucket["usage_known_requests"] + bucket["usage_unknown_requests"]
            assert bucket["input_tokens"] == 7 and bucket["output_tokens"] == 3
            assert all(type(value) is int and value >= 0 for key, value in bucket.items() if key != "date")
        assert "private" not in response.text
        users_response = client.get("/v1/admin/users", headers=headers)
        users = {user["id"]: user for user in users_response.json()["users"]}
        assert users[owner]["total_requests"] == 5
        assert users[owner]["usage_known_requests"] == 2
        assert users[owner]["usage_unknown_requests"] == 3
        assert users[owner]["input_tokens"] == 7 and users[owner]["output_tokens"] == 3
        for field in ("total_requests", "usage_known_requests", "usage_unknown_requests", "input_tokens", "output_tokens"):
            assert users[empty_owner][field] == 0
            assert type(users[empty_owner][field]) is int
        assert "private" not in users_response.text
        assert "token_hash" not in users_response.text and "payload" not in users_response.text
