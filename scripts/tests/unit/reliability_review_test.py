"""No-device regressions for publication and independent-review blockers."""
import os
from pathlib import Path
import sys
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
import encapp  # noqa: E402
from encapp_tool import adb_cmds, adb_payload  # noqa: E402
from .fake_adb import FakeAdbTest  # noqa: E402


class PublicationReviewTest(FakeAdbTest):
    def rejected_destination(self, split, symlink):
        workdir = self.root / "remote"
        workdir.mkdir()
        destination = workdir / self.source.name
        target = self.root / "directory-target"
        target.mkdir()
        if symlink:
            destination.symlink_to(target, target_is_directory=True)
        else:
            destination.mkdir()
            target = destination
        with mock.patch.object(
                adb_cmds, "MAX_SIZE_BYTES", 1 if split else 999999), mock.patch.object(
                adb_cmds, "SPLIT_SIZE_BYTES", 4096):
            self.assertFalse(adb_cmds.push_file_to_device_android(
                str(self.source), "Device A", str(workdir), 0))
        self.assertEqual([], list(target.iterdir()))
        self.assertTrue(destination.is_dir())
        if split:
            self.assertTrue(list(workdir.glob("encapp-part.*")))
            self.assertTrue(list(workdir.glob("*.assembly.partial.*")))
        else:
            self.assertTrue(list(workdir.glob("*.partial.*")))

    def test_direct_directory_target_rejected(self):
        self.rejected_destination(False, False)

    def test_direct_symlink_to_directory_rejected(self):
        self.rejected_destination(False, True)

    def test_split_directory_target_rejected_without_cleaning_parts(self):
        self.rejected_destination(True, False)

    def test_split_symlink_to_directory_rejected_without_cleaning_parts(self):
        self.rejected_destination(True, True)

    def test_reuse_does_not_admit_a_symlink_as_the_final_file(self):
        workdir = self.root / "remote"
        workdir.mkdir()
        destination = workdir / self.source.name
        destination.symlink_to(self.source)
        self.assertTrue(adb_cmds.push_file_to_device_android(
            str(self.source), "Device A", str(workdir), 0))
        self.assertFalse(destination.is_symlink())
        self.assertEqual(self.source.read_bytes(), destination.read_bytes())

    def test_unsupported_no_directory_target_has_no_plain_mv_fallback(self):
        original = adb_payload.shell
        commands = []

        def no_mv_T(serial, inner, path, debug=0, **kwargs):
            commands.append(inner)
            if "mv -fT" in inner:
                return False, "", "mv: unknown option T"
            return original(serial, inner, path, debug, **kwargs)

        with mock.patch.object(adb_payload, "shell", side_effect=no_mv_T):
            self.assertFalse(adb_payload.push(
                "Device A", str(self.source), str(self.destination)))
        self.assertFalse(self.destination.exists())
        self.assertFalse(any("mv -f --" in command for command in commands))
        self.assertEqual(1, len(list(self.root.glob("received.bin.partial.*"))))

    def test_split_final_verification_failure_retains_parts(self):
        workdir = self.root / "remote"
        workdir.mkdir()
        destination = str(workdir / self.source.name)
        original = adb_payload.remote_hash

        def wrong_final(serial, path, debug=0, **kwargs):
            if path == destination:
                return "0" * 32
            return original(serial, path, debug, **kwargs)

        with mock.patch.object(adb_cmds, "MAX_SIZE_BYTES", 1), mock.patch.object(
                adb_cmds, "SPLIT_SIZE_BYTES", 4096), mock.patch.object(
                adb_payload, "remote_hash", side_effect=wrong_final):
            self.assertFalse(adb_cmds.push_file_to_device_android(
                str(self.source), "Device A", str(workdir), 0))
        self.assertTrue(Path(destination).is_file())
        self.assertTrue(list(workdir.glob("encapp-part.*")))

    def test_private_directory_target_is_rejected(self):
        (self.app / "files" / "destination").mkdir()
        remote = "/data/user/0/com.facebook.encapp/files/destination"
        self.assertFalse(adb_payload.push("Device A", str(self.source), remote))
        self.assertEqual([], list((self.app / "files" / "destination").iterdir()))
        self.assertTrue(list((self.app / "files").glob("destination.partial.*")))

    def test_final_file_verification_failure_is_not_success(self):
        original = adb_payload.remote_hash

        def wrong_final(serial, path, debug=0, **kwargs):
            if path == str(self.destination):
                return "0" * 32
            return original(serial, path, debug, **kwargs)

        with mock.patch.object(adb_payload, "remote_hash", side_effect=wrong_final):
            self.assertFalse(adb_payload.push(
                "Device A", str(self.source), str(self.destination)))


