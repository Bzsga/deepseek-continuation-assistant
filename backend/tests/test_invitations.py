import time
from concurrent.futures import ThreadPoolExecutor

import pytest
from fastapi.testclient import TestClient

from app.admin import main
from app.config import Settings
from app.main import create_app
from app.store import DAY, Store, digest


@pytest.fixture
def settings(tmp_path):
    return Settings(db_path=str(tmp_path / "invitations.sqlite3"), worker_enabled=False)


def test_custom_invitation_redeems_with_zero_budget_and_hashed_credentials(settings):
    app = create_app(settings)
    with TestClient(app) as client:
        created_at = time.time()
        code = app.state.store.create_invitation(uses=100, user_limit_micros=0, days=365, code="bzsg")
        assert code == "bzsg"
        with app.state.store.connection() as db:
            invitation = db.execute("SELECT * FROM invitations").fetchone()
            assert invitation["code_hash"] == digest("bzsg")
            assert invitation["code_hash"] != "bzsg"
            assert invitation["remaining"] == 100
            assert invitation["user_limit"] == 0
            assert created_at + 365 * DAY <= invitation["expires_at"] <= time.time() + 365 * DAY
        response = client.post("/v1/invitations/redeem", json={"code": "bzsg"})
        assert response.status_code == 200
        token = response.json()["token"]
        assert len(token) >= 32 and token != code
        headers = {"Authorization": "Bearer " + token}
        quota = client.get("/v1/quota", headers=headers)
        assert quota.status_code == 200
        assert quota.json()["enabled"] is False
        assert quota.json()["remaining_cny"] == 0
        assert quota.json()["per_job_limit_cny"] == 0
        with app.state.store.connection() as db:
            assert db.execute("SELECT token_hash FROM users").fetchone()[0] == digest(token)
            assert db.execute("SELECT remaining FROM invitations").fetchone()[0] == 99


def test_repeated_custom_configuration_preserves_invitation_and_existing_user(settings):
    store = Store(settings)
    store.initialize()
    store.create_invitation(uses=100, user_limit_micros=0, days=365, code="bzsg")
    token, _ = store.redeem("bzsg")
    owner_id = store.authenticate(token)
    with store.connection(write=True) as db:
        db.execute("UPDATE users SET budget_limit=1234 WHERE id=?", (owner_id,))
        before = dict(db.execute("SELECT * FROM invitations WHERE code_hash=?", (digest("bzsg"),)).fetchone())
    assert store.create_invitation(uses=999, user_limit_micros=999999, days=1, code="bzsg") == "bzsg"
    assert store.create_invitation(code="bzsg") == "bzsg"
    with store.connection() as db:
        after = dict(db.execute("SELECT * FROM invitations WHERE code_hash=?", (digest("bzsg"),)).fetchone())
        assert before == after
        assert after["remaining"] == 99
        assert db.execute("SELECT budget_limit FROM users WHERE id=?", (owner_id,)).fetchone()[0] == 1234


def test_simultaneous_custom_configuration_creates_one_invitation(settings):
    store = Store(settings)
    store.initialize()
    with ThreadPoolExecutor(max_workers=6) as pool:
        results = list(pool.map(lambda _: store.create_invitation(100, 0, 365, "bzsg"), range(12)))
    assert results == ["bzsg"] * 12
    with store.connection() as db:
        assert db.execute("SELECT COUNT(*) FROM invitations").fetchone()[0] == 1
        assert db.execute("SELECT remaining FROM invitations").fetchone()[0] == 100


@pytest.mark.parametrize("code", ["", "abc", "bad code", "bad\ncode", "中文邀请码"])
def test_invalid_custom_invitation_rejected_before_storage(settings, code):
    app = create_app(settings)
    with TestClient(app) as client:
        with pytest.raises(ValueError):
            app.state.store.create_invitation(100, 0, 365, code)
        assert client.post("/v1/invitations/redeem", json={"code": code}).status_code == 422
        with app.state.store.connection() as db:
            assert db.execute("SELECT COUNT(*) FROM invitations").fetchone()[0] == 0


def test_admin_custom_invitation_is_idempotent(settings, monkeypatch, capsys):
    monkeypatch.setattr(Settings, "from_env", classmethod(lambda cls: settings))
    monkeypatch.setattr("sys.argv", ["admin", "invite", "--code", "bzsg", "--uses", "100", "--days", "365", "--user-budget", "0"])
    main()
    assert capsys.readouterr().out.strip() == "bzsg"
    monkeypatch.setattr("sys.argv", ["admin", "invite", "--code", "bzsg"])
    main()
    assert capsys.readouterr().out.strip() == "bzsg"
    store = Store(settings)
    with store.connection() as db:
        row = db.execute("SELECT * FROM invitations WHERE code_hash=?", (digest("bzsg"),)).fetchone()
        assert row["remaining"] == 100
        assert row["user_limit"] == 0


def test_zero_budget_must_be_explicit_for_a_new_invitation(settings):
    store = Store(settings)
    store.initialize()
    with pytest.raises(ValueError):
        store.create_invitation(code="bzsg")
    with pytest.raises(ValueError):
        store.create_invitation(code="bzsg", user_limit_micros=-1)
    assert store.create_invitation(code="bzsg", user_limit_micros=0) == "bzsg"
