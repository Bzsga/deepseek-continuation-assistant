"""Private, expiring failure evidence and network-free parser replay.

This module has no HTTP routes and never resubmits a model request. Content is
available only through local storage access or explicit CLI inspect opt-in.
"""
import argparse
import base64
from contextlib import contextmanager
from dataclasses import dataclass, field
import hashlib
import json
import math
import os
from pathlib import Path
import re
import sqlite3
import stat
import sys
import time
import uuid


MAX_ARTIFACT_BYTES = 2 * 1024 * 1024
DEFAULT_TTL_SECONDS = 86400
MAX_TTL_SECONDS = 7 * DEFAULT_TTL_SECONDS
SNAPSHOT_FIELDS = {"model", "mock_mode", "input_price", "output_price", "input_bound",
                   "output_bound", "protocol_version", "access_mode", "operation"}
REQUEST_FIELDS = {"model", "messages", "max_tokens", "max_completion_tokens", "stream", "response_format", "thinking",
                  "reasoning_effort", "temperature", "top_p", "frequency_penalty",
                  "presence_penalty", "stop", "seed"}
FORBIDDEN_KEYS = {"headers", "authorization", "url", "baseurl", "settings", "apikey",
                  "admintoken", "adminpasswordhash", "feedbackworkertoken", "workertoken",
                  "password", "secret", "accesstoken", "cookie", "cookies"}
JSON_STRING_BYTES = re.compile(rb'"(?:[^"\\\x00-\x1f]|\\(?:["\\/bfnrt]|u[0-9A-Fa-f]{4}))*"')
ERROR_MESSAGES = {
    "runner_timeout": "Provider request reached the overall execution time limit.",
    "json": "Provider content was not valid JSON.",
    "schema": "Provider content failed the required schema.",
    "unknown_source": "Provider content referenced an unknown source.",
    "content_shape": "Provider content or envelope had an unsupported structure.",
    "content_filter": "Provider did not supply usable content.",
    "output_limit": "Provider content ended at the output limit.",
    "usage": "Provider usage was not fully known.",
    "usage_bound": "Provider usage exceeded the reserved bound.",
    "gateway_error": "Provider returned an error envelope.",
    "http": "Provider returned an unsuccessful HTTP status.",
    "timeout": "Provider request did not complete within its time limit.",
    "transport": "Provider transport did not complete.",
    "provider_failed": "Provider execution did not produce a confirmed result.",
}

NETWORK_EVENT_STAGES = {
    "connection.connect_tcp": "tcp_connect",  # Includes DNS in the AnyIO backend.
    "connection.start_tls": "tls",
    "http11.send_request_headers": "send_headers",
    "http11.send_request_body": "send_body",
    "http11.receive_response_headers": "waiting_headers",
    "http11.receive_response_body": "reading_body",
}
NETWORK_EVENTS = {f"{event}.{state}" for event in NETWORK_EVENT_STAGES for state in ("started", "complete", "failed")}
NETWORK_STAGES = {"not_started", "tcp_connect", "tls", "send_headers", "send_body", "waiting_headers", "reading_body", "complete"}
TRACE_EXCEPTION_TYPES = {
    "none", "total_timeout", "cancelled", "connect_timeout", "read_timeout", "write_timeout", "pool_timeout",
    "connect_error", "read_error", "write_error", "protocol_error", "transport_error", "other",
}


class ArtifactError(ValueError):
    """Only fixed codes cross the private capture boundary."""

    def __init__(self, code):
        self.code = code
        super().__init__(code)


