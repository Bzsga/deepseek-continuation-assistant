import asyncio
import json
import threading
import uuid
from collections import Counter
from contextlib import asynccontextmanager
from dataclasses import replace
from decimal import Decimal

import httpx
import pytest

from app.config import Settings
from app.main import create_app
from app.provider import ProviderOutcome


@pytest.fixture
def settings(tmp_path):
    return Settings(
        db_path=str(tmp_path / "concurrency.sqlite3"), mock_mode=True,
        daily_limit=20, user_daily_limit=20,
        input_price_per_million=Decimal("2"), output_price_per_million=Decimal("8"),
        provider_timeout_seconds=30, cleanup_interval_seconds=60,
        failure_artifacts_enabled=False,
    )


@asynccontextmanager
async def service(settings):
    app = create_app(settings)
    async with app.router.lifespan_context(app):
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app), base_url="http://testserver"
        ) as client:
            yield app, client


async def authorization(client):
    response = await client.post("/v1/accounts/register", json={
        "username": "runner_" + uuid.uuid4().hex[:20], "password": "synthetic password 123"})
    assert response.status_code == 200
    return {"Authorization": "Bearer " + response.json()["token"]}


async def submit(client, headers, key):
    response = await client.post("/v1/jobs", headers=headers, json={
        "idempotency_key": "concurrency-" + key,
        "messages": [{"id": key, "role": "user", "content": "Synthetic source " + key}],
    })
    assert response.status_code == 200
    return response.json()


def stored_job(app, job_id):
    with app.state.store.connection() as db:
        return dict(db.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone())


async def until(predicate):
    async def poll():
        while not predicate():
            await asyncio.sleep(0.005)
    await asyncio.wait_for(poll(), timeout=3)


async def terminal_job(app, job_id):
    await until(lambda: stored_job(app, job_id)["status"] in {
        "completed", "failed", "uncertain", "cancelled"
    })
    return stored_job(app, job_id)


class ControlledProvider:
    def __init__(self, keys, failed=()):
        self.entered = {key: asyncio.Event() for key in keys}
        self.release = {key: asyncio.Event() for key in keys}
        self.failed = set(failed)
        self.calls = []
        self.returned = []
        self.active = 0
        self.peak = 0

    async def __call__(self, settings, snapshot, messages, *, trace=None):
        key = messages[0]["id"]
        self.calls.append(key)
        self.active += 1
        self.peak = max(self.peak, self.active)
        self.entered[key].set()
        try:
            await self.release[key].wait()
            self.returned.append(key)
            status = "failed" if key in self.failed else "completed"
            entries = [] if status == "failed" else [{
                "id": key + "-entry", "category": "RECENT", "text": "Summary " + key,
                "sourceIds": [key], "kind": "USER_FACT",
            }]
            return ProviderOutcome(status, "Synthetic " + status, entries, 10, 20, 100, usage_known=True)
        finally:
            self.active -= 1


@pytest.mark.parametrize("concurrency", [1, 2, 5])
def test_capacity_reverse_completion_and_failure_keep_independent_results(settings, monkeypatch, concurrency):
    async def scenario():
        keys = [f"segment-{index}" for index in range(concurrency + 1)]
        provider = ControlledProvider(keys, failed={keys[0]})
        monkeypatch.setattr("app.runner.organize", provider)
        # The two-slot case exercises the unmodified default configuration.
        configured = settings if concurrency == 2 else replace(settings, worker_concurrency=concurrency)
        async with service(configured) as (app, client):
            headers = await authorization(client)
            jobs = {key: await submit(client, headers, key) for key in keys}
            await asyncio.wait_for(asyncio.gather(*(
                provider.entered[key].wait() for key in keys[:concurrency]
            )), timeout=3)
            assert len(app.state.runner.tasks) == concurrency
            assert provider.active == provider.peak == concurrency
            assert not provider.entered[keys[-1]].is_set()
            assert stored_job(app, jobs[keys[-1]]["id"])["status"] == "queued"
            quota = (await client.get("/v1/quota", headers=headers)).json()
            assert quota["daily_used"] == concurrency
            assert quota["daily_reserved"] == 1

            order = list(reversed(keys[:concurrency])) + [keys[-1]]
            for key in order:
                provider.release[key].set()
                await terminal_job(app, jobs[key]["id"])

            assert provider.returned == order
            assert Counter(provider.calls) == Counter({key: 1 for key in keys})
            assert provider.peak == concurrency
            for key in keys:
                response = await client.get(f"/v1/jobs/{jobs[key]['id']}", headers=headers)
                assert response.status_code == 200
                result = response.json()
                assert result["status"] == ("failed" if key == keys[0] else "completed")
                assert result["request_counted"] is True
                assert result["cost_cny"] == 0.0001 and result["reserved_cny"] == 0
                assert result["entries"] == ([] if key == keys[0] else [{
                    "id": key + "-entry", "category": "RECENT", "text": "Summary " + key,
                    "sourceIds": [key], "kind": "USER_FACT",
                }])
            quota = (await client.get("/v1/quota", headers=headers)).json()
            assert quota["daily_used"] == quota["user_daily_used"] == len(keys)
            assert quota["daily_reserved"] == quota["user_daily_reserved"] == 0

    asyncio.run(scenario())


