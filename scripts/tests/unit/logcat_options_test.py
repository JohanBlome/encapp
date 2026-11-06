"""Opt-in logcat collection regressions; every device operation is mocked."""
from pathlib import Path
import shlex
import sys
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
import encapp  # noqa: E402
from encapp_tool import adb_cmds, adb_payload  # noqa: E402
from .fake_adb import FakeAdbTest  # noqa: E402


class LogcatOptionsTest(FakeAdbTest):
    def test_flags_are_opt_in_and_independent(self):
        default = encapp.get_options(["encapp.py", "run", "test.pbtxt"])
        self.assertFalse(default.preserve_logcat)
        self.assertFalse(default.full_logcat)
        preserve = encapp.get_options([
            "encapp.py", "run", "test.pbtxt", "--preserve-logcat"])
        self.assertTrue(preserve.preserve_logcat)
        self.assertFalse(preserve.full_logcat)
        full = encapp.get_options(["encapp.py", "run", "test.pbtxt", "--full-logcat"])
        self.assertFalse(full.preserve_logcat)
        self.assertTrue(full.full_logcat)

    def test_android_default_clears_then_streams_then_launches(self):
        order = []
        capture = mock.Mock()
        capture.start.side_effect = lambda: order.append("stream")

        def clear(*args):
            order.append("clear")

        def launch(*args, **kwargs):
            order.append("launch")
            return True, "", ""

        with mock.patch.object(adb_cmds, "reset_logcat", side_effect=clear), mock.patch.object(
                adb_cmds, "run_cmd", side_effect=launch):
            encapp.run_encapp_test("test.pbtxt", "DeviceA", "/owned",
                                   session_id="S1", logcat_capture=capture)
        self.assertEqual(["clear", "stream", "launch"], order)

    def test_preserve_skips_clear_without_disabling_upstream_stream(self):
        for override in ("", "custom-runner"):
            capture = mock.Mock()
            with self.subTest(override=override), mock.patch.object(
                    adb_cmds, "reset_logcat") as reset, mock.patch.object(
                    adb_cmds, "run_cmd", return_value=(True, "", "")):
                encapp.run_encapp_test("test.pbtxt", "DeviceA", "/owned",
                                       run_cmd=override, session_id="S1",
                                       logcat_capture=capture, preserve_logcat=True)
            reset.assert_not_called()
            capture.start.assert_called_once_with()

    def test_ios_launch_path_unchanged(self):
        with mock.patch.object(adb_cmds, "USE_IDB", True), mock.patch.object(
                adb_cmds, "reset_logcat") as reset, mock.patch.object(
                adb_cmds, "remove_files_using_regex"), mock.patch.object(
                adb_cmds, "run_cmd", return_value=(True, "", "")) as command, mock.patch.object(
                encapp, "wait_for_exit"):
            encapp.run_encapp_test("test.pbtxt", "DeviceA", "Documents",
                                   session_id="S1", preserve_logcat=True)
        reset.assert_not_called()
        self.assertTrue(command.call_args.args[0].startswith("xcrun devicectl"))

    def run_suite(self, split=False, full=True, preserve=True, failure=None, ios=False):
        suite = encapp.tests_definitions.TestSuite()
        test = suite.test.add()
        test.common.id = "case"
        test.input.filepath = "fake_input"
        with mock.patch.object(adb_cmds, "USE_IDB", ios), mock.patch.object(
                adb_cmds, "push_file_to_device", return_value=True), mock.patch.object(
                adb_cmds, "pull_files_from_device"), mock.patch.object(
                adb_cmds, "run_cmd", return_value=(True, "", "")), mock.patch.object(
                encapp.encapp_tool.app_utils, "force_stop"), mock.patch.object(
                encapp.time, "sleep"), mock.patch.object(
                encapp, "make_session_id", return_value="S1"), mock.patch.object(
                encapp, "get_media_files"), mock.patch.object(
                encapp, "collect_results", return_value=[]), mock.patch.object(
                encapp, "_configure_mediaformat_shaping"), mock.patch.object(
                encapp, "_restore_mediaformat_shaping") as restore, mock.patch.object(
                encapp, "AndroidLogcatCapture") as capture, mock.patch.object(
                encapp, "dump_session_logcat") as fallback, mock.patch.object(
                encapp, "_capture_full_session_logcat") as full_capture, mock.patch.object(
                encapp, "_oracle_via_manifest", return_value=(True, [])), mock.patch.object(
                encapp, "run_encapp_test", side_effect=failure) as launch:
            capture.return_value.stop.return_value = "streamed-log.txt"
            full_capture.side_effect = adb_payload.PayloadTransferError(
                "injected diagnostic failure")
            if failure:
                with self.assertRaises(type(failure)):
                    encapp.run_codec_tests(suite, [], "Device A", "DeviceA", str(self.root),
                                           str(self.root), "/owned", split=split,
                                           preserve_logcat=preserve, full_logcat=full)
            else:
                encapp.run_codec_tests(suite, [], "Device A", "DeviceA", str(self.root),
                                       str(self.root), "/owned", split=split,
                                       preserve_logcat=preserve, full_logcat=full)
        return launch, full_capture, restore, capture, fallback

    def test_split_and_unsplit_propagate_preserve_and_collect_extra_log(self):
        for split in (True, False):
            with self.subTest(split=split):
                launch, full, restore, capture, fallback = self.run_suite(split=split)
                self.assertTrue(launch.call_args.kwargs["preserve_logcat"])
                full.assert_called_once_with("DeviceA", "/owned", str(self.root), "S1", 0)
                restore.assert_called_once()
                capture.return_value.stop.assert_called_once()
                fallback.assert_not_called()

    def test_full_collection_is_off_by_default(self):
        _, full, restore, _, _ = self.run_suite(full=False, preserve=False)
        full.assert_not_called()
        restore.assert_called_once()

    def test_failed_logcat_does_not_mask_run_error_or_shaping_restore(self):
        _, full, restore, _, _ = self.run_suite(failure=ValueError("run failed"))
        full.assert_called_once()
        restore.assert_called_once()

    def test_ios_ignores_full_collection(self):
        _, full, restore, capture, _ = self.run_suite(ios=True)
        full.assert_not_called()
        capture.assert_not_called()
        restore.assert_called_once()


