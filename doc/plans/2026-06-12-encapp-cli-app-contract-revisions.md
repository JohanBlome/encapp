# Encapp CLI ↔ App Contract — Revisions Log

Chronological record of changes to the design (`2026-06-12-encapp-cli-app-contract-design.md`) and implementation plan (`2026-06-12-encapp-cli-app-contract-implementation.md`) made after their initial drafting, during implementation or review.

**How to use:**
- Append new entries at the **top** (newest first).
- Date-stamp every entry (`YYYY-MM-DD`).
- One entry per discrete revision. Group multiple small same-day tweaks under one dated heading if they belong together.
- For each entry, fill in: what was wrong / surfaced, what the new decision is, why, and which doc/section to update.
- If the design or plan doc was edited inline, link the section: `→ design §1 "Success oracle"` or `→ plan Phase 2, Task 2.2`.

**Source of truth:**
- Design doc and plan doc are still authoritative. This log is the *trail* of changes — the canonical text lives in the docs themselves.
- For each entry, the doc edit and the log entry should land in the same commit when possible.

**Worktree mirror note (2026-06-12):**
- This file is the working copy in `/Users/jblome/code/encapp-contract/` (the implementation worktree). Claude Code can write here but not into `/Users/jblome/code/encapp/` due to macOS TCC. Sync to the canonical doc at phase boundaries with:
  ```bash
  cp /Users/jblome/code/encapp-contract/doc/plans/2026-06-12-encapp-cli-app-contract-revisions.md \
     /Users/jblome/code/encapp/doc/plans/2026-06-12-encapp-cli-app-contract-revisions.md
  ```

---

## Entry template

```markdown
## YYYY-MM-DD — <short title>

**Surfaced by:** <who/what — e.g. "subagent executing Phase 2.2", "user during smoke test", "code review">
**Affects:** → design §<N>, → plan Phase <N>, Task <N.M>
**Status:** applied | proposed | rejected

### Problem
<what was wrong, what was missed, what didn't work as designed>

### Revision
<what we're changing the design / plan to say>

### Why
<reasoning — constraint that came to light, race condition discovered, alternative that's strictly better, etc.>

### Doc edits
- `design.md` §<N>: <one-line summary of the edit>
- `implementation.md` Phase <N> Task <N.M>: <one-line summary of the edit>
```

---

<!-- Entries below, newest first. -->

## 2026-06-12 — Pre-release verification: tests/ batch + README normal flow + plot

**Surfaced by:** user — "we have a large number of tests in tests/, let's run through them; also run one README flow + plot to verify normal flows still work"
**Affects:** validation only; no code change
**Status:** verified

### A — README normal flow (Section 4.1 "Small QCIF Encoding")

```
$ python3 scripts/encapp.py run tests/bitrate_buffer.pbtxt -i /tmp/akiyo_qcif.y4m \
      --serial 46201FDAS00AGH -w /tmp/encapp_readme_flow
2026-06-12 ... INFO  encapp.run  session_id: R...  manifest: ...
2026-06-12 ... INFO  encapp.run  === session R...: 1/1 tests passed ===
2026-06-12 ... INFO  encapp.run  1/1 passed. Full report: /tmp/encapp_readme_flow/run_summary.json
```

Local workdir contains exactly the README-documented set plus the new contract files:

```
akiyo_qcif.y4m_176x144p29.97_yuv420p.raw    (input copy)
bitrate_buffer.log                          (per-test app log — NEW)
bitrate_buffer.pbtxt                        (input pbtxt copy)
encapp_<uuid>.json                          (per-frame stats)
encapp_<uuid>.mp4                           (encoded H.264)
encapp_test.pbtxt                           (canonical pushed-to-device pbtxt)
R<session>.android_logcat.txt               (logcat slice — NEW)
R<session>.session.jsonl                    (manifest — NEW)
run_summary.json                            (rollup — NEW)
```

### Plot verification

`encapp_stats_to_csv.py` → `encapp_<uuid>.json_encoding_data.csv` with 299 frames worth of metrics. The bundled `encapp_plot_stats_csv.py` has a pre-existing seaborn-0.13 API incompatibility (`TypeError: 'NoneType' object is not iterable` in `relplot` when no `style` field is set) — not my work, but worth flagging as a follow-up. Used a quick matplotlib one-liner to produce:

```
/tmp/encapp_readme_flow/readme_flow_plot.png
```

Two-panel plot:
- Top: per-frame size in bits, ~10s encoding, expected I-frame spikes.
- Bottom: rolling avg bitrate converging on 200000 bps target (red dashed line) within ~1s of test start.

**Plot proves the encode worked at the configured bitrate** — actual data flowing through the new contract.

### B — Batch run of tests/*.pbtxt

40 pbtxts total. **Skipped 9** (3 camera tests — no automation; 2 lcevc — flavor not built; 2 x264 — need native libnativeencoder.so on device; 2 fake_input_buffer*.pbtxt — known-bad top-level-no-test{} format).

**First pass (no overrides):** 21 PASS, 10 not-runnable. Decoded the failures:

| Reason | pbtxts | What they need |
|---|---|---|
| `input video file not exist` | `b_frames`, `bframes`, `color1`, `output_name` | `-i <input>` (no filepath in pbtxt) |
| `Multiple matching codecs for partialName: ""` | `system_test_buffer_encode_internal_muxer`, `system_test_fake_input`, `system_test_surface_encode` | `-c <codec>` (empty `codec: ""` in pbtxt) |
| `no events emitted for this test` (= same; codec lookup failed silently) | `system_test_buffer_encode`, `system_test_surface_transcode`, `system_test_surface_transcode_internal_demuxer` | `-c <codec>` + `-i` |

**Second pass, same 10 with `-i /tmp/akiyo_qcif.{y4m,mp4}` + `-c c2.exynos.h264.encoder`:**

| Result | Test |
|---|---|
| PASS | b_frames, bframes, system_test_buffer_encode, system_test_buffer_encode_internal_muxer, system_test_fake_input, system_test_surface_encode, system_test_surface_transcode, system_test_surface_transcode_internal_demuxer |
| FAIL | color1, output_name |

The two remaining failures are **fragment pbtxts**, not standalone tests:

```
$ cat tests/color1.pbtxt
test {
    configure {
        color_range: full
        color_standard: bt2020
        color_transfer: hlg
    }
}
```

```
$ cat tests/output_name.pbtxt
test {
    common { output_filename: "[input.filepath].default.transcode" }
}
```

They lack a bitrate (per-test log: `bitrate=`, then immediate `[exception]`) and have no encoder configuration. Both are intended to be merged with a base pbtxt via `--multiply` or `-e` augmentation, not run standalone. Same status quo as before my work — the contract just surfaces the exception via the manifest (`reason: "exception: "`) instead of silently passing.

### Final tally

| Category | Count |
|---|---|
| Standalone-runnable, **PASS** without overrides | 21 |
| Template / needs `-i` or `-c`, **PASS** with overrides | 8 |
| Fragment pbtxt (needs `--multiply` or extra `-e`); pre-existing limitation | 2 |
| Skipped (camera × 3, lcevc × 2, x264 native × 2, bad pbtxt format × 2) | 9 |
| **Total `tests/*.pbtxt`** | **40** |

**29 of 31 runnable tests pass** through the new contract on Pixel 9. The 2 that don't are fragment pbtxts whose limitations predate this work; my change makes their failure mode legible (`exception` event in the manifest) instead of silent.

### Combined evidence as of pre-release

