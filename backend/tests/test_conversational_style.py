import json

from app.provider import CURRENT_PROTOCOL_VERSION, normalize_entries, provider_messages


def test_style_uses_the_same_recap_viewpoint_without_changing_its_source_rules():
    sources = [
        {"id": "a1", "role": "assistant", "content": "也许先留着这个疑问？"},
        {"id": "u1", "role": "user", "content": "好，这次先不急着解释。"},
        {"id": "a2", "role": "assistant", "content": "那就先放在这里。"},
    ]
    previous, latest = (provider_messages(sources, protocol_version=version) for version in (11, 12))
    old_body, body = (json.loads(prompt[1]["content"]) for prompt in (previous, latest))
    assert CURRENT_PROTOCOL_VERSION == 12
    assert latest == provider_messages(sources)
    assert latest[0] == previous[0]
    for field in ("source_messages", "context_messages", "output_schema"):
        assert body[field] == old_body[field]
    assert body["output_requirements"].startswith(old_body["output_requirements"])
    assert "人称规则同样覆盖 RESPONSE_STYLE" in body["output_requirements"]
    assert "普通记忆尽量直接留下原话的内容" in body["output_requirements"]
    text = "你那时先用简短反问接住疑问，后来顺着我说先放在这里。"
    result = normalize_entries(json.dumps({"entries": [{
        "category": "RECENT", "kind": "UNCONFIRMED", "text": text,
        "sourceIds": ["m1", "m3"], "continuityCue": "RESPONSE_STYLE"
    }]}, ensure_ascii=False), sources)
    assert result[0]["text"] == text
    assert result[0]["sourceIds"] == ["a1", "a2"]
    assert result[0]["kind"] == "UNCONFIRMED"
    assert result[0]["continuityCue"] == "RESPONSE_STYLE"
