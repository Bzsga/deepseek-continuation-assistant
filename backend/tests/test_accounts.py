import json
import time
from concurrent.futures import ThreadPoolExecutor

import pytest

from app.accounts import Accounts, MAX_FAILED_LOGINS_PER_USER, PASSWORD_ITERATIONS, SESSION_SECONDS
from app.config import Settings
from app.store import DAY, ServiceError, Store, digest


PASSWORD = "synthetic password 123"


@pytest.fixture
def registered(tmp_path):
    store = Store(Settings(db_path=str(tmp_path / "accounts.sqlite3"), worker_enabled=False,
                           api_enabled=True, api_key="synthetic-provider-key", user_daily_limit=5))
    store.initialize()
    accounts = getattr(store, "accounts", None) or Accounts(store)
    accounts.initialize()
    return store, accounts


def failure(call):
    with pytest.raises(ServiceError) as caught:
        call()
    return caught.value.status


def test_register_login_normalization_private_hashes_and_independent_owner(registered):
    store, accounts = registered
    legacy_token, _ = store.create_session()
    legacy_owner = store.authenticate(legacy_token)
    first = accounts.register("  Ａlice_测试  ", PASSWORD, invitation_code="  ")
    owner = first["user"]["id"]
    assert owner != legacy_owner
    assert first["user"]["username"] == "Alice_测试"
    assert first["user"]["registration_kind"] == "public"
    assert first["quota"]["user_daily_limit"] == 5
    assert first["quota"]["user_daily_remaining"] == 5
    assert accounts.authenticate(first["token"]) == owner
    assert accounts.is_session_active_hash(digest(first["token"]))
    login = accounts.login("alice_测试", PASSWORD)
    assert login["user"] == first["user"]
    assert login["token"] != first["token"]
    assert accounts.me(owner)["user"] == first["user"]
    assert failure(lambda: accounts.register("ALICE_测试", PASSWORD)) == 409
    with store.connection() as db:
        account = db.execute("SELECT * FROM registered_accounts WHERE user_id=?", (owner,)).fetchone()
        session = db.execute("SELECT * FROM account_sessions WHERE token_hash=?", (digest(first["token"]),)).fetchone()
        legacy = db.execute("SELECT token_hash FROM users WHERE id=?", (owner,)).fetchone()[0]
        assert account["password_hash"].startswith(f"pbkdf2_sha256${PASSWORD_ITERATIONS}$")
        assert PASSWORD not in account["password_hash"]
        assert account["daily_limit_override"] is None
        assert session["expires_at"] - session["created_at"] == SESSION_SECONDS
        assert session["token_hash"] != first["token"]
        assert legacy != digest(first["token"])
    public = json.dumps(accounts.me(owner), ensure_ascii=False)
    for secret in (PASSWORD, first["token"], login["token"], account["password_hash"], account["username_key"]):
        assert secret not in public
    assert accounts.public_registered_count() == 1


def test_logout_and_expiry_revoke_current_session_without_revoking_new_session(registered):
    store, accounts = registered
    session = accounts.register("session_test", PASSWORD)
    token, owner = session["token"], session["user"]["id"]
    accounts.logout(token)
    assert accounts.authenticate(token) is None
    assert not accounts.is_session_active_hash(digest(token))
    accounts.logout(token)  # Idempotent, and does not touch a newer session.
    fresh = accounts.login("session_test", PASSWORD)
    assert accounts.authenticate(fresh["token"]) == owner
    restarted = Accounts(store)
    restarted.initialize()
    assert restarted.authenticate(token) is None
    assert restarted.authenticate(fresh["token"]) == owner
    with store.connection(write=True) as db:
        db.execute("UPDATE account_sessions SET expires_at=? WHERE token_hash=?", (time.time() - 10, digest(fresh["token"])))
    assert restarted.authenticate(fresh["token"]) is None
    assert not restarted.is_session_active_hash(digest(fresh["token"]))
    with store.connection(write=True) as db:
        db.execute("UPDATE account_sessions SET expires_at=? WHERE token_hash=?", (time.time() - DAY - 1, digest(fresh["token"])))
    assert restarted.authenticate(fresh["token"]) is None
    assert restarted.authenticate("not-a-session") is None


def test_last_public_registration_slot_is_atomic_and_invites_bypass_it(registered):
    store, accounts = registered
    store.update_config({"public_registration_limit": 1})

    def register(index):
        try:
            return accounts.register(f"concurrent_{index}", PASSWORD, client_ip=f"192.0.2.{index}")
        except ServiceError as error:
            return error.status

    with ThreadPoolExecutor(max_workers=4) as pool:
        results = list(pool.map(register, range(4)))
    assert sum(isinstance(item, dict) for item in results) == 1
    assert results.count(403) == 3
    assert accounts.public_registered_count() == 1
    invite = accounts.create_invites(1, 7)["invitations"][0]
    invited = accounts.register("invited_after_full", PASSWORD, invite["code"])
    assert invited["user"]["registration_kind"] == "invitation"
    assert invited["quota"]["user_daily_limit"] == 5
    assert accounts.public_registered_count() == 1
    store.update_config({"public_registration_limit": 0})
    assert failure(lambda: accounts.register("closed_registration", PASSWORD)) == 403
    store.update_config({"public_registration_limit": 2})
    accounts.register("reopened_registration", PASSWORD)
    assert accounts.public_registered_count() == 2


