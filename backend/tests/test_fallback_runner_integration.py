import asyncio
import json
import threading

import httpx

from app import failover
from app.config import ProviderRoute, Settings
from app.main import create_app
from app.provider import ProviderOutcome
from account_fixtures import register_owner, registered_job


def test_real_runner_records_each_route_and_idempotent_submission_does_not_replay(tmp_path, monkeypatch):
    async def scenario():
        failover._COOLDOWNS.clear()
        settings = Settings(
            db_path=str(tmp_path / "runner.sqlite3"), api_enabled=True, api_key="primary-key",
            base_url="https://primary.invalid/v1", model="deepseek-v4.1-flash",
            context_tokens=64000, max_output_tokens=4096,
            fallback_providers=(ProviderRoute("alternate", "https://alternate.invalid/v1", "deepseek-v4.1-flash", "alt-key"),
                                ProviderRoute("terra", "https://terra.invalid/v1", "gpt-6.1-terra", "terra-key")),
            provider_route_order=("alternate", "terra", "primary"), failure_artifacts_enabled=False,
            cleanup_interval_seconds=60, daily_limit=10, user_daily_limit=10,
        )
        calls = []

        async def provider(current, snapshot, messages, *, trace=None):
            calls.append((current.base_url, current.api_key, snapshot["model"]))
            if current.base_url == "https://alternate.invalid/v1":
                return ProviderOutcome("uncertain", "[transport] 合成上游故障", [])
            return ProviderOutcome("completed", "已保留来源", [{"id": "summary", "category": "RECENT",
                "text": "合成内容", "sourceIds": ["source"], "kind": "USER_FACT"}],
                input_tokens=20, output_tokens=10, usage_known=True)

        monkeypatch.setattr("app.runner.organize", provider)
        app = create_app(settings)
        async with app.router.lifespan_context(app):
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
                session = (await client.post("/v1/accounts/register", json={
                    "username": "runner_synthetic", "password": "synthetic password 123"})).json()
                assert session["quota"]["max_attempts_per_chunk"] == 3
                headers = {"Authorization": "Bearer " + session["token"]}
                body = {"idempotency_key": "integration-fallback-job",
                        "messages": [{"id": "source", "role": "user", "content": "合成内容"}]}
                job = (await client.post("/v1/jobs", headers=headers, json=body)).json()
                result = None
                for _ in range(300):
                    result = (await client.get("/v1/jobs/" + job["id"], headers=headers)).json()
                    if result["status"] in {"completed", "failed", "uncertain", "cancelled"}:
                        break
                    await asyncio.sleep(0.01)
                assert result["status"] == "completed"
                assert "gpt-6.1-terra" in result["message"]
                assert not result["usage_known"]
                assert result["entries"][0]["sourceIds"] == ["source"]
                assert calls == [("https://alternate.invalid/v1", "alt-key", "deepseek-v4.1-flash"),
                                 ("https://terra.invalid/v1", "terra-key", "gpt-6.1-terra")]
                quota = (await client.get("/v1/quota", headers=headers)).json()
                assert quota["daily_used"] == quota["user_daily_used"] == 2
                same = (await client.post("/v1/jobs", headers=headers, json=body)).json()
                assert same["id"] == job["id"] and len(calls) == 2
                attempts = app.state.store.provider_attempts(job["id"])
                assert [item["route_id"] for item in attempts] == ["alternate", "terra"]
                assert all(item["status"] != "dispatched" for item in attempts)
                assert "terra-key" not in json.dumps(result) + json.dumps(attempts)
        failover._COOLDOWNS.clear()

    asyncio.run(scenario())


def test_shutdown_during_attempt_commit_keeps_validated_result(tmp_path, monkeypatch):
    async def scenario():
        failover._COOLDOWNS.clear()
        settings = Settings(
            db_path=str(tmp_path / "shutdown.sqlite3"), api_enabled=True, api_key="primary-key",
            context_tokens=64000, max_output_tokens=4096, worker_concurrency=1,
            fallback_providers=(ProviderRoute("alternate", "https://alternate.invalid/v1", "deepseek-v4.1-flash", "alt-key"),),
            provider_route_order=("alternate", "primary"), failure_artifacts_enabled=False,
            cleanup_interval_seconds=60,
        )
        entered, release = threading.Event(), threading.Event()

        async def provider(current, snapshot, messages, *, trace=None):
            return ProviderOutcome("completed", "validated", [{"id": "saved", "category": "RECENT",
                "text": "known result", "sourceIds": ["source"], "kind": "USER_FACT"}],
                input_tokens=20, output_tokens=10, usage_known=True)

        monkeypatch.setattr("app.runner.organize", provider)
        app = create_app(settings)
        finish = app.state.store.finish_provider_attempt

        def blocked_finish(*args, **kwargs):
            entered.set()
            if not release.wait(3):
                raise RuntimeError("test commit was not released")
            return finish(*args, **kwargs)

        monkeypatch.setattr(app.state.store, "finish_provider_attempt", blocked_finish)
        async with app.router.lifespan_context(app):
            owner, token = register_owner(app.state.store)
            from app.schemas import JobRequest
            job = registered_job(app.state.store, owner, token, JobRequest.model_validate({
                "idempotency_key": "shutdown-known-result", "messages": [{"id": "source", "role": "user", "content": "synthetic"}]}))
            app.state.runner.notify()
            assert await asyncio.to_thread(entered.wait, 2)
            stopping = asyncio.create_task(app.state.runner.stop())
            await asyncio.sleep(0.02)
            assert not stopping.done()
            release.set()
            await asyncio.wait_for(stopping, 3)
            result = app.state.store.get_job(owner, job["id"])
            assert result["status"] == "completed"
            assert result["entries"][0]["id"] == "saved"
            assert app.state.store.provider_attempts(job["id"])[0]["status"] == "completed"
            assert app.state.store.quota(owner)["daily_used"] == 1
        failover._COOLDOWNS.clear()

    asyncio.run(scenario())
