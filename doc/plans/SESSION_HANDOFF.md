# Session handoff — encapp CLI↔app contract replay

You're picking up an encapp CLI↔app contract overhaul where **all the substantive work is done and verified end-to-end on a Pixel 9** (serial `46201FDAS00AGH`), but the work is **uncommitted** (29 modified/new files in this worktree). The plan is to replay the work as ~31 per-phase commits with verification at each boundary, then merge to master and run `release.sh 1.32`.

## State

| Where | What |
|---|---|
| `/Users/jblome/code/encapp-contract/` (THIS dir) | Working tree — all the work, uncommitted |
| `/tmp/encapp-contract-snapshot/` | Verified target state — restore-from if anything goes wrong |
| Branch | `encapp-cli-app-contract`, HEAD = `32e6acf` (master's tip) |
| Full narrative | `doc/plans/2026-06-12-encapp-cli-app-contract-revisions.md` (~15 entries, one per phase) |
| Original plan | `doc/plans/2026-06-12-encapp-cli-app-contract-implementation.md` |
| Original design | `doc/plans/2026-06-12-encapp-cli-app-contract-design.md` |

## First thing: verify writes work

Before anything, in a fresh Claude session run:

```bash
touch /Users/jblome/code/encapp/.git/worktrees/encapp-contract/_perm_test && \
  rm /Users/jblome/code/encapp/.git/worktrees/encapp-contract/_perm_test && \
  echo "write OK"
```

- **"write OK"** → proceed with autonomous replay.
- **Operation not permitted** → Claude Code can't write to the worktree's gitdir. Either (a) relaunch from `~/code/` so both dirs are under the launch path, or (b) convert worktree to a standalone clone:

  ```bash
  ls /tmp/encapp-contract-snapshot/ | head    # confirm snapshot intact
  cd /Users/jblome/code/encapp
  git worktree remove --force /Users/jblome/code/encapp-contract
  cd /Users/jblome/code
  git clone encapp encapp-contract
  cd encapp-contract
  git checkout -b encapp-cli-app-contract
  rsync -a --exclude=.git /tmp/encapp-contract-snapshot/ ./
  git status -s   # should match the pre-conversion state
  ```

## Replay protocol

For each phase in the table below:

1. **Check current state:** `git log --oneline -3` (last commit subject tells you the most recent phase).
2. **Apply the phase's edits.** The revisions log has the per-phase narrative. For new files, look in the snapshot:
   ```bash
   cat /tmp/encapp-contract-snapshot/<path>
   ```
   For modified files, the snapshot has the final version; you need to apply only that phase's portion. Most phases touch disjoint files — encapp.py and MainActivity.java are the exceptions (touched across many phases — those bundle into single big commits if you can't surgically separate hunks).
3. **Verify** with the column-3 command. If it fails, stop and debug — don't commit a broken state.
4. **Commit** with the prepared message in the revisions log (search the doc for "Suggested commit" or the phase number).
5. Mark the phase done and move to the next.

## Phase table (31 commits in order)

