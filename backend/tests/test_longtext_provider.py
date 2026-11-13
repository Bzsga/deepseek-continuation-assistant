import asyncio
import json
from decimal import Decimal

import httpx
import pytest

from app.config import Settings
from app.provider import ProtocolError, normalize_entries, organize, provider_messages, snapshot_for


@pytest.fixture
def settings():
    return Settings(
        api_enabled=True, api_key="synthetic-longtext-key", base_url="https://synthetic.invalid/v1",
        model="deepseek-synthetic", context_tokens=64000, max_output_tokens=2048,
        input_price_per_million=Decimal("2"), output_price_per_million=Decimal("8"),
    )


@pytest.fixture
def primary():
    return [{"id": "new-user", "role": "user", "content": "改一下，林舟暂不离开小镇；那是小说人物，不是我本人。先听完再建议。"},
            {"id": "new-assistant", "role": "assistant", "content": "先听你讲完。林舟接下来如何回应旧友，还没讨论。"}]


@pytest.fixture
def context():
    return [{"id": "earlier-user", "role": "user", "content": "小说人物林舟原计划离开小镇；不用猜测性别。"},
            {"id": "earlier-assistant", "role": "assistant", "content": "先听你讲完，再一起讨论人物的选择。"}]


def row(text, sources, *, kind="UNCONFIRMED", cue=None):
    return {"category": "RECENT", "text": text, "sourceIds": sources, "kind": kind, "continuityCue": cue}


def content(rows):
    return json.dumps({"entries": rows}, ensure_ascii=False)


def mock_response(monkeypatch, rows):
    requests = []
    real_client = httpx.AsyncClient

    def handler(request):
        requests.append(json.loads(request.content))
        return httpx.Response(200, json={
            "usage": {"prompt_tokens": 150, "completion_tokens": 90},
            "choices": [{"finish_reason": "stop", "message": {"content": content(rows)}}],
        })

    def client(**kwargs):
        return real_client(transport=httpx.MockTransport(handler), **kwargs)

    monkeypatch.setattr("app.provider.httpx.AsyncClient", client)
    return requests


def test_v9_has_separate_reference_scope_stable_primary_aliases_and_full_reservation(settings, primary, context):
    snapshot, reserve = snapshot_for(settings, primary, context_messages=context)
    prompt = provider_messages(primary, settings.max_output_tokens, context_messages=context)
    payload = json.loads(prompt[1]["content"])
    assert payload["source_messages"] == [{**source, "id": f"m{index+1}"} for index, source in enumerate(primary)]
    assert payload["context_messages"] == [{**source, "id": f"m{index+3}"} for index, source in enumerate(context)]
    assert payload["source_messages"] == json.loads(provider_messages(primary)[1]["content"])["source_messages"]
    actual_bound = sum(len(item["content"].encode("utf-8")) for item in prompt) + 1024
    assert snapshot["protocol_version"] == 10 and snapshot["input_bound"] == actual_bound
    assert reserve == 2 * actual_bound + 8 * settings.max_output_tokens
    assert snapshot["input_bound"] > snapshot_for(settings, primary)[0]["input_bound"]
    assert all("content" not in key and "messages" not in key for key in snapshot)
    requirements = payload["output_requirements"]
    for phrase in ("每条结果至少引用一个 source_messages", "不能独立重复归档", "此前说法与本段改动",
                   "小说人物", "不补写原文未明确的性别", "不推断其他分段的结局", "沉默或继续交谈不代表认可"):
        assert phrase in requirements


def test_context_only_rows_are_isolated_without_discarding_valid_corrections(primary, context):
    rows = [row("原计划离开小镇。", ["m3"], kind="USER_FACT"),
            row("小说人物林舟由离开改为暂留，非用户本人。", ["m1", "m3"], kind="USER_FACT", cue="CORRECTION"),
            row("旧助手先听完，再讨论一个方向。", ["m2", "m4"], kind="ASSISTANT_SUGGESTION", cue="RESPONSE_STYLE")]
    warnings, diagnostic = set(), {}
    result = normalize_entries(content(rows), primary, context_messages=context, warnings=warnings, diagnostic=diagnostic)
    assert [entry["text"] for entry in result] == [entry["text"] for entry in rows[1:]]
    assert result[0]["sourceIds"] == ["new-user", "earlier-user"]
    assert result[0]["kind"] == "USER_FACT" and result[0]["continuityCue"] == "CORRECTION"
    assert result[1]["sourceIds"] == ["new-assistant", "earlier-assistant"]
    assert result[1]["continuityCue"] == "RESPONSE_STYLE"
    assert warnings == {"context_only_entries_omitted"}
    assert diagnostic["context_only_entries_omitted"] == 1


