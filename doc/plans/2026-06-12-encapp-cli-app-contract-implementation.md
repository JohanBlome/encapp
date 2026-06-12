# Encapp CLI ↔ App Contract — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use `10x-mm-engineer:executing-plans` to implement this plan task-by-task.

**Goal:** Replace the implicit, logcat-mediated CLI↔app contract with a structured, default-deny contract whose results can be trusted. See companion design doc.

**Design doc:** `doc/plans/2026-06-12-encapp-cli-app-contract-design.md`

**Architecture:** App writes a structured per-test `<test_id>.result.json` (partial→final atomic). CLI reads it as the success oracle. test_id, workdir, and pull set become deterministic. Generation collapses to one canonical pbtxt. Logging gets a framework on both sides.

**Domain:** codec testing (encapp standalone — Python CLI + Android app, single repo)

**Repo:** `/Users/jblome/code/encapp`

**Languages:** Python 3 (CLI), Java (Android app), protobuf (schema)

**Build / test commands:**
```bash
# Build the app (one-time setup, then on demand)
cd /Users/jblome/code/encapp/app && ./gradlew assembleDebug

# Install on device
python3 /Users/jblome/code/encapp/scripts/encapp.py install --serial <SERIAL>

# Python unit tests (we'll add pytest as we go)
cd /Users/jblome/code/encapp && python3 -m pytest scripts/tests/

# Smoke run after a change
python3 scripts/encapp.py run tests/exp1_transcode_realtime.pbtxt -i <input.mp4> --serial <SERIAL> -w /tmp/encapp_smoke
```

**Devices for verification:** at least one of S25+ (`R5CXC2ZH3DR`), Pixel 9 Pro (`48031FDAS000F6`), Pixel 10 Pro (`57080DLCH001A2`) — see encapp skill for the matrix. Run on whichever is connected.

**Release coupling rules (CRITICAL — every phase notes this):**

| Phase | App APK change? | CLI change? | Must ship together? |
|---|---|---|---|
| 1. Proto fields + test_id derivation | yes | yes (writer of test_id) | **YES — same release**. Old app + new CLI: new pbtxt fields ignored, OK. New app + old CLI: app derives test_id from desc, OK. Make sure the proto change is additive only. |
| 2. App `writeResult()` | yes | no | App-only. Old CLI ignores new file, but new file is harmless. Safe to ship first. |
| 3. CLI oracle consumer | no | yes | CLI-only IF phase 2 already shipped. Otherwise behavior degrades to "every test fails." Ship after phase 2 is in users' hands. |
| 4. Workdir handshake | yes (small marker file) | yes (probe + cache) | **YES — same release**. Backward path: if marker missing, CLI falls back to today's probe. |
| 5. Generation cleanup | no | yes | CLI-only. App reads whatever pbtxt is pushed. |
| 6. Java logging cleanup | yes | no | App-only. No protocol change. |
| 7. Python logging cleanup | no | yes | CLI-only. |
| 8. Per-test log files | yes (app writer) + yes (CLI pull) | both | **YES — same release**. CLI pulling a non-existent file is annoying noise; defer CLI side until app writer ships. |
| 9. `run_summary.json` + failure UX | no | yes | CLI-only. |
| 10. Remove `encapp_run.sh` | no | yes (delete file + docs) | CLI-only. Bump major version. |

---

## Phase 1 — Proto fields + test_id derivation

**Goal:** `common.test_id` and `common.timeout_sec` exist; app derives `test_id` from pbtxt fields when missing; UUID fallback removed.

**Release coupling:** App + CLI ship together. Additive proto change keeps old pbtxts working.

### Task 1.1 — Add proto fields

**Files:**
- Modify: `/Users/jblome/code/encapp/proto/tests.proto` (Common message, line 67-78)

**Step 1: Add the two fields** (use proto2 field numbers after existing fields)

```protobuf
message Common {
  optional string id = 1;
  optional string description = 2;
  optional string operation = 3;
  optional string start = 4;
  // template or specific name for output file(s)
  // ...existing comment...
  optional string output_filename = 5;
  // Stable per-test identifier. Used for naming all result artifacts
  // (<test_id>.result.json, <test_id>.json, <test_id>.{mp4,webm,...},
  // <test_id>.log). If unset, the app derives it from the pbtxt fields.
  optional string test_id = 6;
  // Hard timeout for this test, in seconds. If the test does not complete
  // within this window, the CLI force-stops the app and marks the test
  // status as "timeout". If unset, CLI uses a heuristic default.
  optional int32 timeout_sec = 7;
}
```

**Step 2: Regenerate Python proto bindings**

```bash
cd /Users/jblome/code/encapp/proto && make
```

Expected: regenerates `tests_pb2.py` referenced by `scripts/encapp.py`.

**Step 3: Verify Android Studio regenerates Java classes on next build**

```bash
cd /Users/jblome/code/encapp/app && ./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL. New `Common.getTestId()` / `Common.hasTestId()` / `Common.getTimeoutSec()` accessors exist in `app/build/generated/source/proto/.../com/facebook/encapp/proto/Common.java`.

**Step 4: Commit**

```bash
cd /Users/jblome/code/encapp && git add proto/tests.proto && git commit -m "proto: add common.test_id and common.timeout_sec

Additive fields for the new CLI<->app contract. test_id pins all
result-artifact filenames; timeout_sec is the per-test hard kill
deadline.

Test Plan:
- make in proto/ regenerates tests_pb2.py
- ./gradlew assembleDebug builds the app with new accessors"
```

### Task 1.2 — App: derive test_id, drop UUID fallback (with test)

**Files:**
- Modify: `/Users/jblome/code/encapp/app/src/main/java/com/facebook/encapp/utils/Statistics.java:148-153`
- Create: `/Users/jblome/code/encapp/app/src/test/java/com/facebook/encapp/utils/StatisticsTest.java`

**Step 1: Write the failing test first**

```java
// app/src/test/java/com/facebook/encapp/utils/StatisticsTest.java
package com.facebook.encapp.utils;