def test_one_invitation_wins_once_and_plaintext_is_cleared_after_use(registered):
    store, accounts = registered
    store.update_config({"public_registration_limit": 0})
    invitation = accounts.create_invites(1, 7)["invitations"][0]
    assert accounts.list_invites()["invitations"][0]["code"] == invitation["code"]

    def redeem(index):
        try:
            return accounts.register(f"invite_race_{index}", PASSWORD, invitation["code"], client_ip=f"198.51.100.{index}")
        except ServiceError as error:
            return error.status

    with ThreadPoolExecutor(max_workers=4) as pool:
        results = list(pool.map(redeem, range(4)))
    winners = [result for result in results if isinstance(result, dict)]
    assert len(winners) == 1
    assert results.count(400) == 3
    assert accounts.public_registered_count() == 0
    assert accounts.list_invites()["invitations"] == []
    assert accounts.list_invites()["total"] == 0
    with store.connection() as db:
        row = db.execute("SELECT * FROM registration_invites WHERE id=?", (invitation["id"],)).fetchone()
        assert row["code_hash"] == digest(invitation["code"])
        assert row["unused_plaintext"] is None
        assert row["redeemed_by"] == winners[0]["user"]["id"]


def test_failed_registration_rolls_back_counter_owner_and_invitation(registered, monkeypatch):
    store, accounts = registered
    invitation = accounts.create_invites(1, 7)["invitations"][0]
    original_session = accounts._session

    def failed_session(*args):
        raise RuntimeError("synthetic session failure")

    monkeypatch.setattr(accounts, "_session", failed_session)
    with pytest.raises(RuntimeError, match="synthetic session failure"):
        accounts.register("rollback_public", PASSWORD)
    with pytest.raises(RuntimeError, match="synthetic session failure"):
        accounts.register("rollback_invited", PASSWORD, invitation["code"])
    assert accounts.public_registered_count() == 0
    with store.connection() as db:
        assert db.execute("SELECT COUNT(*) FROM registered_accounts").fetchone()[0] == 0
        assert db.execute("SELECT COUNT(*) FROM users").fetchone()[0] == 0
        assert db.execute("SELECT redeemed_at FROM registration_invites").fetchone()[0] is None
    monkeypatch.setattr(accounts, "_session", original_session)
    accounts.register("rollback_invited", PASSWORD, invitation["code"])
    assert accounts.public_registered_count() == 0


def test_duplicate_username_does_not_consume_invite_and_revoked_or_expired_codes_fail(registered):
    store, accounts = registered
    accounts.register("existing_user", PASSWORD)
    invitations = accounts.create_invites(3, 7)["invitations"]
    assert failure(lambda: accounts.register("EXISTING_USER", PASSWORD, invitations[0]["code"])) == 409
    accounts.register("new_invited_user", PASSWORD, invitations[0]["code"])
    accounts.revoke_invite(invitations[1]["id"])
    assert failure(lambda: accounts.register("revoked_user", PASSWORD, invitations[1]["code"])) == 400
    with store.connection(write=True) as db:
        db.execute("UPDATE registration_invites SET expires_at=? WHERE id=?", (time.time() - 1, invitations[2]["id"]))
    assert failure(lambda: accounts.register("expired_user", PASSWORD, invitations[2]["code"])) == 400
    listed = {item["id"]: item for item in accounts.list_invites()["invitations"]}
    assert listed == {}
    accounts.initialize()
    with store.connection() as db:
        assert db.execute("SELECT COUNT(*) FROM registration_invites WHERE unused_plaintext IS NOT NULL").fetchone()[0] == 0
    assert accounts.public_registered_count() == 1


def test_failed_login_throttle_persists_and_checks_unknown_names_equally(registered):
    store, accounts = registered
    accounts.register("login_limit", PASSWORD)
    for _ in range(MAX_FAILED_LOGINS_PER_USER):
        assert failure(lambda: accounts.login("login_limit", "wrong password", client_ip="203.0.113.1")) == 401
    restarted = Accounts(store)
    restarted.initialize()
    assert failure(lambda: restarted.login("login_limit", PASSWORD, client_ip="203.0.113.2")) == 429
    assert failure(lambda: restarted.login("unknown_login", "wrong password", client_ip="203.0.113.3")) == 401
    with store.connection(write=True) as db:
        db.execute("UPDATE account_auth_attempts SET created_at=?", (time.time() - 3601,))
    assert restarted.login("LOGIN_LIMIT", PASSWORD)["user"]["username"] == "login_limit"


@pytest.mark.parametrize("username,password", [("aa", PASSWORD), ("contains space", PASSWORD),
                                             ("valid_name", "short"), ("valid_name", "x" * 129),
                                             ("valid_name", "x" * 8 + "\ud800")])
def test_invalid_credentials_do_not_create_owners(registered, username, password):
    store, accounts = registered
    assert failure(lambda: accounts.register(username, password)) == 422
    assert accounts.public_registered_count() == 0
    with store.connection() as db:
        assert db.execute("SELECT COUNT(*) FROM users").fetchone()[0] == 0
