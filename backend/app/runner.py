import asyncio
import json
import os
import sqlite3
import uuid
from pathlib import Path

from .failure_artifacts import ArtifactStore, ProviderTrace
from .provider import ProviderOutcome, diagnostic_message, organize


class ServiceLease:
    """One worker process per SQLite database, including startup recovery."""

    def __init__(self, db_path):
        self.path = Path(db_path).resolve().with_suffix(".service.lock")
        self.handle = None

    def acquire(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.handle = self.path.open("a+b")
        self.handle.seek(0, os.SEEK_END)
        if self.handle.tell() == 0:
            self.handle.write(b"0")
            self.handle.flush()
        self.handle.seek(0)
        try:
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(self.handle.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(self.handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            self.handle.close()
            self.handle = None
            raise RuntimeError("同一数据库已存在运行中的服务，请使用单个 Uvicorn worker") from None

    def release(self):
        if self.handle is not None:
            self.handle.close()
            self.handle = None


class Runner:
    def __init__(self, settings, store):
        self.settings = settings
        self.store = store
        self.task = None
        self.cleanup_task = None
        self.wake = asyncio.Event()
        self.cleanup_ok = True
        self.artifact_path = Path(settings.db_path).resolve().with_name("failure-artifacts.sqlite3")
        self.artifacts = None

    def start(self):
        try:
            self._purge_artifacts()
        except Exception:
            # Capture availability must not prevent ordinary job processing.
            self.cleanup_ok = False
        if self.settings.worker_enabled:
            self.task = asyncio.create_task(self.run())
        self.cleanup_task = asyncio.create_task(self.cleanup())

    async def stop(self):
        for task in (self.task, self.cleanup_task):
            if task:
                task.cancel()
        await asyncio.gather(*(task for task in (self.task, self.cleanup_task) if task), return_exceptions=True)

    async def cleanup(self):
        while True:
            await asyncio.sleep(self.settings.cleanup_interval_seconds)
            cleanup_ok = True
            try:
                await asyncio.to_thread(self.store.purge_expired)
            except sqlite3.Error:
                cleanup_ok = False
            try:
                await asyncio.to_thread(self._purge_artifacts)
            except Exception:
                cleanup_ok = False
            self.cleanup_ok = cleanup_ok

    def _purge_artifacts(self):
        # Disabling new capture does not disable expiry of existing artifacts.
        if self.artifacts is None:
            if not self.settings.failure_artifacts_enabled:
                try:
                    self.artifact_path.stat()
                except FileNotFoundError:
                    return
            self.artifacts = ArtifactStore(self.artifact_path)
            return  # Constructor performs the initial expiry cleanup.
        self.artifacts.purge()

    def _capture_failure(self, row, snapshot, messages, execution_settings, trace, outcome):
        mock_mode = snapshot.get("mock_mode", execution_settings.mock_mode) if isinstance(snapshot, dict) else execution_settings.mock_mode
        if outcome.status not in {"failed", "uncertain"} or mock_mode:
            return
        capture = {"failure_capture_state": "disabled"}
        if self.settings.failure_artifacts_enabled:
            capture["failure_capture_state"] = "unavailable"
            if self.artifacts is not None:
                try:
                    secrets = tuple(
                        value for settings in (self.settings, execution_settings)
                        for name in ("api_key", "admin_token", "feedback_worker_token", "admin_password_hash")
                        if (value := getattr(settings, name, ""))
                    )
                    saved = self.artifacts.save(
                        row["id"], trace, snapshot, messages, outcome.status, outcome.message,
                        secrets=secrets, ttl_seconds=self.settings.failure_artifacts_ttl_hours * 3600,
                    )
                    capture = {
                        "failure_capture_state": "saved", "failure_capture_id": str(uuid.UUID(saved["id"])),
                        "failure_capture_expires_at": int(saved["expires_at"]),
                    }
                except Exception:
                    # Do not replace the original failure or leak capture errors.
                    pass
        label, separator, encoded = outcome.message.partition(" diagnostic=")
        diagnostic = {}
        if separator:
            try:
                decoded = json.loads(encoded)
                if isinstance(decoded, dict):
                    diagnostic = decoded
            except (ValueError, TypeError, RecursionError):
                pass
        diagnostic.update(capture)
        outcome.message = diagnostic_message(label, diagnostic)

    async def _finish_job(self, row, snapshot, messages, execution_settings, trace, outcome):
        try:
            await asyncio.to_thread(self._capture_failure, row, snapshot, messages, execution_settings, trace, outcome)
            await asyncio.to_thread(self.store.finish, row["id"], outcome)
        finally:
            trace.request = None
            trace.response_body.clear()

    async def run(self):
        while True:
            self.wake.clear()
            try:
                row = await asyncio.to_thread(self.store.claim_next)
            except sqlite3.Error:
                await asyncio.sleep(1)
                continue
            if row is None:
                try:
                    await asyncio.wait_for(self.wake.wait(), timeout=2)
                except asyncio.TimeoutError:
                    pass
                continue
            execution_settings = row["execution_settings"]
            snapshot, messages, trace = {}, [], ProviderTrace()
            finalizing = None
            try:
                snapshot = json.loads(row["provider"])
                messages = json.loads(row["payload"])
                try:
                    outcome = await asyncio.wait_for(
                        organize(execution_settings, snapshot, messages, trace=trace),
                        timeout=execution_settings.provider_timeout_seconds,
                    )
                except asyncio.TimeoutError:
                    outcome = ProviderOutcome("uncertain", "请求达到总时限，本次次数已计入用量，不会自动重试", [])
                finalizing = asyncio.create_task(self._finish_job(row, snapshot, messages, execution_settings, trace, outcome))
                await asyncio.shield(finalizing)
            except asyncio.CancelledError:
                if finalizing is not None:
                    # A known result must finish once; shutdown must not create
                    # a second uncertain artifact for a successful response.
                    await finalizing
                else:
                    outcome = ProviderOutcome("uncertain", "服务停止时调用尚未确认，费用预留已保留；不会自动重试", [])
                    await self._finish_job(row, snapshot, messages, execution_settings, trace, outcome)
                raise
            except Exception:
                # Do not log exception strings that may contain provider data.
                outcome = ProviderOutcome("uncertain", "任务处理意外中断，费用预留已保留，请联系管理员核对", [])
                await self._finish_job(row, snapshot, messages, execution_settings, trace, outcome)