class ProbeAndControlReviewTest(FakeAdbTest):
    def test_permission_stat_failure_is_not_absence(self):
        with mock.patch.object(adb_payload, "shell", return_value=(False, "", "Permission denied")):
            with self.assertRaisesRegex(adb_payload.PayloadTransferError, "Permission denied"):
                adb_cmds.pull_file_from_device(
                    "Device A", str(self.source), str(self.root), 0, snapshot=True)

    def test_malformed_stat_is_not_absence(self):
        with mock.patch.object(adb_payload, "shell", return_value=(True, "not a size", "")):
            with self.assertRaisesRegex(adb_payload.PayloadTransferError, "not a size"):
                adb_cmds.pull_file_from_device(
                    "Device A", str(self.source), str(self.root), 0, snapshot=True)

    def test_size_probe_requires_unsigned_ascii_decimal(self):
        for value in ("-1", "+1", "1_000", "1x", ""):
            with self.subTest(value=value), mock.patch.object(
                    adb_payload, "shell", return_value=(True, value, "")):
                with self.assertRaises(adb_payload.PayloadTransferError):
                    adb_cmds.pull_file_from_device(
                        "Device A", str(self.source), str(self.root), 0, snapshot=True)

    def test_explicit_absence_is_the_only_normal_poll_miss(self):
        self.source.unlink()
        self.assertFalse(adb_cmds.pull_file_from_device(
            "Device A", str(self.source), str(self.root), 0, snapshot=True))
        self.assertEqual([], self.processes)

    def test_run_as_or_transport_failure_is_not_absence(self):
        for error in ("run-as: package is not debuggable", "error: device offline"):
            with self.subTest(error=error), mock.patch.object(
                    adb_payload, "shell", return_value=(False, "", error)):
                with self.assertRaisesRegex(adb_payload.PayloadTransferError, error):
                    adb_cmds.pull_file_from_device(
                        "Device A", str(self.source), str(self.root), 0, snapshot=True)

    def test_control_timeout_kills_and_reaps_owned_client(self):
        created = []
        original = self.transport

        def record(*args, **kwargs):
            process = original(*args, **kwargs)
            created.append(process)
            return process

        with mock.patch.object(adb_payload, "CONTROL_TIMEOUT", 0.05, create=True), mock.patch.dict(
                os.environ, {"FAKE_CONTROL_MODE": "stall"}), mock.patch.object(
                adb_payload.subprocess, "Popen", side_effect=record):
            with self.assertRaises(adb_payload.PayloadTransferError):
                adb_payload.shell("Device A", "true", str(self.source))
        self.assertEqual(1, len(created))
        self.assertIsNotNone(created[0].poll())

    def test_control_cancellation_kills_and_reaps_owned_client(self):
        created = []
        original = self.transport

        def interrupted(*args, **kwargs):
            process = original(*args, **kwargs)
            process.communicate = mock.Mock(side_effect=KeyboardInterrupt("cancel control"))
            created.append(process)
            return process

        with mock.patch.dict(os.environ, {"FAKE_CONTROL_MODE": "stall"}), mock.patch.object(
                adb_payload.subprocess, "Popen", side_effect=interrupted):
            with self.assertRaises(KeyboardInterrupt):
                adb_payload.shell("Device A", "true", str(self.source))
        self.assertEqual(1, len(created))
        self.assertIsNotNone(created[0].poll())

    def test_timeout_releases_the_payload_lock(self):
        import fcntl
        with mock.patch.object(adb_payload, "CONTROL_TIMEOUT", 0.05), mock.patch.dict(
                os.environ, {"FAKE_CONTROL_MODE": "stall"}):
            with self.assertRaises(adb_payload.PayloadTransferError):
                with adb_payload.payload_budget(True):
                    adb_payload.shell("Device A", "true", str(self.source))
        lock = self.root / f"encapp-payload-{os.getuid()}.lock"
        with lock.open("a+b") as handle:
            fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            fcntl.flock(handle.fileno(), fcntl.LOCK_UN)

    def test_dangling_symlink_is_an_error_not_absence(self):
        link = self.root / "dangling.jsonl"
        link.symlink_to(self.root / "absent-target")
        with self.assertRaises(adb_payload.PayloadTransferError):
            adb_cmds.pull_file_from_device(
                "Device A", str(link), str(self.root), 0, snapshot=True)

    def test_nonzero_probe_cannot_spoof_the_absence_marker(self):
        with mock.patch.object(adb_payload, "shell", return_value=(
                False, adb_payload.ABSENT_MARKER, "transport failed")):
            with self.assertRaisesRegex(adb_payload.PayloadTransferError, "transport failed"):
                adb_cmds.pull_file_from_device(
                    "Device A", str(self.source), str(self.root), 0, snapshot=True)

    def test_missing_unsearchable_parent_is_not_normal_absence(self):
        path = self.root / "missing-parent" / "live.jsonl"
        with self.assertRaisesRegex(adb_payload.PayloadTransferError, "parent directory"):
            adb_cmds.pull_file_from_device(
                "Device A", str(path), str(self.root), 0, snapshot=True)

    def test_bulk_controls_use_explicit_larger_byte_budget(self):
        self.assertGreater(adb_payload.bulk_timeout(1024 ** 3), adb_payload.CONTROL_TIMEOUT)
        with mock.patch.object(adb_payload, "control", wraps=adb_payload.control) as command:
            self.assertEqual(adb_payload.remote_hash("Device A", str(self.source)),
                             adb_cmds.get_host_hash(str(self.source), 0))
        hashes = [call for call in command.call_args_list
                  if "md5sum" in " ".join(call.args[0])]
        self.assertEqual(1, len(hashes))
        self.assertGreater(hashes[0].kwargs["timeout"], adb_payload.CONTROL_TIMEOUT)

    def test_split_assembly_and_cleanup_never_use_legacy_unbounded_runner(self):
        workdir = self.root / "remote"
        workdir.mkdir()
        with mock.patch.object(adb_cmds, "MAX_SIZE_BYTES", 1), mock.patch.object(
                adb_cmds, "SPLIT_SIZE_BYTES", 4096), mock.patch.object(
                adb_cmds, "run_cmd", side_effect=AssertionError("legacy unbounded control")):
            self.assertTrue(adb_cmds.push_file_to_device_android(
                str(self.source), "Device A", str(workdir), 0))


