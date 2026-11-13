import asyncio
from dataclasses import replace
from decimal import Decimal
import json
import threading

import httpx
import pytest

from app.config import ProviderRoute, Settings
from app import failover
from app.failure_artifacts import ProviderTrace
from app.provider import ProviderOutcome, organize, snapshot_for


MESSAGES = [{"id": "source-one", "role": "user", "content": "这是合成测试，我喜欢简洁回复。"}]


@pytest.fixture(autouse=True)
def clear_health_preferences():
    failover._COOLDOWNS.clear()
    yield
    failover._COOLDOWNS.clear()


@pytest.fixture
def settings():
    return Settings(
        api_enabled=True, api_key="private-primary-key", model="deepseek-v4.1-flash",
        base_url="https://primary.invalid/v1", context_tokens=64000, max_output_tokens=4096,
        provider_timeout_seconds=180, fallback_slow_seconds=0.02,
        fallback_providers=(
            ProviderRoute("alternate", "https://alternate.invalid/v1", "deepseek-v4.1-flash", "private-alternate-key"),
            ProviderRoute("terra", "https://terra.invalid/v1", "gpt-6.1-terra", "private-terra-key"),
        ),
        provider_route_order=("alternate", "terra", "primary"),
    )


class Ledger:
    def __init__(self, deny_at=None):
        self.starts, self.finishes = [], []
        self.deny_at = deny_at

    def begin_provider_attempt(self, job_id, metadata):
        if len(self.starts) == self.deny_at:
            return None
        attempt_id = "attempt-" + str(len(self.starts))
        self.starts.append({"id": attempt_id, "job_id": job_id, **metadata})
        return attempt_id

    def finish_provider_attempt(self, attempt_id, outcome):
        self.finishes.append((attempt_id, outcome))


def snapshot(settings):
    value, _ = snapshot_for(settings, MESSAGES)
    value["access_mode"] = "public"
    return value


def success(**extra):
    return ProviderOutcome("completed", "已保留来源。", [{"text": "有效结果", "sourceIds": ["source-one"]}],
                           input_tokens=20, output_tokens=10, usage_known=True, **extra)


def run(settings, ledger, provider, **kwargs):
    return asyncio.run(failover.organize_with_fallback(
        settings, snapshot(settings), MESSAGES, store=ledger, job_id="job-one",
        organize_one=provider, **kwargs))


def test_measured_fast_route_is_first_and_dispatch_metadata_contains_no_secrets(settings):
    ledger, calls = Ledger(), []

    async def provider(current, saved, messages, **kwargs):
        calls.append((current.base_url, current.model, saved["model"]))
        return success()

    outcome = run(settings, ledger, provider)
    assert calls == [("https://alternate.invalid/v1", "deepseek-v4.1-flash", "deepseek-v4.1-flash")]
    assert outcome.status == "completed" and "deepseek-v4.1-flash" in outcome.message
    assert "1 条线路" in outcome.message
    assert [item["ordinal"] for item in ledger.starts] == [0]
    assert "private-" not in json.dumps(ledger.starts) + repr(outcome)


def test_slow_attempt_is_cancelled_before_next_dispatch_and_unknown_usage_stays_unknown(settings):
    ledger, events = Ledger(), []
    trace = ProviderTrace()

    async def provider(current, saved, messages, trace):
        events.append(current.base_url)
        if "alternate" in current.base_url:
            trace.request = {"messages": "synthetic-first-body"}
            trace.response_body.extend(b"synthetic-first-response")
            try:
                await asyncio.sleep(10)
            finally:
                events.append("first-cancelled")
        assert trace.request is None and not trace.response_body
        trace.request = {"messages": "synthetic-second-body"}
        return success()

    outcome = run(settings, ledger, provider, trace=trace)
    assert events == ["https://alternate.invalid/v1", "first-cancelled", "https://terra.invalid/v1"]
    assert [item[1].status for item in ledger.finishes] == ["uncertain", "completed"]
    assert outcome.status == "completed" and not outcome.usage_known
    assert (outcome.input_tokens, outcome.output_tokens) == (20, 10)
    assert "gpt-6.1-terra" in outcome.message and "2 条线路" in outcome.message
    assert trace.request == {"messages": "synthetic-second-body"}


