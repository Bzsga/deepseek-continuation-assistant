import asyncio
import json
from decimal import Decimal

import httpx
import pytest

from app.config import Settings
from app.provider import CURRENT_PROTOCOL_VERSION, ProtocolError, normalize_entries, organize, provider_messages, snapshot_for
from app.schemas import GeneratedEntry


CUES = {"PREFERENCE", "CORRECTION", "SHARED_LANGUAGE", "OPEN_THREAD"}
MISSING = object()


@pytest.fixture
def settings():
    return Settings(
        api_enabled=True, api_key="synthetic-continuity-key",
        base_url="https://synthetic.invalid/v1", model="deepseek-synthetic",
        context_tokens=64000, max_output_tokens=2048,
        input_price_per_million=Decimal("2"), output_price_per_million=Decimal("8"),
    )


@pytest.fixture
def messages():
    return [
        {"id": "synthetic:user-source", "role": "user", "content": "我喜欢简短回答。刚才说的是建议，下次再比较方案。"},
        {"id": "synthetic:assistant-source", "role": "assistant", "content": "我们暂把这个步骤称为小回看。"},
    ]


def entry(text="合成摘要", *, sources=None, kind="UNCONFIRMED", category="RECENT", cue=MISSING):
    result = {"category": category, "text": text, "sourceIds": sources or ["m1"], "kind": kind}
    if cue is not MISSING:
        result["continuityCue"] = cue
    return result


def document(rows):
    return json.dumps({"entries": rows}, ensure_ascii=False)


def without_generated_ids(rows):
    return [{key: value for key, value in row.items() if key != "id"} for row in rows]


def test_mixed_cues_preserve_order_content_and_legacy_entry_shape(messages):
    rows = [
        entry("我喜欢简短回答。", category="BACKGROUND", kind="USER_FACT", cue="PREFERENCE"),
        entry("刚才说的是建议。", category="DECISIONS", kind="USER_FACT", cue="CORRECTION"),
        entry("暂把这个步骤称为小回看。", sources=["m1", "m2"], kind="ASSISTANT_SUGGESTION", cue="SHARED_LANGUAGE"),
        entry("下次再比较方案。", sources=["m1", "m2"], cue="OPEN_THREAD"),
        entry("普通条目继续兼容旧结果。"),
        entry("显式空标记。", cue=None),
        entry("未知标记仍保留正文。", cue="FUTURE_SYNTHETIC_CUE"),
        entry("数字标记仍保留正文。", cue=7),
        entry("对象标记仍保留正文。", cue={"unexpected": "PREFERENCE"}),
    ]
    warnings = set()
    result = normalize_entries(document(rows), messages, warnings=warnings)
    expected = []
    source_map = {"m1": messages[0]["id"], "m2": messages[1]["id"]}
    for index, original in enumerate(rows):
        normalized = {**original, "sourceIds": [source_map[source] for source in original["sourceIds"]]}
        if index >= 6:
            normalized["continuityCue"] = None
        expected.append(normalized)

    assert without_generated_ids(result) == expected
    assert warnings == {"continuity_cue_ignored"}
    assert set(result[4]) == {"id", "category", "text", "sourceIds", "kind"}
    assert "continuityCue" in result[5] and result[5]["continuityCue"] is None


def test_user_specific_cues_need_user_source_without_overriding_role_rules(messages):
    rows = [
        entry("助手提出的偏好猜测。", sources=["m2"], kind="USER_FACT", cue="PREFERENCE"),
        entry("助手提出的更正。", sources=["m2"], cue="CORRECTION"),
        entry("助手使用的共同说法。", sources=["m2"], kind="USER_FACT", cue="SHARED_LANGUAGE"),
        entry("用户尚未明确的偏好。", sources=["m1"], cue="PREFERENCE"),
        entry("混合角色不能标用户明确偏好。", sources=["m1", "m2"], kind="USER_FACT", cue="PREFERENCE"),
    ]
    warnings = set()
    result = normalize_entries(document(rows), messages, warnings=warnings)
    assert all(row["continuityCue"] is None for row in result)
    assert all(row["kind"] == "UNCONFIRMED" for row in result)
    assert [row["text"] for row in result] == [row["text"] for row in rows]
    assert [row["category"] for row in result] == [row["category"] for row in rows]
    source_map = {"m1": messages[0]["id"], "m2": messages[1]["id"]}
    assert [row["sourceIds"] for row in result] == [[source_map[source] for source in row["sourceIds"]] for row in rows]
    assert warnings == {"continuity_cue_ignored", "user_fact_role"}