@dataclass
class ProviderTrace:
    confirmed_outcome: object | None = field(default=None, repr=False)
    request: dict | None = field(default=None, repr=False)
    response_body: bytearray = field(default_factory=bytearray, repr=False)
    http_status: int | None = None
    body_read_complete: bool = False
    response_truncated: bool = False
    stage: str = "transport"
    network_stage: str = "not_started"
    request_sent: bool = False
    response_headers_received: bool = False
    exception_type: str = "none"
    prompt_version: int | None = None
    network_events: dict = field(default_factory=dict, repr=False)
    _started_at: float = field(default_factory=time.monotonic, repr=False)
    _network_started_at: float | None = field(default=None, repr=False)
    _ended_at: float | None = field(default=None, repr=False)

    def begin(self):
        self.confirmed_outcome = None
        self.request = None
        self.response_body.clear()
        self.http_status = None
        self.body_read_complete = self.response_truncated = False
        self.stage, self.network_stage, self.exception_type = "transport", "not_started", "none"
        self.request_sent = self.response_headers_received = False
        self.prompt_version = None
        self.network_events.clear()
        self._started_at = time.monotonic()
        self._network_started_at = self._started_at
        self._ended_at = None

    @staticmethod
    def _milliseconds(value):
        return min(1_000_000_000, max(0, int(value * 1000)))

    def set_network_stage(self, stage):
        if stage in NETWORK_STAGES and stage != self.network_stage:
            self.network_stage = stage
            self._network_started_at = time.monotonic()

    def network_event(self, name):
        # The HTTPX callback passes only the name here. Never retain its info:
        # that dictionary contains request headers, hosts and exception objects.
        if name not in NETWORK_EVENTS:
            return
        self.network_events[name] = self._milliseconds(time.monotonic() - self._started_at)
        event, state = name.rsplit(".", 1)
        if state == "started":
            self.set_network_stage(NETWORK_EVENT_STAGES[event])
        elif name == "http11.send_request_body.complete":
            self.request_sent = True
        elif name == "http11.receive_response_headers.complete":
            self.response_headers_received = True
        elif name == "http11.receive_response_body.complete":
            self.set_network_stage("complete")

    def finish(self, exception_type=None):
        if exception_type in TRACE_EXCEPTION_TYPES:
            self.exception_type = exception_type
        if self._ended_at is None:
            self._ended_at = time.monotonic()

    def diagnostics(self):
        now = self._ended_at if self._ended_at is not None else time.monotonic()
        result = {
            "stage": self.stage if self.stage in {"transport", "envelope_json", "response_validation"} else "transport",
            "network_stage": self.network_stage if self.network_stage in NETWORK_STAGES else "not_started",
            "elapsed_ms": self._milliseconds(now - self._started_at),
            "network_stage_elapsed_ms": self._milliseconds(now - (self._network_started_at or self._started_at)),
            "request_sent": self.request_sent is True,
            "response_headers_received": self.response_headers_received is True,
            "exception_type": self.exception_type if self.exception_type in TRACE_EXCEPTION_TYPES else "other",
            "network_events": {name: elapsed for name, elapsed in self.network_events.items()
                               if name in NETWORK_EVENTS and type(elapsed) is int and 0 <= elapsed <= 1_000_000_000},
        }
        if type(self.prompt_version) is int and self.prompt_version in {1, 2, 3, 4, 5, 6, 7, 8, 9}:
            result["prompt_version"] = self.prompt_version
        return result


