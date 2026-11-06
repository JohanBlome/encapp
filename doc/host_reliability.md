# Android host transfer reliability

## Opt-in paced, verified payloads

`--pace-file-transfers` selects Encapp's verified Android upload/download
path. Existing defaults and iOS transfers are unchanged. It requires a
POSIX host (Linux or macOS); the normal path does not import `fcntl` or
resize Linux pipes.

```sh
python scripts/encapp.py run <test.pbtxt> \
  --serial <device-identifier> \
  --device-workdir <owned-device-directory> \
  --mediastore <host-source-directory> \
  --pace-file-transfers \
  --file-transfer-limit 2097152 \
  --file-split-size 2097152 \
  -w <new-result-directory>
```

The transfer-limit and split-size flags alone are **not** rate limiters.
Enabled transfers admit at most 2 MiB/s of payload through host pipes,
with at most 4 KiB per pipe operation, and no accumulated idle/burst
credit. An advisory lock serializes enabled payload streams across
Encapp processes/devices for the same OS user and temporary directory.
This bounds host admission, **not** USB packets, adb's internal queues,
other users, unpaced programs, or the existing live logcat stream.

Each upload uses a unique remote partial and checks its size and MD5
before publishing it by rename. Downloads likewise check observed size,
extra/short bytes, process exit and MD5 before replacing the final host
file. MD5 detects accidental corruption; it is not an authenticity check.
`--fast-copy` does not bypass enabled payload verification. Already-present
sources/parts are reused only after size and hash comparison; pbtxt files
are always transferred afresh.

Large uploads use stable, hash-verified part names, unique local staging,
and ordered concatenation into a unique remote assembly partial. The
complete source size and hash must match before publication. Concatenate
and cleanup commands, including quoting, are bounded to 8192 UTF-8 bytes.
App-private workdirs use `run-as` throughout and require a debuggable app.

A failed payload stops without an automatic retry, reconnect or fallback.
The previous final file is not overwritten by a failed verification.
Local/remote partials and failed split staging are retained and their paths
are logged. An explicitly repeated invocation can reuse verified remote
parts; never assume retained bytes are valid. Only successful assembly is
followed by part cleanup. A cleanup failure is reported even if the final
file was already published. Inactivity/process-exit waits are bounded to
30 seconds; waiting for another enabled transfer's advisory lock is not.

## Growing manifest snapshots

Android manifest polling reads **exactly the byte prefix observed by stat**,
using a bounded remote `dd` producer. Appends after stat do not turn a valid
poll into an oversized immutable transfer. Short transfers, extra bytes and
nonzero exits still fail; a failed existing payload raises out of the poll
rather than being silently retried. A not-yet-present manifest remains a
normal poll miss. Successful snapshots atomically replace the previous
host snapshot and cannot claim a whole-file hash of the growing source.

This fix is used with or without `--pace-file-transfers`. The unpaced path
writes directly to a host partial, with no POSIX selectors or `fcntl`
requirement. iOS and ordinary immutable transfers keep their existing paths.
Only manifests request snapshot mode; completed artifact downloads remain
strict immutable transfers. A prefix may end in an incomplete JSONL record;
the existing parser skips it and waits for later events. Snapshot validity
is not proof of a completed session or a consistent arbitrary mutable file.

## Opt-in Android logcat controls

`--preserve-logcat` skips the pre-launch Android logcat clear, including
custom run-command launches. It does not disable the upstream live stream,
encoder-progress tracking, or fallback capture. Without this flag the
existing clear-before-launch behavior remains unchanged.

`--full-logcat` adds `<session_id>.android_logcat.full.txt`, a frozen
all-tag snapshot of the last 20000 **records** from the main/system buffers
with epoch timestamps (`logcat -d -b main -b system -v epoch -t 20000 '*:V'`).
This is a record bound, not a hard byte or physical-line bound: messages
may contain newlines. It may include pre-existing records and can wrap
away early records. Use session IDs and timestamps to identify the run.
No vendor logging, buffer resizing, extra buffers or device settings are
enabled. The flags are independent and ignored by the normal iOS path.

The additional snapshot does **not** replace or truncate the upstream
`<session_id>.android_logcat.txt` complete live threadtime capture. Its
unique remote stage is size/hash verified through the selected payload
path before publication, then removed only after success. Without pacing,
its host subprocesses need neither `fcntl` nor POSIX pipe selectors.
App-private staging reads logcat as shell and writes the stage via `run-as`;
this requires a debuggable app and a device shell supporting `pipefail`.
If staging, download or cleanup fails, the stage is retained and a warning
names the evidence. Diagnostic failure does not mask a run/shaping error
or become a new measurement-aborting gate. It does not prove complete logs.

## Host-only regressions

```sh
PYTHONPATH=scripts python -m pytest \
  scripts/tests/unit/adb_payload_test.py \
  scripts/tests/unit/growing_manifest_snapshot_test.py \
  scripts/tests/unit/logcat_options_test.py
```

These tests replace adb with owned local fixtures and fake subprocesses;
they never invoke an adb binary or inspect an attached device. They cover
short/extra/corrupt payloads, failure/timeout cleanup, partial writes,
large stderr, app-private paths, verified part reuse and bounded assembly.
A passing host suite is not a hardware throughput or compatibility result.