| # | Phase | Primary files | Verification |
|---|---|---|---|
| 1 | P1.1 proto | `proto/tests.proto`, `scripts/proto/tests_pb2.py`, `ios/Encapp/tests.pb.swift` | `proto/Makefile && ./gradlew :app:assembleDefaultDebug` |
| 2 | P1.3 fill_ids | `scripts/encapp.py` (fill_ids/_derive_id/_slug + wire), `scripts/tests/unit/__init__.py`, `scripts/tests/unit/fill_ids_test.py` | `pytest scripts/tests/unit/ — 25 PASS` |
| 3 | P2.1a SessionManifest writer | `app/src/main/java/com/facebook/encapp/utils/SessionManifest.java`, `app/src/test/java/com/facebook/encapp/utils/SessionManifestTest.java`, `app/build.gradle` (+ testImpl junit + buildConfig + debug buildType) | `./gradlew :app:assembleDefaultDebug` |
| 4 | P2.1b Statistics getters | `app/src/main/java/com/facebook/encapp/utils/Statistics.java` (+ getEncodedfile, getEncodingFrameCount) | `./gradlew :app:assembleDefaultDebug` |
| 5 | P2.2 MainActivity manifest wiring + CliSettings.SESSION_ID | `app/src/main/java/com/facebook/encapp/MainActivity.java` (manifest open/close + per-test thread), `CliSettings.java` (+ SESSION_ID) | `./gradlew :app:assembleDefaultDebug` |
| 6 | P2.3 CLI make_session_id + threading | `scripts/encapp.py` (make_session_id, run_encapp_test session_id kwarg), `scripts/tests/unit/session_id_test.py` | `pytest 27 PASS`; install APK; E2E on Pixel 9 → manifest appears at `/sdcard/R<...>.session.jsonl` |
| 7 | P3.1 session_manifest module | `scripts/encapp_tool/session_manifest.py`, `scripts/tests/unit/session_manifest_test.py` | `pytest scripts/tests/unit/session_manifest_test.py — 11 PASS` |
| 8 | P3.2 _oracle_via_manifest wiring | `scripts/encapp.py` (_oracle_via_manifest, _suite_timeout_sec, _print_manifest_summary, _is_alive, skip-wait_for_exit-when-session_id) | E2E on Pixel 9: happy `1/1 passed`, crash (force-stop) `[CRASH]`, timeout (`timeout_sec:3` pbtxt) `[TIMEOUT]` |
| 9 | P3-cleanup verify_test_result gated on iOS | `scripts/encapp.py` | E2E: no `bitrate_surface_transcoder_show.pbtxt.failed.csv` in CWD on failure |
| 10 | P4.1 App probe mode | `MainActivity.java` (probe path), `CliSettings.java` (+ PROBE, PROBE_MARKER_PATH) | Manual: `adb shell am start -e probe true com.facebook.encapp/.MainActivity && adb shell cat /sdcard/encapp_workdir.txt` |
| 11 | P4.2 workdir_probe module | `scripts/encapp_tool/workdir_probe.py`, `scripts/tests/unit/workdir_probe_test.py` | `pytest workdir_probe_test.py — 10 PASS` |
| 12 | P4.3 Wire probe into encapp.py | `scripts/encapp.py` (replace legacy get_workdir in main with workdir_probe) | E2E run 1 (cache miss) + run 2 (cache hit, no marker on device) |
| 13 | P5.7 Delete Serial proto + naming-pitfall comment | `proto/tests.proto`, regen | `./gradlew :app:assembleDefaultDebug` |
| 14 | P5.8 arg-order fix + dead branch | `scripts/encapp.py` (update_codec_testsuite kwarg fix + remove `if len(protobuf_txt_filepath) <= 0` branch) | E2E `-r 1M,2M,5M` produces 3 distinct test_ids in manifest |
| 15 | P5b Delete dead pbtxt writes | `scripts/encapp.py` (kill run.pbtxt + _aggr.pbtxt writes) | E2E happy + sweep; `ls /tmp/.../*.pbtxt` lacks run.pbtxt + _aggr.pbtxt |
| 16 | P5c In-memory expansion | `scripts/encapp.py` (test_suite= kwarg, retire create_tests_from_definition_expansionPath) | E2E: workdir down to 2 pbtxts (input copy + encapp_test.pbtxt) |
| 17 | P6.1 Constants + buildConfig | `app/src/main/java/com/facebook/encapp/utils/Constants.java`, `app/build.gradle` (buildFeatures.buildConfig + debug buildType) | `./gradlew :app:assembleDefaultDebug`; `find app/build -name BuildConfig.java` → DEBUG=true |
| 18 | P6.4 Non-static TAG | `app/src/main/java/com/facebook/encapp/SurfaceNoEncoder.java`, `utils/FpsMeasure.java` | `./gradlew :app:assembleDefaultDebug` |
| 19 | P7.1 log_setup + CLI flags | `scripts/encapp_tool/log_setup.py`, `scripts/encapp.py` (--log-level/--log-file/--log-modules args + setup_logging at top of main + log.info on session_id/summary lines) | E2E `--log-level DEBUG --log-file /tmp/x.log` → structured lines in file |
| 20 | P7.2 adb_cmds logger | `scripts/encapp_tool/adb_cmds.py` | `pytest 27 PASS` still |
| 21 | P7b print sweep | `scripts/encapp.py` (~15 print → logger in wait_for_exit, run_encapp_test, collect_results, parse_logcat, run_codec_tests_file, verify_test_result, abort_test) | E2E happy: clean INFO output |
| 22 | P7c drop debug= from wait_for_exit + collect_results | `scripts/encapp.py` (signatures + callers updated) | `pytest 27 PASS` + E2E happy |
| 23 | P8.1a TestLogWriter | `app/src/main/java/com/facebook/encapp/utils/TestLogWriter.java` | `./gradlew :app:assembleDefaultDebug` |
| 24 | P8.1b MainActivity TestLogWriter wiring + log artifact | `MainActivity.java` (per-test thread opens/writes/closes TestLogWriter + declares kind:"log" artifact) | E2E: `<test_id>.log` in local workdir + manifest declares log artifact |
| 25 | P8.2 CLI logcat dump | `scripts/encapp.py` (after wait_for_session, `adb shell "logcat -d \| grep -E ' encapp(\.\|:)'"` → `<session>.android_logcat.txt`) | E2E: file populated with `encapp.*` sub-tag lines |
| 26 | P9.1 run_summary module | `scripts/encapp_tool/run_summary.py`, `scripts/tests/unit/run_summary_test.py` | `pytest run_summary_test.py — 6 PASS` |
| 27 | P9.2 Wire run_summary | `scripts/encapp.py` (build_summary + write_run_summary + log format_summary_line at end of _oracle_via_manifest) | E2E happy: `1/1 passed. Full report: ...`; timeout: `0/1 passed. Failed: ...` |
| 28 | P10b encapp_run.sh v2 | `scripts/encapp_run.sh` (now-tracked; manifest oracle opt-in + array-iteration fix) | Bash E2E: `1/1 Passed` + all 3 declared artifacts pulled |
| 29 | Docs | `doc/plans/` (revisions log + plan + design + this handoff) | n/a |
| 30 | Final verification | `diff -r --brief /tmp/encapp-contract-snapshot/ ./` (excluding .git, build) | Should be empty (or only the SESSION_HANDOFF.md itself, depending on order) |
| 31 | Merge + release | `cd /Users/jblome/code/encapp && git checkout master && git merge --no-ff encapp-cli-app-contract && ./scripts/release.sh 1.32` | release.sh runs system tests + bumps version |

