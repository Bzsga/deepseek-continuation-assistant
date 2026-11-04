"""Read-only, privacy-filtered runtime evidence for the hourly maintainer."""

import argparse
import hashlib
import json
import math
import re
import sqlite3
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path


FORMAT = "continuation-runtime-report-v1"
DATABASE = "/var/lib/deepseek-continuation/companion.sqlite3"
SERVICE = "deepseek-continuation.service"
HEALTH_URL = "http://127.0.0.1:3141/health"
MAX_COMMAND_BYTES = 2 * 1024 * 1024
CODES = {
    "output_limit", "json", "schema", "unknown_source", "content_shape", "content_filter",
    "usage_unknown", "usage_bound", "gateway_error", "usage", "transport", "transport_timeout",
    "envelope_json", "runner_timeout", "runner_error", "restart_uncertain", "shutdown_uncertain",
    "legacy_validation", "upstream_unknown", "unclassified_failure",
}
VALUE_TYPES = {"null", "object", "array", "string", "bool", "integer", "number", "other"}
FINISH_REASONS = {"stop", "length", "content_filter", "tool_calls", "function_call", "other"}
JSON_ERROR_TYPES = {"expected_value", "expected_property", "expected_comma", "expected_colon", "extra_data",
                    "unterminated_string", "invalid_escape", "invalid_control_character", "invalid_unicode_escape",
                    "unexpected_bom", "syntax", "duplicate_key", "non_finite_number", "invalid_wrapper", "parser_limit"}
JSON_WRAPPERS = {"plain", "json_fence", "bare_fence", "invalid_fence", "unsupported_fence"}
JSON_FIRST_CHARACTERS = {"empty", "object", "array", "string", "backtick", "markup", "format", "control", "number", "letter", "other"}
SCHEMA_LAYOUTS = {"canonical", "array", "wrapped", "alias", "grouped", "unrecognized", "ambiguous"}
SCHEMA_FIELDS = {"entries", "items", "data", "result", "output", "archive", "BACKGROUND", "PEOPLE",
                 "DECISIONS", "RECENT", "QUOTES", "text", "content", "sourceIds", "source_ids",
                 "category", "kind", "error", "refusal"}
NETWORK_STAGES = {"not_started", "tcp_connect", "tls", "send_headers", "send_body", "waiting_headers", "reading_body", "complete"}
NETWORK_EXCEPTION_TYPES = {"none", "total_timeout", "cancelled", "connect_timeout", "read_timeout",
                           "write_timeout", "pool_timeout", "connect_error", "read_error", "write_error",
                           "protocol_error", "transport_error", "other"}
NETWORK_EVENTS = {
    prefix + "." + state
    for prefix in ("connection.connect_tcp", "connection.start_tls", "http11.send_request_headers",
                   "http11.send_request_body", "http11.receive_response_headers", "http11.receive_response_body")
    for state in ("started", "complete", "failed")
}
USAGE_FIELDS = {
    "prompt_tokens", "completion_tokens", "input_tokens", "output_tokens", "total_tokens",
    "prompt_tokens_details", "completion_tokens_details",
}
MODULES = {"main", "store", "runner", "provider", "config", "admin", "admin_access", "schemas", "feedback"}
MOBILE_KINDS = {"import", "task", "crash", "lifecycle"}
MOBILE_STAGES = {"start", "read", "parse", "save", "complete", "failed", "uncaught", "recovered",
                 "prepare", "submit", "poll", "resume", "cancel"}
MOBILE_METRICS = {"input_bytes", "unzipped_bytes", "elapsed_ms", "heap_free_bytes", "heap_max_bytes",
                  "heap_used_bytes", "conversation_count", "message_count", "chunk_index", "chunk_count", "http_status"}