def test_reference_roles_cannot_promote_assistant_claim_to_user_fact(primary, context):
    result = normalize_entries(content([row("讨论中的说法。", ["m1", "m4"], kind="USER_FACT", cue="PREFERENCE")]),
                               primary, context_messages=context)
    assert result[0]["text"] == "讨论中的说法。"
    assert result[0]["kind"] == "UNCONFIRMED" and result[0]["continuityCue"] is None
    with pytest.raises(ProtocolError, match="unknown_source"):
        normalize_entries(content([row("无对应来源", ["m999"])]), primary, context_messages=context)
    with pytest.raises(ProtocolError, match="content_shape"):
        provider_messages(primary, context_messages=[{**primary[0], "role": "assistant"}])


def test_split_source_reuses_alias_in_context_without_changing_primary_ownership(primary):
    context = [{**primary[0], "content": "同一长消息的较早片段"}]
    payload = json.loads(provider_messages(primary, context_messages=context)[1]["content"])
    assert payload["context_messages"][0]["id"] == payload["source_messages"][0]["id"] == "m1"
    result = normalize_entries(content([row("同一长消息中的更正", ["m1"], kind="USER_FACT")]),
                               primary, context_messages=context)
    assert result[0]["sourceIds"] == [primary[0]["id"]]


@pytest.mark.parametrize("version", [2, 3, 4, 5, 6, 7])
def test_old_snapshot_with_new_context_fails_before_network(settings, primary, context, monkeypatch, version):
    snapshot, _ = snapshot_for(settings, primary, context_messages=context)
    snapshot["protocol_version"] = version
    requests = mock_response(monkeypatch, [])
    result = asyncio.run(organize(settings, snapshot, primary, context_messages=context))
    assert result.status == "failed" and "[content_shape]" in result.message
    assert result.cost_micros == 0 and requests == []


def test_context_is_checked_against_reserved_budget_before_network(settings, primary, context, monkeypatch):
    snapshot, _ = snapshot_for(settings, primary)
    requests = mock_response(monkeypatch, [])
    result = asyncio.run(organize(settings, snapshot, primary, context_messages=context))
    assert result.status == "failed" and "[content_shape]" in result.message
    assert result.cost_micros == 0 and requests == []


def test_v8_one_call_keeps_grounded_results_and_reports_omitted_reference_rows(settings, primary, context, monkeypatch):
    rows = [row("参考区的旧内容不重复归档。", ["m3"]),
            row("本段改为暂留，人物不是用户本人。", ["m1", "m3"], kind="USER_FACT", cue="CORRECTION")]
    requests = mock_response(monkeypatch, rows)
    snapshot, _ = snapshot_for(settings, primary, context_messages=context)
    result = asyncio.run(organize(settings, snapshot, primary, context_messages=context))
    assert result.status == "completed" and len(requests) == 1
    assert requests[0]["messages"] == provider_messages(primary, 2048, context_messages=context)
    assert result.entries[0]["sourceIds"] == ["new-user", "earlier-user"] and len(result.entries) == 1
    assert "[context_only_entries_omitted]" in result.message and "未在本段重复归档" in result.message
    assert result.input_tokens == 150 and result.output_tokens == 90 and result.cost_micros == 1020
    assert all(source["content"] not in result.message for source in primary + context)


def test_only_reference_output_is_reported_as_no_primary_result_without_retry(settings, primary, context, monkeypatch):
    requests = mock_response(monkeypatch, [row("参考区的旧内容。", ["m3"])])
    snapshot, _ = snapshot_for(settings, primary, context_messages=context)
    result = asyncio.run(organize(settings, snapshot, primary, context_messages=context))
    assert result.entries == [] and len(requests) == 1
    assert "本段未得到可归档的正文结果" in result.message
    assert "整理完成" not in result.message and "其余本段结果已保留" not in result.message
