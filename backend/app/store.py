import hashlib
import json
import secrets
import sqlite3
import time
import uuid
from contextlib import contextmanager
from dataclasses import replace
from datetime import datetime, timedelta, timezone

from .config import Settings, money
from .provider import CURRENT_PROTOCOL_VERSION, prices_configured, snapshot_for
from .schemas import RedeemRequest


DAY = 86400
TERMINAL = {"completed", "failed", "uncertain", "cancelled"}
SHANGHAI = timezone(timedelta(hours=8), "Asia/Shanghai")
RUNTIME_FIELDS = {
    "public_enabled", "daily_limit", "user_daily_limit", "public_registration_limit", "model", "base_url", "api_key",
    "context_tokens", "max_output_tokens", "provider_timeout_seconds",
}

# Primary requests retain the historical jobs count. Attempt zero supplies its
# own usage when present; backups add independent counts and usage. Keep this
# as a CTE rather than a schema view so legacy column migrations stay possible.
PROVIDER_REQUEST_USAGE_SQL = """
    SELECT j.id AS job_id,j.owner_id,j.count_day,
        CASE WHEN a.id IS NULL THEN j.usage_known ELSE a.usage_known END AS usage_known,
        CASE WHEN a.id IS NULL THEN j.input_tokens ELSE a.input_tokens END AS input_tokens,
        CASE WHEN a.id IS NULL THEN j.output_tokens ELSE a.output_tokens END AS output_tokens
    FROM jobs AS j LEFT JOIN provider_attempts AS a ON a.job_id=j.id AND a.ordinal=0
    WHERE j.count_state='used'
    UNION ALL
    SELECT a.job_id,j.owner_id,a.count_day,a.usage_known,a.input_tokens,a.output_tokens
    FROM provider_attempts AS a JOIN jobs AS j ON j.id=a.job_id WHERE a.ordinal>0
"""


def local_day(timestamp=None):
    return datetime.fromtimestamp(time.time() if timestamp is None else timestamp, SHANGHAI).date().isoformat()


def reset_at(timestamp=None):
    now = datetime.fromtimestamp(time.time() if timestamp is None else timestamp, SHANGHAI)
    return (now.replace(hour=0, minute=0, second=0, microsecond=0) + timedelta(days=1)).isoformat()


def configuration_fingerprint(settings):
    value = {
        "base_url": settings.base_url, "model": settings.model, "mock_mode": settings.mock_mode,
        "api_key_hash": digest(settings.api_key), "context_tokens": settings.context_tokens,
        "max_output_tokens": settings.max_output_tokens,
        "input_price": str(settings.input_price_per_million),
        "output_price": str(settings.output_price_per_million),
    }
    routes = getattr(settings, "fallback_providers", ())
    order = getattr(settings, "provider_route_order", ())
    if routes or (order and tuple(order) != ("primary",)):
        value["fallback_providers"] = [{
            "id": route.id, "base_url": route.base_url, "model": route.model,
            "api_key_hash": digest(route.api_key), "timeout_seconds": route.timeout_seconds,
            "context_tokens": route.context_tokens, "max_output_tokens": route.max_output_tokens,
        } for route in routes]
        value["provider_route_order"] = list(order)
        value["fallback_slow_seconds"] = getattr(settings, "fallback_slow_seconds", 30)
        value["fallback_cooldown_seconds"] = getattr(settings, "fallback_cooldown_seconds", 300)
    return digest(json.dumps(value, sort_keys=True))


class ServiceError(Exception):
    def __init__(self, status, message):
        super().__init__(message)
        self.status = status
        self.message = message


def digest(secret):
    return hashlib.sha256(secret.encode("utf-8")).hexdigest()


