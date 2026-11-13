import json
from pathlib import Path

from app.provider import CURRENT_PROTOCOL_VERSION, normalize_entries, provider_messages


def synthetic_messages():
    sample = Path(__file__).resolve().parents[2] / "fixtures" / "continuation-voice.json"
    return json.loads(sample.read_text(encoding="utf-8"))["source_messages"]


def test_source_first_version_preserves_the_quoted_input_and_previous_schema():
    sources = synthetic_messages()
    old = provider_messages(sources, protocol_version=9)
    current = provider_messages(sources, protocol_version=10)
    old_body, current_body = (json.loads(prompt[1]["content"]) for prompt in (old, current))
    assert CURRENT_PROTOCOL_VERSION >= 10
    assert current == provider_messages(sources, protocol_version=10)
    assert len(current) == 2
    assert current[0] == old[0]
    assert current_body["source_messages"] == old_body["source_messages"]
    assert current_body["output_schema"] == old_body["output_schema"]
    assert current_body["context_messages"] == old_body["context_messages"] == []
    assert [(message["role"], message["content"]) for message in current_body["source_messages"]] == [
        (message["role"], message["content"]) for message in sources
    ]
    assert current_body["output_requirements"].startswith(old_body["output_requirements"])
    assert "我认同/我决定" in current_body["output_requirements"]
    assert "必要时才忠实压缩" in current_body["output_requirements"]


def test_natural_recap_preserves_words_and_attribution_without_promoting_mixed_sources():
    sources = synthetic_messages()
    text = "你提出可以先想清楚联系是为了什么。我觉得值得想想，但还没决定采用。"
    document = json.dumps({"entries": [
        {"category": "RECENT", "kind": "USER_FACT", "text": text,
         "sourceIds": ["m2", "m3"], "continuityCue": "OPEN_THREAD"},
        {"category": "RECENT", "kind": "USER_FACT", "text": sources[4]["content"],
         "sourceIds": ["m5"], "continuityCue": "CORRECTION"},
    ]}, ensure_ascii=False)
    entries = normalize_entries(document, sources)
    assert entries[0]["text"] == text
    assert entries[0]["kind"] == "UNCONFIRMED"
    assert entries[0]["sourceIds"] == ["voice-2", "voice-3"]
    assert entries[1]["text"] == sources[4]["content"]
    assert entries[1]["kind"] == "USER_FACT"
    assert entries[1]["continuityCue"] == "CORRECTION"