class FullLogcatCollectionTest(FakeAdbTest):
    def setUp(self):
        super().setUp()
        self.log = b"1700000000.001 1 2 E codec: diagnostic detail\n"
        self.stages = []
        self.controls = []
        self.original_shell = adb_payload.shell

    def stage(self, serial, inner, path, debug=0, portable=False, timeout=None):
        self.controls.append((inner, path, portable))
        if "logcat -d" in inner:
            argv = shlex.split(inner)
            if "run-as" in argv:
                remote = self.app / shlex.split(argv[-1])[-1]
            else:
                remote = Path(argv[-1])
            remote.write_bytes(self.log)
            self.stages.append(remote)
            return True, "", ""
        return self.original_shell(serial, inner, path, debug, portable=portable, timeout=timeout)

    def collect(self, device_workdir=None):
        return encapp._capture_full_session_logcat(
            "Device A", device_workdir or str(self.root), str(self.root), "S1")

    def test_bounded_epoch_snapshot_keeps_existing_streamed_log(self):
        stream = self.root / "S1.android_logcat.txt"
        stream.write_bytes(b"upstream complete threadtime stream")
        with mock.patch.object(adb_payload, "shell", side_effect=self.stage):
            path = self.collect()
        self.assertEqual(self.log, Path(path).read_bytes())
        self.assertEqual(b"upstream complete threadtime stream", stream.read_bytes())
        command = self.controls[0][0]
        self.assertIn("-b main -b system -v epoch -t 20000 '*:V'", command)
        self.assertFalse(self.stages[0].exists())
        self.assertIn("rm -f --", self.controls[-1][0])

    def test_unpaced_snapshot_is_verified_without_posix_selectors(self):
        with mock.patch.object(adb_cmds, "PACE_FILE_TRANSFERS", False), mock.patch.dict(
                sys.modules, {"fcntl": None}), mock.patch.object(
                adb_payload.selectors, "DefaultSelector",
                side_effect=AssertionError("selector")), mock.patch.object(
                adb_payload, "shell", side_effect=self.stage):
            path = self.collect()
        self.assertEqual(self.log, Path(path).read_bytes())
        self.assertTrue(all(portable for _, _, portable in self.controls))

    def test_app_private_stage_reads_as_shell_and_writes_as_app(self):
        with mock.patch.object(adb_payload, "shell", side_effect=self.stage):
            path = self.collect("/data/user/0/com.facebook.encapp/files")
        self.assertEqual(self.log, Path(path).read_bytes())
        command = self.controls[0][0]
        self.assertTrue(command.startswith("set -o pipefail; logcat"))
        self.assertIn("| run-as com.facebook.encapp sh -c", command)
        self.assertEqual("/", self.controls[0][1])
        self.assertFalse(self.stages[0].exists())

    def test_staging_failure_never_downloads_or_cleans(self):
        with mock.patch.object(
                adb_payload, "shell", return_value=(False, "", "producer failed")
        ) as command, mock.patch.object(adb_payload, "pull") as pull:
            with self.assertRaises(adb_payload.PayloadTransferError):
                self.collect()
        self.assertEqual(1, command.call_count)
        pull.assert_not_called()

    def test_download_failure_retains_remote_stage(self):
        with mock.patch.object(adb_payload, "shell", side_effect=self.stage), mock.patch.object(
                adb_payload, "pull", return_value=False):
            with self.assertRaises(adb_payload.PayloadTransferError):
                self.collect()
        self.assertTrue(self.stages[0].exists())
        self.assertFalse(any("rm -f" in command for command, _, _ in self.controls))

    def test_cleanup_failure_reports_local_and_remote_evidence(self):
        def fail_cleanup(serial, inner, path, debug=0, portable=False, timeout=None):
            if inner.startswith("rm -f"):
                return False, "", "injected cleanup failure"
            return self.stage(serial, inner, path, debug, portable, timeout)

        with mock.patch.object(adb_payload, "shell", side_effect=fail_cleanup):
            with self.assertRaisesRegex(adb_payload.PayloadTransferError, "local snapshot"):
                self.collect()
        self.assertTrue(self.stages[0].exists())
        self.assertEqual(self.log, (self.root / "S1.android_logcat.full.txt").read_bytes())

    def test_empty_logcat_is_a_valid_empty_snapshot(self):
        self.log = b""
        with mock.patch.object(adb_payload, "shell", side_effect=self.stage):
            self.assertEqual(b"", Path(self.collect()).read_bytes())
