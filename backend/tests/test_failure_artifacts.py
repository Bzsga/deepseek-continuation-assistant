"""Private failure evidence and offline parser replay; no real provider calls."""
import base64
import copy
import json
import os
import socket
import stat
import uuid

import pytest

from app import failure_artifacts as artifacts
from app import provider


PRIVATE = "SYNTHETIC_TRANSCRIPT_MUST_NOT_APPEAR_IN_METADATA"


def source_messages(content=PRIVATE):
    return [
        {"id": "original-user-id", "role": "user", "content": content},
        {"id": "original-assistant-id", "role": "assistant", "content": "A synthetic suggestion, not an accepted decision."},
    ]


def entry(**changes):
    return {"category": "RECENT", "text": "A synthetic fact", "sourceIds": ["m1"],
            "kind": "USER_FACT", **changes}


def trace_for(content=None, *, messages=None, **changes):
    messages = messages or source_messages()
    content = content if content is not None else json.dumps({"entries": [entry()]})
    envelope = {"choices": [{"finish_reason": "stop", "message": {"content": content}}],
                "usage": {"prompt_tokens": 100, "completion_tokens": 50}}
    values = {
        "request": {"model": "synthetic-model", "messages": provider.provider_messages(messages),
                    "max_tokens": 2048, "stream": False, "response_format": {"type": "json_object"}},
        "response_body": bytearray(json.dumps(envelope, ensure_ascii=False).encode()),
        "http_status": 200, "body_read_complete": True, "response_truncated": False,
    }
    values.update(changes)
    return artifacts.ProviderTrace(**values)


def snapshot(**changes):
    return {"model": "synthetic-model", "protocol_version": 4, "input_bound": 10000,
            "output_bound": 2048, "mock_mode": False, "access_mode": "public", **changes}


def save(store, trace=None, *, job_id=None, messages=None, **kwargs):
    return store.save(job_id or str(uuid.uuid4()), trace or trace_for(), snapshot(),
                      messages or source_messages(), "failed", "[schema] Result structure failed validation.", **kwargs)


@pytest.fixture
def clock(monkeypatch):
    value = [1_800_000_000.0]
    monkeypatch.setattr(artifacts.time, "time", lambda: value[0])
    return value


@pytest.fixture
def store(tmp_path):
    return artifacts.ArtifactStore(tmp_path / "private" / "failure-artifacts.sqlite3")


def test_capture_preserves_request_response_and_source_mapping(store):
    trace = trace_for()
    before = copy.deepcopy(trace.request)
    response = bytes(trace.response_body)
    saved = save(store, trace)
    payload = store.load(saved["id"])
    assert payload["request"] == before
    assert base64.b64decode(payload["response"]["body_base64"]) == response
    assert payload["response"]["http_status"] == 200
    assert payload["response"]["body_read_complete"] is True
    assert payload["response"]["truncated"] is False
    assert payload["messages"] == source_messages()
    assert payload["source_id_map"] == {"m1": "original-user-id", "m2": "original-assistant-id"}
    assert payload["complete_original"] is True
    assert payload["redacted"] is False
    trace.request["model"] = "changed-after-capture"
    trace.response_body.clear()
    assert store.load(saved["id"])["request"] == before


def test_metadata_and_repr_do_not_expose_content(store):
    trace = trace_for(PRIVATE)
    assert PRIVATE not in repr(trace)
    assert "source_messages" not in repr(trace)
    saved = save(store, trace)
    rows = store.list_recent()
    assert len(rows) == 1 and rows[0]["id"] == saved["id"]
    encoded = json.dumps(rows)
    assert PRIVATE not in encoded
    assert "original-user-id" not in encoded
    assert "body_base64" not in encoded
    assert "source_messages" not in encoded


def test_snapshot_is_allowlisted_and_error_text_is_fixed(store):
    saved = store.save(str(uuid.uuid4()), trace_for(),
                       snapshot(base_url="https://secret.invalid/" + PRIVATE, api_key=PRIVATE,
                                admin_token=PRIVATE, feedback_worker_token=PRIVATE,
                                configuration_fingerprint=PRIVATE, headers={"Authorization": PRIVATE},
                                settings={"api_key": PRIVATE}),
                       source_messages("ordinary synthetic input"), "failed",
                       "[schema] " + PRIVATE)
    payload = store.load(saved["id"])
    assert payload["snapshot"]["protocol_version"] == 4
    assert not {"base_url", "api_key", "admin_token", "feedback_worker_token", "headers", "settings",
                "configuration_fingerprint"}.intersection(payload["snapshot"])
    assert PRIVATE not in payload["error_message"]
    assert PRIVATE not in json.dumps(store.list_recent())