class Store:
    def __init__(self, settings: Settings):
        self.settings = settings
        from .accounts import Accounts
        self.accounts = Accounts(self)

    @contextmanager
    def connection(self, write=False):
        connection = sqlite3.connect(self.settings.db_path, timeout=15, isolation_level=None)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys=ON")
        connection.execute("PRAGMA secure_delete=ON")
        try:
            if write:
                connection.execute("BEGIN IMMEDIATE")
            yield connection
            if write:
                connection.commit()
        except Exception:
            if write:
                connection.rollback()
            raise
        finally:
            connection.close()

    def initialize(self):
        from pathlib import Path
        Path(self.settings.db_path).resolve().parent.mkdir(parents=True, exist_ok=True)
        with self.connection() as db:
            db.execute("PRAGMA journal_mode=WAL")
            db.executescript("""
                CREATE TABLE IF NOT EXISTS invitations (
                    code_hash TEXT PRIMARY KEY, remaining INTEGER NOT NULL,
                    user_limit INTEGER NOT NULL, expires_at REAL NOT NULL
                );
                CREATE TABLE IF NOT EXISTS users (
                    id TEXT PRIMARY KEY, token_hash TEXT NOT NULL UNIQUE,
                    budget_limit INTEGER NOT NULL, created_at REAL NOT NULL
                );
                CREATE TABLE IF NOT EXISTS budget_caps (
                    name TEXT PRIMARY KEY, micros INTEGER NOT NULL CHECK(micros >= 0)
                );
                CREATE TABLE IF NOT EXISTS jobs (
                    id TEXT PRIMARY KEY, owner_id TEXT NOT NULL REFERENCES users(id),
                    idempotency_key TEXT NOT NULL, fingerprint TEXT NOT NULL,
                    status TEXT NOT NULL, message TEXT NOT NULL,
                    payload TEXT, entries TEXT, provider TEXT NOT NULL,
                    reserved INTEGER NOT NULL DEFAULT 0, cost INTEGER NOT NULL DEFAULT 0,
                    input_tokens INTEGER NOT NULL DEFAULT 0, output_tokens INTEGER NOT NULL DEFAULT 0,
                    created_at REAL NOT NULL, month TEXT NOT NULL,
                    terminal_at REAL, expires_at REAL, purged_at REAL,
                    cancel_requested INTEGER NOT NULL DEFAULT 0,
                    UNIQUE(owner_id, idempotency_key)
                );
                CREATE INDEX IF NOT EXISTS jobs_owner ON jobs(owner_id);
                CREATE INDEX IF NOT EXISTS jobs_month ON jobs(month);
                CREATE INDEX IF NOT EXISTS jobs_queue ON jobs(status, created_at);
                CREATE TABLE IF NOT EXISTS runtime_config (
                    id INTEGER PRIMARY KEY CHECK(id=1), data TEXT NOT NULL, version INTEGER NOT NULL
                );
                CREATE TABLE IF NOT EXISTS user_activity (
                    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                    day TEXT NOT NULL, last_seen REAL NOT NULL,
                    PRIMARY KEY(user_id, day)
                );
                CREATE TABLE IF NOT EXISTS provider_attempts (
                    id TEXT PRIMARY KEY, job_id TEXT NOT NULL REFERENCES jobs(id),
                    ordinal INTEGER NOT NULL CHECK(ordinal>=0), route_id TEXT NOT NULL,
                    base_url TEXT NOT NULL, model TEXT NOT NULL,
                    status TEXT NOT NULL, started_at REAL NOT NULL, finished_at REAL,
                    count_day TEXT NOT NULL, usage_known INTEGER NOT NULL DEFAULT 0,
                    input_tokens INTEGER NOT NULL DEFAULT 0, output_tokens INTEGER NOT NULL DEFAULT 0,
                    UNIQUE(job_id,ordinal), UNIQUE(job_id,route_id)
                );
                CREATE INDEX IF NOT EXISTS provider_attempts_daily ON provider_attempts(count_day,ordinal);
            """)
            db.execute("BEGIN IMMEDIATE")
            user_columns = {row["name"] for row in db.execute("PRAGMA table_info(users)")}
            if "access_mode" not in user_columns:
                db.execute("ALTER TABLE users ADD COLUMN access_mode TEXT NOT NULL DEFAULT 'invitation'")
            if "last_seen" not in user_columns:
                db.execute("ALTER TABLE users ADD COLUMN last_seen REAL")
            job_columns = {row["name"] for row in db.execute("PRAGMA table_info(jobs)")}
            for name, declaration in (
                ("count_state", "TEXT NOT NULL DEFAULT 'none'"),
                ("count_day", "TEXT NOT NULL DEFAULT ''"),
                ("cost_known", "INTEGER NOT NULL DEFAULT 1"),
                ("usage_known", "INTEGER NOT NULL DEFAULT 0"),
            ):
                if name not in job_columns:
                    db.execute(f"ALTER TABLE jobs ADD COLUMN {name} {declaration}")
            if "count_state" not in job_columns:
                # Old rows lack a dispatch marker. Conservatively count terminal
                # work which could have reached the provider, once only.
                db.execute("""UPDATE jobs SET count_day=strftime('%Y-%m-%d',created_at,'unixepoch','+8 hours'),
                    count_state=CASE WHEN status='queued' THEN 'reserved'
                    WHEN status IN ('running','completed','failed','uncertain')
                      OR input_tokens>0 OR output_tokens>0 OR cost>0 THEN 'used'
                    ELSE 'none' END""")
            if "usage_known" not in job_columns:
                db.execute("UPDATE jobs SET usage_known=1 WHERE input_tokens>0 OR output_tokens>0")
                db.execute("UPDATE jobs SET cost_known=0 WHERE usage_known=0 AND status IN ('running','uncertain')")
            db.execute("CREATE INDEX IF NOT EXISTS jobs_daily ON jobs(count_state,count_day,owner_id)")
            db.execute("INSERT OR IGNORE INTO runtime_config VALUES(1,'{}',0)")
            db.commit()
        self.accounts.initialize()
        with self.connection(write=True) as db:
            db.execute("""CREATE TABLE IF NOT EXISTS account_daily_caps (
                user_id TEXT NOT NULL REFERENCES registered_accounts(user_id), day TEXT NOT NULL,
                limit_total INTEGER NOT NULL CHECK(limit_total>=0), PRIMARY KEY(user_id,day)
            )""")

    def _settings(self, db):
        row = db.execute("SELECT data FROM runtime_config WHERE id=1").fetchone()
        return replace(self.settings, **json.loads(row["data"]))

    def current_settings(self):
        with self.connection() as db:
            return self._settings(db)

    def _admin_config(self, db):
        settings = self._settings(db)
        result = {name: getattr(settings, name) for name in RUNTIME_FIELDS if name != "api_key"}
        result.update({
            "api_key_configured": bool(settings.api_key), "api_enabled": settings.api_enabled,
            "mock_mode": settings.mock_mode, "cost_known": settings.cost_known,
            "config_version": db.execute("SELECT version FROM runtime_config WHERE id=1").fetchone()[0],
            "public_registered_count": self.accounts.public_registered_count(db),
        })
        return result

    def admin_config(self):
        with self.connection() as db:
            return self._admin_config(db)

    def _write_config(self, db, changes):
        if not set(changes).issubset(RUNTIME_FIELDS):
            raise ServiceError(422, "包含不可修改的管理配置字段")
        row = db.execute("SELECT data FROM runtime_config WHERE id=1").fetchone()
        overrides = json.loads(row["data"])
        updated = {**overrides, **changes}
        try:
            replace(self.settings, **updated).validate()
        except (ValueError, TypeError):
            raise ServiceError(422, "配置格式或范围不正确，请检查 HTTPS 地址、模型和 token 上限") from None
        if updated != overrides:
            db.execute("UPDATE runtime_config SET data=?,version=version+1 WHERE id=1", (json.dumps(updated),))

    def update_config(self, changes):
        with self.connection(write=True) as db:
            self._write_config(db, changes)
            return self._admin_config(db)

    def daily_quota(self, db, owner_id=None, settings=None, timestamp=None):
        settings = settings or self._settings(db)
        now = time.time() if timestamp is None else timestamp
        row = db.execute(f"""WITH provider_request_usage AS ({PROVIDER_REQUEST_USAGE_SQL}) SELECT COUNT(*) AS used,
            COALESCE(SUM(CASE WHEN owner_id=? THEN 1 ELSE 0 END),0) AS user_used
            FROM provider_request_usage WHERE count_day=?""", (owner_id, local_day(now))).fetchone()
        reserved = db.execute("""SELECT COUNT(*) AS reserved,
            COALESCE(SUM(CASE WHEN owner_id=? THEN 1 ELSE 0 END),0) AS user_reserved
            FROM jobs WHERE count_state='reserved'""", (owner_id,)).fetchone()
        result = {
            "daily_limit": settings.daily_limit, "daily_used": row["used"], "daily_reserved": reserved["reserved"],
            "daily_remaining": max(0, settings.daily_limit - row["used"] - reserved["reserved"]),
            "reset_at": reset_at(now),
        }
        if owner_id is not None:
            base_limit = settings.user_daily_limit
            account = db.execute("SELECT daily_limit_override FROM registered_accounts WHERE user_id=?", (owner_id,)).fetchone()
            if account is not None and account["daily_limit_override"] is not None:
                base_limit = account["daily_limit_override"]
            today = db.execute("SELECT limit_total FROM account_daily_caps WHERE user_id=? AND day=?",
                               (owner_id, local_day(now))).fetchone()
            effective_limit = today["limit_total"] if today is not None else base_limit
            result.update({
                "user_daily_limit": effective_limit, "user_daily_used": row["user_used"],
                "user_daily_reserved": reserved["user_reserved"],
                "user_daily_remaining": max(0, effective_limit - row["user_used"] - reserved["user_reserved"]),
            })
        return result

    def update_user_quota(self, owner_id, changes):
        if not changes or not set(changes).issubset({"daily_limit", "today_remaining", "day"}):
            raise ServiceError(422, "请填写每日上限或今日剩余次数")
        for name in ("daily_limit", "today_remaining"):
            if name in changes and not (name == "daily_limit" and changes[name] is None):
                if type(changes[name]) is not int or not 0 <= changes[name] <= 1_000_000:
                    raise ServiceError(422, "次数须为 0 到 1000000 的整数")
        if "day" in changes and "today_remaining" not in changes:
            raise ServiceError(422, "日期仅用于确认今日剩余次数的修改")
        with self.connection(write=True) as db:
            account = db.execute("SELECT user_id FROM registered_accounts WHERE user_id=?", (owner_id,)).fetchone()
            if account is None:
                raise ServiceError(404, "注册用户不存在")
            now = time.time()
            day = local_day(now)
            if changes.get("day", day) != day:
                raise ServiceError(409, "日期已变化，请刷新用户额度后重试")
            if "daily_limit" in changes:
                db.execute("UPDATE registered_accounts SET daily_limit_override=? WHERE user_id=?",
                           (changes["daily_limit"], owner_id))
            if "today_remaining" in changes:
                quota = self.daily_quota(db, owner_id, timestamp=now)
                limit_total = quota["user_daily_used"] + quota["user_daily_reserved"] + changes["today_remaining"]
                db.execute("""INSERT INTO account_daily_caps VALUES(?,?,?) ON CONFLICT(user_id,day)
                    DO UPDATE SET limit_total=excluded.limit_total""", (owner_id, day, limit_total))
            quota = self.daily_quota(db, owner_id, timestamp=now)
            override = db.execute("SELECT daily_limit_override FROM registered_accounts WHERE user_id=?", (owner_id,)).fetchone()[0]
            return {"id": owner_id, "daily_limit_override": override, "day": day,
                    "daily_limit": quota["user_daily_limit"], "daily_used": quota["user_daily_used"],
                    "daily_reserved": quota["user_daily_reserved"], "daily_remaining": quota["user_daily_remaining"],
                    "reset_at": quota["reset_at"]}

    def _touch_user(self, db, owner_id, timestamp):
        db.execute("UPDATE users SET last_seen=? WHERE id=?", (timestamp, owner_id))
        db.execute("""INSERT INTO user_activity VALUES(?,?,?)
            ON CONFLICT(user_id,day) DO UPDATE SET last_seen=excluded.last_seen""",
                   (owner_id, local_day(timestamp), timestamp))

    def create_session(self):
        token, user_id, now = secrets.token_urlsafe(32), str(uuid.uuid4()), time.time()
        with self.connection(write=True) as db:
            db.execute("INSERT INTO users(id,token_hash,budget_limit,created_at,access_mode) VALUES(?,?,0,?,'public')",
                       (user_id, digest(token), now))
            self._touch_user(db, user_id, now)
        return token, self.quota(user_id)

    def create_invitation(self, uses=1, user_limit_micros=None, days=7, code=None):
        if not 1 <= uses <= 1000 or not 1 <= days <= 365:
            raise ValueError("邀请码次数须为 1–1000，有效期须为 1–365 天")
        code = secrets.token_urlsafe(32) if code is None else RedeemRequest(code=code).code
        with self.connection(write=True) as db:
            if db.execute("SELECT 1 FROM invitations WHERE code_hash=?", (digest(code),)).fetchone():
                return code
            limit = user_limit_micros if user_limit_micros is not None else money(self.settings.user_budget_cny)
            if limit < 0 or (limit == 0 and user_limit_micros is None):
                raise ValueError("请配置用户额度；仅创建凭据时可显式指定 --user-budget 0")
            db.execute("INSERT INTO invitations VALUES (?, ?, ?, ?)", (digest(code), uses, limit, time.time() + days * DAY))
        return code

    def redeem(self, code):
        token, user_id = secrets.token_urlsafe(32), str(uuid.uuid4())
        with self.connection(write=True) as db:
            invite = db.execute("SELECT * FROM invitations WHERE code_hash=?", (digest(code),)).fetchone()
            if not invite or invite["remaining"] <= 0 or invite["expires_at"] <= time.time():
                raise ServiceError(400, "邀请码无效、已用完或已过期")
            db.execute("UPDATE invitations SET remaining=remaining-1 WHERE code_hash=?", (digest(code),))
            now = time.time()
            db.execute("INSERT INTO users(id,token_hash,budget_limit,created_at,access_mode) VALUES(?,?,?,?,'invitation')",
                       (user_id, digest(token), invite["user_limit"], now))
            self._touch_user(db, user_id, now)
        return token, self.quota(user_id)

    def authenticate(self, token):
        if not token or len(token) > 256:
            raise ServiceError(401, "请先连接整理服务以建立会话")
        with self.connection(write=True) as db:
            row = db.execute("SELECT id FROM users WHERE token_hash=?", (digest(token),)).fetchone()
        owner_id = row["id"] if row else self.accounts.authenticate(token)
        if owner_id is None:
            raise ServiceError(401, "会话凭据无效或已过期，请重新登录")
        with self.connection(write=True) as db:
            self._touch_user(db, owner_id, time.time())
        return owner_id

    def _caps(self, db, settings=None):
        settings = settings or self._settings(db)
        caps = {
            "global": money(settings.global_budget_cny),
            "monthly": money(settings.monthly_budget_cny),
            "user": money(settings.user_budget_cny),
            "per_job": money(settings.per_job_limit_cny),
        }
        caps.update({row["name"]: row["micros"] for row in db.execute("SELECT * FROM budget_caps")})
        return caps

    def _quota(self, db, owner_id, settings=None, timestamp=None):
        settings = settings or self._settings(db)
        now = time.time() if timestamp is None else timestamp
        caps = self._caps(db, settings)
        month = datetime.fromtimestamp(now, timezone.utc).strftime("%Y-%m")
        user = db.execute("SELECT * FROM users WHERE id=?", (owner_id,)).fetchone()
        access_mode = "public" if settings.public_enabled or user["access_mode"] == "public" else "invitation"
        daily = self.daily_quota(db, owner_id, settings, now)
        usage = db.execute("""SELECT COALESCE(SUM(cost+reserved),0) AS global_used,
            COALESCE(SUM(CASE WHEN month=? THEN cost+reserved ELSE 0 END),0) AS monthly_used,
            COALESCE(SUM(CASE WHEN owner_id=? THEN cost+reserved ELSE 0 END),0) AS user_used
            FROM jobs""", (month, owner_id)).fetchone()
        # A prior month's unresolved reservation may still settle this month.
        rollover = db.execute("SELECT COALESCE(SUM(reserved),0) FROM jobs WHERE month<>?", (month,)).fetchone()[0]
        remaining = min(caps["global"] - usage["global_used"],
                        caps["monthly"] - usage["monthly_used"] - rollover,
                        min(caps["user"], user["budget_limit"]) - usage["user_used"])
        if access_mode == "public":
            enabled = settings.public_enabled and settings.upstream_ready
            message = "免费整理可用" if enabled else "免费整理服务暂未开放，请稍后再试"
            remaining = 0
            per_job = 0
        else:
            enabled = settings.enabled and all(value > 0 for value in caps.values())
            message = "邀请码整理可用" if enabled else "邀请码整理尚未启用，管理员需配置服务、单价及金额预算"
            per_job = caps["per_job"]
        if settings.daily_limit == 0 or daily["user_daily_limit"] == 0:
            enabled = False
            message = "整理服务已暂停，请稍后再试"
        elif enabled and (daily["daily_remaining"] == 0 or daily["user_daily_remaining"] == 0):
            message = "今日整理次数已用完或已被排队任务预留，北京时间次日零点重置"
        if settings.mock_mode:
            message = "模拟模式，仅验证流程，不调用真实模型" if enabled else "模拟模式预算尚未配置完整"
        automatic_fallback = bool(getattr(settings, "fallback_providers", ())) and (
            access_mode == "public" and not settings.cost_known and not settings.mock_mode
        )
        return {"enabled": enabled, "remaining_cny": max(0, remaining) / 1_000_000,
                "per_job_limit_cny": per_job / 1_000_000,
                "model": settings.display_model, "mock_mode": settings.mock_mode,
                "access_mode": access_mode, "cost_known": settings.cost_known,
                "context_tokens": settings.context_tokens, "max_output_tokens": settings.max_output_tokens,
                "organization_protocol_version": CURRENT_PROTOCOL_VERSION,
                "political_filter_supported": True,
                "automatic_fallback_enabled": automatic_fallback,
                "max_attempts_per_chunk": 1 + len(settings.fallback_providers) if automatic_fallback else 1,
                "message": message, "remaining_micros": max(0, remaining), "per_job_micros": per_job,
                **daily}

    def quota(self, owner_id):
        with self.connection() as db:
            quota = self._quota(db, owner_id)
        return {key: value for key, value in quota.items() if not key.endswith("micros")}

    def create_job(self, owner_id, request, account_session_hash=None):
        messages = [message.model_dump() for message in request.messages]
        context_messages = [message.model_dump() for message in request.context_messages]
        # Keep legacy empty-context bytes and idempotency fingerprints unchanged.
        content = {"messages": messages, "context_messages": context_messages} if context_messages else messages
        if request.operation == "political_filter":
            content = {"operation": request.operation, "messages": messages}
        payload = json.dumps(content, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        fingerprint = digest(payload)
        with self.connection(write=True) as db:
            previous = db.execute("SELECT * FROM jobs WHERE owner_id=? AND idempotency_key=?", (owner_id, request.idempotency_key)).fetchone()
            if previous:
                if previous["fingerprint"] != fingerprint:
                    raise ServiceError(409, "此幂等编号已用于其他内容，请为新请求使用新编号")
                return self._view(previous)
            settings, now = self._settings(db), time.time()
            quota = self._quota(db, owner_id, settings, now)
            account = db.execute("SELECT user_id FROM registered_accounts WHERE user_id=?", (owner_id,)).fetchone()
            if quota["access_mode"] == "public" and account is None:
                raise ServiceError(401, "免费智能整理需要注册并登录，原任务仍可查询或取消")
            if account is not None and (not account_session_hash or
                    not self.accounts.is_session_active_hash(account_session_hash, db=db)):
                raise ServiceError(401, "登录已失效，请重新登录原账号后继续")
            if not quota["enabled"]:
                raise ServiceError(503, quota["message"])
            if quota["daily_remaining"] == 0 or quota["user_daily_remaining"] == 0:
                raise ServiceError(429, "今日整理次数不足，排队任务也占用次数，请在北京时间次日零点后重试")
            context_args = {"context_messages": context_messages} if context_messages else {}
            if request.operation != "organize":
                context_args["operation"] = request.operation
            snapshot, reserve = snapshot_for(settings, messages, **context_args)
            if not settings.cost_known:
                reserve = 0
            snapshot.update({"configuration_fingerprint": configuration_fingerprint(settings),
                             "access_mode": quota["access_mode"]})
            if account is not None and account_session_hash is not None:
                snapshot["account_session_hash"] = account_session_hash
            if snapshot["input_bound"] + snapshot["output_bound"] > settings.context_tokens:
                raise ServiceError(413, "该段内容超出模型上下文的保守上限，请缩短后重新提交")
            if quota["access_mode"] == "invitation":
                if reserve > quota["per_job_micros"]:
                    raise ServiceError(402, "该段费用预估超过单次上限，请缩短内容或联系管理员")
                if reserve > quota["remaining_micros"]:
                    raise ServiceError(402, "试用预算不足，已有任务的费用预留也计入限额")
            active = db.execute("SELECT COUNT(*), SUM(CASE WHEN owner_id=? THEN 1 ELSE 0 END) FROM jobs WHERE status IN ('queued','running')", (owner_id,)).fetchone()
            if active[0] >= 500 or (active[1] or 0) >= 20:
                raise ServiceError(429, "等待中的任务较多，请稍后再提交")
            job_id = str(uuid.uuid4())
            db.execute("""INSERT INTO jobs(id,owner_id,idempotency_key,fingerprint,status,message,payload,provider,
                reserved,created_at,month,count_state,count_day,cost_known) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                (job_id, owner_id, request.idempotency_key, fingerprint, "queued", "已预留一次整理，等待开始", payload,
                 json.dumps(snapshot), reserve, now, datetime.fromtimestamp(now, timezone.utc).strftime("%Y-%m"),
                 "reserved", local_day(now), 0))
            return self._view(db.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone())

    def _view(self, row):
        expired = row["purged_at"] is not None or (row["expires_at"] is not None and row["expires_at"] <= time.time())
        if expired:
            raise ServiceError(410, "此任务的原文和结果已超过 24 小时保留期，请查看本地已保存的结果；重新整理需显式创建新任务")
        return {"id": row["id"], "status": row["status"],
                "entries": json.loads(row["entries"]) if row["entries"] else [],
                "message": row["message"],
                "usage": {"input_tokens": row["input_tokens"], "output_tokens": row["output_tokens"]},
                "usage_known": bool(row["usage_known"]),
                "cost_cny": row["cost"] / 1_000_000,
                "cost_known": bool(row["cost_known"]),
                "reserved_cny": row["reserved"] / 1_000_000,
                "request_counted": row["count_state"] == "used",
                "cancel_requested": bool(row["cancel_requested"]),
                "mock_mode": json.loads(row["provider"])["mock_mode"], "content_expired": False}

    def get_job(self, owner_id, job_id):
        with self.connection() as db:
            row = db.execute("SELECT * FROM jobs WHERE id=? AND owner_id=?", (job_id, owner_id)).fetchone()
        if not row:
            raise ServiceError(404, "任务不存在")
        return self._view(row)

    def cancel(self, owner_id, job_id):
        now = time.time()
        with self.connection(write=True) as db:
            row = db.execute("SELECT * FROM jobs WHERE id=? AND owner_id=?", (job_id, owner_id)).fetchone()
            if not row:
                raise ServiceError(404, "任务不存在")
            if row["status"] == "queued":
                db.execute("UPDATE jobs SET status='cancelled',cancel_requested=1,reserved=0,count_state='none',message=?,terminal_at=?,expires_at=? WHERE id=?",
                           ("已取消，尚未发起模型调用", now, now + DAY, job_id))
            elif row["status"] == "running":
                db.execute("UPDATE jobs SET cancel_requested=1,message=? WHERE id=?", ("已停止后续处理，正在核对本次已发出的调用费用", job_id))
            elif row["status"] == "uncertain":
                db.execute("UPDATE jobs SET cancel_requested=1,message=? WHERE id=?", ("已停止处理，调用结果不确定，费用预留仍需管理员核对", job_id))
            return self._view(db.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone())

    def claim_next(self):
        with self.connection(write=True) as db:
            while True:
                row = db.execute("SELECT * FROM jobs WHERE status='queued' ORDER BY created_at,id LIMIT 1").fetchone()
                if not row:
                    return None
                settings, now = self._settings(db), time.time()
                snapshot = json.loads(row["provider"])
                quota = self._quota(db, row["owner_id"], settings, now)
                compatible = (
                    snapshot.get("configuration_fingerprint") == configuration_fingerprint(settings)
                    and snapshot.get("access_mode") == quota["access_mode"]
                )
                within_limits = (
                    quota["daily_used"] + quota["daily_reserved"] <= settings.daily_limit
                    and quota["user_daily_used"] + quota["user_daily_reserved"] <= quota["user_daily_limit"]
                )
                if not quota["enabled"] or not compatible or not within_limits:
                    db.execute("""UPDATE jobs SET status='failed',message=?,reserved=0,count_state='none',
                        terminal_at=?,expires_at=? WHERE id=?""",
                               ("服务配置或次数上限已改变，本段尚未调用模型，预留已释放", now, now + DAY, row["id"]))
                    continue
                # Commit the count before invoking any network operation. An
                # interrupted dispatch remains charged and is never replayed.
                db.execute("UPDATE jobs SET status='running',count_state='used',count_day=?,cost_known=0,usage_known=0,message=? WHERE id=?",
                           (local_day(now), "正在整理这一段内容", row["id"]))
                claimed = dict(db.execute("SELECT * FROM jobs WHERE id=?", (row["id"],)).fetchone())
                claimed["execution_settings"] = settings
                return claimed

    def begin_provider_attempt(self, job_id, route_metadata):
        """Commit one never-replayed dispatch before its network operation.

        Metadata contains only ordinal/id/base_url/model. Route credentials stay
        in the worker's immutable execution settings, never in this ledger.
        """
        if not isinstance(route_metadata, dict):
            return None
        ordinal = route_metadata.get("ordinal")
        route_id = route_metadata.get("id")
        if type(ordinal) is not int or ordinal < 0 or not isinstance(route_id, str):
            return None
        now = time.time()
        with self.connection(write=True) as db:
            row = db.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone()
            if row is None or row["status"] != "running" or row["cancel_requested"]:
                return None
            settings = self._settings(db)
            snapshot = json.loads(row["provider"])
            if snapshot.get("configuration_fingerprint") != configuration_fingerprint(settings):
                return None
            if route_metadata.get("configuration_fingerprint", snapshot["configuration_fingerprint"]) != snapshot["configuration_fingerprint"]:
                return None
            routes = {"primary": (settings.base_url, settings.model)}
            routes.update({route.id: (route.base_url, route.model) for route in getattr(settings, "fallback_providers", ())})
            if routes.get(route_id) != (route_metadata.get("base_url"), route_metadata.get("model")):
                return None
            if ordinal >= len(routes):
                return None
            previous = db.execute("SELECT ordinal,route_id FROM provider_attempts WHERE job_id=? ORDER BY ordinal", (job_id,)).fetchall()
            if ordinal != len(previous) or any(attempt["route_id"] == route_id for attempt in previous):
                return None
            quota = self._quota(db, row["owner_id"], settings, now)
            if not quota["enabled"] or snapshot.get("access_mode") != quota["access_mode"]:
                return None
            if (quota["daily_used"] + quota["daily_reserved"] > settings.daily_limit
                    or quota["user_daily_used"] + quota["user_daily_reserved"] > quota["user_daily_limit"]):
                return None
            # Automatic alternatives are limited to unpriced public count mode.
            # Historical monetary reservations retain their single-route path.
            if (ordinal > 0 or route_id != "primary") and (
                snapshot.get("access_mode") != "public" or prices_configured(snapshot) or settings.cost_known
            ):
                return None
            if ordinal > 0:
                session_hash = snapshot.get("account_session_hash")
                if (not session_hash or not self.accounts.is_session_active_hash(session_hash, db=db)):
                    return None
                if quota["daily_remaining"] <= 0 or quota["user_daily_remaining"] <= 0:
                    return None
            attempt_id = str(uuid.uuid4())
            db.execute("""INSERT INTO provider_attempts(id,job_id,ordinal,route_id,base_url,model,status,started_at,count_day)
                VALUES(?,?,?,?,?,?,'dispatched',?,?)""",
                       (attempt_id, job_id, ordinal, route_id, route_metadata["base_url"], route_metadata["model"], now,
                        row["count_day"] if ordinal == 0 else local_day(now)))
            return attempt_id

    def finish_provider_attempt(self, attempt_id, outcome, status_override=None):
        status = status_override if status_override is not None else outcome.status
        if status not in TERMINAL:
            raise ValueError("Invalid provider attempt status")
        input_tokens, output_tokens = outcome.input_tokens, outcome.output_tokens
        valid_usage = all(type(value) is int and 0 <= value <= 1_000_000_000 for value in (input_tokens, output_tokens))
        known = bool(outcome.usage_known) and valid_usage
        if not valid_usage:
            input_tokens = output_tokens = 0
        with self.connection(write=True) as db:
            db.execute("""UPDATE provider_attempts SET status=?,finished_at=?,usage_known=?,input_tokens=?,output_tokens=?
                WHERE id=? AND status='dispatched'""",
                       (status, time.time(), int(known), input_tokens, output_tokens, attempt_id))

    def provider_attempts(self, job_id):
        with self.connection() as db:
            return [dict(row) for row in db.execute("SELECT * FROM provider_attempts WHERE job_id=? ORDER BY ordinal", (job_id,))]

    def finish(self, job_id, outcome):
        now = time.time()
        with self.connection(write=True) as db:
            row = db.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone()
            if row["status"] != "running":
                return
            status, message, entries = outcome.status, outcome.message, outcome.entries
            if row["cancel_requested"] and status != "uncertain":
                status, message, entries = "cancelled", "已取消整理，已经发出的调用按返回用量结算", []
            pricing_known = prices_configured(json.loads(row["provider"]))
            reserved = max(row["reserved"], outcome.reservation_floor) if status == "uncertain" and pricing_known else 0
            cost_known = pricing_known and outcome.usage_known and status != "uncertain"
            cost = outcome.cost_micros if cost_known else 0
            if outcome.budget_violation:
                db.execute("INSERT INTO budget_caps VALUES('per_job',0) ON CONFLICT(name) DO UPDATE SET micros=0")
                self._write_config(db, {"public_enabled": False})
            db.execute("""UPDATE jobs SET status=?,message=?,entries=?,reserved=?,cost=?,input_tokens=?,output_tokens=?,
                usage_known=?,cost_known=?,terminal_at=?,expires_at=?,month=? WHERE id=?""",
                       (status, message, json.dumps(entries, ensure_ascii=False), reserved, cost,
                        outcome.input_tokens, outcome.output_tokens, int(outcome.usage_known), int(cost_known), now, now + DAY,
                        datetime.fromtimestamp(now, timezone.utc).strftime("%Y-%m"), job_id))

    def recover_running(self):
        now = time.time()
        with self.connection(write=True) as db:
            db.execute("UPDATE provider_attempts SET status='uncertain',finished_at=? WHERE status='dispatched'", (now,))
            db.execute("""UPDATE jobs SET status='uncertain',message=?,terminal_at=?,expires_at=? WHERE status='running'""",
                       ("服务曾中断，调用结果无法确认，费用预留已保留；不会自动重试", now, now + DAY))

    def purge_expired(self):
        now = time.time()
        with self.connection(write=True) as db:
            count = db.execute("UPDATE jobs SET payload=NULL,entries=NULL,purged_at=? WHERE expires_at<=? AND purged_at IS NULL", (now, now)).rowcount
            db.execute("DELETE FROM invitations WHERE expires_at<=?", (now,))
        if count:
            with self.connection() as db:
                db.execute("PRAGMA wal_checkpoint(TRUNCATE)")
        return count

    def set_cap(self, name, amount):
        if name not in {"global", "monthly", "user", "per_job"} or amount < 0:
            raise ValueError("无效预算名称或金额")
        with self.connection(write=True) as db:
            db.execute("INSERT INTO budget_caps VALUES(?,?) ON CONFLICT(name) DO UPDATE SET micros=excluded.micros", (name, amount))

    def reconcile(self, job_id, actual_cost):
        with self.connection(write=True) as db:
            row = db.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone()
            if not row or row["status"] != "uncertain":
                raise ValueError("只能核对 uncertain 状态任务")
            if actual_cost < 0:
                raise ValueError("实际费用不能为负")
            # Do not extend the content TTL or requeue a paid request.
            status = "cancelled" if row["cancel_requested"] else "failed"
            db.execute("UPDATE jobs SET status=?,cost=?,cost_known=1,reserved=0,message=?,month=? WHERE id=?",
                       (status, actual_cost, "管理员已核对并结算实际费用，重新整理需使用新的任务编号",
                        datetime.now(timezone.utc).strftime("%Y-%m"), job_id))
