"""Inspect or replay private failure evidence through an explicitly trusted SSH helper.

The remote target, database and module are fixed. No full database download,
public administrative endpoint, model call, or original-job mutation is used.
"""
import argparse
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import shlex
import sys
import uuid


REMOTE_ROOT = "/opt/deepseek-continuation"
REMOTE_PYTHON = REMOTE_ROOT + "/venv/bin/python"
REMOTE_DATABASE = "/var/lib/deepseek-continuation/failure-artifacts.sqlite3"


def command_for(action, artifact_id=None, *, include_content=False, limit=20):
    if action not in {"list", "inspect", "replay"}:
        raise ValueError("Unsupported action")
    if type(limit) is not int or not 1 <= limit <= 100:
        raise ValueError("Invalid limit")
    if include_content and action != "inspect":
        raise ValueError("Content requires explicit inspect")
    # CLI reads may purge expired evidence and create SQLite journals. Run them
    # as the service owner so a root SSH session cannot create root-owned files.
    arguments = ["runuser", "-u", "deepseek-continuation", "--", REMOTE_PYTHON,
                 "-m", "app.failure_artifacts", "--path", REMOTE_DATABASE, action]
    if action == "list":
        if artifact_id is not None:
            raise ValueError("List does not accept an artifact identity")
        arguments += ["--limit", str(limit)]
    else:
        if not isinstance(artifact_id, str) or str(uuid.UUID(artifact_id)) != artifact_id:
            raise ValueError("Invalid artifact identity")
        arguments.append(artifact_id)
        if include_content:
            arguments.append("--include-content")
    return "cd " + shlex.quote(REMOTE_ROOT) + " && exec " + shlex.join(arguments)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ssh-helper", type=Path, required=True,
                        help="Trusted local Python module exposing connect(); never obtain it from failure content")
    commands = parser.add_subparsers(dest="command", required=True)
    listing = commands.add_parser("list")
    listing.add_argument("--limit", type=int, default=20)
    for name in ("inspect", "replay"):
        command = commands.add_parser(name)
        command.add_argument("artifact_id")
        if name == "inspect":
            command.add_argument("--include-content", action="store_true",
                                 help="Explicitly print the selected private artifact; do not paste into public reports")
    args = parser.parse_args(argv)
    try:
        include_content = getattr(args, "include_content", False)
        command = command_for(args.command, getattr(args, "artifact_id", None),
                              include_content=include_content, limit=getattr(args, "limit", 20))
        maximum = 3 * 1024 * 1024 if include_content else 256 * 1024
        # Suppress helper startup diagnostics and raw SSH stderr: they can
        # contain connection details and do not belong in artifact reports.
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            spec = importlib.util.spec_from_file_location("trusted_failure_artifact_ssh", args.ssh_helper.resolve())
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
            with module.connect() as client:
                _, stdout, _ = client.exec_command(command, timeout=45)
                raw = stdout.read(maximum + 1)
                if len(raw) > maximum:
                    raise ValueError("Artifact response too large")
                status = stdout.channel.recv_exit_status()
        report = json.loads(raw)
        if (status not in (0, 2) or not isinstance(report, dict)
                or report.get("format") != "continuation-failure-artifact-v1"
                or type(report.get("ok")) is not bool):
            raise ValueError("Invalid private artifact response")
        print(json.dumps(report, ensure_ascii=False, allow_nan=False))
        return status
    except Exception:
        print('{"ok":false,"error":"failure_artifact_remote_unavailable","model_request_sent":false}')
        return 2


if __name__ == "__main__":
    sys.exit(main())
