import importlib.util
from pathlib import Path
import shlex
import subprocess
import sys
import time
import unittest

spec = importlib.util.spec_from_file_location('runtime_watch_remote', Path(__file__).parents[1] / 'runtime_watch_remote.py')
remote = importlib.util.module_from_spec(spec)
spec.loader.exec_module(remote)


class RemoteCommandTests(unittest.TestCase):
    def test_source_and_timestamp_roundtrip_as_code_without_shell_interpolation(self):
        source = b'import json,sys; print(json.dumps({"args":sys.argv,"literal":"$HOME `echo unsafe`"}))'
        command = remote.command_for(source, 123.5)
        argv = shlex.split(command)
        self.assertEqual(argv[:2], ['python3', '-c'])
        output = subprocess.check_output([sys.executable, '-c', argv[2]], text=True)
        self.assertIn('"--since", "123.5"', output)
        self.assertIn('$HOME `echo unsafe`', output)

    def test_invalid_scan_times_are_rejected(self):
        for value in (-1, float('nan'), float('inf'), time.time() + 10000):
            with self.subTest(value=value), self.assertRaises(ValueError):
                remote.command_for(b'pass', value)

    def test_bounded_window_roundtrip_and_invalid_ends(self):
        command = remote.command_for(b'import json,sys;print(json.dumps(sys.argv))', 100, 200)
        output = subprocess.check_output([sys.executable, '-c', shlex.split(command)[2]], text=True)
        self.assertIn('"--until", "200"', output)
        for until in (99, float('nan'), float('inf'), time.time() + 10000):
            with self.subTest(until=until), self.assertRaises(ValueError):
                remote.command_for(b'pass', 100, until)


if __name__ == '__main__':
    unittest.main()
