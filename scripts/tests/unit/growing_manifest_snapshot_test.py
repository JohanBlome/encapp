"""Observed-prefix snapshots of growing control files, with no devices."""
import os
from pathlib import Path
import sys
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from encapp_tool import adb_cmds, adb_payload, session_manifest  # noqa: E402
from .fake_adb import FakeAdbTest  # noqa: E402


class GrowingManifestSnapshotTest(FakeAdbTest):
    def pull(self, paced=True, **kwargs):
        return adb_payload.pull("Device A", str(self.source), str(self.destination),
                                verify_hash=False, snapshot=True, paced=paced, **kwargs)

    def test_growth_after_stat_reads_only_observed_prefix(self):
        prefix = b'{"event":"session_start","session_id":"S1"}\n'
        original = adb_payload.remote_size
        for paced in (True, False):
            self.source.write_bytes(prefix)

            def stat_then_append(*args, **kwargs):
                size = original(*args, **kwargs)
                with self.source.open("ab") as output:
                    output.write(b'{"event":"session_end","status":"complete"}\n')
                return size

            with self.subTest(paced=paced), mock.patch.object(
                    adb_payload, "remote_size", side_effect=stat_then_append):
                self.assertTrue(self.pull(paced=paced))
            self.assertEqual(prefix, self.destination.read_bytes())

    def test_short_snapshot_rejected_in_both_paths(self):
        for paced in (True, False):
            with self.subTest(paced=paced), mock.patch.object(
                    adb_payload, "remote_size", return_value=self.source.stat().st_size + 1):
                self.assertFalse(self.pull(paced=paced))
            self.assertFalse(self.destination.exists())

    def test_extra_snapshot_bytes_and_nonzero_exit_are_rejected(self):
        size = self.source.stat().st_size
        for paced in (True, False):
            for mode in ("extra", "exit_after"):
                with self.subTest(paced=paced, mode=mode), mock.patch.object(
                        adb_payload, "remote_size", return_value=size), mock.patch.dict(
                        os.environ, {"FAKE_PAYLOAD_MODE": mode}):
                    self.assertFalse(self.pull(paced=paced))
                self.assertFalse(self.destination.exists())

    def test_unpaced_snapshot_timeout_reaps_its_child(self):
        with mock.patch.object(adb_payload, "remote_size", return_value=10), mock.patch.dict(
                os.environ, {"FAKE_PAYLOAD_MODE": "stall"}), mock.patch.object(
                adb_payload, "IO_TIMEOUT", 0.05):
            self.assertFalse(self.pull(paced=False))
        self.assertEqual(1, len(self.processes))
        self.assertIsNotNone(self.processes[0].poll())
        self.assertFalse(self.destination.exists())

    def test_snapshot_cannot_claim_whole_file_hash(self):
        with self.assertRaises(ValueError):
            adb_payload.pull("Device A", str(self.source), str(self.destination), snapshot=True)
        self.assertEqual([], self.processes)

    def test_snapshot_never_queries_whole_file_hash(self):
        with mock.patch.object(
                adb_payload, "remote_hash", side_effect=AssertionError("whole-file hash")):
            self.assertTrue(self.pull())

    def test_empty_snapshot_is_exactly_empty(self):
        self.source.write_bytes(b"")
        self.assertTrue(self.pull())
        self.assertEqual(b"", self.destination.read_bytes())

    def test_unpaced_snapshot_avoids_posix_import_and_pipe_selectors(self):
        with mock.patch.dict(sys.modules, {"fcntl": None}), mock.patch.object(
                adb_payload.selectors, "DefaultSelector", side_effect=AssertionError("selector")):
            self.assertTrue(self.pull(paced=False))
        self.assertEqual(self.source.read_bytes(), self.destination.read_bytes())

    def test_unpaced_snapshot_does_not_require_posix_os(self):
        with mock.patch.object(os, "name", "nt"):
            self.assertTrue(self.pull(paced=False))
        self.assertEqual(self.source.read_bytes(), self.destination.read_bytes())

    def test_app_private_snapshot_uses_run_as(self):
        remote = "/data/data/com.facebook.encapp/files/live.jsonl"
        (self.app / "files" / "live.jsonl").write_bytes(b"prefix\nappended\n")
        for paced in (True, False):
            with self.subTest(paced=paced), mock.patch.object(
                    adb_payload, "remote_size", return_value=7):
                self.assertTrue(adb_payload.pull(
                    "Device A", remote, str(self.destination), verify_hash=False,
                    snapshot=True, paced=paced))
            self.assertEqual(b"prefix\n", self.destination.read_bytes())

    def test_wrapper_failure_is_terminal_not_repolled(self):
        with mock.patch.object(adb_payload, "remote_size", return_value=999999):
            with self.assertRaises(adb_payload.PayloadTransferError):
                adb_cmds.pull_file_from_device(
                    "Device A", str(self.source), str(self.root), 0, snapshot=True)
        self.assertEqual(1, len(self.processes))

    def test_missing_manifest_is_normal_not_yet_present(self):
        self.source.unlink()
        self.assertFalse(adb_cmds.pull_file_from_device(
            "Device A", str(self.source), str(self.root), 0, snapshot=True))
        self.assertEqual([], self.processes)

    def test_partial_final_jsonl_line_remains_partial_not_terminal(self):
        prefix = b'{"event":"session_start","session_id":"S1"}\n{"event":"session_end"'
        self.source.write_bytes(prefix + b',"status":"complete"}\n')
        with mock.patch.object(adb_payload, "remote_size", return_value=len(prefix)):
            self.assertTrue(self.pull())
        verdict = session_manifest.classify_session(
            session_manifest.parse_manifest_text(self.destination.read_text()), ["case"])
        self.assertTrue(verdict.session_started)
        self.assertFalse(verdict.session_completed)
        self.assertEqual(session_manifest.Status.NEVER_STARTED, verdict.tests["case"].status)

    def test_oracle_snapshots_manifest_but_not_immutable_artifacts(self):
        import encapp
        suite = encapp.tests_definitions.TestSuite()
        suite.test.add().common.id = "case"
        verdict = session_manifest.classify_session([
            {"event": "session_start", "session_id": "S1"},
            {"event": "artifact", "test_id": "case", "kind": "stats", "path": "stats.json"},
            {"event": "test_end", "test_id": "case", "status": "ok"},
            {"event": "session_end", "status": "complete"},
        ], ["case"])
        capture = mock.Mock()

        def poll(**kwargs):
            kwargs["pull_fn"]("/owned/S1.session.jsonl", str(self.root))
            self.assertEqual(capture.progress_token, kwargs["progress_fn"])
            return verdict

        with mock.patch.object(
                session_manifest, "wait_for_session", side_effect=poll), mock.patch.object(
                adb_cmds, "pull_file_from_device", return_value=True) as pull:
            self.assertEqual((True, [str(self.root / "stats.json")]), encapp._oracle_via_manifest(
                suite, "DeviceA", "/owned", str(self.root), "S1", 0, logcat_capture=capture))
        self.assertEqual([
            mock.call("DeviceA", "/owned/S1.session.jsonl", str(self.root), 0, snapshot=True),
            mock.call("DeviceA", "/owned/stats.json", str(self.root), 0),
        ], pull.call_args_list)

    def test_paced_artifact_failure_cannot_be_reported_as_pass(self):
        import encapp
        suite = encapp.tests_definitions.TestSuite()
        suite.test.add().common.id = "case"
        verdict = session_manifest.classify_session([
            {"event": "artifact", "test_id": "case", "kind": "stats", "path": "stats.json"},
            {"event": "test_end", "test_id": "case", "status": "ok"},
        ], ["case"])
        with mock.patch.object(
                session_manifest, "wait_for_session", return_value=verdict), mock.patch.object(
                adb_cmds, "pull_file_from_device", return_value=False):
            with self.assertRaises(adb_payload.PayloadTransferError):
                encapp._oracle_via_manifest(suite, "DeviceA", "/owned", str(self.root), "S1", 0)
