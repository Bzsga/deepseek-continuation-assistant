import json
from pathlib import Path

from app.provider import CURRENT_PROTOCOL_VERSION, normalize_entries, provider_messages


def test_natural_recollection_adds_expression_rules_without_changing_scope_or_schema():
    sample = Path(__file__).resolve().parents[2] / "fixtures" / "continuation-recollection.json"
    sources = json.loads(sample.read_text(encoding="utf-8"))["source_messages"]
    old = provider_messages(sources, protocol_version=10)
    latest = provider_messages(sources, protocol_version=11)
    old_body, latest_body = (json.loads(prompt[1]["content"]) for prompt in (old, latest))
    assert CURRENT_PROTOCOL_VERSION >= 11
    assert latest == provider_messages(sources, protocol_version=11)
    assert len(latest) == 2 and latest[0] == old[0]
    for field in ("source_messages", "context_messages", "output_schema"):
        assert latest_body[field] == old_body[field]
    assert [(item["role"], item["content"]) for item in latest_body["source_messages"]] == [
        (item["role"], item["content"]) for item in sources
    ]
    assert latest_body["output_requirements"].startswith(old_body["output_requirements"])
    assert "不把历史待答写成要求马上续题的命令" in latest_body["output_requirements"]


def test_vague_recollection_and_one_time_response_survive_validation_with_role_attribution():
    sources = [
        {"id": "u", "role": "user", "content": "我也说不清。刚才那句玩笑，这次我接得住。"},
        {"id": "a", "role": "assistant", "content": "也许是怀念？这只是我的猜测。"},
    ]
    text = "我也说不清。你猜也许是怀念，我还没想定；刚才那句玩笑，这次我接得住。"
    output = json.dumps({"entries": [{
        "category": "RECENT", "kind": "USER_FACT", "text": text, "sourceIds": ["m1", "m2"]
    }]}, ensure_ascii=False)
    result = normalize_entries(output, sources)
    assert result[0]["text"] == text
    assert result[0]["kind"] == "UNCONFIRMED"
    assert result[0]["sourceIds"] == ["u", "a"]
