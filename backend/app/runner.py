import asyncio
import json
import os
import sqlite3
from pathlib import Path

from .provider import ProviderOutcome, organize


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

    def start(self):
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
            try:
                await asyncio.to_thread(self.store.purge_expired)
                self.cleanup_ok = True
            except sqlite3.Error:
                self.cleanup_ok = False

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
            snapshot = json.loads(row["provider"])
            execution_settings = row["execution_settings"]
            try:
                try:
                    outcome = await asyncio.wait_for(
                        organize(execution_settings, snapshot, json.loads(row["payload"])),
                        timeout=execution_settings.provider_timeout_seconds,
                    )
                except asyncio.TimeoutError:
                    outcome = ProviderOutcome("uncertain", "请求达到总时限，本次次数已计入用量，不会自动重试", [])
                await asyncio.to_thread(self.store.finish, row["id"], outcome)
            except asyncio.CancelledError:
                # The process may have stopped after a provider accepted the request.
                self.store.finish(row["id"], ProviderOutcome("uncertain", "服务停止时调用尚未确认，费用预留已保留；不会自动重试", []))
                raise
            except Exception:
                # Do not log exception strings that may contain provider data.
                await asyncio.to_thread(self.store.finish, row["id"], ProviderOutcome("uncertain", "任务处理意外中断，费用预留已保留，请联系管理员核对", []))
