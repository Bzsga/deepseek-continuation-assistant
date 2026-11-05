import asyncio
import json
from decimal import Decimal

import httpx
import pytest

from app.config import Settings
from app.provider import normalize_entries, organize, snapshot_for


SOURCES = [{"id": "u1", "role": "user", "content": "管理员排除了门边旧柜子与走廊，收纳须另想。"},
           {"id": "a1", "role": "assistant", "content": "管理员没有承诺夜间保管。"}]
PREFIX = "明确排除门边旧柜子与走廊，收纳须另想。"


def document(text, kind="USER_FACT"):
    return json.dumps({"entries": [{"category": "DECISIONS", "text": text,
                                    "sourceIds": ["m1", "m2"], "kind": kind}]}, ensure_ascii=False)


@pytest.mark.parametrize("suffix", ["','kind':'USER_FACT'", '\",\"kind\":\"USER_FACT\"', "' , 'kind' : 'USER_FACT'"])
def test_only_matching_terminal_kind_fragment_is_removed(suffix):
    warnings, diagnostic = set(), {}
    result = normalize_entries(document(PREFIX + suffix), SOURCES, warnings=warnings, diagnostic=diagnostic)
    assert result[0]["text"] == PREFIX
    # Structural repair does not weaken independent source-role validation.
    assert result[0]["kind"] == "UNCONFIRMED" and result[0]["sourceIds"] == ["u1", "a1"]
    assert warnings == {"user_fact_role", "schema_text_tail_removed"}
    assert diagnostic["schema_text_tail_removed"] == 1


@pytest.mark.parametrize("text", [
    PREFIX + "','kind':'UNCONFIRMED'",  # Not a duplicate of the actual field.
    PREFIX + "','kind':'FUTURE_KIND'",
    PREFIX + "','category':'DECISIONS'",
    PREFIX + "','kind':'USER_FACT'，后面仍有文字。",
    "','kind':'USER_FACT'",
    "没有完整句末标点','kind':'USER_FACT'",
    PREFIX + "',\"kind\":\"USER_FACT\"",  # Mismatched serialization quote styles.
    PREFIX + "','kind':'USER_FACT'}",  # Do not extend beyond the observed shape.
])
def test_other_text_is_preserved_without_guessing(text):
    warnings = set()
    result = normalize_entries(document(text), SOURCES, warnings=warnings)
    assert result[0]["text"] == text
    assert "schema_text_tail_removed" not in warnings


@pytest.mark.parametrize("as_reference", [False, True])
def test_real_quoted_schema_in_primary_or_reference_is_not_removed(as_reference):
    # A real source example protects the quote even if its spacing differs.
    quoted = {"id": "u2", "role": "user", "content": "我们讨论过 ' , 'kind' : 'USER_FACT' 这段序列化文本。"}
    messages = SOURCES if as_reference else SOURCES + [quoted]
    references = [quoted] if as_reference else []
    text = PREFIX + "','kind':'USER_FACT'"
    warnings = set()
    result = normalize_entries(document(text), messages, context_messages=references, warnings=warnings)
    assert result[0]["text"] == text and "schema_text_tail_removed" not in warnings


def test_http_result_reports_narrow_repair_without_repair_request(monkeypatch):
    requests, real_client = [], httpx.AsyncClient

    def handler(request):
        requests.append(request)
        return httpx.Response(200, json={"usage": {"prompt_tokens": 100, "completion_tokens": 70},
            "choices": [{"finish_reason": "stop", "message": {"content": document(PREFIX + "','kind':'USER_FACT'")}}]})

    def client(**kwargs):
        return real_client(transport=httpx.MockTransport(handler), **kwargs)

    monkeypatch.setattr("app.provider.httpx.AsyncClient", client)
    settings = Settings(api_enabled=True, api_key="synthetic-tail-key", base_url="https://synthetic.invalid/v1",
                        model="synthetic", context_tokens=64000, max_output_tokens=2048,
                        input_price_per_million=Decimal("2"), output_price_per_million=Decimal("8"))
    snapshot, _ = snapshot_for(settings, SOURCES)
    result = asyncio.run(organize(settings, snapshot, SOURCES))
    assert result.status == "completed" and len(requests) == 1
    assert result.entries[0]["text"] == PREFIX
    assert "[schema_text_tail_removed]" in result.message and "原文未修改" in result.message