def test_shutdown_preserves_known_finish_and_uncertain_other_reservation(settings, monkeypatch):
    async def scenario():
        provider = ControlledProvider(["known", "unknown"])
        monkeypatch.setattr("app.runner.organize", provider)
        async with service(replace(settings, worker_concurrency=2)) as (app, client):
            headers = await authorization(client)
            jobs = {key: await submit(client, headers, key) for key in provider.entered}
            await asyncio.wait_for(asyncio.gather(*(
                event.wait() for event in provider.entered.values()
            )), timeout=3)
            finalizing, allow_finish = asyncio.Event(), asyncio.Event()
            original_finalize = app.state.runner._finish_job
            original_finish = app.state.store.finish
            finishes = []

            async def gated_finalize(row, snapshot, messages, execution_settings, trace, outcome):
                if row["id"] == jobs["known"]["id"] and outcome.status == "completed":
                    finalizing.set()
                    await allow_finish.wait()
                await original_finalize(row, snapshot, messages, execution_settings, trace, outcome)

            def record_finish(job_id, outcome):
                finishes.append((job_id, outcome.status))
                original_finish(job_id, outcome)

            monkeypatch.setattr(app.state.runner, "_finish_job", gated_finalize)
            monkeypatch.setattr(app.state.store, "finish", record_finish)
            provider.release["known"].set()
            await asyncio.wait_for(finalizing.wait(), timeout=3)
            stopping = asyncio.create_task(app.state.runner.stop())
            try:
                unknown = await terminal_job(app, jobs["unknown"]["id"])
                assert not stopping.done()
                assert stored_job(app, jobs["known"]["id"])["status"] == "running"
                assert unknown["status"] == "uncertain"
                assert unknown["reserved"] > 0
                assert unknown["reserved"] / 1_000_000 == jobs["unknown"]["reserved_cny"]
            finally:
                allow_finish.set()
                await asyncio.wait_for(stopping, timeout=3)

            known = stored_job(app, jobs["known"]["id"])
            assert known["status"] == "completed" and known["reserved"] == 0 and known["cost"] == 100
            assert json.loads(known["entries"])[0]["text"] == "Summary known"
            assert unknown["cost"] == 0 and unknown["usage_known"] == 0
            assert known["count_state"] == unknown["count_state"] == "used"
            assert Counter(finishes) == Counter({
                (jobs["known"]["id"], "completed"): 1,
                (jobs["unknown"]["id"], "uncertain"): 1,
            })
            assert Counter(provider.calls) == Counter({"known": 1, "unknown": 1})
            assert all(task.done() for task in app.state.runner.tasks)
            assert app.state.runner.cleanup_task.done()

    asyncio.run(scenario())


def test_shutdown_drains_committed_claim_without_calling_provider(settings, monkeypatch):
    async def scenario():
        provider = ControlledProvider(["claim-boundary"])
        monkeypatch.setattr("app.runner.organize", provider)
        async with service(replace(settings, worker_concurrency=1)) as (app, client):
            committed, allow_return = threading.Event(), threading.Event()
            original_claim = app.state.store.claim_next

            def delayed_claim():
                row = original_claim()
                if row is not None:
                    committed.set()
                    if not allow_return.wait(5):
                        raise AssertionError("test did not release the committed claim")
                return row

            monkeypatch.setattr(app.state.store, "claim_next", delayed_claim)
            stopping = None
            try:
                headers = await authorization(client)
                job = await submit(client, headers, "claim-boundary")
                await until(committed.is_set)
                assert stored_job(app, job["id"])["status"] == "running"
                stopping = asyncio.create_task(app.state.runner.stop())
                # stop cancels every worker before cleanup; observing cleanup's
                # cancellation works on Python 3.10 without Task.cancelling().
                await until(app.state.runner.cleanup_task.cancelled)
                assert not stopping.done()
                assert provider.calls == []
            finally:
                allow_return.set()
                if stopping is not None:
                    await asyncio.wait_for(stopping, timeout=3)

            row = stored_job(app, job["id"])
            assert row["status"] == "uncertain" and row["count_state"] == "used"
            assert row["reserved"] > 0 and row["reserved"] / 1_000_000 == job["reserved_cny"]
            assert row["cost"] == 0 and row["usage_known"] == 0
            assert provider.calls == []
            assert all(task.done() for task in app.state.runner.tasks)
            assert app.state.runner.cleanup_task.done()

    asyncio.run(scenario())


@pytest.mark.parametrize("exited_task", ["second_worker", "cleanup"])
def test_health_reports_any_exited_worker_or_cleanup(settings, exited_task):
    async def scenario():
        async with service(replace(settings, worker_concurrency=2)) as (app, client):
            assert (await client.get("/health")).status_code == 200
            runner = app.state.runner
            task = runner.tasks[1] if exited_task == "second_worker" else runner.cleanup_task
            task.cancel()
            await asyncio.gather(task, return_exceptions=True)
            assert not runner.tasks[0].done()
            response = await client.get("/health")
            assert response.status_code == 503
            assert response.json()["status"] == "degraded"

    asyncio.run(scenario())


@pytest.mark.parametrize("value,valid", [(1, True), (5, True), (0, False), (6, False), (True, False), (1.5, False)])
def test_concurrency_defaults_and_strict_bounds(value, valid):
    assert Settings().worker_concurrency == 2
    settings = Settings(worker_concurrency=value)
    if valid:
        settings.validate()
    else:
        with pytest.raises(ValueError, match="WORKER_CONCURRENCY"):
            settings.validate()