import com.facebook.encapp.proto.Common;
import com.facebook.encapp.proto.Configure;
import com.facebook.encapp.proto.Input;
import com.facebook.encapp.proto.Test;
import org.junit.Test;
import static org.junit.Assert.*;

public class StatisticsTest {
    @org.junit.Test
    public void testIdFromExplicitField() {
        Test t = Test.newBuilder()
            .setCommon(Common.newBuilder().setTestId("my_explicit_id"))
            .build();
        Statistics s = new Statistics("desc", t);
        assertEquals("my_explicit_id", s.getId());
    }

    @org.junit.Test
    public void testIdFromOutputFilenameWhenTestIdMissing() {
        Test t = Test.newBuilder()
            .setCommon(Common.newBuilder().setOutputFilename("custom_name"))
            .build();
        Statistics s = new Statistics("desc", t);
        assertEquals("custom_name", s.getId());
    }

    @org.junit.Test
    public void testIdDerivedFromPbtxtFields() {
        Test t = Test.newBuilder()
            .setCommon(Common.newBuilder().setDescription("johnny"))
            .setConfigure(Configure.newBuilder()
                .setCodec("c2.qti.hevc.encoder")
                .setBitrate("1Mbps")
                .setResolution("1280x720"))
            .build();
        Statistics s = new Statistics("desc", t);
        // Deterministic slug, no UUID
        assertFalse(s.getId().startsWith("encapp_"));
        assertTrue(s.getId().contains("johnny"));
        assertTrue(s.getId().contains("1280x720"));
    }
}
```

**Step 2: Run test, verify it fails**

```bash
cd /Users/jblome/code/encapp/app && ./gradlew testDebugUnitTest --tests "com.facebook.encapp.utils.StatisticsTest"
```

Expected: FAIL — `testIdDerivedFromPbtxtFields` fails (current code returns `"encapp_" + UUID`).

**Step 3: Implement derivation** in `Statistics.java`, replacing lines 148-153:

```java
// if no output filename use uuid
if (mTest.hasCommon() && mTest.getCommon().hasTestId()) {
    mId = mTest.getCommon().getTestId();
} else if (mTest.hasCommon() && mTest.getCommon().hasOutputFilename()) {
    mId = mTest.getCommon().getOutputFilename();
} else {
    mId = deriveTestId(mTest);
}
```

Add helper (in same class, private static):

```java
private static String deriveTestId(Test test) {
    StringBuilder sb = new StringBuilder();
    if (test.hasCommon() && test.getCommon().hasDescription()) {
        sb.append(slug(test.getCommon().getDescription()));
    } else {
        sb.append("encapp");
    }
    if (test.hasConfigure()) {
        if (test.getConfigure().hasCodec())      sb.append("_").append(slug(test.getConfigure().getCodec()));
        if (test.getConfigure().hasBitrate())    sb.append("_").append(slug(test.getConfigure().getBitrate()));
        if (test.getConfigure().hasResolution()) sb.append("_").append(slug(test.getConfigure().getResolution()));
    }
    return sb.toString();
}

private static String slug(String s) {
    return s.replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_+|_+$", "");
}
```

Also: delete the `import java.util.UUID;` line at top.

**Step 4: Re-run test, verify pass**

```bash
cd /Users/jblome/code/encapp/app && ./gradlew testDebugUnitTest --tests "com.facebook.encapp.utils.StatisticsTest"
```

Expected: PASS, all 3 tests.

**Step 5: Build app**

```bash
cd /Users/jblome/code/encapp/app && ./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL.

**Step 6: Commit**

```bash
git add app/src/main/java/com/facebook/encapp/utils/Statistics.java \
        app/src/test/java/com/facebook/encapp/utils/StatisticsTest.java
git commit -m "encapp: derive test_id from pbtxt fields, drop UUID fallback

Statistics.getId() now resolves to common.test_id, falling back to
common.output_filename, falling back to a deterministic slug of
description + codec + bitrate + resolution. Removes UUID randomness
that prevented the CLI from predicting result file names.

Test Plan:
- StatisticsTest (3 cases) PASS
- ./gradlew assembleDebug PASS"
```

### Task 1.3 — CLI: fill `common.test_id` before push

**Files:**
- Modify: `/Users/jblome/code/encapp/scripts/encapp.py` — find the generation pipeline (see audit: `update_codec_testsuite` at :1785, write site at :1924-1932)
- Create: `/Users/jblome/code/encapp/scripts/tests/test_test_id.py`

**Step 1: Write failing pytest first**

```python
# scripts/tests/test_test_id.py
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from proto import tests_pb2
from encapp import fill_test_ids  # to be created

def test_fills_missing_test_id():
    suite = tests_pb2.TestSuite()
    t = suite.test.add()
    t.common.description = "johnny"
    t.configure.codec = "c2.qti.hevc.encoder"
    t.configure.bitrate = "1Mbps"
    t.configure.resolution = "1280x720"
    fill_test_ids(suite)
    assert suite.test[0].common.test_id != ""
    assert "johnny" in suite.test[0].common.test_id

def test_preserves_existing_test_id():
    suite = tests_pb2.TestSuite()
    t = suite.test.add()
    t.common.test_id = "my_explicit_id"
    fill_test_ids(suite)
    assert suite.test[0].common.test_id == "my_explicit_id"

def test_disambiguates_duplicates_with_run_index():
    suite = tests_pb2.TestSuite()
    for _ in range(3):
        t = suite.test.add()
        t.common.description = "johnny"
        t.configure.codec = "c2.qti.hevc.encoder"
        t.configure.bitrate = "1Mbps"
    fill_test_ids(suite)
    ids = [t.common.test_id for t in suite.test]
    assert len(set(ids)) == 3, f"duplicate test_ids: {ids}"
```

**Step 2: Run test, expect ImportError on `fill_test_ids`**

