import base64
import hashlib
import hmac
import ipaddress
import secrets
import time
from datetime import datetime, timedelta

from fastapi import APIRouter, Depends, Header, HTTPException, Query, Request, Response
from pydantic import BaseModel, ConfigDict, Field, model_validator

from .schemas import AdminConfigPatch
from .store import SHANGHAI, local_day, reset_at


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
        totals = {"users": int(db.execute("SELECT COUNT(*) FROM users").fetchone()[0])}
        totals["active_users"] = int(db.execute(
            "SELECT COUNT(DISTINCT user_id) FROM user_activity WHERE day BETWEEN ? AND ?",
            (start_date, end_date),
        ).fetchone()[0])
        for row in db.execute("""SELECT strftime('%Y-%m-%d',created_at,'unixepoch','+8 hours') AS date,
                COUNT(*) AS new_users FROM users WHERE created_at>=? AND created_at<? GROUP BY date""",
                (start_at, end_at)):
            daily[row["date"]]["new_users"] = int(row["new_users"])
        for row in db.execute("SELECT day,COUNT(*) AS active_users FROM user_activity WHERE day BETWEEN ? AND ? GROUP BY day",
                              (start_date, end_date)):
            daily[row["day"]]["active_users"] = int(row["active_users"])
        for row in db.execute("""SELECT count_day,COUNT(*) AS requests,
                SUM(CASE WHEN usage_known=1 THEN 1 ELSE 0 END) AS usage_known_requests,
                SUM(CASE WHEN usage_known=1 THEN 0 ELSE 1 END) AS usage_unknown_requests,
                COALESCE(SUM(CASE WHEN usage_known=1 THEN input_tokens ELSE 0 END),0) AS input_tokens,
                COALESCE(SUM(CASE WHEN usage_known=1 THEN output_tokens ELSE 0 END),0) AS output_tokens
                FROM jobs WHERE count_state='used' AND count_day BETWEEN ? AND ? GROUP BY count_day""",
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
    today = local_day()
    users = []
    with store.connection() as db:
        db.execute("BEGIN")
        settings = store._settings(db)
        total = int(db.execute("SELECT COUNT(*) FROM users").fetchone()[0])
        rows = db.execute("""WITH page AS (
                SELECT id,access_mode,created_at,last_seen FROM users ORDER BY created_at DESC,id LIMIT ? OFFSET ?
            ) SELECT page.id,page.access_mode,page.created_at,page.last_seen,
                COALESCE(SUM(jobs.count_state='used' AND jobs.count_day=?),0) AS daily_used,
                COALESCE(SUM(jobs.count_state='reserved'),0) AS daily_reserved,
                COALESCE(SUM(jobs.count_state='used'),0) AS total_requests,
                COALESCE(SUM(jobs.count_state='used' AND jobs.usage_known=1),0) AS usage_known_requests,
                COALESCE(SUM(jobs.count_state='used' AND jobs.usage_known<>1),0) AS usage_unknown_requests,
                COALESCE(SUM(jobs.status='completed'),0) AS completed,
                COALESCE(SUM(jobs.status='failed'),0) AS failed,
                COALESCE(SUM(jobs.status='uncertain'),0) AS uncertain,
                COALESCE(SUM(jobs.status='cancelled'),0) AS cancelled,
                COALESCE(SUM(CASE WHEN jobs.usage_known=1 THEN jobs.input_tokens ELSE 0 END),0) AS input_tokens,
                COALESCE(SUM(CASE WHEN jobs.usage_known=1 THEN jobs.output_tokens ELSE 0 END),0) AS output_tokens
            FROM page LEFT JOIN jobs ON jobs.owner_id=page.id
            GROUP BY page.id,page.access_mode,page.created_at,page.last_seen ORDER BY page.created_at DESC,page.id""",
            (limit, offset, today)).fetchall()
        for row in rows:
            counts = {field: int(row[field]) for field in ("daily_used", "daily_reserved", "total_requests",
                      "usage_known_requests", "usage_unknown_requests",
                      "completed", "failed", "uncertain", "cancelled", "input_tokens", "output_tokens")}
            users.append({"id": row["id"],
                          "access_mode": "public" if settings.public_enabled or row["access_mode"] == "public" else "invitation",
                          "created_at": _iso(row["created_at"]),
                          "last_seen_at": _iso(row["last_seen"]) if row["last_seen"] is not None else None,
                          "daily_limit": int(settings.user_daily_limit),
                          "daily_remaining": max(0, settings.user_daily_limit - counts["daily_used"] - counts["daily_reserved"]),
                          **counts})
    return {"limit": limit, "offset": offset, "total": total, "users": users}


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

    return router
