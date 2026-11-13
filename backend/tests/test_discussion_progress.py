import json

from app.provider import CURRENT_PROTOCOL_VERSION, normalize_entries, provider_messages


def test_progress_protocol_is_an_additive_version_with_the_same_quoted_scope_and_schema():
    current = [{"id": "later-user", "role": "user", "content": "方案甲因时间不合适已排除；方案乙还没有决定。先讨论原因。"}]
    context = [{"id": "earlier-assistant", "role": "assistant", "content": "建议先比较甲乙的时间成本。"}]
    old = provider_messages(current, protocol_version=8, context_messages=context)
    new = provider_messages(current, protocol_version=9, context_messages=context)
    old_body, new_body = (json.loads(prompt[1]["content"]) for prompt in (old, new))
    assert CURRENT_PROTOCOL_VERSION == 9
    assert new == provider_messages(current, context_messages=context)
    assert new[0] == old[0]
    assert new_body["source_messages"] == old_body["source_messages"]
    assert new_body["context_messages"] == old_body["context_messages"]
    assert new_body["output_schema"] == old_body["output_schema"]
    assert new_body["output_requirements"].startswith(old_body["output_requirements"])
    assert "明确排除及理由" in new_body["output_requirements"]
    assert "来源当时的状态" in new_body["output_requirements"]
    assert "不从片段缺失推断解决或认可" in new_body["output_requirements"]


def test_progress_remains_source_grounded_without_promoting_mixed_discussion_to_user_fact():
    primary = [{"id": "new-user", "role": "user", "content": "方案乙还没决定，我想先理解时间成本。"}]
    reference = [{"id": "old-assistant", "role": "assistant", "content": "方案甲的时间成本可能较高。"}]
    document = json.dumps({"entries": [
        {"category": "RECENT", "kind": "USER_FACT", "text": "双方讨论时间成本；方案乙尚未决定。",
         "sourceIds": ["m1", "m2"], "continuityCue": "OPEN_THREAD"},
        {"category": "DECISIONS", "kind": "ASSISTANT_SUGGESTION", "text": "较早的时间成本建议。",
         "sourceIds": ["m2"]},
    ]}, ensure_ascii=False)
    result = normalize_entries(document, primary, context_messages=reference)
    assert len(result) == 1
    assert result[0]["kind"] == "UNCONFIRMED"
    assert result[0]["sourceIds"] == ["new-user", "old-assistant"]
    assert result[0]["continuityCue"] == "OPEN_THREAD"