```bash
cd /Users/jblome/code/encapp && python3 -m pytest scripts/tests/test_test_id.py -v
```

Expected: FAIL with `ImportError: cannot import name 'fill_test_ids'`.

**Step 3: Add `fill_test_ids` to `scripts/encapp.py`**

Add near the top of `encapp.py` (after imports, before `default_values`):

```python
def fill_test_ids(suite):
    """Assigns common.test_id to every Test that doesn't have one.

    Matches the algorithm in Statistics.deriveTestId() on the app side,
    plus a numeric suffix to disambiguate duplicates within a suite.
    """
    seen = {}
    for test in suite.test:
        if test.common.test_id:
            continue
        base = _derive_test_id(test)
        idx = seen.get(base, 0)
        seen[base] = idx + 1
        test.common.test_id = base if idx == 0 else f"{base}_{idx:03d}"
        # Recurse into Parallel siblings
        for sub in test.parallel.test:
            if not sub.common.test_id:
                sub_base = _derive_test_id(sub)
                sub_idx = seen.get(sub_base, 0)
                seen[sub_base] = sub_idx + 1
                sub.common.test_id = sub_base if sub_idx == 0 else f"{sub_base}_{sub_idx:03d}"

def _derive_test_id(test):
    import re
    parts = []
    parts.append(_slug(test.common.description) if test.common.description else "encapp")
    if test.configure.codec:      parts.append(_slug(test.configure.codec))
    if test.configure.bitrate:    parts.append(_slug(test.configure.bitrate))
    if test.configure.resolution: parts.append(_slug(test.configure.resolution))
    return "_".join(parts)

def _slug(s):
    import re
    return re.sub(r"^_+|_+$", "", re.sub(r"[^A-Za-z0-9]+", "_", s))
```

**Step 4: Wire into the generation pipeline.** Find the suite-write site at `encapp.py:1924-1926` (`with open(protobuf_txt_filepath, "w") as f: f.write(text_format.MessageToString(test_suite))`). Insert immediately before:

```python
fill_test_ids(test_suite)
```

**Step 5: Re-run pytest**

```bash
cd /Users/jblome/code/encapp && python3 -m pytest scripts/tests/test_test_id.py -v
```

Expected: PASS, 3 tests.

**Step 6: Smoke-test on device** (with app from Task 1.2 installed)

```bash
python3 scripts/encapp.py run tests/exp1_transcode_realtime.pbtxt -i <input> --serial <SERIAL> -w /tmp/encapp_p1
ls /tmp/encapp_p1/
```

Expected: result file names are deterministic slugs, no `encapp_<UUID>` names.

**Step 7: Commit**

```bash
git add scripts/encapp.py scripts/tests/test_test_id.py
git commit -m "encapp: CLI fills common.test_id before pushing pbtxt to device

Wires fill_test_ids() into the suite-write site so result artifacts
get predictable names. Matches the app-side derivation algorithm so
manual am-start debugging still produces the same names.

Test Plan:
- pytest scripts/tests/test_test_id.py (3 cases) PASS
- Smoke run on device produces deterministic-named outputs"
```

---

## Phase 2 — App `writeResult()` (partial → final atomic)

**Goal:** App writes `<test_id>.result.json.partial` before encoder starts, atomic-renames to `<test_id>.result.json` on success, rewrites in place on caught error.

**Release coupling:** App-only. New file is invisible to old CLI.

### Task 2.1 — `TestResult` POJO + writer

**Files:**
- Create: `/Users/jblome/code/encapp/app/src/main/java/com/facebook/encapp/utils/TestResult.java`
- Create: `/Users/jblome/code/encapp/app/src/test/java/com/facebook/encapp/utils/TestResultTest.java`

**Step 1: Failing test first**

```java
// app/src/test/java/com/facebook/encapp/utils/TestResultTest.java
package com.facebook.encapp.utils;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.json.JSONObject;
import java.io.File;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class TestResultTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void writePartialCreatesPartialFile() throws Exception {
        TestResult r = new TestResult("my_id", tmp.getRoot().getAbsolutePath());
        r.writePartial(1234L, 4321);
        File partial = new File(tmp.getRoot(), "my_id.result.json.partial");
        assertTrue(partial.exists());
        JSONObject obj = new JSONObject(new String(Files.readAllBytes(partial.toPath())));
        assertEquals(1, obj.getInt("schema_version"));
        assertEquals("my_id", obj.getString("test_id"));
        assertEquals("running", obj.getString("status"));
        assertEquals(1234L, obj.getLong("started_at_unix_ms"));
    }

    @Test
    public void writeOkRenamesPartialToFinal() throws Exception {
        TestResult r = new TestResult("my_id", tmp.getRoot().getAbsolutePath());
        r.writePartial(1000L, 4321);
        r.writeOk(3500L, "c2.qti.hevc.encoder", 600, 600, "my_id.mp4", "my_id.json");
        File partial = new File(tmp.getRoot(), "my_id.result.json.partial");
        File finalFile = new File(tmp.getRoot(), "my_id.result.json");
        assertFalse("partial should be renamed away", partial.exists());
        assertTrue(finalFile.exists());
        JSONObject obj = new JSONObject(new String(Files.readAllBytes(finalFile.toPath())));
        assertEquals("ok", obj.getString("status"));
        assertEquals(600, obj.getInt("frames_encoded"));
    }

    @Test
    public void writeErrorWritesFinalWithErrorBlock() throws Exception {
        TestResult r = new TestResult("my_id", tmp.getRoot().getAbsolutePath());
        r.writePartial(1000L, 4321);
        r.writeError(2000L, "configure_failed", "MediaCodec.configure threw", "stacktrace here");
        File finalFile = new File(tmp.getRoot(), "my_id.result.json");
        assertTrue(finalFile.exists());
        JSONObject obj = new JSONObject(new String(Files.readAllBytes(finalFile.toPath())));
        assertEquals("error", obj.getString("status"));
        assertEquals("configure_failed", obj.getJSONObject("error").getString("code"));
    }
}
```