def _encoded(value):
    try:
        return json.dumps(value, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode("utf-8")
    except (ValueError, TypeError, RecursionError, UnicodeError):
        raise ArtifactError("capture_invalid") from None


def _secret_forms(secrets):
    forms = set()
    for secret in secrets:
        if not isinstance(secret, str):
            raise ArtifactError("capture_invalid_secrets")
        if not secret:
            continue
        current = {secret}
        # Source JSON is itself embedded in a JSON string in the wire request;
        # response envelopes can add a further escape layer to an echoed value.
        for _ in range(3):
            current |= {json.dumps(value, ensure_ascii=ascii_only)[1:-1]
                        for value in tuple(current) for ascii_only in (False, True)}
        current |= {value.replace("/", "\\/") for value in tuple(current)}
        current |= {re.sub(r"\\u([0-9a-f]{4})", lambda match: "\\u" + match[1].upper(), value)
                    for value in tuple(current)}
        forms.update(value.encode("utf-8") for value in current)
    return sorted(forms, key=len, reverse=True)


class _Redactor:
    def __init__(self, secrets):
        try:
            self.forms = _secret_forms(secrets)
        except UnicodeError:
            raise ArtifactError("capture_invalid_secrets") from None
        self.changed = False

    def body(self, body, depth=0):
        # JSON permits arbitrary characters (including ASCII key characters)
        # to be written as unicode escapes. Compare decoded string tokens as
        # well as common encoder spellings. Unchanged tokens retain all bytes.
        if self.forms and depth < 4 and b"\\" in body:
            def clean_string(match):
                try:
                    decoded = json.loads(match[0].decode("utf-8"))
                    raw = decoded.encode("utf-8")
                except (ValueError, UnicodeError):
                    return match[0]
                clean = self.body(raw, depth + 1)
                if clean == raw:
                    return match[0]
                return json.dumps(clean.decode("utf-8"), ensure_ascii=False).encode("utf-8")
            body = JSON_STRING_BYTES.sub(clean_string, body)
        for secret in self.forms:
            body = body.replace(secret, b"[REDACTED]")
        return body

    def tree(self, value):
        if isinstance(value, str):
            raw = value.encode("utf-8")
            clean = self.body(raw)
            self.changed |= clean != raw
            return clean.decode("utf-8")
        if isinstance(value, list):
            return [self.tree(item) for item in value]
        if isinstance(value, dict):
            result = {}
            for key, item in value.items():
                if not isinstance(key, str):
                    raise ArtifactError("capture_invalid")
                clean_key = self.tree(key)
                if clean_key in result:
                    raise ArtifactError("capture_redaction_collision")
                result[clean_key] = self.tree(item)
            return result
        if value is None or type(value) in (int, float, bool):
            return value
        raise ArtifactError("capture_invalid")


def _validate_request(request):
    if request is None:
        return
    if not isinstance(request, dict) or set(request) - REQUEST_FIELDS:
        raise ArtifactError("capture_unsafe_request")

    def visit(value):
        if isinstance(value, dict):
            for key, item in value.items():
                if not isinstance(key, str) or re.sub(r"[^a-z]", "", key.lower()) in FORBIDDEN_KEYS:
                    raise ArtifactError("capture_unsafe_request")
                visit(item)
        elif isinstance(value, list):
            for item in value:
                visit(item)

    visit(request)


def _fixed_error(message):
    match = re.match(r"\[([a-z_]+)\]", message) if isinstance(message, str) else None
    code = match[1] if match and match[1] in ERROR_MESSAGES else "provider_failed"
    return code, ERROR_MESSAGES[code]


class ArtifactStore:
    def __init__(self, path):
        self.path = Path(path).absolute()
        try:
            if self.path.is_symlink() or self.path.parent.is_symlink():
                raise ArtifactError("artifact_unsafe_path")
            self.path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
            self._check_directory()
            flags = os.O_CREAT | os.O_EXCL | os.O_WRONLY | getattr(os, "O_NOFOLLOW", 0)
            try:
                descriptor = os.open(self.path, flags, 0o600)
            except FileExistsError:
                if not self.path.is_file():
                    raise ArtifactError("artifact_unsafe_path") from None
            else:
                os.close(descriptor)
            if os.name == "posix":
                os.chmod(self.path, 0o600, follow_symlinks=False)
                if stat.S_IMODE(self.path.stat().st_mode) != 0o600:
                    raise ArtifactError("artifact_file_permissions")
            with self._connection() as database:
                database.execute("""CREATE TABLE IF NOT EXISTS artifacts (
                    id TEXT PRIMARY KEY, job_key TEXT NOT NULL UNIQUE,
                    created_at REAL NOT NULL, expires_at REAL NOT NULL,
                    metadata TEXT, payload BLOB
                )""")
            self.purge()
        except ArtifactError:
            raise
        except (OSError, sqlite3.Error):
            raise ArtifactError("artifact_storage_unavailable") from None

    def _check_directory(self):
        # Never chmod an existing shared parent directory. A deployment must
        # explicitly provision a private application data directory.
        if os.name == "posix" and stat.S_IMODE(self.path.parent.stat().st_mode) != 0o700:
            raise ArtifactError("artifact_directory_permissions")

    @contextmanager
    def _connection(self):
        database = None
        try:
            self._check_directory()
            if self.path.is_symlink() or self.path.parent.is_symlink():
                raise ArtifactError("artifact_unsafe_path")
            if os.name == "posix" and stat.S_IMODE(self.path.stat().st_mode) != 0o600:
                raise ArtifactError("artifact_file_permissions")
            database = sqlite3.connect(str(self.path), timeout=10)
            database.row_factory = sqlite3.Row
            database.execute("PRAGMA journal_mode=DELETE")
            database.execute("PRAGMA secure_delete=ON")
            database.execute("PRAGMA trusted_schema=OFF")
            with database:
                yield database
        except (OSError, sqlite3.Error):
            raise ArtifactError("artifact_storage_unavailable") from None
        finally:
            if database is not None:
                database.close()

    def save(self, job_id, trace, snapshot, messages, status, error_message, *, secrets=(), ttl_seconds=DEFAULT_TTL_SECONDS,
             context_messages=()):
        if (not isinstance(job_id, str) or not job_id or len(job_id) > 200
                or status not in {"failed", "uncertain"}
                or type(ttl_seconds) not in (int, float) or not math.isfinite(ttl_seconds)
                or not 0 < ttl_seconds <= MAX_TTL_SECONDS):
            raise ArtifactError("capture_invalid")
        now = time.time()
        job_key = hashlib.sha256(job_id.encode("utf-8")).hexdigest()
        with self._connection() as database:
            database.execute("BEGIN IMMEDIATE")
            existing = database.execute("SELECT id,expires_at FROM artifacts WHERE job_key=?", (job_key,)).fetchone()
            if existing is not None:
                if existing["expires_at"] <= now:
                    raise ArtifactError("artifact_expired")
                return {"id": existing["id"], "expires_at": existing["expires_at"]}
            try:
                _validate_request(trace.request)
                if (not isinstance(trace.response_body, (bytes, bytearray))
                        or type(trace.body_read_complete) is not bool or type(trace.response_truncated) is not bool
                        or (trace.http_status is not None and (type(trace.http_status) is not int or not 100 <= trace.http_status <= 599))
                        or not isinstance(snapshot, dict)):
                    raise ArtifactError("capture_invalid")
                if len(trace.response_body) > MAX_ARTIFACT_BYTES:
                    raise ArtifactError("capture_too_large")
                # Import lazily: provider imports ProviderTrace from this module.
                from .provider import source_aliases
                if (not isinstance(messages, list) or not isinstance(context_messages, (list, tuple))
                        or any(not isinstance(item, dict) or set(item) != {"id", "role", "content"}
                               for item in messages + list(context_messages))):
                    raise ArtifactError("capture_invalid_sources")
                _, source_map = source_aliases(messages + list(context_messages))
                raw = bytes(trace.response_body)
                redactor = _Redactor(secrets)
                body = redactor.body(raw)
                response_redacted = body != raw
                artifact_id = str(uuid.uuid4())
                expires_at = now + ttl_seconds
                code, fixed_message = _fixed_error(error_message)
                safe_snapshot = {key: value for key, value in snapshot.items() if key in SNAPSHOT_FIELDS
                                 and (value is None or type(value) in (str, int, float, bool))}
                payload = redactor.tree({
                    "version": 1, "id": artifact_id, "job_id": job_id,
                    "created_at": now, "expires_at": expires_at, "status": status,
                    "error_code": code, "error_message": fixed_message,
                    "request": trace.request, "snapshot": safe_snapshot, "technical": trace.diagnostics(),
                    "messages": messages, "source_id_map": source_map,
                    **({"context_messages": list(context_messages)} if context_messages else {}),
                })
                redacted = redactor.changed or response_redacted
                payload.update({"response": {
                    "http_status": trace.http_status, "body_base64": base64.b64encode(body).decode("ascii"),
                    "body_read_complete": trace.body_read_complete, "truncated": trace.response_truncated,
                    "redacted": response_redacted, "original_bytes": len(raw), "stored_bytes": len(body),
                }, "redacted": redacted,
                    "complete_original": bool(trace.request is not None and trace.body_read_complete
                                              and not trace.response_truncated and not redacted)})
                encoded = _encoded(payload)
                if len(encoded) > MAX_ARTIFACT_BYTES:
                    raise ArtifactError("capture_too_large")
                metadata = {
                    "id": artifact_id, "created_at": now, "expires_at": expires_at, "status": status,
                    "error_code": code, "http_status": trace.http_status, "redacted": redacted,
                    "truncated": trace.response_truncated, "body_read_complete": trace.body_read_complete,
                    "complete_original": payload["complete_original"], "response_bytes": len(body),
                    "artifact_bytes": len(encoded), "request_present": trace.request is not None,
                    "source_count": len(messages) + len(context_messages),
                    **({"context_source_count": len(context_messages)} if context_messages else {}),
                }
            except ArtifactError:
                raise
            except (ValueError, TypeError, AttributeError, UnicodeError, RecursionError):
                raise ArtifactError("capture_invalid") from None
            database.execute("INSERT INTO artifacts VALUES(?,?,?,?,?,?)", (
                artifact_id, job_key, now, expires_at, _encoded(metadata).decode("utf-8"), encoded))
            return {"id": artifact_id, "expires_at": expires_at}

    def purge(self, now=None):
        now = time.time() if now is None else now
        if type(now) not in (int, float) or not math.isfinite(now):
            raise ArtifactError("artifact_invalid_time")
        with self._connection() as database:
            # Retain only opaque identity/expiry tombstones. They prevent the
            # same expired job from silently getting a new retention window.
            cursor = database.execute("UPDATE artifacts SET payload=NULL,metadata=NULL WHERE expires_at<=? AND payload IS NOT NULL", (now,))
            return cursor.rowcount

    def list_recent(self, limit=20):
        if type(limit) is not int or not 1 <= limit <= 100:
            raise ArtifactError("artifact_invalid_limit")
        self.purge()
        with self._connection() as database:
            rows = database.execute("SELECT metadata FROM artifacts WHERE payload IS NOT NULL AND expires_at>? ORDER BY created_at DESC LIMIT ?", (time.time(), limit)).fetchall()
        return [json.loads(row["metadata"]) for row in rows]

    def load(self, artifact_id):
        if not isinstance(artifact_id, str) or len(artifact_id) > 100:
            raise ArtifactError("artifact_not_found")
        self.purge()
        with self._connection() as database:
            row = database.execute("SELECT expires_at,payload FROM artifacts WHERE id=?", (artifact_id,)).fetchone()
        if row is None:
            raise ArtifactError("artifact_not_found")
        if row["expires_at"] <= time.time() or row["payload"] is None:
            raise ArtifactError("artifact_expired")
        try:
            return json.loads(row["payload"])
        except (ValueError, UnicodeError, RecursionError):
            raise ArtifactError("artifact_corrupt") from None


def replay_artifact(payload):
    """Parse saved bytes using current pure validators; no transport or billing."""
    from .provider import (ProtocolError, json_error_diagnostics, normalize_entries, normalize_political_filter_entries,
                           response_diagnostics)
    response = payload.get("response", {})
    result = {"ok": False, "stage": "envelope", "envelope_valid": False, "entries_count": 0,
              "error_code": None, "diagnostic": {}, "warnings": [],
              "redacted": bool(payload.get("redacted")), "truncated": bool(response.get("truncated")),
              "complete_original": bool(payload.get("complete_original")),
              "scope": "offline_parse_and_source_validation"}
    try:
        body = base64.b64decode(response["body_base64"], validate=True)
        if len(body) > MAX_ARTIFACT_BYTES:
            raise ProtocolError("body_limit")
        if not response.get("body_read_complete") or response.get("truncated"):
            raise ProtocolError("incomplete_capture")
        if response.get("http_status") != 200:
            raise ProtocolError("http_status")
        try:
            # Match production's json.loads(data bytes/bytearray), including
            # encoding detection and outer-envelope duplicate/number behavior.
            # Strict content parsing remains normalize_entries' responsibility.
            envelope = json.loads(body)
        except UnicodeError:
            raise ProtocolError("body_encoding") from None
        except json.JSONDecodeError as error:
            raise ProtocolError("envelope_json", diagnostic=json_error_diagnostics(error)) from None
        result["diagnostic"] = response_diagnostics(envelope, response.get("http_status"), len(body))
        if not isinstance(envelope, dict) or result["diagnostic"].get("has_error"):
            raise ProtocolError("gateway_error")
        choices = envelope.get("choices")
        if not isinstance(choices, list) or len(choices) != 1 or not isinstance(choices[0], dict):
            raise ProtocolError("content_shape")
        finish = choices[0].get("finish_reason")
        if finish == "length":
            raise ProtocolError("output_limit")
        if finish == "content_filter":
            raise ProtocolError("content_filter")
        if finish != "stop":
            raise ProtocolError("content_shape")
        message = choices[0].get("message")
        if not isinstance(message, dict) or not isinstance(message.get("content"), str):
            raise ProtocolError("content_shape")
        if message.get("refusal"):
            raise ProtocolError("content_filter")
        result.update(envelope_valid=True, stage="content")
        warnings = set()
        operation = payload.get("snapshot", {}).get("operation", "organize")
        if operation == "political_filter":
            entries = normalize_political_filter_entries(message["content"], payload["messages"], warnings=warnings,
                                                         diagnostic=result["diagnostic"])
        elif operation == "organize":
            entries = normalize_entries(message["content"], payload["messages"], warnings=warnings,
                                        diagnostic=result["diagnostic"],
                                        **({"context_messages": payload["context_messages"]}
                                           if payload.get("context_messages") else {}))
        else:
            raise ProtocolError("content_shape")
        result.update(ok=True, stage="complete", entries_count=len(entries), warnings=sorted(warnings))
    except ProtocolError as error:
        result["error_code"] = error.code
        result["diagnostic"].update(error.diagnostic)
    except (ValueError, KeyError, TypeError, UnicodeError, RecursionError, AttributeError):
        result["error_code"] = "artifact_corrupt"
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--path", type=Path, default=Path("data/failure-artifacts.sqlite3"))
    commands = parser.add_subparsers(dest="command", required=True)
    listing = commands.add_parser("list")
    listing.add_argument("--limit", type=int, default=20)
    for name in ("inspect", "replay"):
        command = commands.add_parser(name)
        command.add_argument("artifact_id")
        if name == "inspect":
            command.add_argument("--include-content", action="store_true",
                                 help="Explicitly display private captured request, source and response content")
    args = parser.parse_args(argv)
    try:
        if not args.path.is_file():
            raise ArtifactError("artifact_store_not_found")
        store = ArtifactStore(args.path)
        if args.command == "list":
            result = {"ok": True, "artifacts": store.list_recent(args.limit)}
        else:
            payload = store.load(args.artifact_id)
            if args.command == "replay":
                result = replay_artifact(payload)
            elif args.include_content:
                result = {"ok": True, "artifact": payload}
            else:
                result = {"ok": True, "artifact": {
                    key: payload[key] for key in ("id", "created_at", "expires_at", "status", "error_code", "redacted", "complete_original")
                }, "replay": replay_artifact(payload)}
        result["format"] = "continuation-failure-artifact-v1"
        print(json.dumps(result, ensure_ascii=False, allow_nan=False))
        return 0 if result["ok"] else 2
    except ArtifactError as error:
        print(json.dumps({"ok": False, "format": "continuation-failure-artifact-v1", "error": error.code}))
        return 2
    except Exception:
        print('{"ok":false,"format":"continuation-failure-artifact-v1","error":"artifact_operation_failed"}')
        return 2


if __name__ == "__main__":
    sys.exit(main())
