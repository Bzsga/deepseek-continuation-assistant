import base64
import hashlib
import hmac
import ipaddress
import secrets
import time
from datetime import date, datetime, timedelta

from fastapi import APIRouter, Depends, Header, HTTPException, Query, Request, Response
from pydantic import BaseModel, ConfigDict, Field, model_validator

from .schemas import AdminConfigPatch
from .store import PROVIDER_REQUEST_USAGE_SQL, SHANGHAI, local_day, reset_at


SESSION_SECONDS = 12 * 60 * 60
LOGIN_WINDOW_SECONDS = 15 * 60
MAX_FAILED_LOGINS = 5
PASSWORD_ITERATIONS = 600_000
TERMINAL_STATUSES = ("completed", "failed", "uncertain", "cancelled")


class AdminLogin(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    password: str = Field(min_length=1, max_length=1024, repr=False)

    @model_validator(mode="after")
    def valid_unicode(self):
        try:
            self.password.encode("utf-8")
        except UnicodeError:
            raise ValueError("密码包含无效字符") from None
        return self


class UserQuotaPatch(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    daily_limit: int | None = Field(default=None, ge=0, le=1_000_000)
    today_remaining: int | None = Field(default=None, ge=0, le=1_000_000)
    day: str | None = Field(default=None, pattern=r"^\d{4}-\d{2}-\d{2}$")

    @model_validator(mode="after")
    def valid_changes(self):
        for field in ("today_remaining", "day"):
            if field in self.model_fields_set and getattr(self, field) is None:
                raise ValueError("今日次数及日期不能为 null")
        if self.day is not None:
            try:
                date.fromisoformat(self.day)
            except ValueError:
                raise ValueError("日期格式不正确") from None
        return self


class RegistrationInviteRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    count: int = Field(default=1, ge=1, le=100)
    valid_days: int = Field(default=7, ge=1, le=365)


def _hash(value):
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def _iso(timestamp):
    return datetime.fromtimestamp(timestamp, SHANGHAI).isoformat()


def _password_parts(encoded):
    try:
        algorithm, iterations, salt_text, digest_text = encoded.split("$")
        if algorithm != "pbkdf2_sha256" or iterations != str(PASSWORD_ITERATIONS):
            raise ValueError
        decoded = []
        for value, length in ((salt_text, 16), (digest_text, 32)):
            raw = base64.b64decode(value + "=" * (-len(value) % 4), altchars=b"-_", validate=True)
            canonical = base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")
            if len(raw) != length or not hmac.compare_digest(canonical, value):
                raise ValueError
            decoded.append(raw)
        return decoded[0], decoded[1]
    except (AttributeError, TypeError, ValueError):
        raise HTTPException(503, "管理员密码尚未正确配置") from None


def _client_ip(request):
    host = request.client.host if request.client is not None else "unknown"
    try:
        address = ipaddress.ip_address(host)
    except ValueError:
        return host
    if address.is_loopback:
        forwarded = request.headers.get("x-real-ip")
        if forwarded:
            try:
                return str(ipaddress.ip_address(forwarded.strip()))
            except ValueError:
                pass
    return str(address)


def initialize_admin(store):
    with store.connection(write=True) as db:
        db.execute("""CREATE TABLE IF NOT EXISTS admin_sessions (
            token_hash TEXT PRIMARY KEY, created_at REAL NOT NULL, expires_at REAL NOT NULL
        )""")
        db.execute("""CREATE TABLE IF NOT EXISTS admin_login_attempts (
            ip_hash TEXT NOT NULL, created_at REAL NOT NULL
        )""")
        db.execute("CREATE INDEX IF NOT EXISTS admin_attempts_ip_time ON admin_login_attempts(ip_hash, created_at)")
        now = time.time()
        db.execute("DELETE FROM admin_sessions WHERE expires_at<=?", (now,))
        db.execute("DELETE FROM admin_login_attempts WHERE created_at<=?", (now - LOGIN_WINDOW_SECONDS,))


def _statistics(store, days):
    now = time.time()
    end = datetime.fromtimestamp(now, SHANGHAI).date()
    start = end - timedelta(days=days - 1)
    start_date, end_date = start.isoformat(), end.isoformat()
    start_at = datetime(start.year, start.month, start.day, tzinfo=SHANGHAI).timestamp()
    after_end = end + timedelta(days=1)
    end_at = datetime(after_end.year, after_end.month, after_end.day, tzinfo=SHANGHAI).timestamp()
    daily = {}
    for offset in range(days):
        date = (start + timedelta(days=offset)).isoformat()
        daily[date] = {"date": date, "new_users": 0, "active_users": 0, "requests": 0,
                       "usage_known_requests": 0, "usage_unknown_requests": 0,
                       "input_tokens": 0, "output_tokens": 0,
                       **{status: 0 for status in TERMINAL_STATUSES}}
    with store.connection() as db:
        db.execute("BEGIN")
        settings = store._settings(db)
        totals = {"users": int(db.execute("SELECT COUNT(*) FROM registered_accounts").fetchone()[0])}
        totals["active_users"] = int(db.execute(
            """SELECT COUNT(DISTINCT activity.user_id) FROM user_activity AS activity
               JOIN registered_accounts AS account ON account.user_id=activity.user_id
               WHERE activity.day BETWEEN ? AND ?""",
            (start_date, end_date),
        ).fetchone()[0])
        for row in db.execute("""SELECT strftime('%Y-%m-%d',created_at,'unixepoch','+8 hours') AS date,
                COUNT(*) AS new_users FROM registered_accounts WHERE created_at>=? AND created_at<? GROUP BY date""",
                (start_at, end_at)):
            daily[row["date"]]["new_users"] = int(row["new_users"])
        for row in db.execute("""SELECT activity.day,COUNT(*) AS active_users FROM user_activity AS activity
                JOIN registered_accounts AS account ON account.user_id=activity.user_id
                WHERE activity.day BETWEEN ? AND ? GROUP BY activity.day""", (start_date, end_date)):
            daily[row["day"]]["active_users"] = int(row["active_users"])
        for row in db.execute(f"""WITH provider_request_usage AS ({PROVIDER_REQUEST_USAGE_SQL})
                SELECT count_day,COUNT(*) AS requests,
                SUM(CASE WHEN usage_known=1 THEN 1 ELSE 0 END) AS usage_known_requests,
                SUM(CASE WHEN usage_known=1 THEN 0 ELSE 1 END) AS usage_unknown_requests,
                COALESCE(SUM(CASE WHEN usage_known=1 THEN input_tokens ELSE 0 END),0) AS input_tokens,
                COALESCE(SUM(CASE WHEN usage_known=1 THEN output_tokens ELSE 0 END),0) AS output_tokens
                FROM provider_request_usage WHERE count_day BETWEEN ? AND ? GROUP BY count_day""",
                (start_date, end_date)):
            for field in ("requests", "usage_known_requests", "usage_unknown_requests", "input_tokens", "output_tokens"):
                daily[row["count_day"]][field] = int(row[field])
        for row in db.execute("""SELECT strftime('%Y-%m-%d',created_at,'unixepoch','+8 hours') AS date,
                status,COUNT(*) AS count FROM jobs WHERE created_at>=? AND created_at<?
                AND status IN ('completed','failed','uncertain','cancelled') GROUP BY date,status""",
                (start_at, end_at)):
            daily[row["date"]][row["status"]] = int(row["count"])
        for field in ("new_users", "requests", "usage_known_requests", "usage_unknown_requests",
                      "input_tokens", "output_tokens", *TERMINAL_STATUSES):
            totals[field] = sum(day[field] for day in daily.values())
        for status in ("queued", "running"):
            totals[status] = int(db.execute("SELECT COUNT(*) FROM jobs WHERE status=?", (status,)).fetchone()[0])
        quota = store.daily_quota(db, settings=settings, timestamp=now)
        today = {field: int(quota[field]) for field in ("daily_limit", "daily_used", "daily_reserved", "daily_remaining")}
        today["reset_at"] = quota.get("reset_at", reset_at(now))
    return {"timezone": "Asia/Shanghai", "days": days, "start_date": start_date, "end_date": end_date,
            "generated_at": _iso(now), "totals": totals, "today": today, "daily": list(daily.values())}


def _users(store, limit, offset):
    now = time.time()
    today = local_day(now)
    users = []
    with store.connection() as db:
        db.execute("BEGIN")
        settings = store._settings(db)
        total = int(db.execute("SELECT COUNT(*) FROM registered_accounts").fetchone()[0])
        rows = db.execute(f"""WITH provider_request_usage AS ({PROVIDER_REQUEST_USAGE_SQL}), page AS (
                SELECT u.id,u.access_mode,a.created_at,u.last_seen,a.username,a.registration_kind,a.daily_limit_override
                FROM registered_accounts AS a JOIN users AS u ON u.id=a.user_id
                ORDER BY a.created_at DESC,u.id LIMIT ? OFFSET ?
            ), request_totals AS (
                SELECT owner_id,SUM(count_day=?) AS daily_used,COUNT(*) AS total_requests,
                    SUM(usage_known=1) AS usage_known_requests,SUM(usage_known<>1) AS usage_unknown_requests,
                    SUM(CASE WHEN usage_known=1 THEN input_tokens ELSE 0 END) AS input_tokens,
                    SUM(CASE WHEN usage_known=1 THEN output_tokens ELSE 0 END) AS output_tokens
                FROM provider_request_usage WHERE owner_id IN (SELECT id FROM page) GROUP BY owner_id
            ), job_totals AS (
                SELECT owner_id,SUM(count_state='reserved') AS daily_reserved,
                    SUM(status='completed') AS completed,SUM(status='failed') AS failed,
                    SUM(status='uncertain') AS uncertain,SUM(status='cancelled') AS cancelled
                FROM jobs WHERE owner_id IN (SELECT id FROM page) GROUP BY owner_id
            ) SELECT page.id,page.access_mode,page.created_at,page.last_seen,
                page.username,page.registration_kind,page.daily_limit_override,
                COALESCE(request_totals.daily_used,0) AS daily_used,
                COALESCE(job_totals.daily_reserved,0) AS daily_reserved,
                COALESCE(request_totals.total_requests,0) AS total_requests,
                COALESCE(request_totals.usage_known_requests,0) AS usage_known_requests,
                COALESCE(request_totals.usage_unknown_requests,0) AS usage_unknown_requests,
                COALESCE(job_totals.completed,0) AS completed,
                COALESCE(job_totals.failed,0) AS failed,
                COALESCE(job_totals.uncertain,0) AS uncertain,
                COALESCE(job_totals.cancelled,0) AS cancelled,
                COALESCE(request_totals.input_tokens,0) AS input_tokens,
                COALESCE(request_totals.output_tokens,0) AS output_tokens
            FROM page LEFT JOIN request_totals ON request_totals.owner_id=page.id
                LEFT JOIN job_totals ON job_totals.owner_id=page.id
            ORDER BY page.created_at DESC,page.id""",
            (limit, offset, today)).fetchall()
        for row in rows:
            quota = store.daily_quota(db, row["id"], settings=settings, timestamp=now)
            counts = {field: int(row[field]) for field in ("daily_used", "daily_reserved", "total_requests",
                      "usage_known_requests", "usage_unknown_requests",
                      "completed", "failed", "uncertain", "cancelled", "input_tokens", "output_tokens")}
            users.append({"id": row["id"],
                          "username": row["username"], "registration_kind": row["registration_kind"],
                          "daily_limit_override": row["daily_limit_override"],
                          "access_mode": "public" if settings.public_enabled or row["access_mode"] == "public" else "invitation",
                          "created_at": _iso(row["created_at"]),
                          "last_seen_at": _iso(row["last_seen"]) if row["last_seen"] is not None else None,
                          "daily_limit": int(quota["user_daily_limit"]),
                          "daily_remaining": int(quota["user_daily_remaining"]),
                          **counts})
    return {"limit": limit, "offset": offset, "total": total, "users": users,
            "day": today, "reset_at": reset_at(now)}


def admin_authorizer(store):
    def require_admin(response: Response, authorization: str | None = Header(default=None)):
        response.headers["Cache-Control"] = "no-store"
        settings = store.current_settings()
        if not settings.admin_password_hash and not settings.admin_token:
            raise HTTPException(503, "管理员访问尚未配置")
        parts = (authorization or "").split(" ", 1)
        if len(parts) != 2 or parts[0].lower() != "bearer" or not 1 <= len(parts[1]) <= 512:
            raise HTTPException(401, "请先登录管理员账号")
        token = parts[1]
        try:
            token_hash = _hash(token)
        except UnicodeError:
            raise HTTPException(401, "管理员凭据无效或已过期") from None
        if settings.admin_token and hmac.compare_digest(token_hash, _hash(settings.admin_token)):
            return
        with store.connection() as db:
            row = db.execute("SELECT token_hash,expires_at FROM admin_sessions WHERE token_hash=?", (token_hash,)).fetchone()
        if row is None or row["expires_at"] <= time.time() or not hmac.compare_digest(row["token_hash"], token_hash):
            raise HTTPException(401, "管理员凭据无效或已过期")

    return require_admin


def build_admin_router(store):
    router = APIRouter(prefix="/v1/admin", tags=["administration"])
    require_admin = admin_authorizer(store)

    @router.post("/login")
    def login(body: AdminLogin, request: Request, response: Response):
        response.headers["Cache-Control"] = "no-store"
        settings = store.current_settings()
        salt, expected = _password_parts(settings.admin_password_hash)
        now = time.time()
        ip_hash = _hash(_client_ip(request))
        with store.connection(write=True) as db:
            db.execute("DELETE FROM admin_login_attempts WHERE created_at<=?", (now - LOGIN_WINDOW_SECONDS,))
            db.execute("DELETE FROM admin_sessions WHERE expires_at<=?", (now,))
            attempts = db.execute("SELECT COUNT(*) FROM admin_login_attempts WHERE ip_hash=?", (ip_hash,)).fetchone()[0]
            if attempts >= MAX_FAILED_LOGINS:
                raise HTTPException(429, "登录失败次数较多，请在 15 分钟后重试")
            attempt_id = db.execute("INSERT INTO admin_login_attempts VALUES (?,?)", (ip_hash, now)).lastrowid
        actual = hashlib.pbkdf2_hmac("sha256", body.password.encode("utf-8"), salt, PASSWORD_ITERATIONS)
        if not hmac.compare_digest(actual, expected):
            raise HTTPException(401, "管理员密码不正确")
        token = secrets.token_urlsafe(32)
        created_at = time.time()
        expires_at = created_at + SESSION_SECONDS
        with store.connection(write=True) as db:
            db.execute("DELETE FROM admin_login_attempts WHERE rowid=? AND ip_hash=? AND created_at=?", (attempt_id, ip_hash, now))
            db.execute("INSERT INTO admin_sessions VALUES (?,?,?)", (_hash(token), created_at, expires_at))
        return {"token": token, "expires_at": _iso(expires_at)}

    @router.get("/config", dependencies=[Depends(require_admin)])
    def get_config():
        return store.admin_config()

    @router.patch("/config", dependencies=[Depends(require_admin)])
    def update_config(body: AdminConfigPatch):
        return store.update_config(body.model_dump(exclude_unset=True))

    @router.get("/stats", dependencies=[Depends(require_admin)])
    def statistics(days: int = Query(default=7, ge=1, le=90)):
        return _statistics(store, days)

    @router.get("/users", dependencies=[Depends(require_admin)])
    def users(limit: int = Query(default=50, ge=1, le=200), offset: int = Query(default=0, ge=0)):
        return _users(store, limit, offset)

    @router.patch("/users/{user_id}/quota", dependencies=[Depends(require_admin)])
    def update_user_quota(user_id: str, body: UserQuotaPatch):
        return store.update_user_quota(user_id, body.model_dump(exclude_unset=True))

    @router.get("/registration-invites", dependencies=[Depends(require_admin)])
    def registration_invites(limit: int = Query(default=50, ge=1, le=100), offset: int = Query(default=0, ge=0)):
        return store.accounts.list_invites(limit, offset)

    @router.post("/registration-invites", dependencies=[Depends(require_admin)])
    def create_registration_invites(body: RegistrationInviteRequest):
        return store.accounts.create_invites(body.count, body.valid_days)

    return router