def test_failed_route_cools_and_next_job_prefers_healthy_route_without_extra_probe(settings):
    calls, ledger = [], Ledger()

    async def provider(current, saved, messages, **kwargs):
        calls.append(current.base_url)
        if "alternate" in current.base_url:
            return ProviderOutcome("failed", "[unknown_source] 来源验证失败。", [], input_tokens=8, output_tokens=5, usage_known=True)
        return success()

    first = run(settings, ledger, provider)
    assert first.status == "completed" and first.usage_known
    assert (first.input_tokens, first.output_tokens) == (28, 15)
    second = run(settings, Ledger(), provider)
    assert second.status == "completed"
    assert calls == ["https://alternate.invalid/v1", "https://terra.invalid/v1", "https://terra.invalid/v1"]


def test_all_cooling_routes_still_receive_one_attempt_each_in_configured_order(settings):
    calls = []

    async def provider(current, saved, messages, **kwargs):
        calls.append(current.base_url)
        return ProviderOutcome("uncertain", "[transport] 未确认。", [])

    for unused in range(2):
        ledger = Ledger()
        result = run(settings, ledger, provider)
        assert result.status == "uncertain" and len(ledger.starts) == 3
        assert len(ledger.finishes) == 3
    assert calls[:3] == calls[3:] == ["https://alternate.invalid/v1", "https://terra.invalid/v1", "https://primary.invalid/v1"]


def test_denied_second_dispatch_does_not_make_an_uncounted_provider_call(settings):
    ledger, calls = Ledger(deny_at=1), []

    async def provider(current, saved, messages, **kwargs):
        calls.append(current.model)
        return ProviderOutcome("uncertain", "[transport] 未确认。", [])

    result = run(settings, ledger, provider)
    assert result.status == "uncertain" and "fallback_stopped" in result.message
    assert len(calls) == len(ledger.starts) == len(ledger.finishes) == 1


def test_denied_first_dispatch_never_calls_provider(settings):
    async def provider(*args, **kwargs):
        pytest.fail("denied dispatch reached the provider")

    result = run(settings, Ledger(deny_at=0), provider)
    assert result.status == "failed" and not result.entries


def test_cancellation_during_network_seals_attempt_and_never_starts_fallback(settings):
    ledger, calls = Ledger(), []

    async def scenario():
        began = asyncio.Event()

        async def provider(current, saved, messages, **kwargs):
            calls.append(current.model)
            began.set()
            await asyncio.sleep(10)

        task = asyncio.create_task(failover.organize_with_fallback(
            settings, snapshot(settings), MESSAGES, store=ledger, job_id="cancelled-job", organize_one=provider))
        await began.wait()
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task

    asyncio.run(scenario())
    assert len(calls) == len(ledger.starts) == len(ledger.finishes) == 1
    assert ledger.finishes[0][1].status == "uncertain"


def test_cancellation_during_sqlite_dispatch_drains_commit_without_network(settings):
    began, release = threading.Event(), threading.Event()

    class SlowLedger(Ledger):
        def begin_provider_attempt(self, *args):
            began.set()
            release.wait(2)
            return super().begin_provider_attempt(*args)

    ledger = SlowLedger()

    async def provider(*args, **kwargs):
        pytest.fail("cancelled committed dispatch reached network")

    async def scenario():
        task = asyncio.create_task(failover.organize_with_fallback(
            settings, snapshot(settings), MESSAGES, store=ledger, job_id="cancelled-job", organize_one=provider))
        await asyncio.to_thread(began.wait, 2)
        task.cancel()
        release.set()
        with pytest.raises(asyncio.CancelledError):
            await task

    asyncio.run(scenario())
    assert len(ledger.starts) == len(ledger.finishes) == 1