MOBILE_CLASS = r"(?:app\.banzhan\.deepseekcontinuation|java|javax|kotlin|kotlinx|android|androidx|okhttp3|okio)\.[A-Za-z_$][A-Za-z0-9_.$]*"
MOBILE_FRAME = re.compile(
    r"^" + MOBILE_CLASS + r"\.(?:[A-Za-z_$][A-Za-z0-9_$-]*|<init>|<clinit>)"
    r"\((?:[A-Za-z0-9_$.-]{1,100}\.(?:kt|java):[0-9]{1,7}|Unknown Source|Native Method|SourceFile(?::[0-9]{1,7})?)\)$"
)
ERROR_TYPES = {
    "OperationalError", "DatabaseError", "IntegrityError", "ProgrammingError", "PermissionError",
    "FileNotFoundError", "ModuleNotFoundError", "ImportError", "RuntimeError", "TypeError", "ValueError",
    "KeyError", "AttributeError", "UnicodeDecodeError", "TimeoutError", "MemoryError", "OSError",
    "ConnectionError", "ConnectionResetError", "ConnectError", "ReadError", "TimeoutException",
    "ConnectTimeout", "ReadTimeout", "JSONDecodeError",
}
SHOW_FIELDS = ("ActiveState", "SubState", "Result", "ExecMainCode", "ExecMainStatus", "NRestarts", "MainPID")
STATE_VALUES = {
    "active", "inactive", "failed", "activating", "deactivating", "reloading", "maintenance",
    "running", "dead", "exited", "auto-restart", "start", "start-pre", "start-post", "stop",
    "stop-sigterm", "stop-sigkill", "stop-post", "reload", "success", "exit-code", "signal",
    "core-dump", "watchdog", "timeout", "resources", "start-limit-hit", "oom-kill", "protocol", "dependency",
}


def digest(value):
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def source_result(**extra):
    return {"complete": True, "truncated": False, "error_code": None, **extra}


