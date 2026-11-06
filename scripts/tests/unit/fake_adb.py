"""Owned local shell/pipes standing in for adb; never invokes an adb binary."""
import os
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

from encapp_tool import adb_cmds

REAL_POPEN = subprocess.Popen
TRANSPORT = r'''
import os, subprocess, sys, time
command = sys.argv[1]
mode = os.environ.get("FAKE_PAYLOAD_MODE", "")
if mode == "stall":
    time.sleep(5)
    sys.exit(0)
if mode == "stderr":
    sys.stderr.buffer.write(b"diagnostic" * 20000)
if mode == "fail":
    sys.stderr.write("injected transport failure")
    sys.exit(7)
sys.exit(subprocess.call(command, shell=True, cwd=os.environ["FAKE_APP_ROOT"]))
'''


class FakeAdbTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.app = self.root / "app"
        (self.app / "files").mkdir(parents=True)
        self.source = self.root / "source.bin"
        self.source.write_bytes(b"payload" * 1500)
        self.destination = self.root / "received.bin"
        self.commands = []
        self.processes = []
        self.patches = [
            mock.patch.dict(os.environ, {"FAKE_APP_ROOT": str(self.app)}),
            mock.patch.object(adb_cmds, "USE_IDB", False),
            mock.patch.object(adb_cmds, "PACE_FILE_TRANSFERS", True),
            mock.patch.object(adb_cmds, "run_cmd", side_effect=self.control),
            mock.patch.object(subprocess, "Popen", side_effect=self.transport),
            mock.patch.object(tempfile, "gettempdir", return_value=str(self.root)),
        ]
        for patch in self.patches:
            patch.start()
            self.addCleanup(patch.stop)
        self.addCleanup(self.tmp.cleanup)

    @staticmethod
    def inner(command):
        argv = shlex.split(command)
        if argv[:2] == ["run-as", "com.facebook.encapp"]:
            argv = argv[2:]
        assert argv[:2] == ["sh", "-c"], argv
        return argv[2]

    def control(self, command, **kwargs):
        self.commands.append(command)
        argv = shlex.split(command)
        self.assertEqual(argv[:2], ["adb", "-s"])
        self.assertEqual(argv[3], "shell")
        inner = self.inner(argv[4])
        # This shell only sees our owned local fixture paths (or files/).
        process = REAL_POPEN(["sh", "-c", inner], cwd=self.app,
                             stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        out, err = process.communicate(timeout=5)
        return process.returncode == 0, out.decode(), err.decode()

    def transport(self, argv, **kwargs):
        self.assertEqual(argv[:2], ["adb", "-s"])
        self.assertIn(argv[3], ["exec-in", "exec-out"])
        process = REAL_POPEN([sys.executable, "-c", TRANSPORT, self.inner(argv[4])],
                             **kwargs)
        self.processes.append(process)
        return process