@pytest.mark.parametrize("forbidden", ["headers", "url", "api_key"])
def test_request_cannot_capture_auth_or_transport_configuration(store, forbidden):
    trace = trace_for()
    trace.request[forbidden] = PRIVATE
    with pytest.raises(artifacts.ArtifactError):
        save(store, trace)
    assert store.list_recent() == []


def test_secret_echo_is_redacted_in_raw_and_nested_json_forms(store):
    secret = 'synthetic-key-"\\-雪-' + uuid.uuid4().hex
    forms = {secret}
    for _ in range(3):
        forms.update(json.dumps(value, ensure_ascii=ascii_only)[1:-1]
                     for value in tuple(forms) for ascii_only in (False, True))
    body = " | ".join(sorted(forms)).encode("utf-8")
    messages = source_messages("An exact echo: " + secret)
    trace = trace_for(messages=messages, response_body=bytearray(body))
    saved = save(store, trace, messages=messages, secrets=[secret])
    payload = store.load(saved["id"])
    redacted_body = base64.b64decode(payload["response"]["body_base64"])
    assert all(value.encode("utf-8") not in redacted_body for value in forms)
    assert secret not in payload["messages"][0]["content"]
    assert secret not in json.dumps(payload["request"], ensure_ascii=False)
    assert payload["redacted"] is True
    assert payload["response"]["redacted"] is True
    assert payload["complete_original"] is False


def test_secret_echo_with_fully_unicode_escaped_ascii_is_redacted(store):
    secret = "SYNTHETIC_ASCII_TOKEN_" + uuid.uuid4().hex
    escaped = "".join(f"\\u{ord(character):04x}" for character in secret)
    original = ('{"echo":"' + escaped + '","unrelated":"keep this"}').encode()
    assert json.loads(original)["echo"] == secret
    saved = save(store, trace_for(response_body=bytearray(original)), secrets=[secret])
    payload = store.load(saved["id"])
    cleaned = json.loads(base64.b64decode(payload["response"]["body_base64"]))
    assert secret not in cleaned["echo"]
    assert cleaned["unrelated"] == "keep this"
    assert payload["response"]["redacted"] is True
    assert payload["complete_original"] is False


def test_secret_echo_with_mixed_unicode_escapes_in_nested_json_string_is_redacted(store):
    secret = "SYNTHETIC_NESTED_TOKEN_" + uuid.uuid4().hex
    mixed = "".join(f"\\u{ord(character):04x}" if index % 2 == 0 else character
                    for index, character in enumerate(secret))
    inner = '{"echo":"' + mixed + '"}'
    original = json.dumps({"error": {"message": inner}, "unrelated": 17}).encode()
    assert json.loads(json.loads(original)["error"]["message"])["echo"] == secret
    saved = save(store, trace_for(response_body=bytearray(original)), secrets=[secret])
    payload = store.load(saved["id"])
    cleaned = json.loads(base64.b64decode(payload["response"]["body_base64"]))
    inner_cleaned = json.loads(cleaned["error"]["message"])
    assert secret not in inner_cleaned["echo"]
    assert cleaned["unrelated"] == 17
    assert payload["response"]["redacted"] is True
    assert payload["complete_original"] is False


def test_incomplete_and_truncated_capture_never_claims_complete_original(store):
    trace = trace_for(response_body=bytearray(b'{"choices":['), body_read_complete=False,
                      response_truncated=True)
    saved = save(store, trace)
    payload = store.load(saved["id"])
    assert payload["complete_original"] is False
    assert payload["response"]["body_read_complete"] is False
    assert payload["response"]["truncated"] is True
    assert base64.b64decode(payload["response"]["body_base64"]) == b'{"choices":['


def test_total_size_limit_rejects_without_partial_artifact(store):
    trace = trace_for(response_body=bytearray(b"x" * (2 * 1024 * 1024 + 1)))
    with pytest.raises(artifacts.ArtifactError):
        save(store, trace)
    assert store.list_recent() == []
    # Rejection must leave the independent store usable.
    saved = save(store)
    assert store.load(saved["id"])["response"]["body_read_complete"] is True


def test_whole_artifact_limit_includes_base64_request_and_original_messages(store):
    messages = source_messages("a" * 180_000)
    response = bytearray(b"x" * 1_400_000)
    assert len(response) < 2 * 1024 * 1024
    assert len(base64.b64encode(response)) < 2 * 1024 * 1024
    trace = trace_for(messages=messages, response_body=response)
    with pytest.raises(artifacts.ArtifactError) as error:
        save(store, trace, messages=messages)
    assert error.value.code == "capture_too_large"
    assert store.list_recent() == []


