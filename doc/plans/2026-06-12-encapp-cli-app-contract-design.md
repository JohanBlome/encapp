# Encapp CLI ↔ App Contract — Design

**Date:** 2026-06-12
**Status:** Validated via brainstorming; ready for implementation plan
**Scope:** Android-first. iOS port to follow with no contract redesign expected.

---

## Problem

The encapp CLI (`scripts/encapp.py`) drives the Android app (`app/.../com.facebook.encapp`) through a contract that is **implicit, disk-mediated, and unstructured**. Symptoms observed in production use:

1. **Consistency** — two parallel runners (`encapp.py` flat layout vs `encapp_run.sh` per-test subdirs); duplicate writes from multiple sites; one runner bypasses the canonical helper.
2. **Project flow** — workdir is probed independently by CLI and app; they can disagree silently. Pushes can land where the app does not write.
3. **Success criteria not accurate** — `result_ok` defaults to `True` when the "Test finished" logcat line is missing (ring-buffer rollover, mid-test crash, `pidof` race). Silent failures pass as `ok`.
4. **Handling of test files unclear** — UUID is app-generated, so CLI must `adb shell ls + regex` to discover what to pull. Orphans accumulate (custom `output_filename`, non-`encapp_*` pushed inputs).
5. **Debug is difficult** — `print()` everywhere gated by an integer `debug` threaded through every function; ~15 distinct `encapp.*` logcat tags with no registry; no `BuildConfig.DEBUG` discipline; unfiltered `logcat -d` dumps; no failure summary.
6. **Generation is complex with pitfalls** — 3-4 near-duplicate full-suite pbtxts (`<input>.pbtxt`, `expanded_*.pbtxt`, `run.pbtxt`, `_aggr.pbtxt`, `encapp_test.pbtxt`) written per run; split-mode files written twice from two sites; hardcoded `bitrate_surface_transcoder_show.pbtxt.failed.csv` written outside the workdir; argument-order bug in `update_codec_testsuite`; dead branch at `encapp.py:1944-1954`; unused `Serial` proto message.
7. **Results can't be trusted** — pass/fail lives only in logcat. The per-test JSON has no `result` or `error` field.

## Root cause

The CLI and app communicate through **stringly-typed disk artifacts and unstructured logcat**, not a structured request/response contract. Every symptom above is a downstream effect of that.

## Design overview

Four interlocking changes, each fixing one stratum of the contract:

| § | Stratum | Change |
|---|---|---|
| 1 | **Success oracle** | Default-deny. App writes `<test_id>.result.json` with structured status; CLI consumes it as source of truth. |
| 2 | **Naming & workdir** | CLI fills `common.test_id` (app derives if missing). Lazy workdir handshake, cached per device. Pull becomes deterministic. |
| 3 | **Generation** | One canonical pbtxt per run, one write helper, no intermediate snapshots, dead code removed, single Python runner. |
| 4 | **Debug & observability** | stdlib `logging` on the CLI, single `Constants.TAG = "encapp"` on the app, per-test scoped log files, run summary. |

---

## §1. Success oracle

**Principle: default-deny.** A test is failed unless the app has actively proven it succeeded.

### Lifecycle

| When | App does | CLI sees |
|---|---|---|
| Before encoder starts | Atomic-writes `<test_id>.result.json.partial` with `{status:"running", started_at, pid}` | "Test is alive" |
| On success | Atomic-renames `.partial` → `<test_id>.result.json` with `{status:"ok", finished_at, actual_codec, frames_encoded, outputs:{...}}` | "Test passed" |
| On caught error | Writes final `<test_id>.result.json` with `{status:"error", error:{code,message,stack}}` | "Test failed, here's why" |
| On crash | Leaves `.partial` orphaned | "Test crashed mid-run — diagnostic preserved" |
| Never starts | No file at all | Distinguishable from crash |

### Schema (v1)

```jsonc
{
  "schema_version": 1,
  "test_id": "h264_1mbps_johnny_001",
  "status": "ok" | "error" | "running" | "skipped",
  "started_at_unix_ms": 1718200000000,
  "finished_at_unix_ms": 1718200002500,
  "requested_codec": "c2.qti.hevc.encoder",
  "actual_codec":   "c2.qti.hevc.encoder",
  "frames_encoded": 600,
  "expected_frames": 600,
  "outputs": {
    "video": "h264_1mbps_johnny_001.mp4",
    "stats": "h264_1mbps_johnny_001.json"
  },
  "error": null,                     // or {code, message, stack}
  "app_version": "1.6.0",
  "device_workdir_used": "/sdcard"
}
```

