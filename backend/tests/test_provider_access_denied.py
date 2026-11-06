import asyncio
import json
from decimal import Decimal

import httpx
import pytest

from app.config import Settings
from app.failure_artifacts import ProviderTrace
from app.provider import organize, snapshot_for
from app.schemas import JobRequest
from app.store import Store


KNOWN_MESSAGE = "upstream access forbidden, please contact administrator"
SAFE_LABEL = "上游禁止访问，需要服务管理员处理后再继续"
PRIVATE_MARKER = "sk-synthetic-private-error-detail"
MESSAGES = [{"id": "synthetic-source", "role": "user", "content": "合成整理资料"}]


@pytest.fixture
def settings(tmp_path):
    return Settings(
        db_path=str(tmp_path / "access-denied.sqlite3"), api_enabled=True,
        api_key="sk-synthetic-provider-key", base_url="https://synthetic.invalid/v1",
        model="deepseek-v4.1-flash", max_response_bytes=4096,
        public_enabled=False, worker_enabled=False,
        global_budget_cny=Decimal("1"), monthly_budget_cny=Decimal("1"),
        user_budget_cny=Decimal("1"), per_job_limit_cny=Decimal("0.1"),
        input_price_per_million=Decimal("2"), output_price_per_million=Decimal("8"),
    )


def execute(settings, monkeypatch, raw, *, partial=False, encoding="identity"):
    calls, real_client = [], httpx.AsyncClient

    class Body(httpx.AsyncByteStream):
        async def __aiter__(self):
            yield raw
            if partial:
                raise httpx.ReadError(PRIVATE_MARKER)

    def handler(request):
        calls.append(request)
        return httpx.Response(502, stream=Body(), headers={"content-encoding": encoding})

    monkeypatch.setattr("app.provider.httpx.AsyncClient",
                        lambda **kwargs: real_client(transport=httpx.MockTransport(handler), **kwargs))
    snapshot, _ = snapshot_for(settings, MESSAGES)
    trace = ProviderTrace()
    outcome = asyncio.run(organize(settings, snapshot, MESSAGES, trace=trace))
    assert len(calls) == 1
    assert outcome.status == "uncertain" and outcome.entries == []
    assert not outcome.usage_known and not outcome.budget_violation
    assert outcome.cost_micros == outcome.reservation_floor == 0
    assert outcome.input_tokens == outcome.output_tokens == 0
    assert "HTTP 502" in outcome.message and "不会自动重试" in outcome.message
    assert PRIVATE_MARKER not in outcome.message and settings.api_key not in outcome.message
    return outcome, trace


def test_exact_upstream_access_denial_is_safe_and_preserves_accounting(settings, monkeypatch, caplog):
    raw = json.dumps({"error": {"message": KNOWN_MESSAGE, "private": PRIVATE_MARKER}}).encode()
    outcome, trace = execute(settings, monkeypatch, raw)
    assert SAFE_LABEL in outcome.message
    assert trace.body_read_complete and not trace.response_truncated
    assert PRIVATE_MARKER not in caplog.text and settings.api_key not in caplog.text

    store = Store(settings)
    store.initialize()
    token, _ = store.redeem(store.create_invitation())
    owner = store.authenticate(token)
    request = JobRequest(idempotency_key="access-denied-test-0001", messages=MESSAGES)
    job = store.create_job(owner, request)
    assert job["reserved_cny"] > 0
    assert store.claim_next()["id"] == job["id"]
    store.finish(job["id"], outcome)
    current = store.get_job(owner, job["id"])
    assert current["status"] == "uncertain" and current["request_counted"]
    assert current["reserved_cny"] == job["reserved_cny"] and current["cost_cny"] == 0
    assert not current["usage_known"] and not current["cost_known"]
    assert store.create_job(owner, request)["id"] == job["id"]
    assert store.quota(owner)["daily_used"] == 1


@pytest.mark.parametrize("raw", [
    json.dumps({"error": {"message": KNOWN_MESSAGE + " "}}).encode(),
    json.dumps({"error": {"message": PRIVATE_MARKER}}).encode(),
    json.dumps({"error": KNOWN_MESSAGE}).encode(),
    json.dumps({"message": KNOWN_MESSAGE}).encode(),
    json.dumps([{"error": {"message": KNOWN_MESSAGE}}]).encode(),
    b'{"error":{"message":"' + KNOWN_MESSAGE.encode() + b'","message":"other"}}',
    b'not JSON ' + PRIVATE_MARKER.encode(),
])
def test_other_502_bodies_keep_generic_label(settings, monkeypatch, raw):
    outcome, _ = execute(settings, monkeypatch, raw)
    assert SAFE_LABEL not in outcome.message and "上游网关异常" in outcome.message


@pytest.mark.parametrize("mode", ["incomplete", "oversized", "encoded"])
def test_only_complete_identity_body_can_identify_denial(settings, monkeypatch, mode):
    raw = json.dumps({"error": {"message": KNOWN_MESSAGE}}).encode()
    if mode == "oversized":
        raw += b" " * 5000
    outcome, trace = execute(settings, monkeypatch, raw, partial=mode == "incomplete",
                             encoding="gzip" if mode == "encoded" else "identity")
    assert SAFE_LABEL not in outcome.message and "上游网关异常" in outcome.message
    assert len(trace.response_body) <= settings.max_response_bytes
    if mode != "encoded":
        assert not trace.body_read_complete
