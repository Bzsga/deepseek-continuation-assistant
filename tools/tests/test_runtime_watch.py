import importlib.util
import io
import json
import sqlite3
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest import mock


SCRIPT = Path(__file__).resolve().parents[1] / "runtime_watch.py"
SPEC = importlib.util.spec_from_file_location("runtime_watch", SCRIPT)
watch = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(watch)
PRIVATE = "SYNTHETIC_PRIVATE_KEY_AND_BODY"


def command_reply(output=b"", ok=True, truncated=False):
    return {"ok": ok, "output": output, "truncated": truncated,
            "error_code": None if ok else "command_failed"}


def journal_record(timestamp, cursor, message):
    return json.dumps({"__REALTIME_TIMESTAMP": str(int(timestamp * 1_000_000)),
                       "__CURSOR": cursor, "MESSAGE": message}).encode()


def healthy():
    return watch.source_result(healthy=True, http_status=200, status="ok")


class RuntimeWatchTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.database = str(Path(self.directory.name) / "fixture.sqlite3")
        with sqlite3.connect(self.database) as db:
            db.execute("""CREATE TABLE jobs(id TEXT,terminal_at REAL,status TEXT,count_state TEXT,message TEXT,
                payload TEXT,entries TEXT,provider TEXT,owner_id TEXT)""")

    def add_job(self, job_id, timestamp, status="uncertain", count="used", message="[transport] safe"):
        with sqlite3.connect(self.database) as db:
            db.execute("INSERT INTO jobs VALUES(?,?,?,?,?,?,?,?,?)",
                       (job_id, timestamp, status, count, message, PRIVATE, PRIVATE, PRIVATE, PRIVATE))

    def add_mobile(self, event_id, received, occurred=1, *, owner="private-owner", stage="failed", kind="import", **extra):
        value = {"event_id": event_id, "occurred_at": occurred, "app_version_code": 9,
                 "app_version_name": "1.0.0-preview.9", "android_sdk": 35,
                 "manufacturer": "OPPO", "model": "Find X9", "kind": kind, "stage": stage,
                 "metrics": {"elapsed_ms": 300, "input_bytes": 100}, **extra}
        with sqlite3.connect(self.database) as db:
            db.execute("""CREATE TABLE IF NOT EXISTS mobile_diagnostics (
                owner_id TEXT,event_id TEXT,occurred_at REAL,received_at REAL,received_day TEXT,data TEXT,
                PRIMARY KEY(owner_id,event_id))""")
            db.execute("INSERT INTO mobile_diagnostics VALUES(?,?,?,?,?,?)",
                       (owner, event_id, occurred, received, PRIVATE, json.dumps(value)))

    def test_mobile_receipt_window_includes_offline_upload_and_hides_identifiers(self):
        self.add_mobile("old-phone-clock", 105, 1, exception_class="java.lang.OutOfMemoryError",
                        stack_frames=["app.banzhan.deepseekcontinuation.core.DeepSeekImporter.parse(DeepSeekImporter.kt:32)", PRIVATE],
                        metrics={"elapsed_ms": 300, "input_bytes": 100, PRIVATE: 1}, message=PRIVATE)
        self.add_mobile("outside-window", 111, 105)
        original_connect = sqlite3.connect

        def connect(*args, **kwargs):
            self.assertTrue(kwargs.get("uri"))
            self.assertIn("mode=ro", args[0])
            db = original_connect(*args, **kwargs)

            def authorize(action, table, column, database, trigger):
                if action == sqlite3.SQLITE_READ and table == "mobile_diagnostics" and column in {"owner_id", "received_day"}:
                    return sqlite3.SQLITE_DENY
                return sqlite3.SQLITE_OK

            db.set_authorizer(authorize)
            return db

        with mock.patch.object(watch.sqlite3, "connect", side_effect=connect):
            source, events = watch.collect_mobile(100, 110, 20, self.database)
        self.assertTrue(source["complete"] and source["available"])
        self.assertEqual(len(events), 1)
        self.assertEqual(events[0]["at_unix"], 105)
        self.assertEqual(events[0]["occurred_at_unix"], 1)
        self.assertEqual(events[0]["category"], "client_error")
        self.assertEqual(events[0]["diagnostic"]["exception_class"], "java.lang.OutOfMemoryError")
        self.assertEqual(len(events[0]["diagnostic"]["stack_frames"]), 1)
        for secret in (PRIVATE, "private-owner", "old-phone-clock", "outside-window"):
            self.assertNotIn(secret, json.dumps(events))

    def test_mobile_normal_recovery_and_failure_are_distinct_and_ids_stable(self):
        self.add_mobile("same-event", 100, owner="one", stage="complete")
        self.add_mobile("same-event", 105, owner="two", stage="complete", metrics={"elapsed_ms": 900})
        self.add_mobile("recovery", 110, stage="recovered", kind="lifecycle")
        _, first = watch.collect_mobile(100, 110, 20, self.database)
        _, second = watch.collect_mobile(100, 110, 20, self.database)
        self.assertEqual(first, second)
        self.assertNotEqual(first[0]["event_id"], first[1]["event_id"])
        self.assertEqual(first[0]["fingerprint"], first[1]["fingerprint"])
        self.assertEqual([event["category"] for event in first], ["client_activity", "client_activity", "client_recovery"])

    def test_mobile_absent_old_schema_failure_and_truncation_are_explicit(self):
        source, events = watch.collect_mobile(100, 110, 20, self.database)
        self.assertTrue(source["complete"])
        self.assertFalse(source["available"])
        self.assertEqual(source["coverage"], "not_installed")
        self.assertEqual(events, [])
        failed, _ = watch.collect_mobile(100, 110, 20, str(Path(self.directory.name) / "missing.sqlite3"))
        self.assertFalse(failed["complete"])
        self.assertEqual(failed["error_code"], "mobile_read_failed")
        for index in range(3):
            self.add_mobile(str(index), 105)
        source, events = watch.collect_mobile(100, 110, 2, self.database)
        self.assertFalse(source["complete"])
        self.assertTrue(source["truncated"])
        self.assertEqual(len(events), 2)
        with sqlite3.connect(self.database) as db:
            db.execute("UPDATE mobile_diagnostics SET data=? WHERE event_id='0'", (PRIVATE,))
        invalid, events = watch.collect_mobile(100, 110, 20, self.database)
        self.assertFalse(invalid["complete"])
        self.assertEqual(invalid["error_code"], "mobile_record_invalid")
        self.assertEqual(len(events), 2)

    def test_mobile_incomplete_scan_keeps_overall_cursor_incomplete(self):
        self.add_mobile("one", 105)
        self.add_mobile("two", 106)

        def command(arguments):
            return command_reply(b"ActiveState=active\nSubState=running\nResult=success\n" if arguments[0] == "systemctl" else b"")

        report = watch.collect(100, 110, mobile_limit=1, clock=lambda: 120,
                               database=self.database, command=command, health=healthy)
        self.assertEqual(report["format"], "continuation-runtime-report-v1")
        self.assertFalse(report["complete"])
        self.assertTrue(report["truncated"])
        self.assertTrue(report["sources"]["database"]["complete"])
        self.assertFalse(report["sources"]["mobile"]["complete"])
        self.assertEqual(report["events"][0]["source"], "mobile")

    def test_database_selects_only_permitted_columns_and_redacts_message(self):
        diagnostic = {"http_status": 200, "has_error": True, "response_bytes": 100,
                      "root": "object", PRIVATE: PRIVATE,
                      "usage_fields": {"prompt_tokens": "integer", PRIVATE: "string"}}
        self.add_job("private-job-id", 105, message="[gateway_error] " + PRIVATE + " diagnostic=" + json.dumps(diagnostic))
        original_connect = sqlite3.connect

        def connect(*args, **kwargs):
            self.assertTrue(kwargs.get("uri"))
            self.assertIn("mode=ro", args[0])
            db = original_connect(*args, **kwargs)

            def authorize(action, table, column, database, trigger):
                if action == sqlite3.SQLITE_READ and table == "jobs" and column in {"payload", "entries", "provider", "owner_id"}:
                    return sqlite3.SQLITE_DENY
                return sqlite3.SQLITE_OK

            db.set_authorizer(authorize)
            return db

        with mock.patch.object(watch.sqlite3, "connect", side_effect=connect):
            source, events = watch.collect_database(100, 110, 20, self.database)
        self.assertTrue(source["complete"])
        self.assertEqual(len(events), 1)
        serialized = json.dumps(events)
        self.assertNotIn(PRIVATE, serialized)
        self.assertNotIn("private-job-id", serialized)
        self.assertEqual(events[0]["diagnostic"]["usage_fields"], {"prompt_tokens": "integer"})
        self.assertEqual(events[0]["category"], "upstream_failure")
        self.assertEqual(len(events[0]["event_id"]), 64)

    def test_window_is_inclusive_and_normal_operations_are_ignored(self):
        for index, timestamp in enumerate([99, 100, 105, 110, 111]):
            self.add_job(f"timed-{index}", timestamp)
        self.add_job("cancelled", 105, status="cancelled")
        self.add_job("paused-queue", 105, status="failed", count="none")
        self.add_job("reconciled", 105, status="failed", message="管理员已核对并结算实际费用")
        source, events = watch.collect_database(100, 110, 20, self.database)
        self.assertTrue(source["complete"])
        self.assertEqual([event["at_unix"] for event in events], [100, 105, 110])
        self.assertEqual(source["ignored_normal"], 2)

    def test_event_ids_are_stable_and_incident_fingerprint_ignores_payload_size(self):
        for index, size in enumerate([100, 300]):
            self.add_job(f"case-{index}", 105 + index, message="[gateway_error] diagnostic=" + json.dumps(
                {"http_status": 200, "has_error": True, "response_bytes": size}))
        _, first = watch.collect_database(100, 110, 20, self.database)
        _, second = watch.collect_database(100, 110, 20, self.database)
        self.assertEqual(first, second)
        self.assertNotEqual(first[0]["event_id"], first[1]["event_id"])
        self.assertEqual(first[0]["fingerprint"], first[1]["fingerprint"])

    def test_reasoning_diagnostics_preserve_bounded_metadata_and_deduplicate_sizes(self):
        for index, size in enumerate([100, 300]):
            self.add_job(f"reasoning-{index}", 105 + index, status="failed", message="[output_limit] diagnostic=" + json.dumps({
                "http_status": 200, "has_reasoning": True, "reasoning_content_type": "string",
                "reasoning_content_bytes": size, "reasoning_tokens": size, "elapsed_ms": size,
                "stage": "response_validation",
                "reasoning_content": PRIVATE, "completion_tokens_details": {"private": PRIVATE},
            }))
        source, events = watch.collect_database(100, 110, 20, self.database)
        self.assertTrue(source["complete"])
        self.assertEqual(events[0]["diagnostic"], {
            "http_status": 200, "has_reasoning": True, "reasoning_content_type": "string",
            "reasoning_content_bytes": 100, "reasoning_tokens": 100, "elapsed_ms": 100,
            "stage": "response_validation",
        })
        self.assertEqual(events[0]["fingerprint"], events[1]["fingerprint"])
        self.assertNotIn(PRIVATE, json.dumps(events))
        self.assertEqual(watch.safe_diagnostic({
            "reasoning_content_bytes": True, "reasoning_tokens": -1,
            "has_reasoning": PRIVATE, "reasoning_content_type": PRIVATE,
            "elapsed_ms": -3, "stage": PRIVATE,
        }), {})

    def test_database_truncation_and_failure_are_explicit(self):
        for index in range(3):
            self.add_job(str(index), 105)
        source, events = watch.collect_database(100, 110, 2, self.database)
        self.assertFalse(source["complete"])
        self.assertTrue(source["truncated"])
        self.assertEqual(len(events), 2)
        missing = str(Path(self.directory.name) / "missing.sqlite3")
        failed, events = watch.collect_database(100, 110, 2, missing)
        self.assertFalse(failed["complete"])
        self.assertEqual(failed["error_code"], "database_read_failed")
        self.assertFalse(Path(missing).exists())

    def test_network_timeout_diagnostics_hide_transport_info_and_ignore_timings_in_fingerprint(self):
        for index, elapsed in enumerate((90001, 90020)):
            self.add_job(f"timeout-{index}", 105 + index, message="[runner_timeout] diagnostic=" + json.dumps({
                "stage": "transport", "network_stage": "waiting_headers", "exception_type": "total_timeout",
                "elapsed_ms": elapsed, "network_stage_elapsed_ms": elapsed - 70,
                "request_sent": True, "response_headers_received": False,
                "network_events": {"connection.connect_tcp.complete": 40,
                    "http11.send_request_body.complete": 70, "http11.receive_response_headers.failed": elapsed,
                    PRIVATE: 2, "http11.send_request_headers.started": {"headers": PRIVATE},
                    "connection.start_tls.complete": -1},
                "host": PRIVATE, "exception_message": PRIVATE, "trace_info": {"headers": PRIVATE},
            }))
        source, events = watch.collect_database(100, 110, 20, self.database)
        self.assertTrue(source["complete"])
        self.assertEqual(events[0]["category"], "upstream_failure")
        self.assertEqual(events[0]["diagnostic"]["network_stage"], "waiting_headers")
        self.assertTrue(events[0]["diagnostic"]["request_sent"])
        self.assertEqual(len(events[0]["diagnostic"]["network_events"]), 3)
        self.assertEqual(events[0]["fingerprint"], events[1]["fingerprint"])
        self.assertNotIn(PRIVATE, json.dumps(events))
        self.assertEqual(watch.safe_diagnostic({"network_stage": PRIVATE, "exception_type": PRIVATE,
            "network_stage_elapsed_ms": True, "request_sent": 1, "response_headers_received": PRIVATE,
            "network_events": {"connection.connect_tcp.complete": True, PRIVATE: 2}}), {"network_events": {}})

    def test_json_diagnostics_filter_raw_errors_and_ignore_location_in_fingerprint(self):
        for index, offset in enumerate([12, 50]):
            self.add_job(f"json-{index}", 105 + index, status="failed", message="[json] diagnostic=" + json.dumps({
                "json_error_type": "expected_comma", "json_wrapper": "bare_fence", "json_has_bom": True,
                "json_chars": offset + 40, "json_error_offset": offset, "json_error_line": 2,
                "json_error_column": offset, "body_read_complete": True,
                "json_error_message": PRIVATE, "json_document": PRIVATE,
            }))
        source, events = watch.collect_database(100, 110, 20, self.database)
        self.assertTrue(source["complete"])
        self.assertEqual(events[0]["diagnostic"]["json_error_type"], "expected_comma")
        self.assertEqual(events[0]["diagnostic"]["json_error_offset"], 12)
        self.assertEqual(events[0]["fingerprint"], events[1]["fingerprint"])
        self.assertNotIn(PRIVATE, json.dumps(events))
        self.assertEqual(watch.safe_diagnostic({"json_error_type": PRIVATE, "json_wrapper": PRIVATE,
                                               "json_error_offset": -1, "json_error_line": True,
                                               "json_has_bom": PRIVATE, "body_read_complete": 1}), {})

    def test_json_shape_probe_metadata_is_whitelisted_without_raw_text(self):
        safe = {"prompt_version": 3, "json_first_char": "letter", "json_invisible_prefix": False,
                "json_edge_format_removed": False, "json_candidate_count": 1,
                "json_has_object_candidate": True, "json_has_array_candidate": False,
                "json_scan_limited": False, "json_identical_duplicates": True, "json_missing_key_quote": True}
        filtered = watch.safe_diagnostic({**safe, "json_first_character": PRIVATE,
                                          "json_first_codepoint": 12345, "json_candidates": [PRIVATE],
                                          "json_keys": [PRIVATE]})
        self.assertEqual(filtered, safe)
        self.assertEqual(watch.safe_diagnostic({"json_first_char": PRIVATE, "json_candidate_count": 3,
                                               "json_invisible_prefix": PRIVATE,
                                               "json_has_object_candidate": 1, "json_identical_duplicates": PRIVATE,
                                               "json_missing_key_quote": PRIVATE}), {})
        self.assertEqual(watch.safe_diagnostic({"prompt_version": 2}), {"prompt_version": 2})
        self.assertEqual(watch.safe_diagnostic({"prompt_version": 4}), {"prompt_version": 4})
        self.assertEqual(watch.safe_diagnostic({"prompt_version": 5}), {"prompt_version": 5})
        for invalid_version in (True, 2.0, 6, "3", PRIVATE):
            self.assertEqual(watch.safe_diagnostic({"prompt_version": invalid_version}), {})

    def test_schema_shapes_exclude_private_keys_values_and_invalid_counts(self):
        safe = {"schema_layout": "wrapped", "schema_item_count": 2, "schema_other_fields": 1,
                "schema_normalized": True, "schema_fields": {"result": "object", "entries": "array"}}
        result = watch.safe_diagnostic({**safe, "schema_fields": {
            **safe["schema_fields"], PRIVATE: "string", "text": PRIVATE, "content": {"body": PRIVATE}},
            "schema_raw": PRIVATE, "schema_keys": [PRIVATE]})
        self.assertEqual(result, safe)
        self.assertNotIn(PRIVATE, json.dumps(result))
        for invalid in (True, -1, 1.5, 1_000_001, PRIVATE):
            self.assertEqual(watch.safe_diagnostic({"schema_item_count": invalid,
                                                   "schema_other_fields": invalid}), {})
        self.assertEqual(watch.safe_diagnostic({"schema_layout": PRIVATE, "schema_normalized": 1}), {})

    def test_failure_capture_reference_is_safe_and_does_not_split_incident(self):
        capture_id = "11111111-1111-4111-8111-111111111111"
        safe = {"failure_capture_state": "saved", "failure_capture_id": capture_id,
                "failure_capture_expires_at": 1794000000}
        self.assertEqual(watch.safe_diagnostic({**safe, "failure_capture_body": PRIVATE}), safe)
        self.assertEqual(watch.safe_diagnostic({"failure_capture_id": PRIVATE,
                                               "failure_capture_state": PRIVATE,
                                               "failure_capture_expires_at": True}), {})
        row = {"id": "job-a", "terminal_at": 105, "status": "failed", "count_state": "used",
               "message": "[schema] validation failed diagnostic=" + json.dumps(safe)}
        first = watch.job_event(row)
        changed = {**safe, "failure_capture_id": "22222222-2222-4222-8222-222222222222",
                   "failure_capture_expires_at": 1794000001}
        second = watch.job_event({**row, "id": "job-b",
                                  "message": "[schema] validation failed diagnostic=" + json.dumps(changed)})
        self.assertEqual(first["fingerprint"], second["fingerprint"])
        self.assertNotEqual(first["event_id"], second["event_id"])
        self.assertEqual(first["diagnostic"]["failure_capture_id"], capture_id)

    def test_journal_exports_only_error_classes_and_allowed_frames(self):
        body = '\n'.join([
            "Traceback (most recent call last):",
            '  File "/opt/deepseek-continuation/app/store.py", line 70, in connection',
            '  File "/private/secret_module.py", line 99, in hidden',
            "sqlite3.OperationalError: " + PRIVATE + " https://secret.invalid/?key=" + PRIVATE,
        ])
        line = journal_record(105, "private-journal-cursor", body)
        source, events = watch.collect_journal(100, 110, 20, lambda args: command_reply(line))
        self.assertTrue(source["complete"])
        self.assertEqual(events[0]["error_types"], ["OperationalError"])
        self.assertEqual(events[0]["app_frames"], [{"module": "app.store", "line": 70}])
        text = json.dumps(events)
        for private in (PRIVATE, "private-journal-cursor", "secret.invalid", "secret_module"):
            self.assertNotIn(private, text)

    def test_normal_http_quota_and_cancellation_logs_are_ignored(self):
        records = [journal_record(105, str(index), message) for index, message in enumerate([
            'INFO: POST /v1/jobs?secret=' + PRIVATE + ' HTTP/1.1 429 Too Many Requests',
            'asyncio.exceptions.CancelledError: ' + PRIVATE,
            'INFO: Application shutdown complete.',
        ])]
        source, events = watch.collect_journal(100, 110, 20, lambda args: command_reply(b"\n".join(records)))
        self.assertTrue(source["complete"])
        self.assertEqual(events, [])

    def test_separate_traceback_lines_keep_only_allowed_module_and_line(self):
        lines = [
            "Traceback (most recent call last):",
            '  File "/opt/deepseek-continuation/app/provider.py", line 352, in organize',
            "    sensitive_call(" + PRIVATE + ")",
            '  File "/private/contacts.py", line 88, in hidden',
            "ValueError: " + PRIVATE,
        ]
        records = [journal_record(105 + index / 100, str(index), line) for index, line in enumerate(lines)]
        source, events = watch.collect_journal(100, 110, 20, lambda args: command_reply(b"\n".join(records)))
        self.assertTrue(source["complete"])
        self.assertEqual(len(events), 2)
        self.assertEqual(events[0]["codes"], ["app_trace_frame"])
        self.assertEqual(events[0]["category"], "trace_context")
        self.assertEqual(events[0]["app_frames"], [{"module": "app.provider", "line": 352}])
        self.assertEqual(events[1]["error_types"], ["ValueError"])
        serialized = json.dumps(events)
        for private in (PRIVATE, "sensitive_call", "contacts.py", "organize"):
            self.assertNotIn(private, serialized)

    def test_journal_window_truncation_invalid_records_and_command_failure(self):
        records = [journal_record(timestamp, str(index), "RuntimeError: " + PRIVATE)
                   for index, timestamp in enumerate([99, 100, 110, 111])]
        source, events = watch.collect_journal(100, 110, 20, lambda args: command_reply(b"\n".join(records)))
        self.assertTrue(source["complete"])
        self.assertEqual([event["at_unix"] for event in events], [100, 110])
        limited, _ = watch.collect_journal(100, 110, 2, lambda args: command_reply(b"\n".join(records)))
        self.assertFalse(limited["complete"])
        self.assertTrue(limited["truncated"])
        invalid, _ = watch.collect_journal(100, 110, 2, lambda args: command_reply(PRIVATE.encode()))
        self.assertEqual(invalid["error_code"], "journal_record_invalid")
        failed, _ = watch.collect_journal(100, 110, 2, lambda args: command_reply(ok=False))
        self.assertEqual(failed["error_code"], "command_failed")

    def test_systemd_filters_all_nonallowed_properties(self):
        output = b"ActiveState=active\nSubState=running\nResult=success\nMainPID=123\nNRestarts=2\n"
        output += ("Environment=" + PRIVATE + "\nExecStart=" + PRIVATE).encode()
        result = watch.collect_systemd(lambda args: command_reply(output))
        self.assertTrue(result["complete"])
        self.assertEqual(result["state"]["NRestarts"], 2)
        self.assertNotIn(PRIVATE, json.dumps(result))
        self.assertNotIn("Environment", result["state"])

    def test_report_schema_until_and_incomplete_exit(self):
        self.add_job("in-window", 105)
        commands = []

        def command(arguments):
            commands.append(arguments)
            if arguments[0] == "systemctl":
                return command_reply(b"ActiveState=active\nSubState=running\nResult=success\n")
            return command_reply()

        report = watch.collect(100, 110, clock=lambda: 120, database=self.database, command=command, health=healthy)
        self.assertEqual(report["format"], "continuation-runtime-report-v1")
        self.assertEqual(report["now_unix"], 120)
        self.assertEqual(report["window_end_unix"], 110)
        self.assertTrue(report["complete"])
        self.assertTrue(any("--until=@110" in args for args in commands))
        for complete, expected in [(True, 0), (False, 2)]:
            with mock.patch.object(watch, "collect", return_value={**report, "complete": complete}):
                output = io.StringIO()
                with redirect_stdout(output):
                    code = watch.main(["--since", "100", "--until", "110"])
                self.assertEqual(code, expected)
                self.assertEqual(json.loads(output.getvalue())["complete"], complete)

    def test_invalid_window_is_reported_without_running_sources(self):
        command = mock.Mock(side_effect=AssertionError("must not collect"))
        report = watch.collect(120, 100, clock=lambda: 120, database=self.database, command=command, health=healthy)
        self.assertFalse(report["complete"])
        self.assertEqual(report["error_code"], "invalid_window_or_limit")
        self.assertEqual(report["sources"], {})
        nonfinite = watch.collect(float("nan"), clock=lambda: 120, database=self.database, command=command, health=healthy)
        json.dumps(nonfinite, allow_nan=False)


if __name__ == "__main__":
    unittest.main()