### CLI oracle algorithm

1. Wait for `<test_id>.result.json` to appear (per-test timeout — see below).
2. If file present with `status:"ok"` → pass.
3. If file present with `status:"error"` → fail; reason = `error.message`.
4. If only `.partial` present at timeout / PID death → fail; reason = "crashed during execution at frame N" (from partial's progress field).
5. If neither file at timeout → fail; reason = "test never started" (check `am start` exit + ANR-only logcat slice).

**Logcat becomes diagnostic-only, never the source of truth.**

### Atomicity decision

**Partial + final.** App writes `.partial` early with `status:"running"`, then either renames to final on success or rewrites in place with `status:"error"` on caught failure. POSIX `rename()` is atomic on the same filesystem. Distinguishes "crashed mid-test" from "never started".

### Timeout policy

Per-test configurable via new field `common.timeout_sec` in `tests.proto`. Heuristic default if absent. On expiry: `am force-stop com.facebook.encapp`, mark `status:"timeout"`, pull whatever artifacts exist, continue suite.

---

## §2. Deterministic naming + workdir handshake

### A. test_id ownership

Today: app generates `"encapp_" + UUID.randomUUID()` in `Statistics.java:148-153`. CLI does not know names in advance; uses `adb shell ls + regex` for pull.

New rule:
- CLI normally fills `common.test_id` deterministically: `<desc>_<codec_slug>_<bitrate>_<resolution>_<run_index>`.
- App **derives the same value from pbtxt fields** if `common.test_id` is missing. (Preserves manual `am start` debugging; no inconsistency.)
- The UUID fallback is removed.

Result file naming is then fully predictable from the pbtxt alone (see file inventory below).

### B. Workdir handshake — lazy, cached

Today: CLI probes `/sdcard → /data/data/.../files` in Python; app probes the same independently in `CliSettings.getWorkDir()`. They can disagree.

New flow, run once per device, cached:

1. CLI: `adb shell am start -e probe true com.facebook.encapp/.MainActivity`
2. App: probes writability, writes `<chosen_workdir>/encapp_workdir.txt` containing the absolute path, exits.
3. CLI: reads it back (try `adb shell cat /sdcard/encapp_workdir.txt`, then `run-as` for app-private).
4. CLI caches at `<local_workdir>/.encapp_device_workdir` keyed by `(serial, app_version)`.
5. Subsequent launches pass `workdir=<cached_path>` extra. App **trusts the extra**; if it disagrees with its own writability check, writes `status:"error", error.code:"workdir_invalid"` to the result file and exits.

Cache invalidated by `encapp install` or app version mismatch.

### C. Pull becomes deterministic

For each Test in the suite, CLI pulls exactly:

- `<test_id>.result.json` (oracle)
- `<test_id>.json` (stats, basename-paired with video)
- `<test_id>.{mp4,webm,heic,avif,…}` (encoded output, extension from configure)
- `<test_id>.log` (app-written per-test log; see §4)
- `<test_id>.android_logcat.txt` (Android-only additive diagnostic; see §4)

No `ls`, no regex, no orphans. The old `pull_result` standalone subcommand loses its orphan-discovery semantics; either removed or repurposed to re-pull missing files from a cached suite.

---

## §3. Generation pipeline & artifacts

**Principle: one canonical artifact per run, in-memory mutation, one write path.**

### Generation flow

```
read .pbtxt inputs       → in-memory TestSuite
apply CLI -r/-s/-fps     → in-memory TestSuite        (no file written)
expand proxy_val ranges  → in-memory TestSuite        (no file written)
expand --multiply        → in-memory TestSuite        (no file written)
fill common.test_id      → in-memory TestSuite        (no file written)
rewrite input paths to device paths
                         ↓
       write_canonical_suite(suite, local_workdir, run_id)
                   │
                   ├──→ <local_workdir>/<run_id>.suite.pbtxt    (the one truth)
                   └──→ adb push to device_workdir/<run_id>.suite.pbtxt
```

Exactly one suite file written per run, by exactly one helper. That same file is what's pushed; downstream code references the on-device path so the contract is unambiguous.

### Split mode

`--split` becomes "iterate Tests in the suite, push one at a time" — one pbtxt per iteration (`<run_id>__<test_id>.suite.pbtxt`), written once by the same helper. Resume log `tests_run.log` moves into `<local_workdir>` and becomes JSON-line per completed `test_id` (matches result.json format).

### Deletions

- `run.pbtxt`, `_aggr.pbtxt`, `expanded_*.pbtxt`, `encapp_test.pbtxt` — gone.
- Per-test pbtxt double-write at `encapp.py:657` and `:717` — collapsed to one site.
- Dead `if not protobuf_txt_filepath` branch at `encapp.py:1944-1954` — removed.
- Hardcoded `bitrate_surface_transcoder_show.pbtxt.failed.csv` (written in cwd) — replaced by `<local_workdir>/run_summary.json` (see §4).
- `Serial` proto message (`tests.proto:217`) — removed.
- `update_codec_testsuite` arg-order bug — fixed; helpers called with kwargs.
- `encapp_run.sh` — deprecated and removed. Single Python runner.

---

## §4. Debug & observability

### Python (CLI) logging

Replace `if debug > 0: print(...)` with stdlib `logging`. One root logger `encapp`, per-module children:

```python
log = logging.getLogger("encapp.run")           # scripts/encapp.py
log = logging.getLogger("encapp.adb")           # scripts/encapp_tool/adb_cmds.py
log = logging.getLogger("encapp.suite")         # generation pipeline
log = logging.getLogger("encapp.oracle")        # result.json consumer
log = logging.getLogger("encapp.pull")          # result pull
```

CLI surface:
- `--log-level {DEBUG,INFO,WARNING,ERROR}` (default INFO) — replaces `-d/-q`
- `--log-file PATH` (default `<local_workdir>/encapp.log`) — always written
- `--log-modules encapp.oracle,encapp.adb` — module filter for verbose scoping

Format: `2026-06-12 14:23:01.123 INFO  encapp.oracle  Test h264_1m_johnny_001 passed (2.5s, 600 frames)`. The `debug=` int kwarg threaded through every function is removed.

### Java (app) logging

Single source of truth in `app/.../utils/Constants.java`:

```java
public final class Constants {
    public static final String TAG = "encapp";
    public static final boolean DEBUG = BuildConfig.DEBUG;
}
```

Sites use `Constants.TAG` and prefix the message:

```java
Log.d(TAG, "[surface_encoder] starting muxer for " + filename)
```

Filter by subtag with `grep "\[surface_encoder\]"`, but capture all encapp output with `logcat -s encapp:V`. Wrap `Log.d` calls with `if (Constants.DEBUG)` so debug logging is stripped from release builds. Add a `debug` buildType to `app/build.gradle` so `BuildConfig.DEBUG` actually flips.

### Per-test log files

| File | Android | iOS | Role |
|---|---|---|---|
| `<test_id>.log` | App-written: encapp logs tee'd to a per-test file scoped by test_id | App-written: existing `encapp.log` pattern, renamed per-test | **Primary** diagnostic log — same contract on both platforms |
| `<test_id>.android_logcat.txt` | CLI: `logcat -s encapp:V -d` dumped after each test | n/a | **Additive bonus**: system-level signal (other processes, kernel) |

Per-test scope eliminates ring-buffer rollover risk and keeps each test's diagnostics self-contained.

### Failure UX

On failed test, CLI prints:

```
FAIL  h264_1mbps_johnny_001  crashed during execution at frame 287/600
      reason:   process died (no result.json, .partial present)
      result:   /tmp/results/h264_1mbps_johnny_001.result.json.partial
      logcat:   /tmp/results/h264_1mbps_johnny_001.android_logcat.txt
      app log:  /tmp/results/h264_1mbps_johnny_001.log
      stats:    (not produced)
      output:   (not produced)
      run log:  /tmp/results/encapp.log
```

At end of suite:

```
22/24 passed.  Failed: h264_1mbps_johnny_001, hevc_5mbps_johnny_007.
Full report:   /tmp/results/run_summary.json
```

### Run summary

`<local_workdir>/run_summary.json`:

```jsonc
{
  "schema_version": 1,
  "run_id": "...",
  "started_at_unix_ms": ...,
  "finished_at_unix_ms": ...,
  "serial": "R5CXC2ZH3DR",
  "device_model": "SM-S938U1",
  "app_version": "...",
  "test_count": 24,
  "pass_count": 22,
  "fail_count": 2,
  "tests": [
    {"test_id": "...", "status": "ok",    "duration_ms": 2500, "result_file": "..."},
    {"test_id": "...", "status": "error", "duration_ms":  300, "error": "...", "result_file": "..."}
  ]
}
```

Replaces the hardcoded failure CSV.

---

## File inventory (per test, end state)

Under `<local_workdir>/`:

| File | Owner | Mandatory? | Purpose |
|---|---|---|---|
| `<run_id>.suite.pbtxt` | CLI | yes (1 per run) | The canonical suite, pushed to device |
| `<test_id>.result.json` | App | yes per test | Oracle |
| `<test_id>.result.json.partial` | App (transient) | only on crash | Crash distinguisher |
| `<test_id>.json` | App | yes per test | Per-frame stats (existing, basename-paired with video) |
| `<test_id>.{mp4,webm,heic,...}` | App | yes per test | Encoded output |
| `<test_id>.log` | App | yes per test | Primary diagnostic log (cross-platform) |
| `<test_id>.android_logcat.txt` | CLI | Android only | Additive system logcat |
| `run_summary.json` | CLI | 1 per run | Pass/fail rollup |
| `encapp.log` | CLI | 1 per run | CLI's own log (Python logging) |
| `.encapp_device_workdir` | CLI | cached per device | Workdir handshake cache |
| `device.json` | CLI | 1 per run | Device props (existing, kept) |

---

## iOS considerations

iOS is out of scope for the first implementation but the contract is designed to translate cleanly. Transport-neutral pieces (pre-allocated `test_id`, `<test_id>.result.json` oracle, workdir handshake, pull-by-name, run summary, single canonical pbtxt) work identically on iOS — only the transport calls inside `adb_cmds.py` (and its iOS counterpart) change verbs (`xcrun devicectl copy to|from`, `process terminate`, etc.).

The one platform difference — logcat — is absorbed into the cross-platform `<test_id>.log` written by the app on both platforms. Android adds `<test_id>.android_logcat.txt` as additive bonus only.

CLI-vs-app version mismatch handling (graceful degradation for not-yet-updated iOS app) is **deferred**. To be decided when the iOS port starts.

---

## What gets removed

- `encapp_run.sh` (the parallel bash runner)
- `pull_result` orphan-discovery semantics (kept only as a cached re-pull utility, or removed entirely)
- `Statistics.java` UUID fallback (`Statistics.java:148-153`)
- Triplicate full-suite pbtxts (`run.pbtxt`, `_aggr.pbtxt`, `encapp_test.pbtxt`, plus `expanded_*.pbtxt`)
- Per-test pbtxt double-write site
- Hardcoded `bitrate_surface_transcoder_show.pbtxt.failed.csv`
- Dead branch at `encapp.py:1944-1954`
- `Serial` proto message
- `update_codec_testsuite` positional-arg bug
- `debug=` int kwarg threaded through every CLI function
- ~15 distinct `encapp.*` logcat TAGs (collapsed to one `Constants.TAG = "encapp"` with subtag prefix in message)
- Unfiltered `logcat -d` capture (replaced by per-test `logcat -s encapp:V`)

## What gets added

- `common.test_id` field on `Test` (in `tests.proto`)
- `common.timeout_sec` field on `Test` (in `tests.proto`)
- `Statistics.writeResult()` companion to `writeJSON()` in the app
- `encapp_workdir.txt` writability marker file
- `<local_workdir>/.encapp_device_workdir` CLI cache
- `Constants.java` with single TAG + DEBUG constant
- `debug` buildType in `app/build.gradle`
- stdlib-`logging`-based Python logger setup
- Per-test `<test_id>.log` file writing in the app
- Per-test filtered logcat dump on CLI side
- `run_summary.json` writer

---

## Locked design decisions

| Concern | Decision |
|---|---|
| Oracle | Default-deny. `<test_id>.result.json` is source of truth. |
| Atomicity | Partial + final. `.partial` distinguishes crash from never-started. |
| Timeout | Per-test `common.timeout_sec` (heuristic default); hard kill via `am force-stop`. |
| test_id | App auto-derives from pbtxt fields if missing. CLI normally fills. No UUID fallback. |
| Workdir | Lazy probe on first run per device, cached. App trusts the extra after. |
| Generation | One canonical pbtxt per run. One write helper. No intermediate snapshots. |
| File set | `<test_id>.{output_ext}`, `<test_id>.json` (stats, paired), `<test_id>.result.json`, `<test_id>.log`, `<test_id>.android_logcat.txt` (Android only). |
| Runners | `encapp_run.sh` removed. Single Python runner. |
| Python logging | stdlib `logging`, `--log-level`, `--log-file` defaulted. `debug=` kwarg gone. |
| Java logging | Single `Constants.TAG = "encapp"`, `BuildConfig.DEBUG`-gated `Log.d`, subtag in message. |
| Failure UX | Per-test stdout block + `run_summary.json` rollup. |
| iOS | Design Android-first. iOS port translates `adb_cmds.py` transport calls only. Version-mismatch policy deferred. |
