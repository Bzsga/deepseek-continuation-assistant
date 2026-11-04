"""Bounded, metadata-only mobile diagnostics; never accept free-form log text."""
import asyncio
import json
import re
import sqlite3
import time
from typing import Literal
from uuid import UUID

from fastapi import APIRouter, Depends, Query
from pydantic import BaseModel, ConfigDict, Field, field_validator

from .admin_access import admin_authorizer
from .store import ServiceError, local_day


MAX_DIAGNOSTIC_BODY_BYTES = 32 * 1024
RETENTION_DAYS = 30
RETENTION_SECONDS = RETENTION_DAYS * 86400
DAILY_EVENT_LIMIT = 1000
METRIC_NAMES = frozenset({
    "input_bytes", "unzipped_bytes", "elapsed_ms", "heap_free_bytes", "heap_max_bytes",
    "heap_used_bytes", "conversation_count", "message_count", "chunk_index", "chunk_count", "http_status",
})
CLASS_PREFIX = r"(?:app\.banzhan\.deepseekcontinuation|java|javax|kotlin|kotlinx|android|androidx|okhttp3|okio)\."
CLASS_NAME = CLASS_PREFIX + r"[A-Za-z_$][A-Za-z0-9_.$]*"
STACK_FRAME = re.compile(
    r"^" + CLASS_NAME + r"\.(?:[A-Za-z_$][A-Za-z0-9_$-]*|<init>|<clinit>)"
    r"\((?:[A-Za-z0-9_$.-]{1,100}\.(?:kt|java):[0-9]{1,7}|Unknown Source|Native Method|SourceFile(?::[0-9]{1,7})?)\)$"
)