def test_optional_cue_does_not_make_unknown_source_acceptable(messages):
    with pytest.raises(ProtocolError, match="unknown_source"):
        normalize_entries(document([
            entry("错误来源不能因带标记而放行。", sources=["m999"], kind="USER_FACT", cue="PREFERENCE"),
        ]), messages)


@pytest.mark.parametrize("kind", ["ASSISTANT_SUGGESTION", "UNCONFIRMED"])
def test_response_style_keeps_concrete_observation_with_distinct_assistant_sources(messages, kind):
    sources = messages + [{"id": "synthetic:second-assistant", "role": "assistant", "content": "先用一句话回应，再提供两个选项。"}]
    row = entry("旧助手先简短回应，再提供两个可选方向。", sources=["m2", "m3"], kind=kind, cue="RESPONSE_STYLE")
    warnings = set()
    result = normalize_entries(document([row]), sources, warnings=warnings)
    assert without_generated_ids(result) == [{**row, "sourceIds": [sources[1]["id"], sources[2]["id"]]}]
    assert warnings == set()


@pytest.mark.parametrize("aliases,kind", [
    (["m2"], "ASSISTANT_SUGGESTION"),
    (["m2", "m2"], "UNCONFIRMED"),
    (["m1", "m2"], "ASSISTANT_SUGGESTION"),
    (["m2", "m3"], "USER_FACT"),
    (["m2", "m4"], "UNCONFIRMED"),
])
def test_response_style_without_repeated_assistant_evidence_keeps_text_as_ordinary_entry(messages, aliases, kind):
    sources = messages + [
        {"id": "synthetic:second-assistant", "role": "assistant", "content": "另一段合成回答。"},
        {"id": "synthetic:unknown-source", "role": "unknown", "content": "角色未知的合成资料。"},
    ]
    row = entry("可观察的内容仍然保留，标记不可靠时不能丢失正文。", sources=aliases, kind=kind, cue="RESPONSE_STYLE")
    warnings = set()
    result = normalize_entries(document([row]), sources, warnings=warnings)
    assert result[0]["continuityCue"] is None
    assert result[0]["text"] == row["text"] and result[0]["category"] == row["category"]
    assert result[0]["sourceIds"] == list(dict.fromkeys(sources[int(alias[1:]) - 1]["id"] for alias in aliases))
    assert result[0]["kind"] == ("UNCONFIRMED" if kind == "USER_FACT" else kind)
    assert "continuity_cue_ignored" in warnings


def test_generated_entry_accepts_absent_and_explicit_null_cue():
    absent = GeneratedEntry.model_validate(entry())
    explicit = GeneratedEntry.model_validate(entry(cue=None))
    assert absent.continuityCue is None and explicit.continuityCue is None
    assert "continuityCue" not in absent.model_fields_set
    assert "continuityCue" in explicit.model_fields_set


