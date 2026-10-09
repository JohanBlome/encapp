#!/usr/bin/env python3
import argparse
import csv
import importlib.util
import json
import math
import os
from pathlib import Path
import re
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest import mock


ROOT = Path(__file__).resolve().parents[3]
SCRIPT = ROOT / "scripts" / "encoder_rc_probe.py"
SPEC = importlib.util.spec_from_file_location("encoder_rc_probe", SCRIPT)
probe = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(probe)

PLOT_SCRIPT = ROOT / "scripts" / "encoder_rc_probe_plots.py"
PLOT_SPEC = importlib.util.spec_from_file_location("encoder_rc_probe_plots", PLOT_SCRIPT)
plots = importlib.util.module_from_spec(PLOT_SPEC)
assert PLOT_SPEC.loader is not None
PLOT_SPEC.loader.exec_module(plots)

REPORT_SCRIPT = ROOT / "scripts" / "encoder_rc_probe_report.py"
REPORT_SPEC = importlib.util.spec_from_file_location(
    "encoder_rc_probe_report", REPORT_SCRIPT)
report_renderer = importlib.util.module_from_spec(REPORT_SPEC)
assert REPORT_SPEC.loader is not None
sys.modules[REPORT_SPEC.name] = report_renderer
REPORT_SPEC.loader.exec_module(report_renderer)

COMPARE_SCRIPT = ROOT / "scripts" / "encoder_rc_probe_compare.py"
COMPARE_SPEC = importlib.util.spec_from_file_location(
    "encoder_rc_probe_compare", COMPARE_SCRIPT)
compare = importlib.util.module_from_spec(COMPARE_SPEC)
assert COMPARE_SPEC.loader is not None
sys.modules[COMPARE_SPEC.name] = compare
COMPARE_SPEC.loader.exec_module(compare)