def test_non_utf8_response_is_preserved_but_replay_reports_encoding_failure(store, no_network):
    original = b"\xff\xfe\x00SYNTHETIC_RESPONSE_BYTES"
    saved = save(store, trace_for(response_body=bytearray(original)))
    payload = store.load(saved["id"])
    assert base64.b64decode(payload["response"]["body_base64"]) == original
    result = artifacts.replay_artifact(payload)
    assert result["ok"] is False and result["error_code"] == "body_encoding"
    assert "SYNTHETIC_RESPONSE_BYTES" not in json.dumps(result)


def test_job_idempotency_does_not_replace_content_or_extend_ttl(store, clock):
    job_id = str(uuid.uuid4())
    first = save(store, job_id=job_id)
    assert first["expires_at"] == clock[0] + 86400
    original = store.load(first["id"])
    clock[0] += 30
    repeated = save(store, trace_for("changed result"), job_id=job_id, ttl_seconds=3600)
    assert repeated == first
    assert store.load(first["id"]) == original


def test_expired_job_cannot_be_saved_again_to_restart_retention(store, clock):
    job_id = str(uuid.uuid4())
    save(store, job_id=job_id, ttl_seconds=1)
    clock[0] += 2
    store.purge(now=clock[0])
    with pytest.raises(artifacts.ArtifactError) as error:
        save(store, job_id=job_id)
    assert error.value.code == "artifact_expired"
    assert store.list_recent() == []


def test_expired_artifact_is_refused_and_purged(store, clock):
    saved = save(store, ttl_seconds=2)
    clock[0] += 3
    with pytest.raises(artifacts.ArtifactError):
        store.load(saved["id"])
    assert store.purge(now=clock[0]) in (0, 1)  # load may eagerly discard an expired row.
    assert store.list_recent() == []
    with pytest.raises(artifacts.ArtifactError):
        store.load(saved["id"])


def test_purge_preserves_unexpired_and_constructor_cleans_expired(tmp_path, clock):
    path = tmp_path / "private" / "failure-artifacts.sqlite3"
    instance = artifacts.ArtifactStore(path)
    old = save(instance, ttl_seconds=1)
    live = save(instance, ttl_seconds=100)
    clock[0] += 2
    assert instance.purge(now=clock[0]) == 1
    assert [item["id"] for item in instance.list_recent()] == [live["id"]]
    clock[0] += 100
    reopened = artifacts.ArtifactStore(path)
    assert reopened.list_recent() == []
    with pytest.raises(artifacts.ArtifactError):
        reopened.load(old["id"])
    assert PRIVATE.encode() not in path.read_bytes()


@pytest.mark.skipif(os.name != "posix", reason="POSIX mode bits are enforced on the Linux deployment")
def test_private_directory_and_file_modes(tmp_path):
    path = tmp_path / "private" / "failure-artifacts.sqlite3"
    instance = artifacts.ArtifactStore(path)
    save(instance)
    assert stat.S_IMODE(path.parent.stat().st_mode) == 0o700
    assert stat.S_IMODE(path.stat().st_mode) == 0o600


@pytest.mark.skipif(os.name != "posix", reason="POSIX chmod failure is a deployment-only boundary")
def test_permission_failure_is_not_reported_as_success(tmp_path, monkeypatch):
    path = tmp_path / "private" / "failure-artifacts.sqlite3"
    path.parent.mkdir(mode=0o700)
    path.touch()
    path.chmod(0o644)  # Tightening this existing file is required before use.
    def denied(*args, **kwargs):
        raise PermissionError("synthetic permission failure")
    monkeypatch.setattr(os, "chmod", denied)
    with pytest.raises((artifacts.ArtifactError, PermissionError)):
        artifacts.ArtifactStore(path)


@pytest.mark.skipif(os.name != "posix", reason="POSIX shared-directory permissions require Linux")
def test_existing_shared_directory_is_rejected_without_changing_its_permissions(tmp_path):
    directory = tmp_path / "shared"
    directory.mkdir(mode=0o755)
    directory.chmod(0o755)
    with pytest.raises(artifacts.ArtifactError):
        artifacts.ArtifactStore(directory / "failure-artifacts.sqlite3")
    assert stat.S_IMODE(directory.stat().st_mode) == 0o755


@pytest.fixture
def no_network(monkeypatch):
    def blocked(*args, **kwargs):
        raise AssertionError("offline replay attempted a network/model operation")
    monkeypatch.setattr(socket, "create_connection", blocked)
    monkeypatch.setattr(socket, "socket", blocked)
    monkeypatch.setattr(provider, "organize", blocked)
    return blocked