def safe_diagnostic(value):
    if not isinstance(value, dict):
        return {}
    result = {}
    for name in ("http_status", "response_bytes", "choices_count", "content_bytes", "usage_other_fields",
                 "reasoning_content_bytes", "reasoning_tokens", "elapsed_ms", "json_chars",
                 "json_error_offset", "json_error_line", "json_error_column", "network_stage_elapsed_ms"):
        number = value.get(name)
        if type(number) is int and 0 <= number <= 1_000_000_000:
            result[name] = number
    for name in ("has_error", "has_refusal", "has_reasoning", "json_has_bom", "body_read_complete",
                 "json_invisible_prefix", "json_edge_format_removed", "json_has_object_candidate",
                 "json_has_array_candidate", "json_scan_limited", "schema_normalized",
                 "request_sent", "response_headers_received"):
        if type(value.get(name)) is bool:
            result[name] = value[name]
    for name in ("root", "choices_type", "usage_type", "content_type", "reasoning_content_type"):
        if isinstance(value.get(name), str) and value[name] in VALUE_TYPES:
            result[name] = value[name]
    if isinstance(value.get("finish_reason"), str) and value["finish_reason"] in FINISH_REASONS:
        result["finish_reason"] = value["finish_reason"]
    if value.get("stage") in ("transport", "envelope_json", "response_validation"):
        result["stage"] = value["stage"]
    for name, allowed in (("network_stage", NETWORK_STAGES), ("exception_type", NETWORK_EXCEPTION_TYPES)):
        if isinstance(value.get(name), str) and value[name] in allowed:
            result[name] = value[name]
    network_events = value.get("network_events")
    if isinstance(network_events, dict):
        result["network_events"] = {
            name: network_events[name] for name in sorted(NETWORK_EVENTS)
            if type(network_events.get(name)) is int and 0 <= network_events[name] <= 1_000_000_000
        }
    for name, allowed in (("json_error_type", JSON_ERROR_TYPES), ("json_wrapper", JSON_WRAPPERS),
                          ("json_first_char", JSON_FIRST_CHARACTERS), ("schema_layout", SCHEMA_LAYOUTS)):
        if isinstance(value.get(name), str) and value[name] in allowed:
            result[name] = value[name]
    count = value.get("json_candidate_count")
    if type(count) is int and 0 <= count <= 2:
        result["json_candidate_count"] = count
    prompt_version = value.get("prompt_version")
    if type(prompt_version) is int and prompt_version in {2, 3, 4}:
        result["prompt_version"] = prompt_version
    for name in ("schema_item_count", "schema_other_fields"):
        count = value.get(name)
        if type(count) is int and 0 <= count <= 1_000_000:
            result[name] = count
    fields = value.get("schema_fields")
    if isinstance(fields, dict):
        result["schema_fields"] = {
            name: fields[name] for name in sorted(SCHEMA_FIELDS)
            if isinstance(fields.get(name), str) and fields[name] in VALUE_TYPES
        }
    fields = value.get("usage_fields")
    if isinstance(fields, dict):
        result["usage_fields"] = {
            name: fields[name] for name in sorted(USAGE_FIELDS)
            if isinstance(fields.get(name), str) and fields[name] in VALUE_TYPES
        }
    if value.get("failure_capture_state") in ("saved", "unavailable", "disabled"):
        result["failure_capture_state"] = value["failure_capture_state"]
    capture_id = value.get("failure_capture_id")
    if isinstance(capture_id, str) and re.fullmatch(
            r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", capture_id):
        result["failure_capture_id"] = capture_id
    expires = value.get("failure_capture_expires_at")
    if type(expires) is int and 0 <= expires <= 100_000_000_000:
        result["failure_capture_expires_at"] = expires
    return result


def job_event(row):
    if row["count_state"] != "used":
        return None
    message = row["message"] if isinstance(row["message"], str) else ""
    if message.startswith("管理员已核对并结算"):
        return None
    prefix = re.match(r"^(?:\[[a-z_]+\])+", message)
    codes = sorted(set(re.findall(r"\[([a-z_]+)\]", prefix.group(0))) & CODES) if prefix else []
    if not codes:
        legacy = (
            ("请求达到总时限", "runner_timeout"), ("任务处理意外中断", "runner_error"),
            ("服务曾中断", "restart_uncertain"), ("服务停止时调用尚未确认", "shutdown_uncertain"),
            ("模型返回格式或引用未通过验证", "legacy_validation"),
            ("请求结果或计费用量无法确认", "upstream_unknown"),
        )
        codes = [next((code for text, code in legacy if message.startswith(text)), "unclassified_failure")]
    diagnostic = {}
    if " diagnostic=" in message:
        try:
            diagnostic = safe_diagnostic(json.loads(message.split(" diagnostic=", 1)[1]))
        except (ValueError, TypeError, RecursionError):
            pass
    category = "protocol_failure"
    if "usage_bound" in codes:
        category = "safety_limit"
    elif "runner_error" in codes or ("content_shape" in codes and diagnostic.get("http_status") is None):
        category = "service_error"
    elif set(codes) & {"restart_uncertain", "shutdown_uncertain"}:
        category = "service_interruption"
    elif set(codes) & {"transport", "transport_timeout", "runner_timeout", "gateway_error", "upstream_unknown"}:
        category = "upstream_failure"
    elif "unclassified_failure" in codes:
        category = "unclassified_failure"
    event = {"source": "database", "event_id": digest("job:" + str(row["id"])),
             "at_unix": float(row["terminal_at"]), "status": row["status"],
             "count_state": "used", "category": category, "codes": codes, "diagnostic": diagnostic}
    stable = {name: value for name, value in diagnostic.items()
              if name not in {"response_bytes", "content_bytes", "choices_count", "usage_other_fields",
                              "reasoning_content_bytes", "reasoning_tokens", "elapsed_ms", "json_chars",
                              "json_error_offset", "json_error_line", "json_error_column",
                              "failure_capture_id", "failure_capture_expires_at",
                              "network_stage_elapsed_ms", "network_events"}}
    event["fingerprint"] = digest(json.dumps(["database", category, codes, stable], sort_keys=True))
    return event


def collect_database(since, until, limit, database=DATABASE):
    result = source_result(scanned=0, ignored_normal=0)
    events = []
    try:
        connection = sqlite3.connect(Path(database).resolve().as_uri() + "?mode=ro", uri=True, timeout=3)
        connection.row_factory = sqlite3.Row
        try:
            connection.execute("PRAGMA query_only=ON")
            rows = connection.execute("""SELECT id,terminal_at,status,count_state,message FROM jobs
                WHERE status IN ('failed','uncertain') AND terminal_at>=? AND terminal_at<=?
                ORDER BY terminal_at,id LIMIT ?""", (since, until, limit + 1)).fetchall()
        finally:
            connection.close()
        result["truncated"] = len(rows) > limit
        result["complete"] = not result["truncated"]
        for row in rows[:limit]:
            result["scanned"] += 1
            event = job_event(row)
            if event is None:
                result["ignored_normal"] += 1
            else:
                events.append(event)
    except (sqlite3.Error, OSError, ValueError, TypeError):
        result.update(complete=False, error_code="database_read_failed")
    return result, events


def safe_mobile_diagnostic(value):
    """Whitelist metadata independently of the HTTP validator; no log messages."""
    if not isinstance(value, dict) or value.get("kind") not in MOBILE_KINDS or value.get("stage") not in MOBILE_STAGES:
        raise ValueError("Invalid mobile event")
    result = {"kind": value["kind"], "stage": value["stage"]}
    for name, maximum in (("app_version_code", 2_147_483_647), ("android_sdk", 1000)):
        if type(value.get(name)) is not int or not 1 <= value[name] <= maximum:
            raise ValueError("Invalid mobile version")
        result[name] = value[name]
    version = value.get("app_version_name")
    if not isinstance(version, str) or not 1 <= len(version) <= 80 or not re.fullmatch(r"[A-Za-z0-9._+\-]+", version):
        raise ValueError("Invalid mobile version")
    result["app_version_name"] = version
    for name in ("manufacturer", "model"):
        text = value.get(name, "")
        if isinstance(text, str) and len(text) <= 80 and re.fullmatch(r"[A-Za-z0-9 ._()+\-]*", text):
            result[name] = text
    metrics = value.get("metrics")
    if isinstance(metrics, dict):
        result["metrics"] = {name: metrics[name] for name in sorted(MOBILE_METRICS)
                             if type(metrics.get(name)) is int and 0 <= metrics[name] <= 2**53}
    exception = value.get("exception_class", "")
    if isinstance(exception, str) and len(exception) <= 200 and re.fullmatch(MOBILE_CLASS, exception):
        result["exception_class"] = exception
    frames = value.get("stack_frames")
    if isinstance(frames, list):
        result["stack_frames"] = [frame for frame in frames[:30]
                                  if isinstance(frame, str) and len(frame) <= 400 and MOBILE_FRAME.fullmatch(frame)][:8]
    return result


def mobile_event(row):
    raw = row["data"]
    if not isinstance(raw, str) or len(raw.encode("utf-8")) > 32 * 1024:
        raise ValueError("Invalid mobile event")
    diagnostic = safe_mobile_diagnostic(json.loads(raw))
    received, occurred = row["received_at"], row["occurred_at"]
    if any(type(value) not in (int, float) or not math.isfinite(value) or not 0 <= value <= 4_102_444_800
           for value in (received, occurred)):
        raise ValueError("Invalid mobile event timestamp")
    if diagnostic["stage"] == "recovered":
        # Unclean termination can also be an OS kill; it is not proof of a crash.
        category = "client_recovery"
    elif diagnostic["kind"] == "crash" or diagnostic["stage"] in {"failed", "uncaught"} or diagnostic.get("exception_class"):
        category = "client_error"
    else:
        category = "client_activity"
    code = diagnostic["kind"] + "_" + diagnostic["stage"]
    event = {"source": "mobile", "event_id": digest("mobile:" + str(row["record_id"]) + ":" + str(row["event_id"])),
             "at_unix": float(received), "occurred_at_unix": float(occurred),
             "category": category, "codes": [code], "diagnostic": diagnostic}
    stable = {name: value for name, value in diagnostic.items() if name != "metrics"}
    event["fingerprint"] = digest(json.dumps(["mobile", category, stable], sort_keys=True))
    return event


def collect_mobile(since, until, limit, database=DATABASE):
    result = source_result(scanned=0, available=True)
    events = []
    try:
        connection = sqlite3.connect(Path(database).resolve().as_uri() + "?mode=ro", uri=True, timeout=3)
        connection.row_factory = sqlite3.Row
        try:
            connection.execute("PRAGMA query_only=ON")
            installed = connection.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='mobile_diagnostics'").fetchone()
            if installed is None:
                return {**result, "available": False, "coverage": "not_installed"}, events
            # Use server receipt time: a phone can upload an older crash after
            # coming back online. Never scan by its untrusted local clock.
            # rowid separates the same UUID from different owners without
            # selecting owner IDs, credentials or any conversation data.
            rows = connection.execute("""SELECT rowid AS record_id,event_id,occurred_at,received_at,data
                FROM mobile_diagnostics WHERE received_at>=? AND received_at<=?
                ORDER BY received_at,rowid LIMIT ?""", (since, until, limit + 1)).fetchall()
        finally:
            connection.close()
        result["truncated"] = len(rows) > limit
        result["complete"] = not result["truncated"]
        for row in rows[:limit]:
            result["scanned"] += 1
            try:
                events.append(mobile_event(row))
            except (ValueError, TypeError, RecursionError):
                result.update(complete=False, error_code="mobile_record_invalid")
    except (sqlite3.Error, OSError, ValueError, TypeError):
        result.update(complete=False, error_code="mobile_read_failed")
    return result, events


def run_command(arguments):
    try:
        process = subprocess.run(arguments, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                 stderr=subprocess.DEVNULL, timeout=8, check=False)
    except subprocess.TimeoutExpired:
        return {"ok": False, "output": b"", "truncated": False, "error_code": "command_timeout"}
    except OSError:
        return {"ok": False, "output": b"", "truncated": False, "error_code": "command_unavailable"}
    truncated = len(process.stdout) > MAX_COMMAND_BYTES
    return {"ok": process.returncode == 0, "output": process.stdout[:MAX_COMMAND_BYTES],
            "truncated": truncated, "error_code": None if process.returncode == 0 else "command_failed"}


def collect_systemd(command=run_command):
    reply = command(["systemctl", "show", SERVICE, "--no-pager", "--property=" + ",".join(SHOW_FIELDS)])
    result = source_result(complete=reply["ok"] and not reply["truncated"], truncated=reply["truncated"],
                           error_code=reply["error_code"], state={})
    if not reply["ok"]:
        return result
    for line in reply["output"].decode("utf-8", errors="replace").splitlines():
        name, separator, value = line.partition("=")
        if not separator or name not in SHOW_FIELDS:
            continue
        if name in {"ActiveState", "SubState", "Result"}:
            result["state"][name] = value if value in STATE_VALUES else "other"
        elif re.fullmatch(r"[0-9]{1,10}", value):
            result["state"][name] = int(value)
    if not {"ActiveState", "SubState", "Result"}.issubset(result["state"]):
        result.update(complete=False, error_code="systemd_fields_missing")
    return result


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        return None


def collect_health():
    result = source_result(healthy=False, http_status=None)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    try:
        try:
            response = opener.open(HEALTH_URL, timeout=5)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            result["http_status"] = response.status
            raw = response.read(4097)
        if len(raw) > 4096:
            return {**result, "complete": False, "truncated": True, "error_code": "health_response_too_large"}
        body = json.loads(raw)
        if (not isinstance(body, dict) or body.get("status") not in ("ok", "degraded")
                or body.get("service") != "deepseek-continuation-assistant"):
            raise ValueError
        result["status"] = body["status"]
        result["healthy"] = response.status == 200 and body["status"] == "ok"
        if type(body.get("mock_mode")) is bool:
            result["mock_mode"] = body["mock_mode"]
    except (OSError, ValueError, TypeError, RecursionError):
        result.update(complete=False, error_code="health_read_failed")
    return result


def journal_event(record):
    message = record.get("MESSAGE")
    if not isinstance(message, str):
        return None
    errors = sorted({match.group(1) for match in re.finditer(
        r"(?m)^(?:[A-Za-z_][A-Za-z0-9_.]*\.)?([A-Za-z_][A-Za-z0-9_]*):", message)
        if match.group(1) in ERROR_TYPES})
    markers = {
        "Exception in ASGI application": "asgi_error",
        "Main process exited": "process_exit",
        "Failed with result": "unit_failed",
        "Start request repeated too quickly": "restart_limit",
        "Watchdog timeout": "watchdog",
    }
    codes = sorted({code for marker, code in markers.items() if marker in message})
    frames = []
    for match in re.finditer(r'(?m)^\s*File "/opt/deepseek-continuation/app/([a-z_]+)\.py", line ([0-9]+)', message):
        if match.group(1) in MODULES and len(match.group(2)) <= 7:
            frame = {"module": "app." + match.group(1), "line": int(match.group(2))}
            if frame not in frames:
                frames.append(frame)
    if not errors and not codes:
        if not frames:
            return None
        codes = ["app_trace_frame"]
    timestamp = record.get("__REALTIME_TIMESTAMP")
    if not isinstance(timestamp, str) or not re.fullmatch(r"[0-9]{1,20}", timestamp):
        raise ValueError
    cursor = record.get("__CURSOR")
    if not isinstance(cursor, str) or not cursor:
        raise ValueError
    event = {"source": "journal", "event_id": digest("journal:" + cursor),
             "at_unix": int(timestamp) / 1_000_000,
             "category": "trace_context" if codes == ["app_trace_frame"] and not errors else "service_error",
             "codes": codes, "error_types": errors, "app_frames": frames[:8]}
    event["fingerprint"] = digest(json.dumps(["journal", codes, errors, frames[:8]], sort_keys=True))
    return event


def collect_journal(since, until, limit, command=run_command):
    reply = command(["journalctl", "--unit=" + SERVICE, "--since=@" + str(since), "--until=@" + str(until),
                     "--output=json", "--output-fields=__CURSOR,__REALTIME_TIMESTAMP,MESSAGE", "--no-pager",
                     "--quiet", "--lines=" + str(limit + 1)])
    result = source_result(scanned=0, complete=reply["ok"] and not reply["truncated"],
                           truncated=reply["truncated"], error_code=reply["error_code"])
    events = []
    if not reply["ok"]:
        return result, events
    lines = reply["output"].splitlines()
    if len(lines) > limit:
        result.update(complete=False, truncated=True)
    for line in lines[:limit]:
        result["scanned"] += 1
        try:
            record = json.loads(line)
            if not isinstance(record, dict):
                raise ValueError
            event = journal_event(record)
            if event and since <= event["at_unix"] <= until:
                events.append(event)
        except (ValueError, TypeError, RecursionError):
            result.update(complete=False, error_code="journal_record_invalid")
    return result, events


def collect(since=None, until=None, job_limit=200, journal_limit=200, mobile_limit=100, *, clock=time.time,
            database=DATABASE, command=run_command, health=collect_health):
    now = clock()
    end = now if until is None else until
    start = end - 3600 if since is None else since
    if (not math.isfinite(start) or not math.isfinite(end) or start < 0 or start > end or end > now + 5
            or not 1 <= job_limit <= 1000 or not 1 <= journal_limit <= 1000 or not 1 <= mobile_limit <= 1000):
        return {"format": FORMAT, "now_unix": now, "since": start if math.isfinite(start) else None,
                "window_end_unix": end if math.isfinite(end) else None,
                "complete": False, "truncated": False, "error_code": "invalid_window_or_limit", "events": [], "sources": {}}
    database_result, job_events = collect_database(start, end, job_limit, database)
    journal_result, journal_events = collect_journal(start, end, journal_limit, command)
    mobile_result, mobile_events = collect_mobile(start, end, mobile_limit, database)
    sources = {"database": database_result, "systemd": collect_systemd(command),
               "health": health(), "journal": journal_result, "mobile": mobile_result}
    return {"format": FORMAT, "now_unix": now, "since": start, "window_end_unix": end,
            "complete": all(value["complete"] for value in sources.values()),
            "truncated": any(value["truncated"] for value in sources.values()),
            "overlap_seconds": 300,
            "events": sorted(job_events + journal_events + mobile_events, key=lambda event: (event["at_unix"], event["event_id"])),
            "sources": sources}


def main(argv=None):
    parser = argparse.ArgumentParser(description="只读收集续聊助手的脱敏运行证据")
    parser.add_argument("--since", type=float)
    parser.add_argument("--until", type=float)
    parser.add_argument("--job-limit", type=int, default=200)
    parser.add_argument("--journal-limit", type=int, default=200)
    parser.add_argument("--mobile-limit", type=int, default=100)
    arguments = parser.parse_args(argv)
    report = collect(arguments.since, arguments.until, arguments.job_limit, arguments.journal_limit, arguments.mobile_limit)
    print(json.dumps(report, ensure_ascii=True, separators=(",", ":")))
    return 0 if report["complete"] else 2


if __name__ == "__main__":
    sys.exit(main())
