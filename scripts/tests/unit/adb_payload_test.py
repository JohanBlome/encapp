"""No-device reliability regressions using owned local shell and pipes."""
import os
from pathlib import Path
import shlex
import sys
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from encapp_tool import adb_cmds, adb_payload  # noqa: E402
from .fake_adb import FakeAdbTest  # noqa: E402


class PayloadTransferTest(FakeAdbTest):
    def pull(self, **kwargs):
        return adb_payload.pull("Device A", str(self.source),
                                str(self.destination), **kwargs)

    def test_verified_download(self):
        self.assertTrue(self.pull())
        self.assertEqual(self.source.read_bytes(), self.destination.read_bytes())
        self.assertFalse(list(self.root.glob("received.bin.partial.*")))

    def test_empty_immutable_download(self):
        self.source.write_bytes(b"")
        self.assertTrue(self.pull())
        self.assertEqual(b"", self.destination.read_bytes())

    def test_missing_source_does_not_start_payload(self):
        self.source.unlink()
        self.assertFalse(self.pull())
        self.assertEqual([], self.processes)

    def test_hash_failure_retains_partial_and_existing_destination(self):
        self.destination.write_bytes(b"previous result")
        with mock.patch.object(adb_payload, "remote_hash", return_value="0" * 32):
            self.assertFalse(self.pull())
        self.assertEqual(b"previous result", self.destination.read_bytes())
        self.assertEqual(1, len(list(self.root.glob("received.bin.partial.*"))))

    def test_short_and_extra_streams_fail_without_retry(self):
        size = self.source.stat().st_size
        for observed in (size - 1, size + 1):
            with self.subTest(observed=observed), mock.patch.object(
                    adb_payload, "remote_size", return_value=observed):
                before = len(self.processes)
                self.assertFalse(self.pull(verify_hash=False))
                self.assertEqual(before + 1, len(self.processes))
                self.assertFalse(self.destination.exists())

    def test_nonzero_exit_fails_without_retry(self):
        with mock.patch.dict(os.environ, {"FAKE_PAYLOAD_MODE": "fail"}):
            self.assertFalse(self.pull())
        self.assertEqual(1, len(self.processes))
        self.assertIsNotNone(self.processes[0].poll())
        self.assertFalse(self.destination.exists())

    def test_stall_is_bounded_and_child_reaped(self):
        with mock.patch.dict(os.environ, {"FAKE_PAYLOAD_MODE": "stall"}), mock.patch.object(
                adb_payload, "IO_TIMEOUT", 0.05):
            self.assertFalse(self.pull())
        self.assertEqual(1, len(self.processes))
        self.assertIsNotNone(self.processes[0].poll())

    def test_large_stderr_does_not_deadlock(self):
        with mock.patch.dict(os.environ, {"FAKE_PAYLOAD_MODE": "stderr"}):
            self.assertTrue(self.pull())
        self.assertEqual(self.source.read_bytes(), self.destination.read_bytes())

    def test_verified_upload_and_quoted_paths(self):
        remote = self.root / "Device A's payload.bin"
        self.assertTrue(adb_payload.push("Device A", str(self.source), str(remote)))
        self.assertEqual(self.source.read_bytes(), remote.read_bytes())
        self.assertFalse(list(self.root.glob("*.partial.*")))

    def test_upload_mismatch_preserves_previous_and_partial(self):
        remote = self.root / "published.bin"
        remote.write_bytes(b"previous source")
        with mock.patch.object(adb_payload, "remote_hash", return_value="0" * 32):
            self.assertFalse(adb_payload.push("Device A", str(self.source), str(remote)))
        self.assertEqual(b"previous source", remote.read_bytes())
        self.assertEqual(1, len(list(self.root.glob("published.bin.partial.*"))))

    def test_partial_pipe_writes_do_not_drop_bytes(self):
        real_write = os.write
        with mock.patch.object(os, "write", side_effect=lambda fd, data: real_write(fd, data[:37])):
            self.assertTrue(adb_payload.push("Device A", str(self.source), str(self.destination)))
        self.assertEqual(self.source.read_bytes(), self.destination.read_bytes())

    def test_app_private_upload_and_download(self):
        remote = "/data/user/0/com.facebook.encapp/files/Device A's source.bin"
        self.assertTrue(adb_payload.push("Device A", str(self.source), remote))
        self.assertTrue(adb_payload.pull("Device A", remote, str(self.destination)))
        self.assertEqual(self.source.read_bytes(), self.destination.read_bytes())
        self.assertTrue(all("run-as" in command for command in self.commands))

    def test_pacing_admission_and_block_sizes(self):
        size = self.source.stat().st_size
        admit = mock.Mock()
        with mock.patch.object(adb_payload, "payload_budget") as budget:
            budget.return_value.__enter__.return_value = admit
            self.assertTrue(adb_payload.push("Device A", str(self.source), str(self.destination)))
        counts = [call.args[0] for call in admit.call_args_list]
        self.assertEqual(size, sum(counts))
        self.assertLessEqual(max(counts), adb_payload.BLOCK_BYTES)
        self.assertEqual((size + 4095) // 4096, len(counts))

    def test_pacer_sleeps_for_every_admission_without_idle_credit(self):
        with mock.patch.object(adb_payload.time, "sleep") as sleep:
            with adb_payload.payload_budget(True) as admit:
                admit(4096)
                admit(1000)
            with adb_payload.payload_budget(True) as admit:
                admit(2000)
        self.assertEqual([mock.call(count / adb_payload.RATE_BYTES)
                          for count in (4096, 1000, 2000)], sleep.call_args_list)

    def test_non_posix_opt_in_rejected_without_transport(self):
        with mock.patch.object(adb_payload.os, "name", "nt"):
            self.assertFalse(self.pull())
        self.assertEqual([], self.processes)

    def test_unpaced_budget_does_not_import_fcntl(self):
        with mock.patch.dict(sys.modules, {"fcntl": None}):
            with adb_payload.payload_budget(False) as admit:
                admit(1)

    def test_lock_rejects_symlink(self):
        lock = self.root / f"encapp-payload-{os.getuid()}.lock"
        lock.symlink_to(self.source)
        self.assertFalse(self.pull())
        self.assertEqual([], self.processes)

    def test_atomic_rename_failure_preserves_partial(self):
        original = adb_payload.shell

        def fail_rename(serial, inner, path, debug=0, **kwargs):
            if "mv -fT" in inner:
                return False, "", "injected rename failure"
            return original(serial, inner, path, debug, **kwargs)
        with mock.patch.object(adb_payload, "shell", side_effect=fail_rename):
            self.assertFalse(adb_payload.push("Device A", str(self.source), str(self.destination)))
        self.assertFalse(self.destination.exists())
        self.assertEqual(1, len(list(self.root.glob("received.bin.partial.*"))))


class SplitTransferTest(FakeAdbTest):
    def setUp(self):
        super().setUp()
        self.source.write_bytes(bytes(range(256)) * 4)
        self.remote = self.root / "remote"
        self.remote.mkdir()
        self.patches.extend([
            mock.patch.object(adb_cmds, "MAX_SIZE_BYTES", 32),
            mock.patch.object(adb_cmds, "SPLIT_SIZE_BYTES", 37),
        ])
        for patch in self.patches[-2:]:
            patch.start()
            self.addCleanup(patch.stop)

    def push(self):
        return adb_cmds.push_file_to_device_android(
            str(self.source), "Device A", str(self.remote), debug=0)

    def test_verified_split_assembly_and_cleanup(self):
        self.assertTrue(self.push())
        self.assertEqual(self.source.read_bytes(), (self.remote / self.source.name).read_bytes())
        self.assertEqual([self.source.name], sorted(p.name for p in self.remote.iterdir()))
        self.assertFalse(list(self.root.glob("encapp-split.*")))

    def test_part_failure_retained_and_explicit_repeat_reuses_hashes(self):
        original = adb_payload.push
        calls = 0

        def fail_second(*args, **kwargs):
            nonlocal calls
            calls += 1
            return False if calls == 2 else original(*args, **kwargs)
        with mock.patch.object(adb_payload, "push", side_effect=fail_second):
            self.assertFalse(self.push())
        self.assertEqual(1, len(list(self.remote.glob("encapp-part.*"))))
        self.assertFalse((self.remote / self.source.name).exists())
        self.assertTrue(list(self.root.glob("encapp-split.*")))
        with mock.patch.object(adb_payload, "push", wraps=original) as upload:
            self.assertTrue(self.push())
            self.assertEqual(27, upload.call_count)  # 28 parts, first reused.

    def test_assembly_mismatch_does_not_publish_or_clean_parts(self):
        original = adb_payload.remote_hash

        def wrong_assembly(serial, path, debug=0, **kwargs):
            return ("0" * 32 if ".assembly.partial." in path else
                    original(serial, path, debug, **kwargs))
        with mock.patch.object(adb_payload, "remote_hash", side_effect=wrong_assembly):
            self.assertFalse(self.push())
        self.assertFalse((self.remote / self.source.name).exists())
        self.assertEqual(28, len(list(self.remote.glob("encapp-part.*"))))
        self.assertEqual(1, len(list(self.remote.glob("*.assembly.partial.*"))))

    def test_many_quoted_unicode_paths_have_bounded_ordered_commands(self):
        paths = [str(self.remote / f"Device A's é part {index:06d}") for index in range(1000)]
        for operation in ("cat", "rm"):
            commands = list(adb_payload.file_commands(
                "Device A", paths, operation, str(self.remote / "assembly")))
            self.assertGreater(len(commands), 1)
            flattened = []
            for command in commands:
                self.assertLessEqual(len(command.encode()), 8192)
                inner = shlex.split(self.inner(shlex.split(command)[4]))
                flattened.extend(inner[2:-2] if operation == "cat" else inner[3:])
            self.assertEqual(paths, flattened)

    def test_invalid_file_batches_rejected(self):
        for args in (([], "cat", "out"), (["a"], "cat", None),
                     (["a"], "unknown", None), (["x" * 9000], "rm", None)):
            with self.subTest(args=args), self.assertRaises(ValueError):
                list(adb_payload.file_commands("Device A", *args))

    def test_bad_split_size_never_starts_payload(self):
        with mock.patch.object(adb_cmds, "SPLIT_SIZE_BYTES", 0):
            with self.assertRaises(ValueError):
                self.push()
        self.assertEqual([], self.processes)

    def test_verified_source_reuse_does_not_trust_fast_copy(self):
        self.assertTrue(self.push())
        with mock.patch.object(adb_payload, "push") as upload:
            self.assertTrue(adb_cmds.push_file_to_device(
                str(self.source), "Device A", str(self.remote), True, 0))
            upload.assert_not_called()
        published = self.remote / self.source.name
        published.write_bytes(b"x" * self.source.stat().st_size)
        self.assertTrue(adb_cmds.push_file_to_device(
            str(self.source), "Device A", str(self.remote), True, 0))
        self.assertEqual(self.source.read_bytes(), published.read_bytes())

    def test_app_private_split_assembly(self):
        self.assertTrue(adb_cmds.push_file_to_device_android(
            str(self.source), "Device A",
            "/data/user/0/com.facebook.encapp/files", debug=0))
        published = self.app / "files" / self.source.name
        self.assertEqual(self.source.read_bytes(), published.read_bytes())

    def test_pacing_cli_is_explicit_and_platform_checked(self):
        import encapp
        self.assertFalse(encapp.get_options(["encapp.py", "run", "test.pbtxt"]).pace_file_transfers)
        self.assertTrue(encapp.get_options([
            "encapp.py", "run", "test.pbtxt", "--pace-file-transfers"]).pace_file_transfers)
        with mock.patch.object(adb_payload.os, "name", "nt"):
            with self.assertRaises(SystemExit) as exit_code:
                encapp.get_options(["encapp.py", "run", "test.pbtxt", "--pace-file-transfers"])
            self.assertEqual(2, exit_code.exception.code)


if __name__ == "__main__":
    unittest.main()
