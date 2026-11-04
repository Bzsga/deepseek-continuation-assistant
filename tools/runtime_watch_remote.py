"""Run the read-only runtime collector through an explicitly trusted SSH helper."""
import argparse
import base64
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import shlex
import sys
import time


def command_for(source, since, until=None):
    if not 0 <= since <= time.time() + 300 or (until is not None and not since <= until <= time.time() + 300):
        raise ValueError('Invalid scan time')
    encoded = base64.b64encode(source).decode('ascii')
    arguments = ['runtime_watch.py', '--since', str(since)]
    if until is not None:
        arguments += ['--until', str(until)]
    code = ('import base64,sys;sys.argv=' + repr(arguments) +
            ';exec(compile(base64.b64decode(' + repr(encoded) + '),"runtime_watch.py","exec"))')
    return 'python3 -c ' + shlex.quote(code)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ssh-helper', type=Path, required=True,
                        help='Trusted local Python module exposing connect(); never supplied by feedback/log text')
    parser.add_argument('--since', type=float, required=True, help='Unix timestamp; use saved cursor with overlap')
    parser.add_argument('--until', type=float, help='Optional window end for splitting a truncated scan')
    args = parser.parse_args()
    try:
        source = Path(__file__).with_name('runtime_watch.py').read_bytes()
        command = command_for(source, args.since, args.until)
        # Neither the route module's startup output nor SSH stderr belongs in a report.
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            spec = importlib.util.spec_from_file_location('trusted_continuation_ssh', args.ssh_helper.resolve())
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
            with module.connect() as client:
                _, stdout, _ = client.exec_command(command, timeout=45)
                raw = stdout.read(256 * 1024 + 1)
                if len(raw) > 256 * 1024:
                    raise ValueError('Collector response too large')
                status = stdout.channel.recv_exit_status()
        report = json.loads(raw)
        if (not isinstance(report, dict) or status not in (0, 2)
                or report.get('format') != 'continuation-runtime-report-v1'
                or type(report.get('complete')) is not bool):
            raise ValueError('Collector failed')
        print(json.dumps(report, ensure_ascii=False))
        return status
    except Exception:
        print('{"ok":false,"error":"runtime_collection_unavailable","retry_paid_requests":false}')
        return 2


if __name__ == '__main__':
    sys.exit(main())