class EncoderRcProbeTest(unittest.TestCase):
    def test_public_probe_surface_has_no_private_paths_or_internal_urls(self):
        public_files = [
            ROOT / "app/src/main/AndroidManifest.xml",
            ROOT / "app/src/main/java/com/facebook/encapp/AsyncBufferEncoder.java",
            ROOT / "app/src/main/java/com/facebook/encapp/BufferEncoder.java",
            ROOT / "app/src/main/java/com/facebook/encapp/Encoder.java",
            ROOT / "app/src/main/java/com/facebook/encapp/ProbeCapabilitiesReceiver.java",
            ROOT / "app/src/main/java/com/facebook/encapp/utils/FakeInputReader.java",
            ROOT / "app/src/main/java/com/facebook/encapp/utils/MuxerWrapper.java",
            ROOT / "app/src/main/java/com/facebook/encapp/utils/NalLogWriter.java",
            ROOT / "app/src/main/java/com/facebook/encapp/utils/NalScanner.java",
            ROOT / "app/src/test/java/com/facebook/encapp/utils/FakeInputReaderTest.java",
            ROOT / "app/src/test/java/com/facebook/encapp/utils/NalLogWriterTest.java",
            ROOT / "app/src/test/java/com/facebook/encapp/utils/NalScannerTest.java",
            ROOT / "scripts/encoder_rc_probe.py",
            ROOT / "scripts/encoder_rc_probe_plots.py",
            ROOT / "scripts/encoder_rc_probe_report.py",
            ROOT / "scripts/encoder_rc_probe_compare.py",
            ROOT / "scripts/encapp_tool/_version.py",
            ROOT / "scripts/tests/unit/encoder_rc_probe_test.py",
            ROOT / "doc/encoder_rc_probe.md",
        ]
        private_path = re.compile(r"/(?:home|Users)/[A-Za-z0-9._-]+/")
        private_url = re.compile(r"https?://[^\s\"']*\.internal(?:/|\b)")
        for path in public_files:
            text = path.read_text(encoding="utf-8")
            self.assertIsNone(private_path.search(text), path)
            self.assertIsNone(private_url.search(text), path)

    def test_generate_rejects_whitespace_output_from_bad_shell_continuation(self):
        for value in (" ", " /tmp/output", "/tmp/output "):
            args = probe._parser().parse_args(["generate", value])
            with self.assertRaisesRegex(ValueError, "whitespace|trailing spaces"):
                probe.generate(args)

    def test_default_order_seed_is_product_neutral(self):
        args = probe._parser().parse_args(["generate", "/tmp/output"])
        self.assertEqual(args.order_seed, int.from_bytes(b"ENCA", "big"))

    def test_serial_auto_selects_one_authorized_device(self):
        completed = SimpleNamespace(
            stdout="List of devices attached\nSERIAL\tdevice product:test\n")
        with mock.patch.dict(os.environ, {}, clear=False), \
                mock.patch.object(probe, "_run_checked", return_value=completed):
            os.environ.pop("ANDROID_SERIAL", None)
            self.assertEqual(probe._resolve_serial(None), "SERIAL")

    def test_serial_requires_selection_for_multiple_devices(self):
        completed = SimpleNamespace(
            stdout="List of devices attached\nA\tdevice\nB\tdevice\n")
        with mock.patch.dict(os.environ, {}, clear=False), \
                mock.patch.object(probe, "_run_checked", return_value=completed):
            os.environ.pop("ANDROID_SERIAL", None)
            with self.assertRaisesRegex(RuntimeError, "multiple authorized"):
                probe._resolve_serial(None)

    def test_device_run_lock_rejects_concurrent_owner(self):
        serial = f"TEST-{os.getpid()}"
        with probe._device_run_lock(serial):
            with self.assertRaisesRegex(RuntimeError, "already using adb device"):
                with probe._device_run_lock(serial):
                    self.fail("second lock unexpectedly acquired")

    def test_probe_workdir_preflight_uses_app_uid(self):
        completed = SimpleNamespace(returncode=0, stdout="", stderr="")
        with mock.patch.object(probe, "_adb", return_value=completed) as adb_mock:
            self.assertEqual(
                probe._require_probe_workdir("SERIAL"),
                "/data/user/0/com.facebook.encapp/files")
        commands = [call.args for call in adb_mock.call_args_list]
        self.assertIn(
            ("SERIAL", "shell", "run-as", "com.facebook.encapp",
             "mkdir", "-p", "files"), commands)
        self.assertTrue(any("touch" in command for command in commands))

    def test_probe_workdir_preflight_rejects_non_debuggable_app(self):
        completed = SimpleNamespace(
            returncode=1, stdout="", stderr="run-as: package not debuggable")
        with mock.patch.object(probe, "_adb", return_value=completed):
            with self.assertRaisesRegex(RuntimeError, "debug APK|All files access"):
                probe._require_probe_workdir("SERIAL")

    def test_failure_details_classifies_never_started_setup(self):
        with tempfile.TemporaryDirectory() as td:
            case_dir = Path(td)
            (case_dir / "run_summary.json").write_text(json.dumps({
                "tests": [{"status": "never_started",
                           "reason": "manifest not present yet"}],
            }))
            details = probe._encapp_failure_details(
                case_dir, 255, None, "", False)
            self.assertEqual(details["kind"], "setup_failure")
            self.assertEqual(details["reason"], "manifest not present yet")
            self.assertTrue(details["pre_result"])
            self.assertIsNone(
                probe._encapp_failure_details(case_dir, 0, None, "", True))

    def test_failure_details_classifies_app_crash_and_logs(self):
        with tempfile.TemporaryDirectory() as td:
            case_dir = Path(td)
            (case_dir / "run_summary.json").write_text(json.dumps({
                "tests": [{"status": "crash",
                           "reason": "test_start emitted but no test_end"}],
            }))
            (case_dir / "session.android_logcat.txt").write_text("log")
            details = probe._encapp_failure_details(
                case_dir, 255, None, "", False)
            self.assertEqual(details["kind"], "app_crash")
            self.assertIn("test_start", details["reason"])
            self.assertTrue(any(
                path.endswith("session.android_logcat.txt")
                for path in details["diagnostics"]))

    def test_full_accepts_every_generate_option(self):
        parser = probe._parser()
        subparsers = next(
            action for action in parser._actions
            if isinstance(action, argparse._SubParsersAction))

        def options(command):
            return {
                option
                for action in subparsers.choices[command]._actions
                for option in action.option_strings
                if option not in {"-h", "--help"}
            }

        self.assertLessEqual(options("generate"), options("full"))

    def test_runner_has_no_encapp_install_option(self):
        parser = probe._parser()
        subparsers = next(
            action for action in parser._actions
            if isinstance(action, argparse._SubParsersAction))
        for command in ("run", "full"):
            options = {
                option
                for action in subparsers.choices[command]._actions
                for option in action.option_strings
            }
            self.assertNotIn("--install", options)
            self.assertNotIn("--install-each", options)
        self.assertNotIn('cmd.append("--install")', SCRIPT.read_text())

    def test_full_orchestrates_generate_run_and_analyze(self):
        args = probe._parser().parse_args([
            "full", "/tmp/output", "--serial", "SERIAL", "--no-plots"])
        manifest = Path("/tmp/output/manifest.json")
        with mock.patch.object(probe, "generate", return_value=manifest) as generate_mock, \
                mock.patch.object(probe, "run_matrix") as run_mock, \
                mock.patch.object(probe, "analyze") as analyze_mock, \
                mock.patch.object(probe, "_run_checked") as plots_mock:
            self.assertEqual(probe.full(args), manifest)
        generate_mock.assert_called_once_with(args)
        run_args = run_mock.call_args.args[0]
        self.assertEqual(run_args.manifest, str(manifest))
        self.assertEqual(run_args.serial, "SERIAL")
        self.assertEqual(run_args.case_regex, ".*")
        self.assertEqual(run_args.limit, 0)
        analyze_mock.assert_called_once_with(run_args)
        plots_mock.assert_not_called()

    def test_parallel_analysis_preserves_manifest_order_and_isolates_failures(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            cases = [{"id": name} for name in ("slow", "missing", "fast")]
            (root / "manifest.json").write_text(json.dumps({
                "schema_version": probe.SCHEMA_VERSION,
                "global": {"fps": 30, "bitrate_bps": 1_000_000},
                "cases": cases,
            }))
            args = argparse.Namespace(
                manifest=str(root), smoothing_seconds=1.0,
                qpextract=None, ffmpeg=None, case_regex=".*", jobs=3,
                no_plots=True, raw_qp_overlay=False, plot_repeats=False,
                allow_missing=True)

            def analyze_case(case, *_unused):
                if case["id"] == "missing":
                    raise FileNotFoundError("missing")
                if case["id"] == "slow":
                    import time
                    time.sleep(0.02)
                summary = {"case_id": case["id"]}
                return summary, [{"case_id": case["id"]}], [], []

            with mock.patch.object(probe, "_analyze_case", side_effect=analyze_case), \
                    mock.patch.object(probe, "_write_report"):
                probe.analyze(args)
            body = json.loads((root / "analysis" / "analysis.json").read_text())
            self.assertEqual(
                [row["case_id"] for row in body["runs"]], ["fast", "slow"])
            self.assertEqual(body["missing_cases"], ["missing"])
            with (root / "analysis" / "frames.csv").open() as handle:
                rows = list(csv.DictReader(handle))
            self.assertEqual(
                [row["case_id"] for row in rows], ["fast", "slow"])

    def test_case_publication_order_is_scenario_profile_mode_repeat(self):
        rows = [
            {"case_id": "drop", "scenario": "drops", "qp_profile": "unbounded",
             "mode": "cbr", "repeat": 1},
            {"case_id": "bounded_vbr", "scenario": "dynamics",
             "qp_profile": "jcq_27_38", "mode": "vbr", "repeat": 1},
            {"case_id": "unbounded_vbr", "scenario": "dynamics",
             "qp_profile": "unbounded", "mode": "vbr", "repeat": 1},
            {"case_id": "unbounded_cbr_r2", "scenario": "dynamics",
             "qp_profile": "unbounded", "mode": "cbr", "repeat": 2},
            {"case_id": "unbounded_cbr_r1", "scenario": "dynamics",
             "qp_profile": "unbounded", "mode": "cbr", "repeat": 1},
            {"case_id": "hard", "scenario": "hard_start",
             "qp_profile": "unbounded", "mode": "cbr", "repeat": 1},
        ]
        self.assertEqual(
            [row["case_id"] for row in sorted(rows, key=probe._case_sort_key)],
            ["unbounded_cbr_r1", "unbounded_cbr_r2", "unbounded_vbr",
             "bounded_vbr", "hard", "drop"])

    def test_analysis_jobs_rejects_nonpositive_values(self):
        self.assertEqual(probe._analysis_jobs(4), 4)
        with self.assertRaisesRegex(ValueError, "at least 1"):
            probe._analysis_jobs(0)

    def test_manifest_path_accepts_result_folder_or_file(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            manifest = root / "manifest.json"
            manifest.write_text("{}\n")
            self.assertEqual(
                probe._resolve_manifest_path(str(root)), manifest.resolve())
            self.assertEqual(
                probe._resolve_manifest_path(str(manifest)), manifest.resolve())

    def test_legacy_pacing_failure_is_not_an_artifact_failure(self):
        self.assertIsNone(probe._status_artifact_error({
            "artifact_error": (
                "feed wall/PTS ratio=1.102; max feed gap excess=201.3ms"),
        }))
        self.assertEqual(
            probe._status_artifact_error({
                "artifact_error": (
                    "failed to collect x.nal.json; max feed gap excess=201.3ms"),
            }),
            "failed to collect x.nal.json")

    def test_help_discloses_result_layout_and_folder_input(self):
        parser = probe._parser()
        self.assertIn("OUTPUT/manifest.json", parser.format_help())
        subparsers = next(
            action for action in parser._actions
            if isinstance(action, argparse._SubParsersAction))
        analyze_help = subparsers.choices["analyze"].format_help()
        full_help = subparsers.choices["full"].format_help()
        self.assertIn("OUTPUT or OUTPUT/manifest.json", analyze_help)
        self.assertIn("OUTPUT/analysis/", full_help)
        self.assertIn("Scientific warnings never stop", full_help)

    def test_full_writes_partial_analysis_before_reporting_invalid_runs(self):
        args = probe._parser().parse_args([
            "full", "/tmp/output", "--serial", "SERIAL", "--no-plots"])
        manifest = Path("/tmp/output/manifest.json")
        run_error = RuntimeError("2 case(s) failed; first: bad case")
        with mock.patch.object(probe, "generate", return_value=manifest), \
                mock.patch.object(probe, "run_matrix", side_effect=run_error), \
                mock.patch.object(probe, "analyze") as analyze_mock:
            with self.assertRaisesRegex(RuntimeError, "workflow completed"):
                probe.full(args)
        analyze_mock.assert_called_once()
        self.assertTrue(analyze_mock.call_args.args[0].allow_missing)

    def test_full_allow_missing_accepts_valid_subset(self):
        args = probe._parser().parse_args([
            "full", "/tmp/output", "--serial", "SERIAL", "--no-plots",
            "--allow-missing"])
        manifest = Path("/tmp/output/manifest.json")
        run_error = RuntimeError("2 case(s) failed; first: bad case")
        with mock.patch.object(probe, "generate", return_value=manifest), \
                mock.patch.object(probe, "run_matrix", side_effect=run_error), \
                mock.patch.object(probe, "analyze") as analyze_mock:
            self.assertEqual(probe.full(args), manifest)
        analyze_mock.assert_called_once()

    def test_manifest_declares_rate_control_probe_contract(self):
        manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text()
        self.assertIn(probe.ENCAPP_PROBE_CONTRACT_KEY, manifest)
        self.assertIn(f'android:value="{probe.ENCAPP_PROBE_CONTRACT}"', manifest)
        self.assertIn(".ProbeCapabilitiesReceiver", manifest)
        self.assertIn(probe.ENCAPP_PROBE_ACTION, manifest)

    def test_proto_exposes_probe_controls(self):
        path = ROOT / "scripts/proto/tests_pb2.py"
        spec = importlib.util.spec_from_file_location("probe_tests_pb2", path)
        module = importlib.util.module_from_spec(spec)
        assert spec.loader is not None
        spec.loader.exec_module(module)
        runtime_fields = module.Runtime.DESCRIPTOR.fields_by_name
        self.assertEqual(runtime_fields["fake_input_complexity"].number, 7)
        self.assertNotIn("content_reset", runtime_fields)
        self.assertEqual(
            runtime_fields["fake_input_complexity"].message_type.name,
            "FakeInputComplexityParameter")
        configure_fields = module.Configure.DESCRIPTOR.fields_by_name
        self.assertEqual(configure_fields["nal_log"].number, 29)

    def test_standalone_encapp_version_override(self):
        path = ROOT / "scripts/encapp_tool/_version.py"
        previous = os.environ.get("ENCAPP_EXPECTED_VERSION")
        try:
            os.environ["ENCAPP_EXPECTED_VERSION"] = "7.42"
            spec = importlib.util.spec_from_file_location(
                "standalone_encapp_version", path)
            module = importlib.util.module_from_spec(spec)
            assert spec.loader is not None
            spec.loader.exec_module(module)
            self.assertEqual(module.__version_info__, ("7", "42"))
            self.assertEqual(module.__version__, "7.42")
        finally:
            if previous is None:
                os.environ.pop("ENCAPP_EXPECTED_VERSION", None)
            else:
                os.environ["ENCAPP_EXPECTED_VERSION"] = previous

    def test_parse_installed_encapp_contract(self):
        package_dump = """
          versionCode=132 minSdk=26 targetSdk=35
          versionName=1.32
        """
        info = probe._parse_encapp_package_dump(package_dump)
        self.assertEqual(info["version_name"], "1.32")
        self.assertEqual(info["version_code"], 132)
        completed = (
            'Broadcast completed: result=0, data="'
            f'{probe.ENCAPP_PROBE_CONTRACT_KEY}={probe.ENCAPP_PROBE_CONTRACT}"')
        self.assertEqual(
            probe._parse_encapp_capability_broadcast(completed),
            probe.ENCAPP_PROBE_CONTRACT)
        self.assertIsNone(
            probe._parse_encapp_capability_broadcast(
                "Broadcast completed: result=0"))

    def test_parse_bitrate(self):
        self.assertEqual(probe._parse_bitrate("8M"), 8_000_000)
        self.assertEqual(probe._parse_bitrate("250 kbps"), 250_000)
        with self.assertRaises(ValueError):
            probe._parse_bitrate("eight")

    def test_drop_schedule_has_expected_shapes(self):
        d = probe._scenario_definitions(30)["drops"]
        drops = d["drops"]
        self.assertIn(97, drops)  # isolated drop at 3 s + 7 frames
        self.assertTrue(all(n in drops for n in range(187, 189)))
        self.assertTrue(all(n in drops for n in range(367, 375)))
        half = [n for n in drops if 480 <= n < 600]
        self.assertEqual(len(half), 60)

    def test_complexity_steps_avoid_gop_boundaries(self):
        scenarios = probe._scenario_definitions(30)
        for name in ("dynamics", "hard_start"):
            for frame, _complexity in scenarios[name]["events"][1:]:
                self.assertEqual(frame % 30, 11)

    def test_generate_is_self_describing_and_codec_invariant(self):
        with tempfile.TemporaryDirectory() as td:
            args = argparse.Namespace(
                output=td, codec="c2.qti.hevc.encoder", mime="video/hevc",
                resolution="640x480", fps=30, bitrate="2M",
                i_frame_interval=1, modes="cbr,vbr",
                qp_profiles="unbounded,jcq_27_38", scenarios="hard_start",
                repeats=1, order_seed=1,
            )
            manifest_path = probe.generate(args)
            body = json.loads(manifest_path.read_text())
            self.assertEqual(len(body["cases"]), 4)
            self.assertEqual(body["global"]["bitrate_bps"], 2_000_000)
            self.assertEqual(
                body["input_contract"]["submission_policy"],
                "as_fast_as_codec_accepts")
            self.assertIn("Same generated bytes", body["input_contract"]["note"])
            bounded = next(c for c in body["cases"] if c["qp_profile"] == "jcq_27_38")
            pbtxt = (Path(td) / bounded["config"]).read_text()
            self.assertIn('filepath: "fake_input_rc"', pbtxt)
            self.assertIn('key: "video-qp-i-min"', pbtxt)
            self.assertIn('key: "video-qp-p-max"', pbtxt)
            self.assertIn('key: "max-bframes"', pbtxt)
            self.assertIn("bitrate_mode:", pbtxt)
            self.assertIn("ignore_power_status: true", pbtxt)
            self.assertIn("color_format: 21", pbtxt)
            self.assertNotIn("description: \"encoder RC probe:", pbtxt)
            args.bitrate = "2.5M"
            with self.assertRaises(ValueError):
                probe.generate(args)

    def test_leaky_bucket_and_drop_accounting(self):
        case = {
            "id": "rc_drops_cbr_unbounded_r01", "repeat": 1,
            "mode": "cbr", "qp_profile": "unbounded", "qp_bounds": {},
            "scenario": "drops",
            "scenario_def": {
                "frames": 5, "events": [(0, "HIGH")], "drops": [2],
                "request_sync": [],
                "drop_regions": [{"name": "single", "start": 2, "end": 4}],
            },
        }
        global_cfg = {
            "mime": "video/hevc", "fps": 10, "bitrate_bps": 8_000,
            "codec": "codec", "resolution": "16x16", "bitrate": "8k",
            "i_frame_interval": 1,
        }
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            result = {
                "id": case["id"], "encodedfile": "missing.mp4",
                "frames": [
                    {"frame": 0, "original_frame": 0, "iframe": 1,
                     "size": 200, "pts": 0, "proctime": 1_000_000},
                    {"frame": 1, "original_frame": 1, "iframe": 0,
                     "size": 100, "pts": 100_000, "proctime": 1_000_000},
                    {"frame": 2, "original_frame": 3, "iframe": 0,
                     "size": 100, "pts": 300_000, "proctime": 1_000_000},
                    {"frame": 3, "original_frame": 4, "iframe": 0,
                     "size": 100, "pts": 400_000, "proctime": 1_000_000},
                ],
            }
            result_path = root / "result.json"
            result_path.write_text(json.dumps(result))
            summary, rows, _steps, drops = probe._analyze_one(
                case, result_path, global_cfg, root / "analysis", None, None)
            self.assertEqual(summary["unexpected_missing_count"], 0)
            self.assertEqual(summary["expected_drop_not_missing_count"], 0)
            self.assertEqual(rows[0]["vbv_delay_ms"], 200.0)
            self.assertEqual(rows[2]["dt_us"], 200_000)
            self.assertEqual(rows[2]["vbv_delay_ms"], 100.0)
            self.assertEqual(len(drops), 1)
            self.assertEqual(drops[0]["expected_drop_count"], 1)
            self.assertEqual(drops[0]["observed_missing_count"], 1)
            self.assertEqual(drops[0]["max_pts_gap_ms"], 200.0)

    def test_find_case_result_skips_non_object_json(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            (root / "result.json").write_text(json.dumps({"id": "case", "frames": []}))
            (root / "thermal_samples.json").write_text(json.dumps([{"status": 0}]))
            self.assertEqual(probe._find_case_result(root, "case"), root / "result.json")

    def test_result_completeness_is_exact(self):
        case = {"scenario_def": {"frames": 5, "drops": [2]}}
        with tempfile.TemporaryDirectory() as td:
            path = Path(td) / "result.json"
            frames = [{"frame": i, "original_frame": n, "size": 1,
                       "pts": n * 100_000}
                      for i, n in enumerate([0, 1, 3, 4])]
            path.write_text(json.dumps({"frames": frames}))
            self.assertTrue(probe._result_is_complete(path, case))
            path.write_text(json.dumps({"frames": frames + [dict(frames[-1])]}))
            self.assertFalse(probe._result_is_complete(path, case))
            path.write_text(json.dumps({"frames": frames[:-1]}))
            self.assertFalse(probe._result_is_complete(path, case))

    def test_tau63_detects_first_crossing(self):
        rows = []
        for i in range(120):
            value = 10.0 if i < 60 else 20.0 - 10.0 * (0.5 ** ((i - 60) / 5.0))
            rows.append({"original_frame": i, "pts_us": i * 100_000,
                         "qp_avg": value})
        tau = probe._tau63(rows, 60, 120, "qp_avg", fps=10)
        self.assertIsNotNone(tau)
        self.assertGreaterEqual(tau, 700.0)
        self.assertLessEqual(tau, 900.0)

    def test_tau63_requires_sustained_crossing(self):
        rows = []
        for i in range(100):
            value = 10.0 if i < 50 else (20.0 if i == 50 or i >= 75 else 10.0)
            rows.append({"original_frame": i, "pts_us": i * 100_000,
                         "metric": value, "is_i": 0})
        tau = probe._tau63(rows, 50, 100, "metric", fps=10)
        self.assertIsNotNone(tau)
        self.assertGreaterEqual(tau, 2500.0)

    def test_tau63_ignores_i_frame_sawtooth_and_short_segments(self):
        rows = []
        fps = 30
        for i in range(600):
            if i < 300:
                p_qp = 34.0
            else:
                p_qp = 34.0 - 6.0 * (1.0 - math.exp(-((i - 300) / fps)))
            is_i = int(i % fps == 0)
            rows.append({"original_frame": i, "pts_us": int(i * 1e6 / fps),
                         "qp_avg": p_qp - 5.0 if is_i else p_qp, "is_i": is_i})
        tau = probe._tau63(rows, 300, 600, "qp_avg", fps)
        self.assertGreaterEqual(tau, 900.0)
        self.assertLessEqual(tau, 1100.0)
        self.assertIsNone(probe._tau63(rows, 300, 360, "qp_avg", fps))

    def test_startup_convergence_uses_repeated_complexity(self):
        rows = []
        for i in range(90):
            if i < 30:
                value = 20.0 - 10.0 * (0.5 ** (i / 3.0))
            elif i < 60:
                value = 5.0
            else:
                value = 20.0
            rows.append({"original_frame": i, "size_bytes": value,
                         "is_i": 0})
        frame = probe._startup_convergence(
            rows, [(0, "HIGH"), (30, "LOW"), (60, "HIGH")], 90, 10,
            "size_bytes", relative_tolerance=0.15)
        self.assertIsNotNone(frame)
        self.assertLess(frame, 15)

    def test_feed_throughput_from_android_logcat(self):
        with tempfile.TemporaryDirectory() as td:
            path = Path(td) / "run.android_logcat.txt"
            path.write_text(
                "10-05 12:00:00.000  1  2 D tag: InputFeeder started\n"
                "10-05 12:01:01.500  1  2 D tag: Queued EOS at frame 1800\n")
            throughput = probe._feed_throughput(Path(td), 1800)
            self.assertEqual(throughput["feed_wall_duration_s"], 61.5)
            self.assertAlmostEqual(
                throughput["feed_achieved_fps"], 1800 / 61.5)

    def test_feed_throughput_uses_self_contained_duration_evidence(self):
        with tempfile.TemporaryDirectory() as td:
            path = Path(td) / "run.android_logcat.txt"
            path.write_text(
                "10-05 12:01:01.400 1 2 I tag: Input pacing "
                "wall_duration_us=59900000 timeline_duration_us=60000000 "
                "max_lag_us=1400000 max_gap_excess_us=42000\n")
            throughput = probe._feed_throughput(Path(td), 1800)
            self.assertEqual(throughput["feed_wall_duration_s"], 59.9)
            self.assertAlmostEqual(
                throughput["feed_achieved_fps"], 1800 / 59.9)

    def test_sidecar_uses_session_device_workdir_and_prefers_collected_file(self):
        with tempfile.TemporaryDirectory() as td:
            case_dir = Path(td)
            (case_dir / "S1.session.jsonl").write_text(json.dumps({
                "event": "session_start",
                "device_workdir": "/data/user/0/com.facebook.encapp/files",
            }) + "\n")
            sidecar = case_dir / "case.nal.json"
            sidecar.write_text(json.dumps({"frames": [
                {"frame_index": 0}, {"frame_index": 1},
            ]}))
            case = {
                "id": "case",
                "scenario_def": {"frames": 2, "drops": []},
            }
            self.assertEqual(
                probe._device_workdir_from_case(case_dir),
                "/data/user/0/com.facebook.encapp/files")
            self.assertEqual(
                probe._private_workdir_relative(
                    "/data/user/0/com.facebook.encapp/files", "case.nal.json"),
                "files/case.nal.json")
            with mock.patch.object(probe, "_pull_workdir_file") as pull_mock:
                self.assertEqual(
                    probe._pull_nal_sidecar("SERIAL", case, case_dir), sidecar)
            pull_mock.assert_not_called()

    def test_report_explains_interventions_and_derives_findings(self):
        scenarios = probe._scenario_definitions(30)
        manifest = {
            "global": {"fps": 30, "bitrate_bps": 8_000_000},
            "cases": [
                {"scenario": name, "scenario_def": definition}
                for name, definition in scenarios.items()
            ],
        }
        design = "\n".join(probe._scenario_design_lines(manifest))
        self.assertIn("5.37 s → MID", design)
        self.assertIn("16–20 s: every other input omitted", design)
        self.assertIn("hollow orange circles mark I-frames", design)

        summaries = [{
            "mode": "cbr", "qp_profile": "unbounded",
            "scenario": "hard_start", "mean_bitrate_bps": 8_000_000,
            "vbv_delay_max_ms": 120.0, "p_median_qp_avg": 31.0,
            "i_p_size_ratio": 4.0, "startup_i_vs_steady_i_ratio": 1.2,
            "startup_qp_convergence_frame": 15,
        }]
        steps = [{
            "mode": "cbr", "qp_profile": "unbounded", "direction": "up",
            "qp_tau63_ms": 100.0, "bitrate_tau63_ms": 200.0,
        }]
        drops = [{
            "mode": "cbr", "qp_profile": "unbounded", "region": "half_rate",
            "region_bitrate_ratio": 0.5,
            "post_size_vs_baseline_median": 1.1,
            "post_qp_delta_median": -1.0,
        }]
        observations = probe._executive_observations(
            manifest, summaries, steps, drops)
        self.assertTrue(any(
            "For CBR complexity transitions" in item for item in observations))
        self.assertTrue(any("30→15 fps interval" in item for item in observations))
        findings = "\n".join(
            probe._finding_lines(manifest, summaries, steps, drops))
        self.assertIn("## Key findings", findings)
        self.assertIn("### Executive observations", findings)
        self.assertIn("| CBR | 1 | 1.00× | 120 ms", findings)
        self.assertIn("100 ms (1/1)", findings)
        self.assertIn("| CBR / unbounded | 0.50× | 1.10× | -1.0 |", findings)

    def test_repeat_plot_inputs_average_aligned_frames(self):
        cases = [
            {"id": f"case_r{repeat}", "scenario": "dynamics",
             "qp_profile": "unbounded", "mode": "cbr", "repeat": repeat,
             "scenario_def": {"frames": 2, "events": [[0, "LOW"]]} }
            for repeat in (1, 2)
        ]
        summaries = [
            {"case_id": case["id"], "scenario": "dynamics",
             "qp_profile": "unbounded", "mode": "cbr", "repeat": case["repeat"],
             "mean_bitrate_bps": value, "vbv_delay_max_ms": value / 1000,
             "p_median_qp_avg": 30 + case["repeat"], "i_p_size_ratio": 2.0}
            for case, value in zip(cases, (8_000_000, 10_000_000))
        ]
        frame_rows = []
        for repeat, sizes in ((1, (100, 200)), (2, (300, 400))):
            for frame, size in enumerate(sizes):
                frame_rows.append({
                    "case_id": f"case_r{repeat}", "scenario": "dynamics",
                    "qp_profile": "unbounded", "mode": "cbr", "repeat": repeat,
                    "original_frame": frame, "pts_us": frame * 33333,
                    "dt_us": 33333, "size_bytes": size,
                    "rolling_bps_1s": size * 8, "qp_min": 20.0,
                    "qp_avg": 30.0 + repeat, "qp_max": 40.0,
                    "vbv_delay_ms": float(size), "is_i": frame == 0,
                })
        plots_input = probe._repeat_average_plot_inputs(
            cases, summaries, frame_rows)
        self.assertEqual(len(plots_input), 1)
        case, summary, rows = plots_input[0]
        self.assertEqual(case["id"], "rc_dynamics_cbr_unbounded_avg")
        self.assertEqual(case["repeat_count"], 2)
        self.assertEqual(summary["mean_bitrate_bps"], 9_000_000)
        self.assertEqual([row["size_bytes"] for row in rows], [200.0, 300.0])
        self.assertEqual(rows[0]["size_bytes_repeat_min"], 100.0)
        self.assertEqual(rows[0]["size_bytes_repeat_max"], 300.0)

    def test_qp_plot_smoothing_is_pts_time_weighted(self):
        rows = [
            {"pts_us": 0, "dt_us": 1_000_000, "qp_avg": 10.0},
            {"pts_us": 1_000_000, "dt_us": 1_000_000, "qp_avg": 20.0},
            {"pts_us": 2_000_000, "dt_us": 1_000_000, "qp_avg": 40.0},
        ]
        self.assertEqual(
            probe._rolling_time_weighted(rows, "qp_avg", 2.0),
            [10.0, 15.0, 30.0])
        self.assertEqual(
            probe._rolling_time_weighted(rows, "qp_avg", 0),
            [10.0, 20.0, 40.0])

    def test_self_contained_html_report_embeds_images(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            image = root / "plot.png"
            image.write_bytes(b"\x89PNG\r\n\x1a\nimage")
            report = root / "REPORT.md"
            report.write_text(
                "# Report\n\n| A | B |\n|---|---|\n| 1 | 2 |\n\n"
                "![Plot](plot.png)\n")
            output = report_renderer.write_html_report(report)
            text = output.read_text()
            self.assertIn("<table>", text)
            self.assertIn("data:image/png;base64,", text)
            self.assertNotIn('src="plot.png"', text)

    def test_report_gallery_embeds_aggregate_images_idempotently(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            analysis = root / "analysis"
            output = analysis / "aggregate_plots"
            output.mkdir(parents=True)
            report = analysis / "REPORT.md"
            report.write_text("# Report\n")
            names = ["01_peak_rate.png", "02_qp.png"]
            for name in names:
                (output / name).write_bytes(b"png")
            plots._update_report_gallery(root, output, names)
            plots._update_report_gallery(root, output, names)
            text = report.read_text()
            self.assertEqual(text.count(plots.AGGREGATE_GALLERY_BEGIN), 1)
            self.assertEqual(text.count(plots.AGGREGATE_GALLERY_END), 1)
            self.assertIn(
                "![Peak Rate](aggregate_plots/01_peak_rate.png)", text)
            self.assertIn("![Qp](aggregate_plots/02_qp.png)", text)

    def test_qp_alignment_rejects_count_and_pts_mismatch(self):
        frames = [{"pts": 0, "size": 10}, {"pts": 33333, "size": 20}]
        qp = {0: {"qp_avg": 30.0}, 1: {"qp_avg": 31.0}}
        with tempfile.TemporaryDirectory() as td:
            sidecar = Path(td) / "x.nal.json"
            sidecar.write_text(json.dumps({"frames": [
                {"frame_index": 0, "pts_us": 0, "total_size": 10},
                {"frame_index": 1, "pts_us": 33333, "total_size": 20},
            ]}))
            self.assertIsNone(probe._validate_qp_alignment(frames, sidecar, qp))
            self.assertIn("frame-count mismatch", probe._validate_qp_alignment(frames, sidecar, {0: qp[0]}))
            frames[1]["pts"] = 33334
            self.assertIn("PTS mismatch", probe._validate_qp_alignment(frames, sidecar, qp))

    def test_aggregate_rolling_peak_requires_full_window(self):
        rows = [
            {"pts_us": i * 100_000, "dt_us": 100_000, "bits": 100.0}
            for i in range(30)
        ]
        self.assertEqual(plots._peak_rolling_bps(rows, 1.0), 1_000.0)
        self.assertEqual(plots._peak_rolling_bps(rows, 4.0), 0.0)

    def test_pulse_recovery_uses_pre_pulse_steady_low_baseline(self):
        fps = 10
        rows = []
        for frame in range(100):
            bits = 100.0
            if 50 <= frame < 60:
                bits = 50.0
            rows.append({
                "case_id": "case", "repeat": 1, "mode": "cbr",
                "qp_profile": "unbounded", "scenario": "dynamics",
                "original_frame": frame, "bits": bits,
            })
        cases = {"case": {
            "id": "case", "scenario": "dynamics",
            "scenario_def": {
                "frames": 100,
                "events": [(0, "LOW"), (40, "HIGH"), (50, "LOW")],
            },
        }}
        records = plots.pulse_recovery_records({"case": rows}, cases, fps)
        first = next(row for row in records if row["post_second"] == 0)
        second = next(row for row in records if row["post_second"] == 1)
        self.assertEqual(first["pulse_seconds"], 1.0)
        self.assertEqual(first["post_over_steady_low"], 0.5)
        self.assertEqual(second["post_over_steady_low"], 1.0)

    def test_rolling_qpy_averages_p_frames_and_excludes_i_frames(self):
        rows = []
        for frame in range(40):
            is_i = int(frame % 10 == 0)
            rows.append({
                "pts_us": frame * 100_000, "dt_us": 100_000,
                "is_i": is_i, "qp_avg": 10.0 if is_i else 34.0,
            })
        trend = plots._rolling_p_qpy(rows, 1.0)
        self.assertTrue(trend)
        self.assertTrue(all(row["qpy_weighted_mean_1s"] == 34.0
                            for row in trend))

    def test_ip_size_ratio_uses_median_i_over_median_p(self):
        rows = [
            {"case_id": "case", "repeat": 1, "mode": "cbr",
             "qp_profile": "unbounded", "scenario": "hard_start",
             "is_i": 1, "size_bytes": 100},
            {"case_id": "case", "repeat": 1, "mode": "cbr",
             "qp_profile": "unbounded", "scenario": "hard_start",
             "is_i": 1, "size_bytes": 300},
            {"case_id": "case", "repeat": 1, "mode": "cbr",
             "qp_profile": "unbounded", "scenario": "hard_start",
             "is_i": 0, "size_bytes": 10},
            {"case_id": "case", "repeat": 1, "mode": "cbr",
             "qp_profile": "unbounded", "scenario": "hard_start",
             "is_i": 0, "size_bytes": 20},
        ]
        record = plots.ip_size_ratio_records({"case": rows})[0]
        self.assertEqual(record["i_median_bytes"], 200.0)
        self.assertEqual(record["p_median_bytes"], 15.0)
        self.assertAlmostEqual(record["i_over_p_size_ratio"], 200.0 / 15.0)

    def test_half_rate_records_capture_configured_and_effective_cadence(self):
        header = (
            "case_id,repeat,mode,qp_profile,region,region_start,region_end,"
            "observed_missing_count,output_frame_count,region_bitrate_ratio,"
            "post_size_vs_baseline_median,post_qp_delta_median,max_pts_gap_ms,"
            "max_vbv_delay_ms\n")
        row = (
            "case,1,cbr,jcq_27_38,half_rate,480,600,60,60,0.75,3.9,-2.9,"
            "66.667,1000\n")
        with tempfile.TemporaryDirectory() as td:
            path = Path(td) / "drops.csv"
            path.write_text(header + row)
            record = plots.half_rate_records(path)[0]
        self.assertEqual(record["configured_fps"], 30)
        self.assertEqual(record["submitted_fps"], 15)
        self.assertEqual(record["expected_input_slots"], 120)
        self.assertEqual(record["omitted_input_slots"], 60)
        self.assertEqual(record["output_frame_count"], 60)
        self.assertEqual(record["max_pts_gap_ms"], 66.667)

    def test_compare_inventory_is_minimized_by_default(self):
        inventory = {
            "product": "ProductA", "sample_id": "ProductA#1",
            "codec": "codec.name", "mime": "video/hevc",
            "resolution": "640x480", "fps": 30,
            "bitrate_bps": 1_000_000, "total_runs": 1,
            "valid_focused_runs": 1, "excluded_runs": 0,
            "result_root": "/private/source", "model": "device",
            "soc": "chip", "build_fingerprint": "build",
        }
        sample = SimpleNamespace(inventory=inventory)
        public = compare._published_inventory([sample], False)[0]
        self.assertNotIn("result_root", public)
        self.assertNotIn("model", public)
        self.assertNotIn("soc", public)
        self.assertNotIn("build_fingerprint", public)
        self.assertEqual(public["product"], "ProductA")
        full = compare._published_inventory([sample], True)[0]
        self.assertEqual(full, inventory)

    def test_compare_window_offsets_are_one_line_width_apart(self):
        self.assertEqual(
            compare._centered_display_offsets(3, 1.5),
            [-1.5, 0.0, 1.5],
        )
        self.assertEqual(
            compare._centered_display_offsets(2, 2.0),
            [-1.0, 1.0],
        )

    def test_compare_window_page_offsets_preserve_data_limits(self):
        try:
            plt, _ = compare._setup_matplotlib()
        except ModuleNotFoundError:
            self.skipTest("matplotlib is not installed")
        rows = [
            {
                "product": product,
                "mode": mode,
                "qp_profile": "unbounded",
                "window_s": window,
                "peak_over_target_median": value,
            }
            for product, value in (("A", 1.0), ("B", 1.0))
            for mode in ("cbr", "vbr")
            for window in (0.2, 0.5)
        ]
        figure = compare._window_page(plt, rows, ["A", "B"], ["unbounded"])
        try:
            x_min, x_max = figure.axes[0].get_xlim()
            self.assertLess(x_min, 0.2)
            self.assertGreater(x_max, 0.5)
        finally:
            plt.close(figure)

    def test_compare_product_spec_parser(self):
        specs = compare._parse_product_specs([
            "ProductA=/tmp/product_a/manifest.json",
            "ProductB=/tmp/product_b=result",
        ])
        self.assertEqual(specs[0][0], "ProductA")
        self.assertEqual(str(specs[0][1]), "/tmp/product_a")
        self.assertEqual(str(specs[1][1]), "/tmp/product_b=result")
        with self.assertRaises(ValueError):
            compare._parse_product_specs(["missing-separator"])

    def test_compare_parser_accepts_group_alias(self):
        args = compare._parser().parse_args([
            "/tmp/out", "--group", "ProductA=/tmp/product_a",
            "--product", "ProductB=/tmp/product_b/manifest.json", "--no-plots",
        ])
        self.assertEqual(
            args.product,
            ["ProductA=/tmp/product_a", "ProductB=/tmp/product_b/manifest.json"])

    def test_compare_generates_missing_per_root_analysis(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            args = SimpleNamespace(
                reanalyze=False, qpextract="qpextract", ffmpeg="ffmpeg",
                smoothing_seconds=2.0, raw_qp_overlay=True,
                plot_repeats=False, no_case_plots=False, jobs=3)
            with mock.patch.object(compare.rcprobe, "analyze") as analyze_mock:
                compare._ensure_analysis(root, args)
            analyze_args = analyze_mock.call_args.args[0]
            self.assertEqual(analyze_args.manifest, str(root))
            self.assertTrue(analyze_args.allow_missing)
            self.assertEqual(analyze_args.smoothing_seconds, 2.0)

    def test_compare_parser_accepts_scoped_contract_overrides(self):
        args = compare._parser().parse_args([
            "/tmp/out", "--group", "A=/tmp/a", "--group", "B=/tmp/b",
            "--ignore-contract-field", "global.i_frame_interval",
            "--ignore-contract-field", "input_contract.pixel_format",
        ])
        self.assertEqual(args.ignore_contract_field, [
            "global.i_frame_interval", "input_contract.pixel_format"])

    def test_compare_contract_diff_names_changed_fields(self):
        differences = compare._contract_differences(
            {"global": {"fps": 30}, "input_contract": {}},
            {"global": {"fps": 60},
             "input_contract": {"pixel_format": "nv12"}})
        self.assertEqual(differences, [
            "global.fps: baseline=30, candidate=60",
            "input_contract.pixel_format: baseline=<missing>, candidate='nv12'",
        ])

    def test_compare_contract_ignores_codec_order_and_repeats(self):
        def manifest(codec, bitrate=8_000_000, reverse=False):
            cell = {
                "id": "case", "mode": "cbr", "qp_profile": "unbounded",
                "qp_bounds": {}, "scenario": "dynamics",
                "scenario_def": {"frames": 30, "events": [[0, "LOW"]],
                                 "drops": [], "request_sync": []},
            }
            cases = [dict(cell, repeat=1), dict(cell, id="case_r2", repeat=2)]
            if reverse:
                cases.reverse()
            return {
                "global": {"codec": codec, "mime": "video/hevc",
                           "resolution": "640x480", "fps": 30,
                           "bitrate_bps": bitrate, "i_frame_interval": 1},
                "input_contract": {"source": "fake_input_rc"},
                "cases": cases,
            }
        first = compare._contract(manifest("codec.a"))
        second = compare._contract(manifest("codec.b", reverse=True))
        self.assertEqual(first, second)
        self.assertEqual(compare._signature(first), compare._signature(second))
        changed = compare._contract(manifest("codec.a", bitrate=9_000_000))
        self.assertNotEqual(compare._signature(first), compare._signature(changed))
        legacy_manifest = manifest("codec.a")
        legacy_manifest["global"]["legacy_feed_mode"] = True
        legacy_manifest["input_contract"]["legacy_feed_mode"] = True
        legacy = compare._contract(legacy_manifest)
        self.assertEqual(first, legacy)
        self.assertEqual(
            first["input_contract"]["submission_policy"],
            "as_fast_as_codec_accepts")

    def test_compare_product_aggregate_weights_samples_not_runs(self):
        rows = [
            {"product": "P", "sample_id": "P#1", "mode": "cbr",
             "metric": 1.0},
            {"product": "P", "sample_id": "P#2", "mode": "cbr",
             "metric": 3.0},
        ]
        result = compare._product_aggregate(rows, ("mode",), ("metric",))[0]
        self.assertEqual(result["sample_count"], 2)
        self.assertEqual(result["metric_median"], 2.0)
        self.assertEqual(result["metric_q25"], 1.5)
        self.assertEqual(result["metric_q75"], 2.5)

    def test_rate_conditioned_qp_containment_scales_only_overlaps(self):
        sizes = plots._contained_marker_sizes([
            (1.0, 36.0, 100.0),
            (1.0, 36.0, 150.0),
            (1.0, 36.0, 120.0),
            (2.0, 40.0, 80.0),
        ])
        self.assertEqual(sizes, [600.0, 337.5, 150.0, 80.0])

    def test_rate_conditioned_qp_pinning_uses_diameter_scale(self):
        self.assertEqual(plots._pinning_marker_area(0.0), 36.0)
        self.assertEqual(plots._pinning_marker_area(0.5), 324.0)
        self.assertEqual(plots._pinning_marker_area(1.0), 900.0)

    def test_rate_conditioned_qp_records_achieved_rate_and_max_pinning(self):
        common = {
            "case_id": "case", "repeat": 1, "mode": "vbr",
            "qp_profile": "jcq_27_38", "scenario": "dynamics",
        }
        rows = [
            dict(common, original_frame=0, is_i=1, bits=8000.0,
                 dt_us=33333, qp_avg=30.0),
            dict(common, original_frame=1, is_i=0, bits=8000.0,
                 dt_us=33333, qp_avg=36.0),
            dict(common, original_frame=2, is_i=0, bits=8000.0,
                 dt_us=33334, qp_avg=38.0),
        ]
        records = plots.rate_conditioned_qp_records(
            {"case": rows},
            {"case": {"qp_bounds": {"min": 27, "max": 38}}},
            240_000.0,
        )
        self.assertEqual(len(records), 1)
        record = records[0]
        self.assertAlmostEqual(record["achieved_over_target"], 1.0)
        self.assertAlmostEqual(record["p_qpy_median"], 37.0)
        self.assertAlmostEqual(record["p_qpy_stddev"], 1.0)
        self.assertAlmostEqual(record["p_qpy_mean_abs_step"], 2.0)
        self.assertAlmostEqual(record["pinned_at_max_fraction"], 0.5)

    def test_rate_conditioned_qp_plot_labels_unequal_rate_tradeoff(self):
        try:
            plt, _ = plots._setup_matplotlib()
        except ModuleNotFoundError:
            self.skipTest("matplotlib is not installed")
        records = [
            {"mode": mode, "scenario": scenario,
             "qp_profile": "jcq_27_38", "achieved_over_target": 1.10,
             "p_qpy_median": 36.0, "pinned_at_max_fraction": pinned}
            for mode, pinned in (("cbr", 0.38), ("vbr", 0.18))
            for scenario in ("dynamics", "hard_start", "drops")
        ]
        figure = plots._plot_rate_conditioned_qp(plt, records)
        try:
            self.assertIsNotNone(figure)
            axis = figure.axes[0]
            self.assertIn("Achieved mean bitrate", axis.get_xlabel())
            self.assertEqual(axis.get_ylabel(), "Median P-frame QPY")
            marker_sizes = [
                float(collection.get_sizes()[0])
                for collection in axis.collections
                if collection.__class__.__name__ == "PathCollection"
            ]
            self.assertEqual(len(marker_sizes), 6)
            self.assertEqual(len(set(marker_sizes)), 6)
            self.assertAlmostEqual(axis.dataLim.y0, 36.0)
            self.assertAlmostEqual(axis.dataLim.y1, 36.0)
        finally:
            plt.close(figure)

    def test_comparison_aggregates_rate_conditioned_qp_in_two_stages(self):
        rate_rows = [
            {"mode": "vbr", "qp_profile": "jcq_27_38",
             "scenario": "dynamics", "achieved_over_target": rate,
             "p_qpy_median": qp, "p_qpy_stddev": 2.0,
             "p_qpy_mean_abs_step": 1.2,
             "pinned_at_max_fraction": 0.2}
            for rate, qp in ((1.20, 36.0), (1.30, 35.0))
        ]
        sample = argparse.Namespace(
            product="ProductA", sample_id="ProductA#1",
            runs=[], windows=[], qp=[], rate_qp=rate_rows,
            drops=[], steps=[], manifest={"global": {"fps": 30}},
        )
        aggregates = compare._aggregate([sample])
        self.assertEqual(len(aggregates["sample_rate_qp"]), 1)
        self.assertEqual(len(aggregates["product_rate_qp"]), 1)
        self.assertAlmostEqual(
            aggregates["sample_rate_qp"][0]["achieved_over_target"], 1.25)
        self.assertAlmostEqual(
            aggregates["product_rate_qp"][0]["p_qpy_median_median"], 35.5)

    def test_comparison_rate_conditioned_qp_plot_uses_rate_and_pinning(self):
        try:
            plt, _ = compare._setup_matplotlib()
        except ModuleNotFoundError:
            self.skipTest("matplotlib is not installed")
        rows = []
        for product in ("ProductA", "ProductB"):
            for mode, rate, qp, pinned in (
                    ("cbr", 1.04, 36.8, 0.38),
                    ("vbr", 1.28, 35.4, 0.18)):
                rows.append({
                    "product": product, "mode": mode,
                    "qp_profile": "jcq_27_38", "scenario": "dynamics",
                    "achieved_over_target_median": rate,
                    "achieved_over_target_q25": rate - 0.01,
                    "achieved_over_target_q75": rate + 0.01,
                    "p_qpy_median_median": qp,
                    "p_qpy_median_q25": qp - 0.1,
                    "p_qpy_median_q75": qp + 0.1,
                    "pinned_at_max_fraction_median": pinned,
                })
        figure = compare._rate_conditioned_qp_page(
            plt, rows, ["ProductA", "ProductB"])
        try:
            self.assertIsNotNone(figure)
            axis = figure.axes[0]
            self.assertIn(
                "Achieved mean bitrate", axis.get_xlabel())
            marker_sizes = [
                float(collection.get_sizes()[0])
                for collection in axis.collections
                if collection.__class__.__name__ == "PathCollection"
            ]
            self.assertEqual(len(marker_sizes), 4)
            self.assertEqual(len(set(marker_sizes)), 4)
            coordinates = {
                tuple(float(value) for value in collection.get_offsets()[0])
                for collection in axis.collections
                if collection.__class__.__name__ == "PathCollection"
            }
            self.assertEqual(coordinates, {(1.04, 36.8), (1.28, 35.4)})
            self.assertAlmostEqual(axis.dataLim.x0, 1.0)
            self.assertAlmostEqual(axis.dataLim.x1, 1.29)
            self.assertAlmostEqual(axis.dataLim.y0, 35.3)
            self.assertAlmostEqual(axis.dataLim.y1, 36.9)
            self.assertEqual(figure.texts[0].get_text().count("\n"), 2)
            self.assertGreater(axis.get_position().width, 0.5)
        finally:
            plt.close(figure)

    def test_stream_qp_stats_report_all_i_and_p_low_weighted_mean_high(self):
        rows = [
            {"case_id": "case", "repeat": 1, "mode": "vbr",
             "qp_profile": "jcq_27_38", "scenario": "dynamics",
             "is_i": 1, "qp_min": 27.0, "qp_avg": 30.0, "qp_max": 35.0},
            {"case_id": "case", "repeat": 1, "mode": "vbr",
             "qp_profile": "jcq_27_38", "scenario": "dynamics",
             "is_i": 0, "qp_min": 29.0, "qp_avg": 34.0, "qp_max": 38.0},
            {"case_id": "case", "repeat": 1, "mode": "vbr",
             "qp_profile": "jcq_27_38", "scenario": "dynamics",
             "is_i": 0, "qp_min": 31.0, "qp_avg": 35.0, "qp_max": 37.0},
        ]
        records = plots.stream_qp_records({"case": rows})
        all_frames = next(row for row in records if row["frame_class"] == "all")
        i_frames = next(row for row in records if row["frame_class"] == "I")
        p_frames = next(row for row in records if row["frame_class"] == "P")
        self.assertEqual(all_frames["qpy_low"], 27.0)
        self.assertEqual(all_frames["qpy_weighted_mean"], 33.0)
        self.assertEqual(all_frames["qpy_high"], 38.0)
        self.assertEqual(i_frames["frame_count"], 1)
        self.assertEqual(p_frames["qpy_weighted_mean"], 34.5)

    def test_thermal_parser_uses_current_hal_section(self):
        text = """Thermal Status: 0\nCached temperatures:\n\tTemperature{mValue=99.0, mType=0, mName=cpu-0, mStatus=0}\nCurrent temperatures from HAL:\n\tTemperature{mValue=41.5, mType=0, mName=cpu-0, mStatus=0}\n\tTemperature{mValue=45.0, mType=0, mName=gpu-0, mStatus=2}\n\tTemperature{mValue=95.0, mType=1, mName=tsens-17, mStatus=0}\n\tTemperature{mValue=39.0, mType=0, mName=ddr, mStatus=0}\nCurrent cooling devices from HAL:\n"""
        parsed = probe._parse_thermalservice(text)
        self.assertEqual(parsed["status"], 0)
        self.assertEqual(parsed["max_soc_c"], 95.0)
        self.assertEqual(parsed["max_thermal_c"], 95.0)
        self.assertEqual(parsed["thermal_sensor_basis"], "soc")
        self.assertEqual(parsed["max_sensor_status"], 2)

    def test_thermal_parser_uses_skin_proxy_when_soc_temperature_absent(self):
        text = """Thermal Status: 0
Current temperatures from HAL:
\tTemperature{mValue=1.0, mType=6, mName=hw-comparator, mStatus=0}
\tTemperature{mValue=32872.0, mType=3, mName=skin-sensor, mStatus=0}
\tTemperature{mValue=1.0, mType=8, mName=thermal-power, mStatus=0}
Current cooling devices from HAL:
"""
        parsed = probe._parse_thermalservice(text)
        self.assertIsNone(parsed["max_soc_c"])
        self.assertAlmostEqual(parsed["max_thermal_c"], 32.872)
        self.assertEqual(parsed["thermal_sensor_basis"], "proxy")
        self.assertEqual(parsed["thermal_sensor_names"], ["skin-sensor"])

    def test_thermal_gate_accepts_proxy_and_supports_explicit_bypass(self):
        proxy_text = """Thermal Status: 0
Current temperatures from HAL:
\tTemperature{mValue=32872.0, mType=3, mName=skin-sensor, mStatus=0}
Current cooling devices from HAL:
"""
        no_temperature_text = """Thermal Status: 0
Current temperatures from HAL:
\tTemperature{mValue=1.0, mType=8, mName=thermal-power, mStatus=0}
Current cooling devices from HAL:
"""
        completed = SimpleNamespace(stdout=proxy_text)
        with mock.patch.object(probe, "_adb", return_value=completed):
            accepted = probe._wait_for_thermal("SERIAL", 70.0, 1)
        self.assertEqual(accepted["thermal_sensor_basis"], "proxy")
        completed.stdout = no_temperature_text
        with mock.patch.object(probe, "_adb", return_value=completed):
            with self.assertRaisesRegex(RuntimeError, "--ignore-thermal"):
                probe._wait_for_thermal("SERIAL", 70.0, 1)
            bypassed = probe._wait_for_thermal(
                "SERIAL", 70.0, 1, ignore_thermal=True)
        self.assertIsNone(bypassed["max_thermal_c"])

    def test_parser_accepts_ignore_thermal_for_run_and_full(self):
        parser = probe._parser()
        run_args = parser.parse_args(["run", "/tmp/result", "--ignore-thermal"])
        full_args = parser.parse_args(["full", "/tmp/result", "--ignore-thermal"])
        self.assertTrue(run_args.ignore_thermal)
        self.assertTrue(full_args.ignore_thermal)

    def test_thermal_parser_normalizes_millidegrees(self):
        text = """Thermal Status: 0
Current temperatures from HAL:
\tTemperature{mValue=24800.0, mType=2, mName=battery, mStatus=0}
\tTemperature{mValue=35416.0, mType=0, mName=therm-quiet-usr, mStatus=0}
\tTemperature{mValue=35208.0, mType=0, mName=therm-RFIC, mStatus=0}
\tTemperature{mValue=1.0, mType=8, mName=thermal-power, mStatus=0}
Current cooling devices from HAL:
"""
        parsed = probe._parse_thermalservice(text)
        self.assertEqual(parsed["status"], 0)
        self.assertAlmostEqual(parsed["temperatures_c"]["therm-quiet-usr"], 35.416)
        self.assertAlmostEqual(parsed["max_soc_c"], 35.416)
        self.assertEqual(parsed["max_sensor_status"], 0)


if __name__ == "__main__":
    unittest.main()