def test_offline_replay_reports_original_parse_failure_without_exposing_body(store, no_network):
    saved = save(store, trace_for("not JSON " + PRIVATE))
    result = artifacts.replay_artifact(store.load(saved["id"]))
    assert result["ok"] is False
    assert result["error_code"] == "json"
    assert PRIVATE not in json.dumps(result)
    assert "entries" not in result


def test_offline_replay_uses_current_lossless_normalizer_and_source_checks(store, no_network):
    content = json.dumps({"data": {"entries": [entry()]}})
    saved = save(store, trace_for(content))
    result = artifacts.replay_artifact(store.load(saved["id"]))
    assert result["ok"] is True and result["entries_count"] == 1
    assert "entries" not in result
    invalid = save(store, trace_for(json.dumps({"entries": [entry(sourceIds=["m999"])]})))
    rejected = artifacts.replay_artifact(store.load(invalid["id"]))
    assert rejected["ok"] is False and rejected["error_code"] == "unknown_source"


def test_same_artifact_can_be_reparsed_after_validator_fix_without_changing_evidence(store, no_network, monkeypatch):
    saved = save(store, trace_for(json.dumps({"data": {"entries": [entry()]}})))
    original_payload = store.load(saved["id"])
    current_normalizer = provider.normalize_entries
    def old_strict_normalizer(*args, **kwargs):
        raise provider.ProtocolError("schema", "entries/missing")
    monkeypatch.setattr(provider, "normalize_entries", old_strict_normalizer)
    before = artifacts.replay_artifact(original_payload)
    assert before["ok"] is False and before["error_code"] == "schema"
    monkeypatch.setattr(provider, "normalize_entries", current_normalizer)
    after = artifacts.replay_artifact(original_payload)
    assert after["ok"] is True and after["entries_count"] == 1
    assert store.load(saved["id"]) == original_payload


def test_offline_replay_validates_envelope_before_entries(store, no_network):
    trace = trace_for()
    envelope = json.loads(trace.response_body)
    envelope["choices"][0]["finish_reason"] = "length"
    trace.response_body = bytearray(json.dumps(envelope).encode())
    saved = save(store, trace)
    result = artifacts.replay_artifact(store.load(saved["id"]))
    assert result["ok"] is False and result["error_code"] == "output_limit"


def test_offline_outer_envelope_matches_production_bytes_json_parser(store, no_network):
    choices = [{"finish_reason": "stop", "message": {
        "content": json.dumps({"entries": [entry(text="合成内容") ]}, ensure_ascii=False)}}]
    # Production json.loads(bytes) accepts UTF-16 BOM and applies ordinary
    # duplicate-key/nonfinite handling to the envelope; content remains strict.
    body = ('{"unused":NaN,"choices":[],"choices":' +
            json.dumps(choices, ensure_ascii=False) + '}').encode("utf-16")
    parsed = json.loads(bytearray(body))
    expected = provider.normalize_entries(parsed["choices"][0]["message"]["content"], source_messages())
    saved = save(store, trace_for(response_body=bytearray(body)))
    result = artifacts.replay_artifact(store.load(saved["id"]))
    assert result["ok"] is True and result["entries_count"] == len(expected) == 1
    assert result["scope"] == "offline_parse_and_source_validation"


def test_replay_treats_payload_as_data_not_code(store, tmp_path, no_network):
    marker = tmp_path / "must-not-exist"
    malicious = "__import__('pathlib').Path(" + repr(str(marker)) + ").write_text('executed')"
    saved = save(store, trace_for(malicious))
    result = artifacts.replay_artifact(store.load(saved["id"]))
    assert result["ok"] is False
    assert not marker.exists()


def test_cli_defaults_to_metadata_and_explicit_inspect_can_show_private_content(tmp_path, capsys, no_network):
    path = tmp_path / "private" / "failure-artifacts.sqlite3"
    instance = artifacts.ArtifactStore(path)
    saved = save(instance)
    for command in (["list"], ["inspect", saved["id"]], ["replay", saved["id"]]):
        artifacts.main(["--path", str(path), *command])
        output = capsys.readouterr().out
        assert PRIVATE not in output
        assert "source_messages" not in output
    artifacts.main(["--path", str(path), "inspect", saved["id"], "--include-content"])
    assert PRIVATE in capsys.readouterr().out


def test_missing_sample_is_explicit_and_contains_no_input_payload(store):
    with pytest.raises(artifacts.ArtifactError) as error:
        store.load(str(uuid.uuid4()))
    assert PRIVATE not in str(error.value)