def test_known_reply_remains_available_when_shutdown_arrives_during_ledger_commit(settings):
    began, release = threading.Event(), threading.Event()
    trace = ProviderTrace()

    class SlowFinishLedger(Ledger):
        def finish_provider_attempt(self, attempt_id, outcome):
            if outcome.status == "completed":
                began.set()
                release.wait(2)
            super().finish_provider_attempt(attempt_id, outcome)

    ledger = SlowFinishLedger()

    async def provider(current, saved, messages, **kwargs):
        if "alternate" in current.base_url:
            await asyncio.sleep(10)
        return success()

    async def scenario():
        task = asyncio.create_task(failover.organize_with_fallback(
            settings, snapshot(settings), MESSAGES, store=ledger, job_id="known-reply-job",
            organize_one=provider, trace=trace))
        await asyncio.to_thread(began.wait, 2)
        task.cancel()
        release.set()
        with pytest.raises(asyncio.CancelledError):
            await task

    asyncio.run(scenario())
    assert [item[1].status for item in ledger.finishes] == ["uncertain", "completed"]
    known = trace.confirmed_outcome
    assert known.status == "completed" and known.entries
    assert "gpt-6.1-terra" in known.message and "2 条线路" in known.message
    assert not known.usage_known and (known.input_tokens, known.output_tokens) == (20, 10)
    assert "private-" not in repr(trace)


@pytest.mark.parametrize("mode", ["priced", "invitation", "mock", "no_fallback"])
def test_legacy_and_priced_paths_do_not_create_a_failover_ledger(settings, mode):
    if mode == "priced":
        settings = replace(settings, input_price_per_million=Decimal("2"), output_price_per_million=Decimal("8"))
    elif mode == "mock":
        settings = replace(settings, mock_mode=True)
    elif mode == "no_fallback":
        settings = replace(settings, fallback_providers=())
    saved = snapshot(settings)
    if mode == "invitation":
        saved["access_mode"] = "invitation"
    ledger, calls = Ledger(), []

    async def provider(current, saved, messages, **kwargs):
        calls.append(current.model)
        return success()

    result = asyncio.run(failover.organize_with_fallback(
        settings, saved, MESSAGES, store=ledger, job_id="legacy-job", organize_one=provider))
    assert result.status == "completed" and calls == [settings.model]
    assert not ledger.starts and not ledger.finishes
    assert failover.execution_timeout(settings, saved) == settings.provider_timeout_seconds


def test_budget_violation_stops_without_dispatching_another_model(settings):
    ledger = Ledger()

    async def provider(current, saved, messages, **kwargs):
        return ProviderOutcome("uncertain", "[usage_bound] 输出用量越界。", [], budget_violation=True)

    result = run(settings, ledger, provider)
    assert result.budget_violation and len(ledger.starts) == 1


def test_smaller_provider_context_is_not_sent_an_incompatible_admitted_scope(settings):
    settings = replace(settings, fallback_providers=(replace(settings.fallback_providers[0], context_tokens=512),))
    ledger, calls = Ledger(), []

    async def provider(current, saved, messages, **kwargs):
        calls.append(current.base_url)
        return success()

    result = run(settings, ledger, provider)
    assert result.status == "completed" and calls == [settings.base_url]
    assert ledger.starts[0]["ordinal"] == 0


def test_total_timeout_covers_any_last_route_under_cooldown_sort(settings):
    settings = replace(settings, fallback_slow_seconds=30)
    assert failover.execution_timeout(settings, snapshot(settings)) == 155


@pytest.mark.parametrize("model", ["gpt-6.1-terra", "gpt-4o", "deepseek-v4.1-flash"])
def test_terra_uses_completion_budget_and_legacy_request_parameters_remain_stable(settings, monkeypatch, model):
    requests, real_client = [], httpx.AsyncClient

    def handler(request):
        requests.append(json.loads(request.content))
        return httpx.Response(200, json={
            "usage": {"prompt_tokens": 20, "completion_tokens": 10},
            "choices": [{"finish_reason": "stop", "message": {"content": json.dumps({"entries": []})}}],
        })

    monkeypatch.setattr("app.provider.httpx.AsyncClient",
                        lambda **kwargs: real_client(transport=httpx.MockTransport(handler), **kwargs))
    settings = replace(settings, model=model)
    result = asyncio.run(organize(settings, snapshot(settings), MESSAGES))
    assert result.status == "completed" and len(requests) == 1
    request = requests[0]
    if model == "gpt-6.1-terra":
        assert request["max_completion_tokens"] == 4096 and "max_tokens" not in request
        assert request["reasoning_effort"] == "low" and "thinking" not in request
    else:
        assert request["max_tokens"] == 4096 and "max_completion_tokens" not in request
        assert request.get("reasoning_effort") == ("none" if model.startswith("deepseek") else None)