**Step 2: Run, verify fail**

```bash
cd /Users/jblome/code/encapp/app && ./gradlew testDebugUnitTest --tests "com.facebook.encapp.utils.TestResultTest"
```

Expected: FAIL (`TestResult` doesn't exist).

**Step 3: Implement `TestResult.java`**

```java
package com.facebook.encapp.utils;

import android.util.Log;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public class TestResult {
    private static final String TAG = "encapp.result";
    public static final int SCHEMA_VERSION = 1;

    private final String mTestId;
    private final String mWorkdir;
    private final File mPartial;
    private final File mFinal;

    public TestResult(String testId, String workdir) {
        mTestId = testId;
        mWorkdir = workdir;
        mPartial = new File(workdir, testId + ".result.json.partial");
        mFinal = new File(workdir, testId + ".result.json");
    }

    public void writePartial(long startedAtUnixMs, int pid) {
        try {
            JSONObject o = new JSONObject();
            o.put("schema_version", SCHEMA_VERSION);
            o.put("test_id", mTestId);
            o.put("status", "running");
            o.put("started_at_unix_ms", startedAtUnixMs);
            o.put("pid", pid);
            writeAtomic(mPartial, o);
        } catch (JSONException | IOException e) {
            Log.e(TAG, "writePartial failed for " + mTestId, e);
        }
    }

    public void writeOk(long finishedAtUnixMs, String actualCodec,
                        int framesEncoded, int expectedFrames,
                        String videoOutput, String statsOutput) {
        try {
            JSONObject base = readPartialOrEmpty();
            base.put("schema_version", SCHEMA_VERSION);
            base.put("test_id", mTestId);
            base.put("status", "ok");
            base.put("finished_at_unix_ms", finishedAtUnixMs);
            base.put("actual_codec", actualCodec == null ? JSONObject.NULL : actualCodec);
            base.put("frames_encoded", framesEncoded);
            base.put("expected_frames", expectedFrames);
            JSONObject outputs = new JSONObject();
            if (videoOutput != null) outputs.put("video", videoOutput);
            if (statsOutput != null) outputs.put("stats", statsOutput);
            base.put("outputs", outputs);
            base.put("error", JSONObject.NULL);
            writeAtomic(mFinal, base);
            if (!mPartial.delete()) {
                Log.w(TAG, "could not delete partial for " + mTestId);
            }
        } catch (JSONException | IOException e) {
            Log.e(TAG, "writeOk failed for " + mTestId, e);
        }
    }

    public void writeError(long finishedAtUnixMs, String code, String message, String stack) {
        try {
            JSONObject base = readPartialOrEmpty();
            base.put("schema_version", SCHEMA_VERSION);
            base.put("test_id", mTestId);
            base.put("status", "error");
            base.put("finished_at_unix_ms", finishedAtUnixMs);
            JSONObject err = new JSONObject();
            err.put("code", code);
            err.put("message", message == null ? "" : message);
            err.put("stack", stack == null ? "" : stack);
            base.put("error", err);
            writeAtomic(mFinal, base);
            mPartial.delete();
        } catch (JSONException | IOException e) {
            Log.e(TAG, "writeError failed for " + mTestId, e);
        }
    }

    private JSONObject readPartialOrEmpty() {
        try {
            if (!mPartial.exists()) return new JSONObject();
            byte[] bytes = new byte[(int) mPartial.length()];
            try (java.io.FileInputStream fis = new java.io.FileInputStream(mPartial)) {
                fis.read(bytes);
            }
            return new JSONObject(new String(bytes));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static void writeAtomic(File target, JSONObject obj) throws IOException {
        File tmp = new File(target.getAbsolutePath() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            fos.write(obj.toString(2).getBytes("UTF-8"));
            fos.getFD().sync();
        }
        if (!tmp.renameTo(target)) {
            throw new IOException("atomic rename failed for " + target);
        }
    }
}
```

**Step 4: Re-run test**

```bash
cd /Users/jblome/code/encapp/app && ./gradlew testDebugUnitTest --tests "com.facebook.encapp.utils.TestResultTest"
```

Expected: PASS, 3 tests.

**Step 5: Commit**

```bash
git add app/src/main/java/com/facebook/encapp/utils/TestResult.java \
        app/src/test/java/com/facebook/encapp/utils/TestResultTest.java
git commit -m "encapp: add TestResult writer (partial -> final atomic)

Writes <test_id>.result.json.partial before encode, renames to final
on success, rewrites in place on caught error. Atomic via tmp+rename.

Test Plan:
- TestResultTest (3 cases) PASS"
```

### Task 2.2 — Wire `TestResult` into per-test thread

**Files:**
- Modify: `/Users/jblome/code/encapp/app/src/main/java/com/facebook/encapp/MainActivity.java:1209-1268` (per-test thread `finally`)

**Step 1: Locate the per-test thread.** From audit: the thread runs each test, `Encoder.start()` returns a status string, and the `finally` writes `<id>.json` via `Statistics.writeJSON()`.

Read `MainActivity.java:1200-1270` to confirm structure.

**Step 2: Add `writePartial` BEFORE encoder start.** Just before the call to `currentEncoder.start(test)` (around line 1214), add:

```java
TestResult tr = new TestResult(stats.getId(), CliSettings.getWorkDir());
tr.writePartial(System.currentTimeMillis(), android.os.Process.myPid());
```

**Step 3: Add `writeOk` / `writeError` in the finally block.**

In the `finally` block (around lines 1228-1265), after `stats.writeJSON()`, add:

```java
try {
    String status = currentEncoder.start(test); // existing call's return — keep stored
    // ... existing code ...
    if (status == null || status.isEmpty()) {
        tr.writeOk(
            System.currentTimeMillis(),
            stats.getCodec(),         // add getter if missing
            stats.getEncodingProcessingFrames(),
            test.getInput().getPlayoutFrames(),
            stats.getEncodedfile(),    // existing
            stats.getId() + ".json"
        );
    } else {
        tr.writeError(System.currentTimeMillis(), "encoder_error", status, "");
    }
} catch (Exception ex) {
    tr.writeError(System.currentTimeMillis(),
                  "exception",
                  ex.getMessage() == null ? ex.getClass().getName() : ex.getMessage(),
                  Log.getStackTraceString(ex));
    throw ex;
}
```

(NOTE — the exact placement depends on the existing try/catch shape. Read the full block and adapt.)

**Step 4: Build**

```bash
cd /Users/jblome/code/encapp/app && ./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL.

**Step 5: Install and smoke-test**

```bash
python3 scripts/encapp.py install --serial <SERIAL>
python3 scripts/encapp.py run tests/exp1_transcode_realtime.pbtxt -i <input> --serial <SERIAL> -w /tmp/encapp_p2
ls /tmp/encapp_p2/
adb -s <SERIAL> shell ls /sdcard/ | grep result.json
```

Expected: At least one `<test_id>.result.json` exists on device (CLI doesn't pull it yet — that's phase 3).

Pull and inspect manually:
```bash
adb -s <SERIAL> shell cat /sdcard/<test_id>.result.json
```

Expected: JSON with `status: "ok"`, frame counts, codec name.

**Step 6: Negative smoke — kill app mid-test**

In one terminal:
```bash
python3 scripts/encapp.py run <some_long_test.pbtxt> --serial <SERIAL> -w /tmp/encapp_p2_crash
```
In another, after a second or two:
```bash
adb -s <SERIAL> shell am force-stop com.facebook.encapp
```

Expected: `/sdcard/` shows a `<test_id>.result.json.partial` (no final), proving the crash path leaves the partial behind.

**Step 7: Commit**

```bash
git add app/src/main/java/com/facebook/encapp/MainActivity.java
git commit -m "encapp: write TestResult around each per-test thread

writePartial before encoder start, writeOk/writeError in finally.
On crash, the .partial is left behind so CLI can distinguish
mid-test crash from never-started.

Test Plan:
- assembleDebug PASS
- Smoke run produces <test_id>.result.json with status=ok
- Force-stopped run leaves <test_id>.result.json.partial"
```

---

## Phase 3 — CLI oracle consumer

**Goal:** CLI waits for `<test_id>.result.json`, treats absence as failure, surfaces structured reasons.

**Release coupling:** CLI-only, but requires phase 2 app to be installed on user devices. If phase 2 missing, every test will fail (acceptable: forces users to update the app).

### Task 3.1 — Oracle module with tests

**Files:**
- Create: `/Users/jblome/code/encapp/scripts/encapp_tool/oracle.py`
- Create: `/Users/jblome/code/encapp/scripts/tests/test_oracle.py`

**Step 1: Failing test first**

```python
# scripts/tests/test_oracle.py
import json, os, tempfile
import pytest
from encapp_tool.oracle import classify_result, TestVerdict, Status

def write(path, obj):
    with open(path, "w") as f: json.dump(obj, f)

def test_status_ok_is_pass(tmp_path):
    p = tmp_path / "t.result.json"
    write(p, {"schema_version":1,"test_id":"t","status":"ok","frames_encoded":600})
    v = classify_result(str(p), str(tmp_path) + "/t.result.json.partial")
    assert v.status == Status.PASS
    assert v.reason is None

def test_status_error_is_fail_with_reason(tmp_path):
    p = tmp_path / "t.result.json"
    write(p, {"schema_version":1,"test_id":"t","status":"error",
              "error":{"code":"configure_failed","message":"boom","stack":""}})
    v = classify_result(str(p), str(tmp_path) + "/t.result.json.partial")
    assert v.status == Status.FAIL
    assert "configure_failed" in v.reason
    assert "boom" in v.reason

def test_partial_only_is_crash(tmp_path):
    p = tmp_path / "t.result.json"
    partial = tmp_path / "t.result.json.partial"
    write(partial, {"schema_version":1,"test_id":"t","status":"running","pid":4321})
    v = classify_result(str(p), str(partial))
    assert v.status == Status.CRASH
    assert "crashed" in v.reason.lower()

def test_no_files_is_never_started(tmp_path):
    p = tmp_path / "t.result.json"
    v = classify_result(str(p), str(tmp_path) + "/t.result.json.partial")
    assert v.status == Status.NEVER_STARTED
```

**Step 2: Run test, expect ImportError**

```bash
cd /Users/jblome/code/encapp && python3 -m pytest scripts/tests/test_oracle.py -v
```

Expected: FAIL (module doesn't exist).

**Step 3: Implement `oracle.py`**

```python
# scripts/encapp_tool/oracle.py
"""CLI-side oracle for encapp test results.

Reads <test_id>.result.json (and its .partial sibling) and classifies the
outcome as PASS / FAIL / CRASH / TIMEOUT / NEVER_STARTED.

Default-deny: missing file == failure, never success.
"""
import json, os, time
from dataclasses import dataclass
from enum import Enum
from typing import Optional


class Status(Enum):
    PASS = "pass"
    FAIL = "fail"
    CRASH = "crash"
    TIMEOUT = "timeout"
    NEVER_STARTED = "never_started"


@dataclass
class TestVerdict:
    status: Status
    reason: Optional[str]
    result_file: Optional[str]   # path to result.json or .partial, whichever exists
    raw: Optional[dict]          # parsed contents, if file present


def classify_result(final_path: str, partial_path: str) -> TestVerdict:
    if os.path.exists(final_path):
        try:
            with open(final_path) as f: obj = json.load(f)
        except Exception as e:
            return TestVerdict(Status.FAIL, f"result.json unreadable: {e}", final_path, None)
        if obj.get("status") == "ok":
            return TestVerdict(Status.PASS, None, final_path, obj)
        if obj.get("status") == "error":
            err = obj.get("error") or {}
            reason = f"{err.get('code','unknown')}: {err.get('message','(no message)')}"
            return TestVerdict(Status.FAIL, reason, final_path, obj)
        return TestVerdict(Status.FAIL, f"unexpected status: {obj.get('status')!r}", final_path, obj)
    if os.path.exists(partial_path):
        try:
            with open(partial_path) as f: obj = json.load(f)
        except Exception:
            obj = None
        return TestVerdict(Status.CRASH, "test crashed during execution", partial_path, obj)
    return TestVerdict(Status.NEVER_STARTED, "no result.json or .partial produced", None, None)


def wait_for_result(adb_pull_fn, device_workdir, local_workdir, test_id, timeout_sec, poll_interval=1.0):
    """Polls the device for <test_id>.result.json; returns when present or timeout.

    adb_pull_fn(remote_path, local_dir) -> bool   pulls and returns True if file exists.
    """
    final_name = f"{test_id}.result.json"
    partial_name = f"{final_name}.partial"
    deadline = time.monotonic() + timeout_sec
    while time.monotonic() < deadline:
        if adb_pull_fn(f"{device_workdir}/{final_name}", local_workdir):
            return classify_result(os.path.join(local_workdir, final_name),
                                   os.path.join(local_workdir, partial_name))
        time.sleep(poll_interval)
    # Timeout: try to pull whatever's there for diagnostics
    adb_pull_fn(f"{device_workdir}/{partial_name}", local_workdir)
    final = os.path.join(local_workdir, final_name)
    partial = os.path.join(local_workdir, partial_name)
    if os.path.exists(final):
        return classify_result(final, partial)
    if os.path.exists(partial):
        return TestVerdict(Status.TIMEOUT, f"no completion within {timeout_sec}s", partial, None)
    return TestVerdict(Status.TIMEOUT, f"no result file within {timeout_sec}s", None, None)
```

**Step 4: Re-run test**

```bash
cd /Users/jblome/code/encapp && python3 -m pytest scripts/tests/test_oracle.py -v
```

Expected: PASS, 4 tests.

**Step 5: Commit**

```bash
git add scripts/encapp_tool/oracle.py scripts/tests/test_oracle.py
git commit -m "encapp: add oracle module (classify_result + wait_for_result)

Default-deny verdict logic. Distinguishes PASS / FAIL / CRASH /
TIMEOUT / NEVER_STARTED based on presence and contents of
<test_id>.result.json[.partial]. Pure-Python, no adb dependency
in the classifier; wait helper takes a pull callback.

Test Plan:
- pytest scripts/tests/test_oracle.py (4 cases) PASS"
```

### Task 3.2 — Replace `wait_for_exit` + `parse_logcat` oracle path

**Files:**
- Modify: `/Users/jblome/code/encapp/scripts/encapp.py` — `wait_for_exit` (:206), `collect_results` (:277), `parse_logcat` (:379)

**Step 1: Identify the call sites.** From audit:
- `wait_for_exit` is called from `run_encapp_test` (`encapp.py:239`)
- `collect_results` is called at `:1904-1912` (split) and `:1991-1995` (aggregate)
- `parse_logcat` defines `result_ok = True` by default, the bug

Read 200-line slices around each before editing.

**Step 2: Add oracle-based result collection.** Replace the `result_ok` logic in `collect_results` with per-test oracle classification. New flow:

```python
from encapp_tool.oracle import wait_for_result, Status, TestVerdict

def collect_results_v2(test_suite, serial, device_workdir, local_workdir, debug, log):
    verdicts = {}
    for test in test_suite.test:
        test_id = test.common.test_id
        timeout = test.common.timeout_sec or _default_timeout(test)

        def _pull(remote, local_dir):
            ok = adb_cmds.pull_file_if_exists(serial, remote, local_dir, debug)
            return ok

        verdict = wait_for_result(_pull, device_workdir, local_workdir, test_id, timeout)
        verdicts[test_id] = verdict
        if verdict.status == Status.TIMEOUT:
            log.warning("Test %s timed out, force-stopping app", test_id)
            adb_cmds.force_stop(serial, "com.facebook.encapp", debug)
    return verdicts
```

Add `pull_file_if_exists` and `force_stop` to `adb_cmds.py`:

```python
def pull_file_if_exists(serial, remote_path, local_dir, debug):
    # Check existence first to avoid spam on miss
    ret, _, _ = run_cmd(f"adb -s {serial} shell ls {remote_path}", debug=debug)
    if ret != 0:
        return False
    pull_files_from_device(serial, os.path.basename(remote_path),
                           os.path.dirname(remote_path), local_dir, debug=debug)
    return os.path.exists(os.path.join(local_dir, os.path.basename(remote_path)))

def force_stop(serial, package, debug):
    run_cmd(f"adb -s {serial} shell am force-stop {package}", debug=debug)
```

Add `_default_timeout(test)`:

```python
def _default_timeout(test):
    # 3x expected duration + 30s safety, with a 60s floor and 600s ceiling
    fps = test.input.framerate or 30
    frames = test.input.playout_frames or 300
    expected = (frames / fps)
    return max(60, min(600, int(expected * 3 + 30)))
```

**Step 3: Replace caller logic.** In `run_codec_tests_file` / `run_codec_tests` paths, replace the old `result_ok` boolean with the verdict map. Print structured failure per test, return overall pass/fail.

**Step 4: Smoke-test happy path**

```bash
python3 scripts/encapp.py run tests/exp1_transcode_realtime.pbtxt -i <input> --serial <SERIAL> -w /tmp/encapp_p3
```

Expected: stdout shows per-test PASS lines from the oracle, exit code 0.

**Step 5: Smoke-test crash path** (kill app mid-test)

```bash
python3 scripts/encapp.py run tests/long_test.pbtxt --serial <SERIAL> -w /tmp/encapp_p3_crash &
sleep 2
adb -s <SERIAL> shell am force-stop com.facebook.encapp
wait
```

Expected: stdout shows `FAIL ... crashed during execution`, exit code non-zero.

**Step 6: Commit**

```bash
git add scripts/encapp.py scripts/encapp_tool/adb_cmds.py
git commit -m "encapp: CLI consumes <test_id>.result.json as success oracle

Replaces logcat-regex with default-deny verdict from oracle module.
Adds per-test timeout (common.timeout_sec or heuristic). Force-stops
the app on timeout. Logcat capture stays for diagnostics only.

Test Plan:
- Happy-path smoke run: all tests PASS
- Crash-injection smoke run: tests reported CRASH, exit non-zero"
```

---

## Phase 4 — Workdir handshake

**Goal:** App publishes its chosen workdir; CLI reads, caches, trusts.

**Release coupling:** App + CLI together. CLI falls back to today's probe if marker file missing.

**Tasks (condensed — each 2-5 min; same TDD structure as above):**

1. **4.1 App: write workdir marker on probe-mode launch**
   - Add `CliSettings.WORKDIR_MARKER = "encapp_workdir.txt"`
   - In `MainActivity.onCreate`, if intent extra `probe=true`, write the marker into the chosen workdir and exit cleanly (no test run). Unit-test the marker contents.
2. **4.2 CLI: probe + cache helper in `adb_cmds.py`**
   - New `get_or_probe_workdir(serial, cache_dir, app_version)`. Reads `<cache_dir>/.encapp_device_workdir.<serial>` if exists and `app_version` matches; else `am start -e probe true`, waits for marker, pulls, caches.
3. **4.3 CLI: thread cached workdir into push/launch**
   - Replace the `get_workdir()` call in `encapp.py:3134` with `get_or_probe_workdir`. Pass result as `workdir=` extra to `am start`.
4. **4.4 App: trust the `workdir` extra**
   - In `CliSettings.setWorkDir`, if extra is present, use it without re-probing. If write fails, the test thread writes a `TestResult` with `error.code=workdir_invalid` and exits.
5. **4.5 Smoke test on a fresh device** (`encapp clear` first, then `run`) — verify cache file appears in `<local_workdir>/`.
6. **4.6 Commit** (one or two commits, e.g. "app: write workdir marker" + "CLI: probe/cache/trust workdir extra").

---

## Phase 5 — Generation cleanup

**Goal:** One canonical pbtxt per run. Kill the dead branches, duplicate writes, hardcoded CSV.

**Release coupling:** CLI-only.

**Tasks:**

1. **5.1 Add pytest harness for the generator**
   - Create `scripts/tests/test_suite_generator.py` with golden-file tests: feed sample pbtxts + CLI overrides, assert the resulting `TestSuite` proto.
2. **5.2 Refactor to in-memory mutation**
   - Pull the generation logic out of `run_codec_tests_file` (`encapp.py:672`) into a pure function `build_suite(input_paths, options) -> TestSuite`. No file IO inside. Tests from 5.1 must pass.
3. **5.3 Single write helper `write_canonical_suite(suite, local_workdir, run_id)`**
   - Writes exactly one `<run_id>.suite.pbtxt`. Returns the path. All push sites call this.
4. **5.4 Delete dead intermediates**
   - Remove writes of `run.pbtxt`, `_aggr.pbtxt`, `expanded_*.pbtxt`, `encapp_test.pbtxt` (`encapp.py:1924-1926`, `:838-841`, `:1016-1017`, `:661-662`).
5. **5.5 Collapse split-mode double-write**
   - One of the two sites at `encapp.py:657` and `:717` becomes the canonical helper call; the other is deleted.
6. **5.6 Move hardcoded failure CSV**
   - Replace the literal `"bitrate_surface_transcoder_show.pbtxt.failed.csv"` write at `encapp.py:875` with a write into `<local_workdir>/`. (Will be subsumed by `run_summary.json` in phase 9 — for now just relocate.)
7. **5.7 Delete `Serial` message from proto**
   - Remove `tests.proto:217-219`. Verify no Python or Java code references it (`grep -rn 'Serial' --include='*.py' --include='*.java'`).
8. **5.8 Fix `update_codec_testsuite` arg-order bug**
   - At `encapp.py:1785-1793`, replace positional `debug` with `is_parallel=False, debug=debug`.
9. **5.9 Delete dead branch `encapp.py:1944-1954`**
   - The `if not protobuf_txt_filepath` branch can never trigger; remove it.
10. **5.10 Smoke-test full run** — verify only one suite.pbtxt in `<local_workdir>/`, no orphans, suite still works.
11. **5.11 Commit (one per item or grouped logically)**

---

## Phase 6 — Java logging cleanup

**Goal:** One TAG, BuildConfig.DEBUG-gated, subtag in message.

**Release coupling:** App-only.

**Tasks:**

1. **6.1 Add `debug` buildType to `app/build.gradle`** (so `BuildConfig.DEBUG` flips).
2. **6.2 Create `app/.../utils/Constants.java`** with `TAG = "encapp"` and `DEBUG = BuildConfig.DEBUG`.
3. **6.3 Per-file refactor (one commit per Java file is fine)**: for each file under `app/src/main/java/com/facebook/encapp/`:
   - Replace local `TAG` with `Constants.TAG`.
   - Convert `Log.d(TAG, "...")` to `Log.d(Constants.TAG, "[<subtag>] ...")` where `<subtag>` is the old TAG suffix (e.g. `encapp.surface_encoder` → `[surface_encoder]`).
   - Wrap `Log.d` in `if (Constants.DEBUG) { ... }`. Keep `Log.w` / `Log.e` always-on.
4. **6.4 Fix `SurfaceNoEncoder.TAG` non-static bug** (line 38 — minor cleanup since touched anyway).
5. **6.5 Smoke-test**: `adb logcat -s encapp:V` shows all encapp lines; subtag is greppable.
6. **6.6 Commits**

---

## Phase 7 — Python logging cleanup

**Goal:** stdlib `logging` everywhere. `debug=` kwarg gone.

**Release coupling:** CLI-only.

**Tasks:**

1. **7.1 Add `scripts/encapp_tool/log_setup.py`** with `setup_logging(level, log_file, modules)`.
2. **7.2 Replace top-level `print(...)` in `encapp.py`** with `logger = logging.getLogger("encapp.run"); logger.info(...)` etc. Module-by-module.
3. **7.3 Replace `if debug > 0: print(...)` patterns in `adb_cmds.py`** — `logger = logging.getLogger("encapp.adb")`, `logger.debug(...)`.
4. **7.4 Add `--log-level`, `--log-file`, `--log-modules` CLI flags**; map `-d`/`-q` to them with deprecation warnings.
5. **7.5 Remove the `debug=` kwarg from function signatures.** Mechanical; do in one large commit gated by `pytest` + smoke run.
6. **7.6 Smoke-test**: `python3 scripts/encapp.py run ... --log-level DEBUG --log-file /tmp/x.log` produces a structured log file.
7. **7.7 Commits**

---

## Phase 8 — Per-test log files

**Goal:** `<test_id>.log` written by app (cross-platform-ready). `<test_id>.android_logcat.txt` dumped per-test on the CLI side.

**Release coupling:** App + CLI together (CLI pulling a non-existent file is just warning noise, but pair them for cleanliness).

**Tasks:**

1. **8.1 App: per-test log tee**
   - Add `TestLogWriter` (in `utils/`) — a file-backed Logger that tees `Log.d/i/w/e` for one test to `<workdir>/<test_id>.log`. Use it from the per-test thread (open before encoder start, close in `finally`).
2. **8.2 CLI: per-test logcat slice**
   - In the result-pull stage, after each test's verdict is known, dump `adb -s <serial> logcat -s encapp:V -d` to `<local_workdir>/<test_id>.android_logcat.txt`. Then `adb logcat -c` to reset for the next test.
3. **8.3 Pull `<test_id>.log` deterministically** alongside other artifacts.
4. **8.4 Smoke-test**: verify both files appear per test.
5. **8.5 Commits**

---

## Phase 9 — `run_summary.json` + failure UX

**Goal:** Single rollup file + readable stdout block.

**Release coupling:** CLI-only.

**Tasks:**

1. **9.1 Add `scripts/encapp_tool/run_summary.py`** with `RunSummary` dataclass and `write(local_workdir)`.
2. **9.2 Add unit tests** — synthesize verdicts, assert summary JSON shape.
3. **9.3 Wire into top of `codec_test`** — create `RunSummary` at entry, append per-test verdict, write at exit.
4. **9.4 Format the per-test failure block** (the multi-line stdout block from the design doc) — helper in `oracle.py`.
5. **9.5 Print final summary line** — `"22/24 passed. Failed: <id1>, <id2>. Full report: <path>"`.
6. **9.6 Delete the relocated `bitrate_surface_transcoder_show.pbtxt.failed.csv` write from phase 5.6** (now subsumed by summary).
7. **9.7 Smoke-test happy + failure runs.**
8. **9.8 Commits**

---

## Phase 10 — Remove `encapp_run.sh`

**Goal:** Single Python runner. Bash runner is deprecated and removed.

**Release coupling:** CLI-only. Major version bump.

**Tasks:**

1. **10.1 Audit `encapp_run.sh` for any features not yet in `encapp.py`** — anything found, file as a follow-up before removal.
2. **10.2 Update `README.md` and `doc/release_script.md`** — remove references to `encapp_run.sh`; document `encapp.py` as the only runner.
3. **10.3 Delete `scripts/encapp_run.sh`.**
4. **10.4 Smoke-test**: any CI / docs flow that referenced it is updated.
5. **10.5 Commit** with a clear message: `"encapp: remove encapp_run.sh, replaced by encapp.py"`.
6. **10.6 Tag a major version** (`encapp-2.0.0` or similar) and note the breaking change in release notes.

---

## Cross-phase verification matrix

After each phase, run this minimum verification:

| Check | Command | Phases requiring it |
|---|---|---|
| Python tests | `cd /Users/jblome/code/encapp && python3 -m pytest scripts/tests/` | 1, 3, 5, 7, 9 |
| App tests | `cd app && ./gradlew testDebugUnitTest` | 1, 2, 4, 6, 8 |
| App build | `cd app && ./gradlew assembleDebug` | 1, 2, 4, 6, 8 |
| Smoke run (happy) | `python3 scripts/encapp.py run tests/exp1_transcode_realtime.pbtxt -i <input> --serial <S> -w /tmp/p<N>` | every phase |
| Smoke run (crash) | force-stop mid-run; verify CLI reports CRASH | 3, 4, 8 |
| Smoke run (timeout) | tiny `timeout_sec` value; verify CLI force-stops + reports TIMEOUT | 3 |

Each commit should leave the tree in a runnable state. Each phase should be a logical PR/diff boundary.

---

## What this plan does NOT cover

- iOS app changes (design is iOS-aware, implementation is Android-first)
- Quality script changes (`encapp_quality.py`, `encapp_stats_to_csv.py`) — they keep reading `<test_id>.json`, unchanged
- Migration of any user pbtxts — the proto change is additive, old pbtxts work
- CI / Sandcastle integration — out of scope; verify locally on dev machine devices
- Bringing `encapp_run.sh`'s per-test subdir layout into `encapp.py` — design chose flat layout; if subdirs are wanted later, separate feature

---

## Execution choice

**Plan saved to `/Users/jblome/code/encapp/doc/plans/2026-06-12-encapp-cli-app-contract-implementation.md`.**

Two execution options:

**1. Subagent-Driven (this session)** — I dispatch a fresh subagent per task, review between tasks, fast iteration.

**2. Parallel Session (separate)** — Open a new session with executing-plans, batch execution with checkpoints.

Which approach?