## Practical strategy for `encapp.py`

`scripts/encapp.py` is the only file touched by many phases (1.3, 3.2, 3-cleanup, 4.3, 5.8, 5b, 5c, 7.1, 7b, 7c, 8.2, 9.2 — 12 phases). Splitting it surgically per-phase via `git add -p` is doable but slow. Pragmatic choices:

- **Best fidelity (slow):** for each phase, use `git add -p scripts/encapp.py` and select only the hunks belonging to that phase. Requires knowing what each hunk corresponds to — the revisions log helps.
- **Acceptable fidelity (fast):** bundle all encapp.py changes into one commit at phase P9.2 (last phase that touches it) with a message listing every change theme. The earlier phases that touched encapp.py commit only their non-encapp.py files; the encapp.py portion is deferred. This loses bisection on encapp.py specifically but keeps per-phase commits clean for the other files.

User said "we will need to rerun all tests and verifications when replaying the commits" — so they're committed to per-phase verification. Discuss the encapp.py strategy with them when you start.

## After the replay

```bash
cd /Users/jblome/code/encapp
git checkout master
git merge --no-ff encapp-cli-app-contract \
  -m "Merge encapp-cli-app-contract: CLI<->app contract overhaul"

export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export GRADLE_OPTS="-Djava.net.preferIPv4Stack=true -Djava.net.preferIPv4Addresses=true"
export ANDROID_SERIAL=46201FDAS00AGH
./scripts/release.sh 1.32
```

`release.sh` is interactive — it'll prompt for documentation confirm, proceed-with-release, commit-staged-changes, use-default-message, push-to-remote.

## What "1.32" represents

- Current version: 1.31 (set in `app/build.gradle`)
- Next: 1.32 — minor bump, justified by substantial backward-compatible feature batch (additive proto, session manifest, workdir probe, run_summary, per-test logs, etc.).
- Not 2.0 — no breaking changes; every contract addition is gated on the new `session_id` extra, legacy callers fall through.