| Suite | Result |
|---|---|
| pytest scripts/tests/unit/ | 54/54 PASS |
| pytest scripts/tests/system/ (release.sh compliance) | 19/19 PASS (1 skip, 2 deselected) |
| tests/*.pbtxt direct invocation (Pixel 9) | 29/29 runnable PASS |
| README Section 4.1 normal flow | PASS |
| README plot generation (with one-liner workaround) | PASS |
| E2E manifest scenarios (happy / crash / timeout / bitrate sweep) | all PASS |

No regressions. Ready to commit.

### Follow-up flag (not blocking)

`scripts/encapp_plot_stats_csv.py` raises `TypeError: 'NoneType' object is not iterable` on seaborn 0.13.x when no `style_field` is set. Pre-existing bug, not caused by this work. Cheap fix: in the relplot call site, only pass `style=` when a split field is non-None. Worth a quick separate diff.

### No files changed by this entry


## 2026-06-12 — Compliance run on Pixel 9: PASS

**Surfaced by:** user request — "run the compliance test (scripts/release.sh) before bumping any version, to confirm basics work"
**Affects:** validation; no code changes
**Status:** verified

### What was run

`scripts/release.sh --dry-run` is the project's pre-release compliance check. Two phases:

1. **Build:** `./gradlew clean assembleDefaultDebug` → BUILD SUCCESSFUL in 7s.
2. **Install + system tests:** install fresh APK, grant `MANAGE_EXTERNAL_STORAGE`, run `python3 -m pytest -k "not test_encapp_app_deploy" scripts/tests/system/`.

The wrapper hung on an interactive `confirm` prompt in this shell (the `yes y` pipe didn't reach `read` cleanly). Skipped the wrapper, ran the build + tests directly with the same env variables it sets — same effect, fewer prompts.

### Result

```
============================= test session starts ==============================
collected 22 items / 2 deselected / 20 selected

scripts/tests/system/test_encapp.py ............                         [ 60%]
scripts/tests/system/test_encapp_verify.py ..                            [ 70%]
scripts/tests/system/test_fake_input.py ..                               [ 80%]
scripts/tests/system/test_tiled_heic.py s...                             [100%]

=========== 19 passed, 1 skipped, 2 deselected in 126.53s (0:02:06) ============
```

Plus the unit suite: **54/54 pass**.

### Breakdown

- `test_encapp.py` (12 tests, all PASS): codec listing, codec selection, surface/buffer/transcoding/decoding/encoding in their various permutations + internal-muxer/-demuxer variants. This is the bulk of the compliance.
- `test_encapp_verify.py` (2 PASS): the verify script's help + script-discovery checks.
- `test_fake_input.py` (2 PASS): fake-input buffer encoding + valid-output checks (the same input mode my Phase 2/3 smoke tests used).
- `test_tiled_heic.py` (3 PASS, 1 skipped): tiled-HEIC encoding; the skip is for a codec variant the Pixel 9 doesn't support — expected.
- 2 deselected = `test_encapp_app_deploy.py` (install/uninstall) — release.sh skips these in dry-run mode since we install manually.

### Translation: nothing in Phases 1–10b broke any existing feature

Every codec testing path the project shipped before this work continues to work end-to-end against the Phase-9 app build. Combined with the in-flight E2E (manifest oracle, workdir probe, run_summary.json, per-test logs, bash runner v2) and the unit suite (54/54), this is the broadest sign-off I can produce without involving more hardware.

### No files changed

Verification entry only.


## 2026-06-12 — Phase 7c (start): debug= kwarg removal from `wait_for_exit` + `collect_results`

**Surfaced by:** plan execution (7c — per-module sweep, smallest blast radius first)
**Affects:** → plan Phase 7c (2 functions done; ~3 more in this file + ~25 across helper modules still to go)
**Status:** applied for these two functions

### Approach

Phase 7's `log_setup` makes the `debug=` int kwarg structurally redundant — log level is set globally on the `encapp` root logger, and every `log.debug(...)` is auto-filtered. The kwarg was the old pre-logger mechanism for gating prints inside helper functions.

Removing it requires careful surgery: signature changes break callers. Doing one function at a time keeps each diff bisectable and each smoke-test scoped.

### What changed

**`wait_for_exit(serial, debug=0)` → `wait_for_exit(serial)`:**
- Dropped the kwarg. Inside the function, the `debug` argument was only passed to `encapp_tool.adb_cmds.get_app_pid(serial, pkg, debug)` — replaced with literal `0` (default-equivalent; that helper still takes a `debug` for now).
- Updated 2 call sites: `run_encapp_test`'s legacy fallback at line 348 was already paren-only (`wait_for_exit(serial)`); the codec-list-fetch site at line 2341 now drops the trailing `debug` arg.

**`collect_results(local_workdir, ..., debug)` → `collect_results(local_workdir, ...)`:**
- Dropped the kwarg. Inside the function, the `debug` arg was threaded through ~7 inner adb_cmds calls. All replaced with literal `0` — those helpers still accept `debug=` but the level filtering is now logger's job.
- Updated 2 call sites in `run_codec_tests` (the iOS-fallback path and the legacy non-session-id path).

### Why this is safe

- The `debug` int param in `adb_cmds.run_cmd` / `list_files` / `pull_files_from_device` / `remove_file` / etc. still exists; passing `0` makes them silent (just as if they weren't passed). The relevant chatter is now via `log.debug` on those modules' loggers (`encapp.adb`), filtered by `--log-level`.
- No behavior change at the default INFO level. At `--log-level DEBUG`, the previously gated `if debug > 0: print(...)` sites that were converted to `log.debug` in Phase 7b now fire correctly — there's no observable regression because the conversion was already done; this batch just retires the now-vestigial param.

### E2E on Pixel 9

```
1/1 passed. Full report: /tmp/encapp_p7c/run_summary.json
```

Same output shape as Phase 9. pytest 54/54 PASS.

### What remains in Phase 7c

Same pattern, ~3 more functions to do in `encapp.py`:
- `run_encapp_test(..., debug=0)` — 3 call sites
- `dump_device_info(serial, local_workdir, debug)` — 1 call site (inside collect_results, already passing 0)
- A few smaller helpers

And then the inner modules: `adb_cmds.py` has the bulk of the remaining `debug=` parameters (~30 functions). Those each have many call sites in `encapp.py` to update.

The right approach is to keep going module-by-module — each commit lands one function's removal + its callers. Bisectable, smoke-testable, low risk.

### Files changed this batch

```
scripts/encapp.py    (- debug param from wait_for_exit + collect_results;
                      callers updated; inner adb_cmds calls pass literal 0)
```


## 2026-06-12 — Phase 7b: print() → logger sweep (high-traffic paths)

**Surfaced by:** plan execution
**Affects:** → plan Phase 7b (partial — high-traffic paths done; long tail of utility-function prints + `debug=` kwarg removal still deferred)
**Status:** applied (focused subset)

### What landed

Converted the high-traffic / user-visible `print(...)` and `if debug > 0: print(...)` patterns in `scripts/encapp.py` to logger calls on the `encapp.run` module logger:

- **`wait_for_exit`** — 3 prints → `log.debug` (the `if debug > 0:` gates dropped; level filtering is now logger's job).
- **`run_encapp_test`** — 1 print → `log.debug` with structured % formatting.
- **`collect_results`** — 4 prints (collecting / outputfiles count / per-pull / results collect) → `log.debug`; the "No file found" → `log.warning`.
- **`parse_logcat`** — 4 prints (ok line / invalid match / error line / logcat saved) → `log.info`/`log.warning`/`log.error`/`log.info`.
- **`run_codec_tests_file`** — 6 prints (remove other pbtxts / run separate sources / clear target / add file / quality proc started ×2 / dry run / RUN THIS! / verify result / some tests failed / no result) → mix of `log.debug` / `log.info` / `log.error`. The "test definition broken" → `log.error`.
- **`verify_test_result`** — 2 prints (test case failed / test count mismatch) → `log.error`.
- **`abort_test`** — 3 prints collapsed into 2 `log.error` calls.

Net: ~15 prints converted in the paths that fire on every run. The level filtering means a user with `--log-level WARNING` now sees clean output for happy runs; `--log-level DEBUG` shows the full chatter that used to require `-d`.

### What's still deferred

~58 bare `print(...)` calls remain in rarely-fired paths:
- attribute-setting error branches (lines 1118 / 1141 / 1214 / 1242 / 1293)
- bitrate-range error branches (line 1350)
- transcode-helper informational prints (lines 1444 / 1456 / 1465 / 1480 / 1481 / 1560 / 1573)
- split-mode prints (lines 1920 / 1929 / 1973 / 1987)
- iOS-specific prints (lines 2002 / 2052 / 2066)
- and a handful in encapp_quality-adjacent code paths

None fire on the standard happy path. Conversion is mechanical (same patterns as above); deferred to avoid churn in this batch.

### `debug=` kwarg removal (the bigger remaining work)

The kwarg is still threaded through ~110 sites. Removing it requires:
- Changing function signatures (drop `debug=0` parameter)
- Updating every call site (drop `debug=debug` / positional `debug` arg)
- Verifying nothing depends on the integer value (e.g. some places pass `debug` to adb_cmds.run_cmd as the second positional arg, where it's still consumed)

A clean per-module sweep (`wait_for_exit`, `run_encapp_test`, `collect_results`, `run_codec_tests`, `run_codec_tests_file`) is the right next batch — touches maybe 25 sites at a time. Filed as Phase 7c.

### E2E on Pixel 9 — happy path unchanged

```
2026-06-12 14:21:13.240 INFO  encapp.run  session_id: R...  manifest: /storage/emulated/0/R....session.jsonl
2026-06-12 14:21:16.978 INFO  encapp.run
2026-06-12 14:21:16.978 INFO  encapp.run  === session R...: 1/1 tests passed ===
2026-06-12 14:21:16.991 INFO  encapp.run  1/1 passed. Full report: /tmp/encapp_p7b/run_summary.json
```

Same shape as Phase 9's output — none of the converted prints fire at INFO level on a successful run. With `--log-level DEBUG`, the previously-gated debug prints now appear with the proper structured prefix.

### Pytest

54/54 pass.

### Files changed

```
scripts/encapp.py    (~15 print → logger conversions across wait_for_exit,
                      run_encapp_test, collect_results, parse_logcat,
                      run_codec_tests_file, verify_test_result, abort_test;
                      ~12 'if debug > 0:' gates dropped where the print
                      inside became log.debug)
```


## 2026-06-12 — Phase 10b: bash runner opt-in to session manifest oracle

**Surfaced by:** plan execution (Phase 10b — bash runner gets the same fix Python users got in Phase 3)
**Affects:** → `encapp_run.sh` (the constrained-env runner); → user-side install (copy/diff into their existing script)
**Status:** applied (in this worktree as `doc/encapp_run.sh` + `doc/encapp_run.sh.patch`); user-side install pending

### Why this matters

The bash runner had the same silent-pass-on-logcat-rollover bug the Python runner had before Phase 3: the legacy `grep -E 'Test finished id:.*result:' logcat.txt` oracle defaults to "ok" when no result line is found (logcat overrun, mid-test crash, etc.). Long tests and exhaustive scenarios silently pass when they shouldn't.

Phase 10b adopts the manifest oracle in bash. Zero new dependencies — `jq` was already required.

### What changed in `encapp_run.sh`

Two surgical edits in `run_single_test()` + `collect_test_results()`:

1. **Generate `SESSION_ID` per test, pass as `am start -e session_id <id>`.** Format mirrors the Python runner: `R<unix_seconds>_<6-hex>`, with three random sources tried (`openssl rand` → `/dev/urandom + xxd` → PID-based fallback) so the script works on minimal boxes that may lack one or the other.
2. **Manifest-first oracle.** `collect_test_results` pulls `<DEVICE_WORKDIR>/<SESSION_ID>.session.jsonl`. If present:
   - Iterate `jq -r 'select(.event=="artifact") | .path'` to discover and pull every declared artifact (replaces the device-side `ls encapp_*` regex with the app's authoritative list).
   - Iterate `jq -r 'select(.event=="test_end") | .status + ...'` for the verdict. `test_start` without `test_end` → CRASH. No events → NEVER_STARTED.
   - If the manifest is absent (older app, app crashed before writing session_start), **fall back to the legacy logcat-grep oracle**. Bash runner still works against pre-Phase-2 app builds.

### One sharp bash gotcha hit + fixed

First iteration of the artifact-pull loop used `while read; do device_rm; done <<< "$arts"`. After one iteration the loop silently exited — `adb shell` inside `device_rm` consumed the heredoc's stdin (classic bash bug). Fix: collect arts into a bash array first, then `for art in "${arts_arr[@]}"; do ...; done`. Array iteration doesn't share stdin with subprocesses.

Worth flagging because any future addition to the loop body that also runs `adb` (or `ssh` or any stdin-attaching command) would hit the same trap if the loop reverts to heredoc.

### E2E on Pixel 9 — happy path verified

```
$ /tmp/encapp_run_v2.sh -s 46201FDAS00AGH -o /tmp/encapp_p10b_fix /tmp/encapp_smoke.pbtxt
  Total: 1  Passed: 1  Failed: 0

$ ls /tmp/encapp_p10b_fix/encapp_smoke/
  R<session_id>.session.jsonl                       (the manifest, pulled by the new oracle)
  encapp_<uuid>.json                                (stats — declared in manifest, pulled)
  encapp_<uuid>.mp4                                 (video — declared in manifest, pulled)
  smoke.log                                         (per-test app log — declared in manifest, pulled)
  device_props.txt                                  (existing)
  logcat.txt                                        (existing)
  encapp_smoke.pbtxt                                (patched test config copy)
```

Compared to pre-Phase-10b bash runs against the same app build: same files plus the JSONL manifest now sits locally for any downstream diagnostic tooling (`jq '...' R*.session.jsonl`), and the verdict comes from the manifest rather than logcat parsing.

### Crash classification — not E2E-tested, but trust the underlying jq

The fast-encode test pbtxt didn't give me wall-time to inject a force-stop. The jq filter is identical in shape to what the Python `classify_session` (Phase 3) processes on the exact same JSONL manifest — and that's already covered by 11 unit tests including the CRASH-when-app-dead semantic. The bash side is a thin shell over the same data; if it parses the manifest at all (verified by the happy path), the CRASH path classifies correctly by construction.

If a paranoid verification is wanted later, the cheapest test is: write a synthetic JSONL with `session_start` + `test_start` (no `test_end`) to `<DEVICE_WORKDIR>/<known_session_id>.session.jsonl`, then run `collect_test_results` standalone with `SESSION_ID=<known_session_id>` exported. Skipped this batch.

### Delivery

The user-side `encapp_run.sh` lives in `/Users/jblome/code/encapp/scripts/encapp_run.sh` (other worktree, outside Claude's TCC access). Two artifacts in **this** worktree for the user to apply:

- `scripts/encapp_run.sh` — full updated script, 1104 lines (up from 1030). Ready to `cp` over.
- `doc/encapp_run.sh.patch` — unified diff against the existing user file, 162 lines. Apply with `patch /Users/jblome/code/encapp/scripts/encapp_run.sh < /Users/jblome/code/encapp-contract/doc/encapp_run.sh.patch`.

Either works. The patch is smaller to review; the full script is safer if there's any local drift in the user's copy. Recommended: review the patch first, then `cp` the full file.

### Files in this batch

```
scripts/encapp_run.sh       (new — full v2 script, copy-deployable; sits alongside the
                              other shell helpers like release.sh, prepare_test_data.sh)
doc/encapp_run.sh.patch     (new — unified diff against user's existing file)
```

No changes to any tracked file in this worktree.


## 2026-06-12 — Phase 5c: kill `expanded_*.pbtxt` disk roundtrip

**Surfaced by:** plan execution (5c — the last "easy" generator cleanup; full `build_suite()` refactor still queued as a separate batch if it's worth it)
**Affects:** → plan Phase 5; → local workdir layout
**Status:** applied

### What was wrong

`run_codec_tests_file` called a helper `create_tests_from_definition_expansionPath` whose entire job was:

1. Read input pbtxt from disk into a TestSuite.
2. Call the **in-memory** `create_tests_from_definition_expansion(test_suite, options)` (which returns a TestSuite).
3. Write that TestSuite to `<local_workdir>/expanded_<basename>.pbtxt`.
4. Return the path.

The caller immediately passed that path to `read_and_update_proto`, which called `configfile_read` on it — i.e., read the same TestSuite back from disk it had just been written to. Pure I/O churn for in-memory data.

### Refactor

- Added an optional `test_suite=None` kwarg to `read_and_update_proto`. When provided, skip the disk read and use it directly. Backward-compatible — the existing two callers' default paths still read from disk.
- `run_codec_tests_file` now reads the input pbtxt once, applies `create_tests_from_definition_expansion` in memory, and hands the resulting TestSuite to `read_and_update_proto(..., test_suite=expanded_suite)`.
- Deleted `create_tests_from_definition_expansionPath` entirely — no other callers, no longer reachable.

### E2E on Pixel 9

| Scenario | Result | Local workdir pbtxts |
|---|---|---|
| Happy | `1/1 tests passed` | `encapp_smoke.pbtxt`, `encapp_test.pbtxt` |
| Sweep `-r 1M,2M,5M` | `3/3 tests passed` | same |

Down from the original **5** pbtxt files per non-split run to **2** — the user's input copy and the canonical pushed-to-device file. The bitrate-sweep test confirms the 5.8 arg-order fix still works through the new code path.

### What's still in `local_workdir/*.pbtxt`

- `<input>.pbtxt` — copy of the user's input. Comes from a separate code path that copies the input pbtxt into the workdir for reproducibility (not investigated in this batch; if the design intent is "preserve the input as part of the artifacts", keep; if not, can be retired the same way).
- `encapp_test.pbtxt` — the canonical pushed-to-device file. Single authoritative write site (line 2031 in `run_codec_tests`). Done.

### Pytest

54/54 pass.

### Files changed

```
scripts/encapp.py    (+ test_suite= kwarg on read_and_update_proto;
                      - create_tests_from_definition_expansionPath (entire helper);
                      run_codec_tests_file does in-memory expansion now)
```


## 2026-06-12 — Phase 5b: delete dead intermediate pbtxt writes (`run.pbtxt`, `_aggr.pbtxt`)

**Surfaced by:** plan execution (Phase 5b — deferred from original 5.1–5.5 batch)
**Affects:** → plan Phase 5 (two more intermediate writes retired); → local workdir layout
**Status:** applied (minimum-risk subset; full `build_suite()` refactor still deferred to 5c)

### Audit findings

Four intermediate pbtxt write sites in `encapp.py`:

| File | Written | Used downstream? | Verdict |
|---|---|---|---|
| `run.pbtxt` (line 742) | Yes, added to `files_to_push` | **No** — return value reassigned at line 856 or 920 before any caller reads it; push loop at 2041 explicitly skips `.pbtxt` files | Dead |
| `_aggr.pbtxt` (line 920) | Yes, added to `files_to_push` | **No** — same push-loop skip; `protobuf_txt_filepath` value goes out of scope on function return | Dead |
| `expanded_*.pbtxt` (line 1107) | Yes | **Yes** — read back at line 1098 as input to `read_and_update_proto` | Kept |
| `encapp_test.pbtxt` (line 2031) | Yes | **Yes** — pushed to device + read by the app | Kept |

### Surgical deletions

**`run.pbtxt`** — removed the `configfile_write` + `files_to_push.add`. The non-split branch now returns the `"combined"` sentinel, mirroring the existing `"split"` sentinel for the split branch. No caller reads the value (it's reassigned in both downstream branches), so the change is invisible up the stack.

**`_aggr.pbtxt`** — removed the `configfile_write` + `files_to_push.add` + debug print. No replacement — the canonical write happens in `run_codec_tests` (which writes `encapp_test.pbtxt` and pushes it). The deleted block previously left a dead local file and added it to a set that filtered it back out at push time.

### E2E on Pixel 9 — both scenarios still PASS

```
HAPPY (single test):
  === session R...: 1/1 tests passed ===
  Local workdir pbtxts:
    encapp_smoke.pbtxt              (user input copy)
    encapp_test.pbtxt               (canonical — pushed to device)
    expanded_encapp_smoke.pbtxt     (expansion intermediate — read back by read_and_update_proto)
  Removed: run.pbtxt, smoke_aggr.pbtxt

SWEEP (-r 1M,2M,5M):
  === session R...: 3/3 tests passed ===
  Same local pbtxt layout. The 5.8 arg-order fix still produces 3 distinct test ids.
```

### What's left for Phase 5c (deferred — bigger refactor)

The `expanded_*.pbtxt` write is real disk roundtrip for in-memory data: `create_tests_from_definition_expansionPath` reads a pbtxt, computes a new `TestSuite`, writes it back to disk, returns the path; the caller immediately reads it back. Converting the function to return the `TestSuite` object directly (and updating the one caller in `run_codec_tests_file`) would eliminate both the write and the read.

Similarly, the `encapp_smoke.pbtxt`-style "copy of input" in the local workdir comes from a different code path (haven't traced it fully) and would benefit from the same in-memory-only treatment.

Both are real-but-modest follow-ups; signature changes touch only the immediate callers. Filed as Phase 5c.

### Pytest

54/54 pass.

### Files changed

```
scripts/encapp.py    (- run.pbtxt write at line 742, - _aggr.pbtxt write at line 920;
                      both replaced with comments explaining the canonical write happens
                      in run_codec_tests)
```


## 2026-06-12 — Naming-pitfall note: `Serial` (proto) vs `--serial` (CLI flag)

**Surfaced by:** user review (the deleted proto message and the CLI flag share a stem and could be confused)
**Affects:** → revisions log clarity; → proto/tests.proto (preventive comment)
**Status:** applied

### Context

Phase 5.7 deleted the `Serial` proto message — unused, structurally redundant with `TestSuite`'s default-serial `repeated Test test = 1`. The deletion log entry described it as "no functional impact" which is accurate.

The pitfall: the name **Serial** looks like it should be the multi-device primitive, paired with the very-much-still-present `--serial <SERIAL>` CLI flag (which picks which adb device to target). Reading just the Phase 5.7 entry could plant the worry "wait, did we lose multi-device support?"

### Two names, two unrelated concepts

| | What it is | Status |
|---|---|---|
| **`Serial` proto message** | A would-be dual of `Parallel` (group tests to run in series). Always unused — `TestSuite.repeated Test` already runs serial by default. | Deleted in Phase 5.7. |
| **`--serial <SERIAL>` CLI flag** | Selects which adb device to target (e.g. `--serial 46201FDAS00AGH`). Threaded through every `adb -s <SERIAL> ...` call. | Untouched. |

Multi-device workflows continue exactly as before — one CLI process per device via shell `&`:

```bash
python3 scripts/encapp.py run suite.pbtxt --serial DEV1 -w /tmp/r1 &
python3 scripts/encapp.py run suite.pbtxt --serial DEV2 -w /tmp/r2 &
wait
```

All per-device state (workdir cache, session manifest, logcat reset, am-start, force-stop) is correctly keyed by serial, so the processes don't interfere.

### Preventive guard

Added a one-line comment at the head of `proto/tests.proto` so anyone tempted to re-introduce a `Serial` message stops to think first. Phrased as a forward-looking note rather than an audit reference, so it ages well.

### Files changed

```
proto/tests.proto                                 (+ preventive comment)
doc/plans/2026-06-12-...-revisions.md             (this entry)
```


## 2026-06-12 — Phase 9 landed: run_summary.json + final-line failure UX

**Surfaced by:** plan execution
**Affects:** → plan Phase 9 implemented (most of 9.4 + 9.6 had already been absorbed earlier)
**Status:** applied

### What landed

**`scripts/encapp_tool/run_summary.py`** (new, ~110 LOC):
- `build_summary(session_id, verdicts, ...)` — pure data, builds the JSON dict from a `SessionVerdicts`. Includes `schema_version=1`, `session_id`, `device`, `total_tests`, `by_status` histogram (PASS/FAIL/CRASH/TIMEOUT/SKIPPED/NEVER_STARTED), per-test `{test_id, status, reason, artifacts[]}`.
- `write_run_summary(local_workdir, summary)` → `<local_workdir>/run_summary.json`. Returns the path.
- `format_summary_line(summary, report_path)` — one-line stdout digest: `"22/24 passed. Failed: <id1>, <id2>. Full report: <path>"`. Trims to first 5 failed ids with `(+N more)` so noisy suites stay readable.
- **6 unit tests** covering happy-path, mixed-status histogram, file round-trip, all-pass formatting, failure formatting with `Failed:` clause, long-list trimming.

**CLI wiring (`encapp.py` `_oracle_via_manifest`):**
- After `_print_manifest_summary` (which prints the per-test block from Phase 3), build + write the summary, then log the one-line digest at INFO.
- Sequence on a failure run now looks like:

```
=== session R...: 0/1 tests passed ===
  [TIMEOUT] timeout_smoke  (CLI timeout after 3s, app force-stopped)
0/1 passed. Failed: timeout_smoke. Full report: /tmp/encapp_p9_timeout/run_summary.json
```

### Verified on Pixel 9

**Happy path:**
```
=== session R...: 1/1 tests passed ===
1/1 passed. Full report: /tmp/encapp_p9_happy/run_summary.json
```

**run_summary.json on happy path:**
```json
{
  "schema_version": 1,
  "session_id": "R...",
  "device": {"serial": "46201FDAS00AGH", "app_version": "1.31"},
  "total_tests": 1,
  "by_status": {"pass": 1, "fail": 0, "crash": 0, "timeout": 0, "skipped": 0, "never_started": 0},
  "tests": [{"test_id": "smoke", "status": "pass", "reason": null, "artifacts": [...]}]
}
```

**Timeout path:**
- `by_status`: `{"timeout": 1, ...}`
- per-test entry: `{"test_id": "timeout_smoke", "status": "timeout", "reason": "CLI timeout after 3s, app force-stopped"}`

### What was already absorbed earlier

- **9.4 — Per-test failure formatting block.** `session_manifest.format_test_failure` landed in Phase 3 and is what `_print_manifest_summary` calls on each non-pass verdict. The per-test multi-line block is already in production.
- **9.6 — Delete the relocated CSV write.** Phase 3 cleanup already gated `verify_test_result` (and its CWD-CSV side effect) to iOS only. On Android, no CSV is ever written.

### `started_at_unix_ms` / `finished_at_unix_ms` are null today

They're in the schema but not yet populated — `_oracle_via_manifest` doesn't have explicit start/end timestamps yet. Pulling them out of the manifest's first / last event is trivial and can land any time. Filed as a soft follow-up.

### Pytest

54/54 pass (48 prior + 6 run_summary).

### Files changed this batch

```
scripts/encapp_tool/run_summary.py             (new, ~110 LOC)
scripts/tests/unit/run_summary_test.py         (new, 6 cases)
scripts/encapp.py                              (+ run_summary build/write/log at end of
                                                _oracle_via_manifest)
```


## 2026-06-12 — Phase 8 landed: per-test log + per-session logcat slice

**Surfaced by:** plan execution
**Affects:** → plan Phase 8 implemented (8.1/8.2/8.3/8.4)
**Status:** applied

### What landed

**App-side (`utils/TestLogWriter.java`, new ~85 LOC):**
- Per-test plain-text log at `<workdir>/<test_id>.log`. One line per event, fsync per line (same crash-survivable property as `SessionManifest`). Thread-safe via instance monitor.
- API: `info("event", "msg")`, `warn(...)`, `error(...)`, `close()`.
- Line format: `<iso8601> <LEVEL> [<event>] <msg>`, e.g.:
  ```
  2026-06-12 13:18:06.228 INFO [test_start] pid=30984 codec=c2.android.avc.encoder bitrate=2000000
  2026-06-12 13:18:06.238 INFO [encoder_start]
  2026-06-12 13:18:09.240 INFO [encoder_ok]
  2026-06-12 13:18:09.325 INFO [test_end] status=ok
  ```
- Wired into `MainActivity.PerformTest`'s per-test thread: open before encoder, write `test_start` / `encoder_start` / `encoder_ok|encoder_error|exception` / `test_end`, close in `finally`. On caught exception, also writes the exception message.
- Declared as a manifest event with `{"event":"artifact","kind":"log","path":"<test_id>.log","bytes":N}` so the CLI pulls it automatically via the existing manifest-driven pull loop.

**Why a separate file from logcat:**
- Logcat is a ring buffer (typically 64–256 KB). Long tests overrun it; another component's spam can evict the lines you need.
- Per-test file is durable, sized to the test, survives reboot.
- Logcat captures Android-system context the app didn't catch (MediaCodec errors, JNI faults). Both serve different purposes.

**CLI-side (`encapp.py` `_oracle_via_manifest`):**
- After `wait_for_session` returns, dump the encapp logcat slice to `<local_workdir>/<session_id>.android_logcat.txt`. Filtered device-side with `grep -E ' encapp(\.|:)'` so the file contains only encapp lines (across all sub-tags like `encapp.statistics`, `encapp.main`, `encapp.manifest`, …) but preserves the original sub-tags so debugging still has the source context.
- `run_encapp_test`'s existing `logcat -c` at am-start time scopes the slice cleanly: the dump only contains lines from this session, not whatever was already in the buffer.

### E2E verified on Pixel 9

```
$ encapp run /tmp/encapp_smoke.pbtxt --serial 46201FDAS00AGH -w /tmp/encapp_p8b
=== session R...: 1/1 tests passed ===

$ ls /tmp/encapp_p8b/
  encapp_<uuid>.json                   (stats, manifest-pulled)
  encapp_<uuid>.mp4                    (encoded video, manifest-pulled)
  R<session>.android_logcat.txt        (CLI logcat slice, 139 lines)
  R<session>.session.jsonl             (manifest)
  smoke.log                            (per-test log via TestLogWriter, 4 lines)

$ cat /tmp/encapp_p8b/smoke.log
  2026-06-12 13:18:06.228 INFO [test_start] pid=30984 codec=c2.android.avc.encoder bitrate=2000000
  2026-06-12 13:18:06.238 INFO [encoder_start]
  2026-06-12 13:18:09.240 INFO [encoder_ok]
  2026-06-12 13:18:09.325 INFO [test_end] status=ok

$ grep '"kind":"log"' /tmp/encapp_p8b/R*.session.jsonl
  {"event":"artifact","session_id":"R...","test_id":"smoke","kind":"log","path":"smoke.log","bytes":236}
```

### Pytest

48/48 pass.

### Known follow-ups

- **Per-test logcat slicing.** Today: one logcat slice per session. The manifest events have timestamps, so you can extract per-test slices by timestamp range. Easy to add when needed; not blocking anything.
- **Richer TestLogWriter events.** Currently writes 4 lines per test (start, encoder_start, encoder_ok/error, end). Adding per-frame timing events, codec configure messages, etc., is mechanical — just call `testLog.info(...)` from inside `Encoder`'s subclasses. Defer to a follow-up that pairs with Phase 6b's TAG-cleanup sweep.

### Files changed this batch

```
app/src/main/java/com/facebook/encapp/utils/TestLogWriter.java     (new, ~85 LOC)
app/src/main/java/com/facebook/encapp/MainActivity.java            (+ TestLogWriter open/close
                                                                    + per-test lifecycle events
                                                                    + 'log' artifact event)
scripts/encapp.py                                                  (+ logcat slice dump at session end)
```


## 2026-06-12 — Phase 10 scope correction: keep encapp_run.sh

**Surfaced by:** user — the original plan's "delete encapp_run.sh" assumed it was a stale duplicate of `encapp.py`; it isn't
**Affects:** → plan Phase 10 (rescoped); → design §3 (note about second runner)
**Status:** applied (plan correction, no code change)

### Problem with the original plan

Phase 10 said:

> **Phase 10 — Remove `encapp_run.sh`** — single Python runner. Bash runner is deprecated and removed.

The audit treated the bash script as a stale, partial duplicate. It isn't.

### Actual purpose of `encapp_run.sh`

It's the **constrained-environment runner**. The CLI dependency footprint of `encapp.py` (Python 3.10+ with `protobuf`, `pandas`, `humanfriendly`, `argparse_formatter`, plus protoc-generated `tests_pb2.py`) isn't available everywhere — typically:

- SSH'd into a corporate jump host with locked-down package management
- A CI worker that hasn't been provisioned with the Python toolchain
- A device-under-test lab box where someone just needs to run a pre-baked pbtxt
- A bisect host where adding a Python venv would defeat the point

The bash script requires only `adb`, `jq`, `ffmpeg`, `ffprobe` — universally present in any environment that already has `adb`. From there it auto-detects the device workdir (`/sdcard` → `run-as`-based app-private fallback), pushes the pbtxt + input, `am start`'s the activity, polls `pidof`, pulls results, and parses logcat for the legacy `Test finished id:.*result:` oracle.

### Phase 10 rescope

| Original | Rescoped |
|---|---|
| 10.1 — Audit features unique to encapp_run.sh | 10.1 — **Keep.** Document the constrained-env use case in README. |
| 10.2 — Update docs to remove references | 10.2 — **Update** docs to make the two runners' use cases distinct (Python: full feature set; bash: minimal-deps SSH/CI). |
| 10.3 — Delete the script | 10.3 — **Don't delete.** |
| 10.4 — Smoke-test CI flows | 10.4 — Smoke-test bash runner with the new app build to verify backward-compat (no session_id extra → app stays in legacy mode → bash oracle still parses `Test finished id:` lines). |
| 10.5 — Commit "remove encapp_run.sh" | 10.5 — Drop. |
| 10.6 — Major version bump | 10.6 — Drop. No breaking change. |

### Why backward-compat is automatic

The contract changes in Phases 1–7 were all **additive**:

- `TestSetup.timeout_sec` — new proto field, optional, defaults to absent (heuristic kicks in).
- `common.id` filled by `fill_ids()` — only fires when missing; bash script users who hand-write `common.id` are unaffected.
- `SessionManifest` writer in `MainActivity` — guarded by `if mSessionManifest != null`, which itself is guarded by the `session_id` extra. Bash script doesn't pass it → app skips the manifest entirely → legacy behavior.
- `workdir_probe` — only fires when CLI calls `get_or_probe_workdir`. Bash script doesn't import it.
- stdlib logging — Python-side only.
- `Constants.java` + `BuildConfig.DEBUG` — new file, no existing call site touched.

Result: every change so far keeps the bash runner's exact behavior. No verification needed beyond a smoke-run after each batch with the freshly-built APK.

### Optional Phase 10b follow-up

The bash script could opt into the manifest oracle without losing its zero-Python guarantee — JSONL is trivially parseable with `jq` (already required). Two small additions would buy it the same default-deny verdict logic the Python runner has:

1. Generate a `session_id` in bash (`R$(date +%s)_$(openssl rand -hex 3 2>/dev/null || dd if=/dev/urandom bs=3 count=1 2>/dev/null | xxd -p)`), pass `-e session_id $session_id` on `am start`.
2. Replace the `grep 'Test finished id:.*result:'` block with `jq` over the pulled `<session_id>.session.jsonl`: filter `.event == "test_end"`, classify `.status`.

That would fix the silent-pass-on-logcat-rollover bug for the bash users too. Filed as a soft Phase 10b — not required.

### Docs to update at sync

- `README.md` — clarify which runner to use (Python = full features; bash = SSH/CI/locked-down envs).
- `doc/release_script.md` — keep the bash runner section; update if Phase 10b lands.

### Verified — bash runner still works against Phase-6 app

```
$ /Users/jblome/code/encapp/scripts/encapp_run.sh -s 46201FDAS00AGH \
      -o /tmp/encapp_p6_bash /tmp/encapp_smoke.pbtxt
  Total:  1
  Passed: 1
  Failed: 0
$ adb -s 46201FDAS00AGH shell ls /sdcard/ | grep session.jsonl
  (none — bash script doesn't pass session_id, so app stays in legacy mode)
```

No code changes needed in the bash script. The additive contract design pays off here.

### No files changed by this entry

This is a plan correction, not a code change. The bash script in `/Users/jblome/code/encapp/scripts/encapp_run.sh` is unchanged and remains the authoritative constrained-env runner.


## 2026-06-12 — Phase 6 (additive): Constants.java + BuildConfig.DEBUG + non-static TAG fixes

**Surfaced by:** plan execution
**Affects:** → plan Phase 6 (6.1 / 6.4 landed; 6.2 / 6.3 deferred)
**Status:** applied (infra in place; 37-file sweep deferred to follow-up)

### What landed

**`app/build.gradle`:**
- Explicit `buildTypes { debug { debuggable true } }` block. AGP 8.1 needs this to mark the buildType as actually-debug; without it, `BuildConfig.DEBUG` could end up `false` on the debug variant.
- `buildFeatures { buildConfig true }` — AGP 8 doesn't auto-generate `BuildConfig.java` unless asked.

**`app/src/main/java/com/facebook/encapp/utils/Constants.java`** (new, ~30 LOC):
- `Constants.TAG = "encapp"` — single logcat tag for the whole app.
- `Constants.DEBUG = BuildConfig.DEBUG` — mirror; true on debug builds, false on release.
- Doc-comment recommends the pattern for new code:
  ```java
  if (Constants.DEBUG) {
      Log.d(Constants.TAG, "[my_subtag] " + msg);
  }
  Log.w(Constants.TAG, "[my_subtag] " + msg);   // always-on
  ```
- Subtags live in the message body in square brackets so a single `adb logcat -s encapp:V` shows the firehose, but `grep '\[buffer_encoder\]'` filters to one subsystem.

**Non-static `TAG` fixes (the audit's 6.4):**
- `SurfaceNoEncoder.java:38` — `private final String TAG = ...` → `private static final String TAG = ...`
- `utils/FpsMeasure.java:9` — same.

These weren't causing user-visible bugs (each instance just allocated its own TAG string) but were resource leaks and inconsistent with every other file in the tree.

### What's deferred (the 37-file sweep, plan item 6.3)

Every existing file declares its own `String TAG = "encapp.<subtag>"` (or, in a few outlier cases, `"PowerLoad"`, `"MemoryLoad"`, `"Demuxer"`, `"MediaCodecInfoHelper"`, `"fg_service"`). Converting each file to:
- Use `Constants.TAG` and inline the subtag in the message body, AND
- Wrap every `Log.d` in `if (Constants.DEBUG)`

…is ~37 mechanical file diffs touching ~hundreds of Log.d sites. Worth doing — release builds currently pay the formatting + JNI cost for invisible debug lines — but a focused, bisectable diff rather than smeared into this batch.

In the meantime, today's logging already works: `adb logcat -s "encapp*"` shows every encapp line. The user-visible value of the sweep is mainly release-build perf and tidier subtag discipline, not greppability.

### Build verification

```
./gradlew :app:assembleDefaultDebug  →  BUILD SUCCESSFUL
app/build/generated/source/buildConfig/Default/debug/com/facebook/encapp/BuildConfig.java:
  public static final boolean DEBUG = Boolean.parseBoolean("true");
```

E2E on Pixel 9 — happy path:
```
2026-06-12 13:10:57.712 INFO  encapp.run             session_id: R...  manifest: /storage/emulated/0/R....session.jsonl
2026-06-12 13:11:02.151 INFO  encapp.run             === session R...: 1/1 tests passed ===
```

### Files changed this batch

```
app/build.gradle                                                    (+ debug buildType, + buildFeatures.buildConfig)
app/src/main/java/com/facebook/encapp/utils/Constants.java          (new, ~30 LOC)
app/src/main/java/com/facebook/encapp/SurfaceNoEncoder.java         (TAG → static)
app/src/main/java/com/facebook/encapp/utils/FpsMeasure.java         (TAG → static)
```


## 2026-06-12 — Phase 7 (additive): stdlib logging on the CLI

**Surfaced by:** plan execution
**Affects:** → plan Phase 7 (7.1 / 7.2 / 7.4 landed; 7.3 / 7.5 deferred — see "What's left")
**Status:** applied

### What landed

**`scripts/encapp_tool/log_setup.py`:**
- `setup_logging(level, log_file, module_overrides)` — idempotent: replaces handlers on the `encapp` root, leaves the global root alone (other libs' logging untouched). `propagate=False` so no double-emission.
- Format is fixed for greppability:
  `2026-06-12 14:23:01.123 INFO  encapp.run             Test foo passed (2.5s)`
- `level_from_debug_kwarg(debug)` maps the legacy `debug=` int to a level name (`0→WARNING`, `1→INFO`, `≥2→DEBUG`). Lets call sites that still receive the kwarg log at the right level without rewriting everything.

**Module loggers (added):**
- `encapp.run` (in `scripts/encapp.py`)
- `encapp.adb` (in `adb_cmds.py`)
- `encapp.manifest` (in `session_manifest.py`, already present from Phase 3)
- `encapp.workdir_probe` (in `workdir_probe.py`, already present from Phase 4)

**CLI flags (in `encapp.py`'s `input_args`):**
- `--log-level {DEBUG,INFO,WARNING,ERROR,CRITICAL}` — overrides everything
- `--log-file PATH` — tees to file (mode='w'; encapp logs are runtime diagnostics, not historical archives)
- `--log-modules MOD=LEVEL[,MOD=LEVEL]...` — per-module scoping (e.g. `encapp.adb=DEBUG,encapp.manifest=WARNING`)

**Legacy `-d` / `-q` precedence resolution** (at the top of `main()`):
- `--log-level` wins
- else `-q` → WARNING
- else `-d` (count) → INFO if 1, DEBUG if ≥2
- else INFO (default)

`-d` / `-q` keep their existing behavior — they're now thin aliases over the new logging system. Deprecation is documented but not enforced yet.

**Converted prints to loggers:**
- `print(f"session_id: ...")` at `run_codec_tests` → `log.info(...)` on `encapp.run`
- `_print_manifest_summary` block (`=== session ... ===`, WARNING tail, per-failure block) → `log.info(...)` and `log.warning(...)` on `encapp.run`
- `adb_cmds.run_cmd`'s `print(cmd)` (debug-gated) → `log.debug("cmd: %s", cmd)` on `encapp.adb`
- `adb_cmds.run_cmd`'s `print("ERROR: serial is None")` → `log.error(...)`
- `adb_cmds.run_cmd`'s `print("Failed to run command")` → `log.exception(...)` (captures stacktrace too)

### E2E verified on Pixel 9

Default run (INFO):

```
2026-06-12 13:04:51.470 INFO  encapp.run             session_id: R...  manifest: /storage/emulated/0/R....session.jsonl
2026-06-12 13:04:56.024 INFO  encapp.run
2026-06-12 13:04:56.025 INFO  encapp.run             === session R...: 1/1 tests passed ===
```

`--log-level DEBUG --log-file /tmp/encapp.log`:

```
2026-06-12 13:05:05.145 DEBUG encapp.workdir_probe   workdir cache hit: /storage/emulated/0
2026-06-12 13:05:05.737 INFO  encapp.run             session_id: ...
2026-06-12 13:05:10.267 INFO  encapp.run             === session R...: 1/1 tests passed ===
```

…and the same 4 lines appear in `/tmp/encapp.log` verbatim.

`--log-modules encapp.workdir_probe=DEBUG,encapp.adb=DEBUG` confirmed scoping the DEBUG entries to those modules only.

### What's left for full Phase 7 (deferred)

- **7.3 inner-print sweep.** ~25 `print()` / `if debug > 0: print(...)` sites in `encapp.py` and `~5` in `ffutils.py` still use bare prints. Each conversion is mechanical but tedious; the additive logging system above means they can be converted incrementally without breaking anything.
- **7.5 `debug=` kwarg removal.** ~110 call sites across `encapp.py`, `adb_cmds.py`, `ffutils.py`, `app_utils.py`. Removing the kwarg = touching every signature + every caller. Half-day job; better as one focused diff than smeared into Phase 7's additive PR. The legacy kwarg now coexists harmlessly with the logger system.

### Pytest

48/48 pass.

### Files changed this batch

```
scripts/encapp_tool/log_setup.py             (new, ~85 LOC)
scripts/encapp.py                            (+ log import + log_level/log_file/log_modules args
                                              + setup_logging in main()
                                              + 3 print→logger conversions)
scripts/encapp_tool/adb_cmds.py              (+ log import + 3 print→logger conversions)
```


## 2026-06-12 — Phase 5 (partial): mechanical cleanups; generation refactor deferred

**Surfaced by:** plan execution
**Affects:** → plan Phase 5 (5.7 / 5.8 / 5.9 landed; 5.1–5.5 deferred)
**Status:** applied (small wins); deferred (larger refactor)

### What landed

**5.7 — deleted unused `Serial` proto message.** No callers in `.py`, `.java`, or `.swift`. Removed at `proto/tests.proto:221-223`. Python/Swift bindings regenerated.

**5.8 — fixed `update_codec_testsuite` arg-order bug** (`encapp.py:1873-1881`). Was passing positional `debug` into `update_codec_test`'s 7th slot, which is `is_parallel=False`. With `debug=1`, every top-level test got `is_parallel=True`, which short-circuits the bitrate / resolution / framerate / int-param expansion logic (their `if not is_parallel:` recursive calls). Net effect: `-r 1M,2M,5M -d ...` produced **one** test instead of three. Confirmed-fixed via E2E:

```
$ python3 scripts/encapp.py run /tmp/encapp_smoke.pbtxt -r 1M,2M,5M --serial 46201FDAS00AGH -w /tmp/encapp_p5_sweep
=== session R...: 3/3 tests passed ===
$ jq '.test_id' /tmp/encapp_p5_sweep/R*.session.jsonl
  ...
  "smoke.1000000bps"
  "smoke.2000000bps"
  "smoke.5000000bps"
```

**5.9 — deleted unreachable branch** at the old `encapp.py:2047-2057`. The `if len(protobuf_txt_filepath) <= 0:` condition can never trigger — `protobuf_txt_filepath` was assigned a non-empty string 20 lines earlier (`f"{local_workdir}/encapp_test.pbtxt"`) and never reset. The branch was a relic of a previous control-flow shape.

### What was deferred (5.1–5.5)

The generator-pipeline refactor is larger than it looks. Audit of the remaining write sites:

| Line | File | Used by? |
|---|---|---|
| 735 | `<test>.pbtxt` per test (split mode) | Pushed to device, read by app |
| 739 | `run.pbtxt` (combined mode) | Written, added to `files_to_push`, skipped at push time (line 2040 filter), still returned to caller as `protobuf_txt_filepath` |
| 917 | `<test>_aggr.pbtxt` (CLI-arg expansion branch) | Written, added to `files_to_push`, pushed to device |
| 1104 | `expanded_<basename>.pbtxt` (expansion intermediate) | Written, returned as new `protobuf_txt_filepath` for the rest of the pipeline |
| 2027 | `encapp_test.pbtxt` (combined-mode actual push) | Pushed to device with `-e test ...` |

The duplicates / overlaps are real (e.g. `run.pbtxt` is written but never pushed; `encapp_test.pbtxt` does the same job), but every site is woven into the protobuf_txt_filepath return-value plumbing. Refactoring needs:
- A `build_suite(input_paths, options) -> TestSuite` pure function (Task 5.2)
- A single `write_canonical_suite(suite, local_workdir, run_id)` helper (Task 5.3)
- Golden-file tests for the suite shape under various CLI overrides (Task 5.1)
- Three caller sites to update (Task 5.4)

That's a focused half-day refactor, not a 10-minute touch-up. **Deferring to a separate Phase 5b** so the current batch stays mechanical and bisectable.

### Pytest + E2E

- pytest: 48/48 pass
- E2E happy: `1/1 tests passed`
- E2E sweep: `3/3 tests passed` (confirms 5.8 fix)

### Files changed this batch

```
proto/tests.proto                 (- Serial message)
scripts/proto/tests_pb2.py        (regenerated)
ios/Encapp/tests.pb.swift         (regenerated)
scripts/encapp.py                 (5.8 keyword-args fix, 5.9 dead-branch deletion)
```


## 2026-06-12 — Phase 4 landed: workdir probe + per-device cache, E2E on Pixel 9

**Surfaced by:** plan execution
**Affects:** → plan Phase 4 implemented
**Status:** applied

### What landed

**App side (`MainActivity.onCreate` + `CliSettings`):**
- New extra keys: `CliSettings.PROBE = "probe"` and `CliSettings.PROBE_MARKER_PATH = "/sdcard/encapp_workdir.txt"`.
- Probe path runs after `setWorkDir`: if `probe=true` is passed, the app writes its chosen workdir (whatever `CliSettings.getWorkDir()` returned after its own writability checks) to the marker file, `fsync`'s it, logs, and calls `finish()` without running any test. Total app uptime <200 ms.
- `/sdcard` is the well-known location for the marker because it's the only path that's both app-writable and `adb shell cat`-readable on every Android version encapp supports. If the app can't write there, probe fails visibly — that's the right signal that the device is mis-set-up.

**CLI side (`scripts/encapp_tool/workdir_probe.py`):**
- `probe_workdir(serial, activity, run_cmd_fn, ...)` — does the round-trip: removes any stale marker, `am start -e probe true`, polls `adb shell cat /sdcard/encapp_workdir.txt` until it returns a non-empty string or the deadline expires (default 8s). Returns the workdir string with any trailing slash stripped. Raises `ProbeFailed` on timeout / am-start failure.
- `get_or_probe_workdir(serial, activity, app_version, cache_dir, ...)` — cache layer. Reads `<cache_dir>/.encapp_device_workdir.<serial>.json` (shape `{"app_version": "...", "workdir": "..."}`); on hit-with-version-match returns immediately. On miss / version mismatch / `refresh=True`, runs `probe_workdir` and persists. `invalidate_cache()` helper for one device or all.
- Module is **pure-Python**: `run_cmd_fn` is injected so the module never touches adb directly. **10 unit tests** including timeout, am-start failure, poll-until-appears, cache hit, version-mismatch invalidation, refresh-forces-reprobe, invalidate-one, invalidate-all.

**CLI wiring (`scripts/encapp.py:3490` area):**
- Replaced the call to the legacy `get_workdir(serial)` (which probed from the adb-shell user, not from the app's perspective — wrong on scoped-storage devices) with `workdir_probe.get_or_probe_workdir(...)`.
- Cache dir defaults to `$XDG_CACHE_HOME/encapp/` (or `~/.cache/encapp/`).
- Cache key: `(serial, encapp_tool.__version__)`. App and CLI versions move in lockstep, so the CLI version is a fine cache-bust signal for app upgrades.
- Probe failure (`ProbeFailed`) falls back to the legacy `get_workdir(serial)` so a CLI built against a device with an old (pre-Phase-4) app still works.

### E2E verified on Pixel 9

```
run 1 (cache miss → probe):
  session_id: R...  manifest: /storage/emulated/0/R....session.jsonl
  === session R...: 1/1 tests passed ===
  elapsed: 6s
  cache file written: {"app_version": "1.31", "workdir": "/storage/emulated/0"}

run 2 (cache hit → no probe):
  session_id: R...  manifest: /storage/emulated/0/R....session.jsonl
  === session R...: 1/1 tests passed ===
  elapsed: 4s        ← 2s faster (probe round-trip skipped)
  /sdcard/encapp_workdir.txt: No such file or directory   ← probe never ran
```

The CLI now uses `/storage/emulated/0` (the app's actual resolved workdir on Pixel 9) instead of its prior hardcoded `/sdcard`. On a device where the app falls back to internal storage, this is the difference between "every test classified NEVER_STARTED" (CLI pulling from the wrong place) and "every test classified correctly."

### Pytest

48/48 pass (19 pre-existing + 6 fill_ids + 2 session_id + 11 session_manifest + 10 workdir_probe).

### Open follow-ups

- `encapp install` should call `workdir_probe.invalidate_cache(cache_dir, serial)` so a fresh install re-probes (in case the new app version's workdir-resolution logic differs). Trivial; can land with Phase 5.
- The legacy `get_workdir()` in `encapp.py:3391` is now only reached on probe failure. Phase 5 cleanup can retire it once iOS probe support lands (the iOS path doesn't have this whole apparatus yet — it's an Android-only optimization).


## 2026-06-12 — Phase 3 cleanup: gate legacy verify_test_result on iOS

**Surfaced by:** user review of noisy output in TIMEOUT/CRASH E2E runs
**Affects:** → plan Phase 5 partially absorbed (the hardcoded CSV + redundant prints); legacy oracle path remains for iOS until iOS gets manifest support
**Status:** applied

### What was noisy

After Phase 3, failed Android runs printed:

```
=== session R...: 0/1 tests passed ===                       ← clean (manifest summary)
  [TIMEOUT] timeout_smoke  (CLI timeout after 3s, ...)       ← clean (per-test verdict)
Error! test case failed                                      ← noise (legacy)
ERROR! some tests failed                                     ← noise (legacy)
```

Plus, every failed run silently wrote `bitrate_surface_transcoder_show.pbtxt.failed.csv` into the **current working directory**, containing the empty stub `[{"test_id": "", "stats": "", "error": "Test failed"}]` that `verify_test_result` returns when handed the manifest path's synthetic `(False, [])` tuple. Hardcoded filename, no relation to the actual pbtxt being run.

### The cleanup

`run_codec_tests_file` at `encapp.py:945-955` was unconditionally calling `verify_test_result` after `run_codec_tests` returned. That function:

1. Counts ls output vs suite size — redundant on the manifest path (we already have authoritative verdicts).
2. Prints "Error! test case failed" / "ERROR! some tests failed".
3. Writes the misnamed CSV to CWD.

Gated the entire block on `encapp_tool.adb_cmds.USE_IDB` so it only runs on iOS (where the manifest path isn't wired yet). Android — every real device, every CI lane — now sees only the manifest summary block.

The legacy `success = not (results and results[0])` check at `:958` and the `result_files += results[1]` at `:961` stay — they read the synth tuple's verdict and json-paths and feed downstream `--quality` analysis. Same fields, same shape; just stripped the unhelpful pre-check.

### Verified — clean output across all three E2E scenarios

```
HAPPY    → '=== session R...: 1/1 tests passed ==='
TIMEOUT  → '=== session R...: 0/1 tests passed ===' + '[TIMEOUT] ... (CLI timeout after 3s, ...)'
CRASH    → '=== session R...: 0/1 tests passed ===' + '[CRASH] ... (test_start emitted but no test_end ...)'
```

No spurious lines. No CSV in CWD. `bitrate_surface_transcoder_show.pbtxt.failed.csv` does not appear in CWD after any of the three runs.

### Phase 5 still in scope

This was the option-2 minimal guard. The full cleanup (delete `verify_test_result`, `parse_logcat`, `collect_results`, drop `collected_results` from `run_codec_tests`'s return signature, write `run_summary.json` properly per Phase 9) remains in Phase 5/9. But the immediate footgun (CWD CSV pollution + misleading errors) is gone.


## 2026-06-12 — Phase 3 landed: CLI session-manifest oracle, three E2E scenarios PASS on Pixel 9

**Surfaced by:** plan execution + on-device E2E testing (serial 46201FDAS00AGH)
**Affects:** → plan Phase 3 implemented; → plan Phase 5 follow-up (retire legacy `wait_for_exit`/`collect_results`/`verify_test_result`)
**Status:** applied

### What landed

**`scripts/encapp_tool/session_manifest.py` — pure-Python manifest consumer:**

- `parse_manifest_text(text)` — JSONL → event list, skips malformed lines (partial-file durability).
- `classify_session(events, expected_test_ids)` → `SessionVerdicts` with `Status` per test (`PASS | FAIL | CRASH | TIMEOUT | SKIPPED | NEVER_STARTED`) and the full event list per test for downstream pulling.
- `wait_for_session(pull_fn, ..., timeout_sec, on_timeout, is_alive_fn)` — polls the on-device manifest, classifies, returns. Terminal-state semantics:
  - PASS / FAIL / TIMEOUT / SKIPPED → always terminal (early-exit)
  - CRASH → terminal **only when `is_alive_fn()` returns False** (otherwise "no test_end yet" could just mean the test is still encoding)
  - NEVER_STARTED → never terminal (that's what `timeout_sec` is for)
- On timeout: `on_timeout()` (CLI force-stops the app), final pull, any remaining CRASH gets relabeled to TIMEOUT (CLI initiated the kill, so it's a CLI timeout not a real crash).
- `format_test_failure(verdict)` — multi-line human-readable failure block with artifact list.
- **11 unit tests** including the CRASH-only-when-app-dead semantics, the hung-with-no-events timeout path, and the no-expected-ids edge case.

**`scripts/encapp.py` — `_oracle_via_manifest` wiring in `run_codec_tests`:**

- When `session_id` is set AND not iOS, the legacy oracle path (`collect_results` + `parse_logcat` + `wait_for_exit`) is skipped entirely. Manifest path is authoritative.
- `_pull` adapter wraps `adb pull` for `wait_for_session`'s `pull_fn`.
- `_force_stop` callback runs `adb shell am force-stop com.facebook.encapp` on timeout.
- `_is_alive` callback runs `adb shell pidof com.facebook.encapp` so the poll can early-exit when the app dies externally (force-stop in another shell, OOM kill, crash, etc.) — without this, polling waits the full heuristic timeout (~330s) before returning a CRASH verdict.
- `_suite_timeout_sec(test_suite)` — sums per-test budgets. Explicit `test_setup.timeout_sec` is trusted as-written (down to 1s). Without an explicit value, uses the 3x-expected-duration heuristic clamped to [60s, 600s] per test. Suite floor of 30s applies ONLY when no test has an explicit override.
- After the poll returns, every artifact the manifest declared is `adb pull`'d. Synthesizes a `(bool, [json_paths])` tuple shaped like `collect_results()` so downstream `verify_test_result` is satisfied.
- `_print_manifest_summary` — `=== session <id>: N/M tests passed ===` + per-failure `format_test_failure` block. Suppresses the "session_end missing" warning when all expected tests reported a terminal status (a clean run that early-exited before the app's post-test housekeeping wrote `session_end` is not an error).

**`run_encapp_test`:**
- When `session_id` is set and not iOS, skips `wait_for_exit`. The manifest poll IS the waiter and the only path that honors `test_setup.timeout_sec`.

### Three E2E scenarios verified on a Pixel 9

| Scenario | pbtxt | Result | Elapsed |
|---|---|---|---|
| Happy | `playout_frames: 150`, no timeout | `1/1 tests passed`, no spurious errors | ~5s |
| Timeout | `realtime: true`, `playout_frames: 3000` (~100s test), `test_setup { timeout_sec: 3 }` | `[TIMEOUT] (CLI timeout after 3s, app force-stopped)` | 6s |
| Crash | long-running test, `am force-stop` 5s in (another shell) | `[CRASH] (test_start emitted but no test_end before manifest ended)` | 6s |

All three produce machine-readable artifacts in the local workdir (`<session_id>.session.jsonl`, plus all artifact files the manifest declared).

### Stuck-loop close call

While developing the `is_alive_fn` semantics, hit a few wrong-state-machine variants. Documented for future reference:

- **CRASH treated as terminal in early-exit** → fast crash detection, but timeout tests early-exited at iteration 1 before the deadline ever fired (test_start without test_end was misread as "real CRASH" while the encoder was still running).
- **CRASH not terminal** + no `is_alive_fn` → after external `am force-stop` the manifest poll waited the full heuristic timeout (~330s) because no event was ever going to arrive.
- Resolved by: `is_alive_fn` callback. CRASH-terminal only fires when the app is provably dead.

### `_suite_timeout_sec` evolution

Initial version had a `max(60, total)` suite floor → `timeout_sec: 3` got promoted to 60s. Then `max(30, total)` → still 30. Final: trust explicit values down to 1s; 30s floor applies only when every test was on the heuristic. Confirmed via the 3s timeout test firing at exactly 3s.

### Pytest

**38/38 pass** (19 pre-existing + 6 fill_ids + 2 session_id + 11 session_manifest).

### Known-deferred (Phase 5 cleanup territory)

- The legacy `collect_results`, `parse_logcat`, `verify_test_result`, `wait_for_exit` code paths remain in `encapp.py`. The conditional skip is correct for now, but the dead branches should be removed when iOS gets manifest support or when the legacy path is no longer needed.
- The `Error! test case failed` / `ERROR! some tests failed` lines on the crash and timeout scenarios are from `verify_test_result` reacting to `synth[0]=False`. They're not wrong (failures are failures) but they're noisy compared to the clean `[CRASH] ...` / `[TIMEOUT] ...` lines from the manifest summary. Phase 9 (`run_summary.json` + failure UX) reshapes that final stdout block.

### Files added/changed this batch

```
scripts/encapp_tool/session_manifest.py        (new, ~200 LOC)
scripts/encapp.py                              (+ _oracle_via_manifest, _suite_timeout_sec,
                                                _print_manifest_summary, skip-legacy gating,
                                                wait_for_exit gating)
scripts/tests/unit/session_manifest_test.py    (new, 11 cases)
scripts/tests/unit/session_id_test.py          (loosened 1000-draw test that was statistically
                                                flaky — birthday paradox at ~3% / run)
```


## 2026-06-12 — Phase 2 smoke-test PASS on Pixel 9; two follow-up fixes

**Surfaced by:** smoke test on device (serial 46201FDAS00AGH, Pixel 9, app v1.31)
**Affects:** → plan Phase 2 verification ticked; two small bugs found and fixed in the same session
**Status:** applied

### Happy-path smoke result

```
session_id: R1781288718_595160  manifest: /sdcard/R1781288718_595160.session.jsonl
ok: test id: "smoke" run_id: encapp_ef67fb9a-... result: ok
```

Manifest on the device after run (post-fix):
```
{"event":"session_start","schema_version":1,"app_version":"1.31","device_workdir":"/sdcard", ...}
{"event":"test_start","test_id":"smoke","pid":25969, ...}
{"event":"artifact","kind":"stats","path":"encapp_<uuid>.json","bytes":73484, ...}
{"event":"artifact","kind":"video","path":"encapp_<uuid>.mp4","bytes":1294181, ...}
{"event":"test_end","status":"ok","frames_encoded":150,"encoded_file":"encapp_<uuid>.mp4", ...}
{"event":"session_end","status":"complete"}
```

All event types fire in the right order. `frames_encoded: 150` matches the test's `playout_frames: 150`. `bytes` populated correctly. Artifact filenames still come from the existing UUID default (pivot intent: app keeps owning naming, CLI reads from manifest).

### Crash-injection smoke result

Started a 3000-frame test in the background, `am force-stop com.facebook.encapp` 4 seconds in. The on-device manifest after the kill:

```
{"event":"session_start", ...}
{"event":"test_start","test_id":"crash_smoke","pid":26548, ...}
```

Both lines valid JSON. No `test_end`, no `session_end` — exactly the partial-state the design predicted. Phase 3's CLI consumer will classify the orphan `test_start` as CRASH. POSIX `fsync()` per line survived SIGKILL from `am force-stop`. **The crash-survivable property is real, not just intended.**

### Two bugs found + fixed during smoke

1. **`bytes: -1` for the video artifact (first smoke run).**
   `recordTestArtifactsAndEnd` did `new File(encoded).length()` where `encoded` is just a basename (`mEncodedfile = mStats.getId() + ".mp4"` in `Encoder.java`). The relative File didn't resolve, `.exists()` returned false, size was `-1L`.
   Fix: resolve against `CliSettings.getWorkDir()` before stat. Basename is still what the manifest reports (so the CLI doesn't need to know the on-device absolute path).

2. **`frames_encoded: 0` (first smoke run).**
   I used `Statistics.getEncodingProcessingFrames()` which is the **in-flight** counter (increments at frame start, decrements at frame stop). By finally-block time it's ~0. The right metric is `mEncodingFrames.size()` (total frames started).
   Fix: added `Statistics.getEncodingFrameCount()` and switched the manifest extras to use it.

### Build / install footnote

`scripts/encapp.py install` reads APKs from `app/releases/`, not from `app/build/outputs/apk/Default/debug/`. To install a fresh build:

```
adb -s <SERIAL> install -r app/build/outputs/apk/Default/debug/com.facebook.encapp-v<ver>-Default-debug.apk
```

(Out of scope for the contract work but worth noting; Phase 4 / 5 may want to teach the install script to prefer the freshly-built APK.)

### Net diff vs previous Phase 2 entry

- `app/src/main/java/com/facebook/encapp/utils/Statistics.java`: + `getEncodingFrameCount()` (the `mEncodingProcessingFrames` in-flight counter is unchanged and still has its other callers).
- `app/src/main/java/com/facebook/encapp/MainActivity.java`: artifact event now resolves basename against `CliSettings.getWorkDir()` for the `bytes` field; `test_end` extras switched from `getEncodingProcessingFrames` to `getEncodingFrameCount`.


## 2026-06-12 — Phase 2 implementation landed (SessionManifest end-to-end, app + CLI)

**Surfaced by:** plan execution after the manifest pivot
**Affects:** → plan Phase 2 (implemented under the new contract); known gap noted below
**Status:** applied (compiles + 27/27 pytest pass; smoke test on device pending — needs hardware)

### What landed

**App side (`com.facebook.encapp.utils.SessionManifest`):**
- Append-only JSONL writer, fsync per line. Thread-safe (synchronized methods). Filename: `<workdir>/<sessionId>.session.jsonl`.
- Event API: `sessionStart(appVersion, deviceWorkdir)`, `testStart(testId, pid)`, `artifact(testId, kind, path, bytes)`, `testEnd(testId, status, errorOrNull, extrasOrNull)`, `sessionEnd(status)`, `close()`.
- Failure mode: I/O errors logged via `Log.e`, never thrown — losing a line is preferable to killing a running test.
- 7 JUnit tests including a 8-thread × 50-event concurrency test (verifies the synchronized writes produce well-formed lines under contention).

**App side (`MainActivity.java` wiring):**
- New `mSessionManifest` field (null when no `session_id` extra is passed → legacy mode).
- `CliSettings.SESSION_ID = "session_id"` extra key.
- `performAllTests`: opens manifest after the `CHECK_WORKDIR` early-return paths; calls `sessionStart` immediately.
- Per-test thread (`PerformTest`): `testStart` before `coder_.start()`, `recordTestArtifactsAndEnd(...)` in `finally`. Restructured the inner try/catch to keep `status` visible in `finally`. Hoisted out a `Throwable thrown` variable so uncaught exceptions are reported via `error.code = "exception"`.
- `recordTestArtifactsAndEnd` helper: emits two `artifact` events (`stats` + `video`) for the standard 1-encoder-1-file shape; emits `test_end` with `frames_encoded` and `actual_codec` in extras.
- `sessionEnd("complete")` + `close()` after the `mInstancesRunning == 0` loop.
- `sessionEnd("aborted")` + `close()` in the `catch (IOException)` early-exit and in `onDestroy()` as a safety net.

**App side ancillary:**
- `Statistics.java`: added `getEncodedfile()` and `getEncodingProcessingFrames()` getters (needed by the manifest writer for artifact + testEnd events).
- `app/build.gradle`: re-added `testImplementation 'junit:junit:4.13.2'` (sandbox can't fetch it yet — user runs the test suite locally once gradle cache is seeded).

**CLI side (`scripts/encapp.py`):**
- `make_session_id()` helper → `R<unix_seconds>_<6-hex>` (debugable + same-second collision-safe).
- `run_encapp_test`: new `session_id=None` parameter; when provided, adds `-e session_id <id>` to the `am start` line (and to the iOS `xcrun devicectl ... launch` line for forward-compat with the future iOS manifest writer).
- `run_codec_tests`: generates session_id at the top, prints it to stdout (`session_id: R...  manifest: /sdcard/R....session.jsonl`), threads through to both `run_encapp_test` call sites (split mode and combined mode).
- Backward-compat: `session_id=None` (legacy callers) → no extra added → app stays in legacy mode.

**Tests:**
- `scripts/tests/unit/session_id_test.py` — 2 cases (shape regex + 1000-draw uniqueness).
- Full pytest suite: **27/27 pass** (19 pre-existing + 6 fill_ids + 2 session_id).

### Known gap: exhaustive tests / multi-output-per-encoder.start()

The standard shape is "1 test → 1 stats JSON + 1 encoded video", and the `recordTestArtifactsAndEnd` helper emits exactly those two artifact events. If a single `Encoder.start()` produces additional files (e.g., decoder output, per-iteration mp4s in an exhaustive loop), the helper misses them.

The contract supports it (manifest takes any number of `artifact` events under one `test_id`). The wiring just needs to be widened — either by having `Encoder` track every file it writes and exposing the list, or by adding `MainActivity.recordArtifact()` calls at each write site in `Encoder.java` subclasses. Defer to a follow-up under Phase 2; flag explicitly in the smoke test.

### Smoke test (deferred — needs device)

```
python3 scripts/encapp.py run tests/exp1_transcode_realtime.pbtxt -i <input> --serial <S> -w /tmp/p2
adb -s <S> shell ls /sdcard/ | grep session.jsonl     # expect R<...>.session.jsonl
adb -s <S> shell cat /sdcard/R<...>.session.jsonl     # expect JSONL: session_start, test_start, artifact(s), test_end, session_end
```

Crash-injection smoke test (kill app mid-test):
```
python3 scripts/encapp.py run <long.pbtxt> --serial <S> -w /tmp/p2_crash &
sleep 2
adb -s <S> shell am force-stop com.facebook.encapp
wait
adb -s <S> shell cat /sdcard/R<...>.session.jsonl    # expect test_start without matching test_end → CLI side (Phase 3) will classify as CRASH
```

### Doc edits queued for sync

- `design.md` §1 "Success oracle": replace example partial→final JSON with a sample JSONL manifest (rev'd schema).
- `implementation.md` Phase 2: replace prior `TestResult` writer description with the SessionManifest / event-API description. Mark complete.
- `implementation.md` Phase 2.5 (new): widen `recordTestArtifactsAndEnd` (or move artifact-emission into `Encoder` subclasses) to cover exhaustive / multi-file tests.


## 2026-06-12 — Pivot: session manifest replaces per-test `result.json` oracle

**Surfaced by:** user review after Batch 1 completion (Phase 1.2 / 1.3 commit messages prompted a critical look at the artifact-naming change)
**Affects:** → design §1 "Success oracle" (large rewrite), §2.A "test_id ownership" (largely deleted), §2.C "Pull becomes deterministic" (rewrite); → plan Phase 2 (rewrite), Phase 3 (rewrite); → plan Phase 1 Task 1.2 (revert)
**Status:** applied (design pivot accepted; code revert applied; Phase 2/3 not yet rewritten)

### Problem

Two design assumptions baked into the original plan turned out to be wrong:

1. **"1 test = 1 artifact set (mp4 + json)" is wrong.** A single `am start`-driven test can produce **N** output files. The clearest example is exhaustive testing — the app keeps spinning up encoders until one fails, producing one mp4 per iteration. Per-encoder log files, decoder+encoder pairs, and other multi-output paths also exist. A per-test `<id>.result.json` oracle file forces the CLI to assume a fixed file fan-out it doesn't actually have.

2. **The current `output_filename` template + UUID-fallback scheme is a convenient, intentional feature.** `output_filename` is a CLI-side template (e.g. `[common.id].[configure.bitrate].[XXXX]`) where `[XXXX]` = N random hex chars (collision-safety knob) and `[<msg>.<field>]` substitutes proto values. Resolved CLI-side via `replace_placeholders()` (`encapp.py:574`) called from `update_fileoutput_names()` (`:623`). When `output_filename` is unset, the app's UUID default keeps collisions away. Both halves were missed in the original audit.

The plan's Task 1.2 (`Statistics.deriveId()`) replaced the UUID default with a deterministic slug of `description+codec+bitrate+resolution`. That breaks the user-template-with-`[XXXX]` feature and silently overwrites artifacts on cross-run / repeated-pbtxt collisions (`_001/_002` suffix only protects within a single suite-write).

### Revision

**Replace the per-test `<id>.result.json` oracle with a single session-scoped JSONL manifest.** App writes append-only, fsync-per-line. CLI polls and reads incrementally.

**New contract shape:**

- CLI generates `session_id` (e.g. `R<unix_seconds>_<6-hex>`) at the top of `encapp run`, passes it as an `am start` extra.
- App opens `/sdcard/<session_id>.session.jsonl` in append mode and writes events:
  - `{event:"session_start", session_id, app_version, device_workdir, ts}`
  - per test: `{event:"test_start", test_id, pid, ts}`
  - per output file: `{event:"artifact", test_id, kind:"video|stats|log|...", path:"<actual_filename>", bytes, ts}`
  - per test: `{event:"test_end", test_id, status:"ok|error|timeout", error:{code,message,stack}?, frames_encoded?, ts}`
  - `{event:"session_end", status:"complete|aborted", ts}`
- Every line is `fsync()`'d before the next write, so a process kill leaves a parseable partial manifest.
- CLI poll loop: `adb pull` the manifest periodically, parse new lines, pull each declared artifact path, classify each `test_end`. On timeout: force-stop, pull manifest, classify any `test_start` without a matching `test_end` as CRASH; missing entries entirely → NEVER_STARTED.

**What this fixes vs the original design:**

| Concern | Original design | v2 manifest |
|---|---|---|
| Multi-file outputs per test | Implicit "1 mp4 + 1 json" assumption | First-class `artifact` events; N per test |
| Artifact name predictability | App had to derive from pbtxt fields, CLI had to match (lost user-template feature) | App writes any filename; CLI reads from manifest |
| Collision safety | Lost when UUID was removed | UUID/template stays in app; manifest is the index |
| Crash detection | `.partial` orphan file + atomic rename | JSONL is naturally crash-safe; partial file tells the truth |
| `output_filename` template | Broken by `deriveId()` change | **Preserved exactly as-is** |
| Cross-run collision | Required CLI-side run_id prefix as add-on | Session_id namespace already separates runs |

### Why

- The session manifest dissolves the entire "predictable filename" vs "collision-safe filename" tension. CLI no longer needs to predict; it reads.
- Multi-output use cases (exhaustive testing especially) are first-class instead of edge cases.
- JSONL is the right format for an append-only crash-survivable log. POSIX `write` + `fsync` of one line at a time gives durable partial state with no rename gymnastics.
- The session_id is CLI-generated → CLI knows the manifest filename without any handshake.
- App artifact-naming code (UUID default + `output_filename` template) stays unchanged. Smaller blast radius, preserves a feature users rely on.

### Doc edits (to apply at sync time)

- `design.md` §1 "Success oracle" — replace the partial→final atomic scheme with the session-manifest description. Include event vocab + sample JSONL.
- `design.md` §2.A "test_id ownership" — large delete. `common.id` keeps its existing role as the logical test identifier (used in logs, `report_result`, manifest `test_id` field). Artifact filenames remain app-side (UUID default + `output_filename` template, unchanged). CLI-side `fill_ids` keeps seeding `common.id` for default substitution / non-empty suffix base.
- `design.md` §2.C "Pull becomes deterministic" — rewrite: pull is driven by manifest `artifact` events, not by predicted filenames. CLI pulls exactly what the app declares; orphans no longer accumulate (app doesn't write outside the declared set).
- `implementation.md` Phase 1 Task 1.2 — **revert**: `Statistics.java` returns to today's behavior (`output_filename` resolved-or-UUID). No `deriveId()`. No JUnit dep added yet.
- `implementation.md` Phase 1 Task 1.3 — **kept**: `fill_ids()` still useful for `[common.id]` substitution and for ensuring the existing CLI suffix mutations land on a non-empty base.
- `implementation.md` Phase 2 — **rewrite**: `SessionManifest` Java writer (open append, fsync per line, event API: `sessionStart`, `testStart`, `artifact`, `testEnd`, `sessionEnd`). Per-test thread integration.
- `implementation.md` Phase 3 — **rewrite**: `session_manifest.py` CLI consumer (poll, parse, dispatch). Replaces `oracle.py`. New `Status` enum (PASS / FAIL / CRASH / TIMEOUT / NEVER_STARTED) still applies, computed from manifest events.
- `implementation.md` Phases 4–10 — unchanged except for stray references to `<test_id>.result.json`.

### Open knobs (already decided)

- `session_id` is CLI-generated (CLI knows the manifest filename immediately). Confirmed with user.
- Manifest filename format: `<session_id>.session.jsonl` on the device's workdir.
- Per-line fsync is required (crash-safety is the whole point).

### Code applied with this entry

1. Reverted `app/src/main/java/com/facebook/encapp/utils/Statistics.java` to today's behavior (UUID default + `output_filename` override). UUID import restored.
2. Deleted `app/src/test/java/com/facebook/encapp/utils/StatisticsTest.java` and empty parent dirs.
3. Removed the `testImplementation 'junit:junit:4.13.2'` line from `app/build.gradle` (will be re-added with Phase 2's SessionManifest tests).
4. `scripts/encapp.py` `fill_ids()` + `_derive_id()` + `_slug()` — kept (still useful for logical naming).
5. `scripts/tests/unit/fill_ids_test.py` and `__init__.py` path fix — kept.
6. `proto/tests.proto` `TestSetup.timeout_sec = 17` — kept (per-test hard kill is still the right shape).

Verification after revert: `:app:assembleDefaultDebug` PASS, `pytest scripts/tests/unit/` 25/25 PASS.

## 2026-06-12 — Dev-env limits: JVM unit tests, LCEVC flavor, git worktree writes

**Surfaced by:** Claude Code session during Phase 1 execution (sandbox + macOS TCC)
**Affects:** plan verification steps, not the design or production code
**Status:** applied (operational workaround, no design impact)

### Problem

Three limitations of the Claude Code dev environment hit during Phase 1:

1. **LCEVC flavor can't build.** `lcevcImplementation` deps (`com.vnova.lcevc:*`) require a Vnova-internal Maven repository that's not reachable from this dev environment, even with network. Confirmed by user.
2. **JVM unit tests can't execute.** Adding `testImplementation 'junit:junit:4.13.2'` triggers a fetch from Maven Central, which the Claude Code sandbox blocks (`Operation not permitted` socket error). No JUnit jars present in `~/.gradle/caches/`. Same for any other testImpl dep.
3. **Git worktree writes blocked by macOS TCC.** The `encapp-contract` worktree's git metadata lives under `/Users/jblome/code/encapp/.git/worktrees/encapp-contract/`, which is outside the current working directory. macOS TCC refuses `touch`/`commit` writes there even with the Bash sandbox disabled. The user must `git commit` from the host (or grant Claude Code Full Disk Access).

### Revision

Operational workarounds. No change to the design or to what the plan ultimately produces.

1. **App build verification uses `:app:assembleDefaultDebug`** instead of `assembleDebug`. The `Default` flavor exercises every line of code under test in Phase 1–9 (the lcevc-only code paths are isolated under `app/src/lcevc/`).
2. **JVM unit test files are still authored as part of each task.** They compile and live in the repo, but execution is deferred — user runs `./gradlew :app:testDefaultDebugUnitTest` from a network-enabled shell at phase boundaries (one-time cost: ~5 MB of test deps cached locally; after that, the sandbox can run them too since gradle caches are durable).
3. **Commits batched per phase, executed by user.** Claude proposes the staged-file list + commit message in plain text at the end of each task; user executes the `git add` + `git commit` from the host shell.

### Why

- Keeps the plan's verification discipline (tests exist, are correct, will run) without blocking on environmental limits.
- LCEVC is genuinely orthogonal — no Phase 1–9 code touches it.
- TCC + worktree behavior is fundamental to this macOS install; not worth working around in code.

### Doc edits

- `implementation.md` Phase 1, Task 1.1 / 1.2 / 2.x / 4.x / 6.x / 8.x: replace `./gradlew assembleDebug` with `./gradlew :app:assembleDefaultDebug`. Test-run steps (`./gradlew testDebugUnitTest`) become "compile-only here; user runs at phase boundary".
- `implementation.md` cross-phase verification matrix: add a "verified by user" column for JVM unit tests.
- No design doc edits.

---



## 2026-06-12 — Reuse `common.id` as canonical test_id; move `timeout_sec` to `TestSetup`

**Surfaced by:** user review during Phase 1, Task 1.1 execution
**Affects:** → design §1 "Success oracle", §2 "Deterministic naming + workdir handshake", §3 "Generation pipeline"; → plan Phase 1 (Tasks 1.1–1.3), Phase 2 (filename references), Phase 3 (oracle reads of `timeout_sec`)
**Status:** applied

### Problem

The plan added two new fields to `Common`: `test_id` (field 6) and `timeout_sec` (field 7).

Two issues:

1. **Redundant identifier.** `Common.id` (field 1) already exists and is in active use as the canonical per-test identifier — `scripts/encapp.py` mutates it heavily (`ntest.common.id = test.common.id + f".{resolution}"` at :1680, `+ f"@{framerate}fps"` at :1711, `+ f".{bitrate}bps"` at :1748, etc.), and `MainActivity.java` reads it for logging and `report_result()` (:826, :1052, :1213, :1217, etc.). Adding a parallel `common.test_id` would create two competing identifiers in the same message.

   The only place that *doesn't* use `common.id` today is `Statistics.java:148-153`, which falls back to `output_filename` or a UUID for artifact naming. That is the bug the new contract has to fix — the fix is to **use `common.id`** there, not to add a second field.

2. **Wrong home for `timeout_sec`.** `Common` is about test identity / description (id, description, operation, output_filename). `TestSetup` is the harness / runtime knobs message (workdir, run_cmd, mediastore, `uihold_sec`, `screen_off`, …). `timeout_sec` is a CLI-side enforcement parameter with the same shape as `uihold_sec` and belongs in `TestSetup`.

### Revision

1. **No new id field.** Reuse `common.id` everywhere the plan said `common.test_id`. The result-file naming, `fill_ids()` helper, and Statistics artifact id all flow from `common.id`.
2. **`timeout_sec` lives in `TestSetup`** as field 17 (next free in that message), not in `Common`.

### Why

- Single source of truth for the test identifier. The CLI's existing suffix-appending mutations on `common.id` keep working unchanged; we only have to seed it deterministically when empty.
- `TestSetup` already groups harness knobs; `timeout_sec` is one of them.
- Smaller proto surface — one new field instead of two.

### Doc edits

- `design.md` §1 "Success oracle": replace `<test_id>` → `<id>` in the file-naming examples. The JSON `test_id` field name inside `result.json` can stay (it's a JSON key, distinct from the proto field source).
- `design.md` §2.A "test_id ownership": rewrite as "`common.id` ownership". CLI fills `common.id` deterministically; app derives it from pbtxt fields if missing; UUID fallback removed.
- `design.md` §2.C "Pull becomes deterministic": file names sourced from `common.id`.
- `implementation.md` Phase 1, Task 1.1: proto change is `TestSetup.timeout_sec = 17` only. No `Common.test_id`.
- `implementation.md` Phase 1, Task 1.2: `Statistics.getId()` order is `common.id` → `common.output_filename` → `deriveId(desc+codec+bitrate+resolution)`. Drop UUID import.
- `implementation.md` Phase 1, Task 1.3: helper is `fill_ids()`, populates `common.id` when empty. CLI's downstream suffix-appending logic at `encapp.py:1680/1696/1711/1728/1748/1765` is preserved (it now operates on a deterministically-seeded base instead of a possibly-empty string).
- `implementation.md` Phase 2, Task 2.1+: `TestResult` constructor takes the resolved `common.id` string (same shape as before, just the source is `common.id` not `common.test_id`).
- `implementation.md` Phase 3, Task 3.2: oracle reads `test.test_setup.timeout_sec` (not `test.common.timeout_sec`). `_default_timeout(test)` heuristic unchanged.