class DiagnosticEvent(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    event_id: str = Field(min_length=36, max_length=36)
    occurred_at: float = Field(ge=0, le=4_102_444_800, allow_inf_nan=False)
    app_version_code: int = Field(ge=1, le=2_147_483_647)
    app_version_name: str = Field(min_length=1, max_length=80, pattern=r"^[A-Za-z0-9._+\-]+$")
    android_sdk: int = Field(ge=1, le=1000)
    manufacturer: str = Field(default="", max_length=80, pattern=r"^[A-Za-z0-9 ._()+\-]*$")
    model: str = Field(default="", max_length=80, pattern=r"^[A-Za-z0-9 ._()+\-]*$")
    kind: Literal["import", "task", "crash", "lifecycle"]
    stage: Literal["start", "read", "parse", "save", "complete", "failed", "uncaught", "recovered",
                   "prepare", "submit", "poll", "resume", "cancel"]
    metrics: dict[str, int] = Field(default_factory=dict, max_length=len(METRIC_NAMES))
    exception_class: str = Field(default="", max_length=200)
    stack_frames: list[str] = Field(default_factory=list, max_length=30)

    @field_validator("event_id")
    @classmethod
    def canonical_id(cls, value):
        try:
            parsed = UUID(value)
        except ValueError:
            raise ValueError("Invalid event ID") from None
        if str(parsed) != value:
            raise ValueError("Invalid event ID")
        return value

    @field_validator("metrics")
    @classmethod
    def safe_metrics(cls, values):
        if any(name not in METRIC_NAMES or not 0 <= value <= 2**53 for name, value in values.items()):
            raise ValueError("Invalid diagnostic metric")
        return values

    @field_validator("exception_class")
    @classmethod
    def safe_exception(cls, value):
        if value and re.fullmatch(CLASS_NAME, value) is None:
            raise ValueError("Invalid exception class")
        return value

    @field_validator("stack_frames")
    @classmethod
    def safe_frames(cls, values):
        if any(len(value) > 400 or STACK_FRAME.fullmatch(value) is None for value in values):
            raise ValueError("Invalid diagnostic frame")
        return values


class DiagnosticBatch(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    events: list[DiagnosticEvent] = Field(min_length=1, max_length=20)


class DiagnosticsStore:
    def __init__(self, store):
        self.store = store

    def initialize(self):
        with self.store.connection(write=True) as db:
            db.execute("""CREATE TABLE IF NOT EXISTS mobile_diagnostics (
                owner_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                event_id TEXT NOT NULL, occurred_at REAL NOT NULL, received_at REAL NOT NULL,
                received_day TEXT NOT NULL, data TEXT NOT NULL,
                PRIMARY KEY(owner_id,event_id)
            )""")
            db.execute("CREATE INDEX IF NOT EXISTS diagnostics_received ON mobile_diagnostics(received_at)")
            db.execute("CREATE INDEX IF NOT EXISTS diagnostics_owner_day ON mobile_diagnostics(owner_id,received_day)")
            db.execute("DELETE FROM mobile_diagnostics WHERE received_at<?", (time.time() - RETENTION_SECONDS,))

    def purge_expired(self):
        with self.store.connection(write=True) as db:
            return db.execute("DELETE FROM mobile_diagnostics WHERE received_at<?",
                              (time.time() - RETENTION_SECONDS,)).rowcount

    async def cleanup(self):
        while True:
            await asyncio.sleep(3600)
            try:
                await asyncio.to_thread(self.purge_expired)
            except sqlite3.Error:
                # Do not log database exception strings or interrupt organizing.
                continue

    def accept(self, owner_id, batch):
        now = time.time()
        day = local_day(now)
        accepted, duplicates, expired = [], [], []
        pending = {}
        with self.store.connection(write=True) as db:
            db.execute("DELETE FROM mobile_diagnostics WHERE received_at<?", (now - RETENTION_SECONDS,))
            for event in batch.events:
                payload = json.dumps(event.model_dump(), ensure_ascii=True, sort_keys=True, separators=(",", ":"))
                prior = db.execute("SELECT data FROM mobile_diagnostics WHERE owner_id=? AND event_id=?",
                                   (owner_id, event.event_id)).fetchone()
                previous_data = prior["data"] if prior is not None else pending.get(event.event_id)
                if previous_data is not None:
                    if previous_data != payload:
                        raise ServiceError(409, "诊断事件编号已用于不同内容")
                    if event.event_id not in duplicates:
                        duplicates.append(event.event_id)
                elif event.occurred_at < now - RETENTION_SECONDS:
                    if event.event_id not in expired:
                        expired.append(event.event_id)
                else:
                    pending[event.event_id] = payload
                if event.event_id not in accepted:
                    accepted.append(event.event_id)
            used = db.execute("SELECT COUNT(*) FROM mobile_diagnostics WHERE owner_id=? AND received_day=?",
                              (owner_id, day)).fetchone()[0]
            if used + len(pending) > DAILY_EVENT_LIMIT:
                raise ServiceError(429, "今日诊断日志数量已达上限；不影响智能整理次数")
            for event_id, payload in pending.items():
                occurred_at = json.loads(payload)["occurred_at"]
                db.execute("INSERT INTO mobile_diagnostics VALUES(?,?,?,?,?,?)",
                           (owner_id, event_id, occurred_at, now, day, payload))
        return {"accepted_event_ids": accepted, "duplicate_event_ids": duplicates,
                "expired_event_ids": expired, "retention_days": RETENTION_DAYS}

    def list(self, limit, since):
        with self.store.connection() as db:
            rows = db.execute("""SELECT owner_id,received_at,data FROM mobile_diagnostics
                WHERE received_at>=? AND received_at>=? ORDER BY received_at DESC,event_id DESC LIMIT ?""",
                              (since, time.time() - RETENTION_SECONDS, limit)).fetchall()
        return {"items": [{**json.loads(row["data"]), "owner_id": row["owner_id"],
                           "received_at": row["received_at"]} for row in rows],
                "retention_days": RETENTION_DAYS}


def build_diagnostics_router(store, diagnostics, owner):
    router = APIRouter()
    require_admin = admin_authorizer(store)

    @router.post("/v1/diagnostics")
    def submit_diagnostics(body: DiagnosticBatch, owner_id=Depends(owner)):
        return diagnostics.accept(owner_id, body)

    @router.get("/v1/admin/diagnostics", dependencies=[Depends(require_admin)])
    def list_diagnostics(limit: int = Query(default=100, ge=1, le=200),
                         since: float = Query(default=0, ge=0, le=4_102_444_800)):
        return diagnostics.list(limit, since)

    return router