class RestorationAndCliReviewTest(FakeAdbTest):
    def interrupted_finalization(self, body_error=None, diagnostic="full"):
        suite = encapp.tests_definitions.TestSuite()
        suite.test.add().common.id = "case"
        order = []

        def restore(*args, **kwargs):
            order.append("restore")

        def metadata(*args, **kwargs):
            order.append("metadata")
            return "metadata.json"

        def interrupt(*args, **kwargs):
            order.append("diagnostic")
            raise KeyboardInterrupt("diagnostic cancelled")

        with mock.patch.object(
                adb_cmds, "push_file_to_device", return_value=True), mock.patch.object(
                encapp, "_configure_mediaformat_shaping"), mock.patch.object(
                encapp, "_restore_mediaformat_shaping", side_effect=restore), mock.patch.object(
                encapp, "_write_mediaformat_shaping_metadata",
                side_effect=metadata), mock.patch.object(
                encapp, "run_encapp_test", side_effect=body_error), mock.patch.object(
                encapp, "_oracle_via_manifest", return_value=(True, [])), mock.patch.object(
                encapp, "AndroidLogcatCapture") as capture, mock.patch.object(
                encapp, "_capture_full_session_logcat", side_effect=interrupt):
            capture.return_value.stop.return_value = "streamed.txt"
            if diagnostic == "stream":
                capture.return_value.stop.side_effect = interrupt
            expected = type(body_error) if body_error else KeyboardInterrupt
            try:
                encapp.run_codec_tests(suite, [], "Device A", "Device A", str(self.root),
                                       str(self.root), str(self.root), full_logcat=True)
            except BaseException as error:
                actual = error
            else:
                self.fail("diagnostic cancellation was swallowed")
        self.assertIsInstance(actual, expected)
        self.assertEqual(["restore", "metadata"], order[:2])
        if body_error:
            self.assertIs(actual, body_error)
        else:
            self.assertIn("diagnostic cancelled", str(actual))

    def test_diagnostic_interrupt_preserves_original_run_failure_and_cleanup(self):
        self.interrupted_finalization(ValueError("original run failure"))

    def test_diagnostic_interrupt_is_reraised_after_cleanup_on_success(self):
        self.interrupted_finalization()

    def test_stream_interrupt_cannot_skip_cleanup(self):
        self.interrupted_finalization(ValueError("original run failure"), "stream")

    def test_pacing_flag_rejected_for_unsupported_commands(self):
        for command in ("pull_result", "list", "clear", "kill"):
            with self.subTest(command=command), self.assertRaises(SystemExit) as error:
                encapp.get_options(["encapp.py", command, "--pace-file-transfers"])
            self.assertEqual(2, error.exception.code)

    def test_run_accepts_pacing_flag(self):
        options = encapp.get_options(["encapp.py", "run", "test.pbtxt", "--pace-file-transfers"])
        self.assertTrue(options.pace_file_transfers)