def test_v8_retains_priority_with_v6_schema_sources_and_output_budget(settings, messages):
    snapshot, reserve = snapshot_for(settings, messages)
    assert CURRENT_PROTOCOL_VERSION == snapshot["protocol_version"] == 8
    prompt = provider_messages(messages, snapshot["output_bound"], protocol_version=8)
    old_prompt = provider_messages(messages, snapshot["output_bound"], protocol_version=6)
    payload, old_payload = (json.loads(value[1]["content"]) for value in (prompt, old_prompt))
    schema = payload["output_schema"]["properties"]["entries"]
    old_schema = old_payload["output_schema"]["properties"]["entries"]
    assert schema["items"]["required"] == old_schema["items"]["required"] == ["category", "text", "sourceIds", "kind"]
    assert set(schema["items"]["properties"]["continuityCue"]["enum"]) == CUES | {"RESPONSE_STYLE", None}
    assert schema == old_schema
    assert schema["maxItems"] == old_schema["maxItems"] == 6
    for value in (prompt, old_prompt):
        assert "最多输出 6 项" in value[0]["content"]
        assert "不超过 409 字" in value[0]["content"]
        assert "完整 JSON 输出预算为 2048 tokens" in value[0]["content"]
    assert snapshot["output_bound"] == settings.max_output_tokens == 2048
    actual_bound = sum(len(item["content"].encode("utf-8")) for item in prompt) + 1024
    old_bound = sum(len(item["content"].encode("utf-8")) for item in old_prompt) + 1024
    assert snapshot["input_bound"] == actual_bound > old_bound
    assert reserve == 2 * actual_bound + 8 * snapshot["output_bound"]
    assert payload["source_messages"] == old_payload["source_messages"]
    assert prompt[0] == old_prompt[0]
    old_requirements = old_payload["output_requirements"].replace(
        "只引用本次 source_messages.id 实际提供的 m 别名，",
        "只引用本次 source_messages.id 或 context_messages.id 实际提供的 m 别名，",
    )
    assert payload["output_requirements"].startswith(old_requirements)
    priority = payload["output_requirements"][len(old_requirements):]
    assert "彼此不重复的用户明确交流要求、纠正和边界" in priority
    assert "不能只保留最后一条" in priority
    assert "同一要求被后续明确更新" in priority
    assert "全部含义、否定或条件及必要用户来源" in priority
    assert "先压缩普通助手内容建议和重复事实" in priority
    assert "RESPONSE_STYLE 不得挤掉用户明确要求" in priority


def test_http_extracts_cues_with_one_request_and_no_repair_call(settings, messages, monkeypatch):
    messages = messages + [{"id": "synthetic:second-assistant", "role": "assistant", "content": "先回应感受，再讨论一个方向。"}]
    requests, real_client = [], httpx.AsyncClient
    response = {
        "usage": {"prompt_tokens": 100, "completion_tokens": 80},
        "choices": [{"finish_reason": "stop", "message": {"content": document([
            entry("我喜欢简短回答。", kind="USER_FACT", cue="PREFERENCE"),
            entry("旧助手先简短回应，再提供一个讨论方向。", sources=["m2", "m3"], kind="ASSISTANT_SUGGESTION", cue="RESPONSE_STYLE"),
            entry("无效标记不触发第二次请求。", cue=False),
        ])}}],
    }

    def handler(request):
        assert request.method == "POST"
        assert request.url == "https://synthetic.invalid/v1/chat/completions"
        requests.append(json.loads(request.content))
        return httpx.Response(200, json=response)

    def client(**kwargs):
        return real_client(transport=httpx.MockTransport(handler), **kwargs)

    monkeypatch.setattr("app.provider.httpx.AsyncClient", client)
    snapshot, _ = snapshot_for(settings, messages)
    outcome = asyncio.run(organize(settings, snapshot, messages))
    assert outcome.status == "completed" and len(requests) == 1
    assert [row["continuityCue"] for row in outcome.entries] == ["PREFERENCE", "RESPONSE_STYLE", None]
    assert [row["sourceIds"] for row in outcome.entries] == [
        [messages[0]["id"]], [messages[1]["id"], messages[2]["id"]], [messages[0]["id"]]]
    assert "[continuity_cue_ignored]" in outcome.message
    assert outcome.usage_known and (outcome.input_tokens, outcome.output_tokens, outcome.cost_micros) == (100, 80, 840)
    request = requests[0]
    assert request["max_tokens"] == snapshot["output_bound"] == 2048
    assert request["messages"] == provider_messages(messages, 2048, protocol_version=8)
    wire = json.loads(request["messages"][1]["content"])
    assert "continuityCue" in wire["output_schema"]["properties"]["entries"]["items"]["properties"]
    assert "先覆盖本段彼此不重复的用户明确交流要求" in wire["output_requirements"]
    assert snapshot["input_bound"] == sum(len(item["content"].encode("utf-8")) for item in request["messages"]) + 1024
