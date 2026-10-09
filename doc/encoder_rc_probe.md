# Encoder rate-control probe

`encoder_rc_probe.py` creates a deterministic, rerunnable black-box test for
Android hardware video encoders. It is intended for Qualcomm HEVC first, but the
manifest and runner are codec/device independent.

## What it measures

- Complexity-step response and effective 63.2% response time for area-weighted
  frame QP and P-frame bitrate. Steps occur 11 frames after GOP boundaries to
  avoid keyframe confounding.
- Maximum accumulated overshoot as zero-floor leaky-bucket/VBV delay.
- Input-frame omission behavior for isolated drops, 2/4/8-frame bursts,
  a four-second 30-to-15 fps interval, and deterministic 10% random omission.
- First I-frame versus later I-frames, I/P size ratio, startup QP, and first-GOP
  convergence.
- Interaction between CBR/VBR and five static QP profiles:
  unbounded, min-27 only, max-38 only, `[27,38]`, and `[30,35]`.

The stimulus comes from Encapp's in-process `fake_input_rc` source. LOW is the
legacy scrolling stripe field. MID and HIGH add deterministic, temporally
decorrelated luma noise using eight prewarmed phases; chroma is unchanged.
`fake_input_rc` explicitly disables legacy per-codec noise scaling, so every
codec receives byte-identical source frames at a given resolution, schedule, and
frame number. Tests use direct NV12 buffers (`color_format: 21`), disable
B-frames, and submit deterministic fake frames as quickly as the codec accepts
them. PTS spacing and configured frame rate remain fixed; wall-clock feed timing
is retained as diagnostic evidence but is not a validity gate.

## Full workflow

From the Encapp checkout, `full` accepts the same input parameters as
`generate`, then runs, analyzes, and produces aggregate plots:

```bash
python3 scripts/encoder_rc_probe.py full /tmp/encoder_rc_probe \
  --codec c2.qti.hevc.encoder \
  --mime video/hevc \
  --resolution 2304x1728 \
  --fps 30 \
  --bitrate 8M \
  --i-frame-interval 1 \
  --repeats 3
```

`full` checks the already-installed Encapp probe contract and never installs or
replaces an APK. If exactly one authorized ADB device is connected it is selected
automatically; pass `--serial` (or set `ANDROID_SERIAL`) only to disambiguate
multiple devices. Use `--resume` (the default) to continue an interrupted result
root. The positional `OUTPUT` is a folder: `full` writes the generated matrix to
`OUTPUT/manifest.json`, raw case evidence to `OUTPUT/raw/`, and reports and CSVs
to `OUTPUT/analysis/`. Scientific warnings are recorded per case and never stop
the remaining run, analysis, or plotting phases. If a case has an actual
execution or artifact failure, `full` still completes analysis and plots for the
available cases, then exits nonzero; `--allow-missing` accepts that subset.

## Generate

From the Encapp checkout:

```bash
python scripts/encoder_rc_probe.py generate /tmp/encoder_rc_probe \
  --codec c2.qti.hevc.encoder \
  --mime video/hevc \
  --resolution 2304x1728 \
  --fps 30 \
  --bitrate 8M \
  --i-frame-interval 1 \
  --repeats 3
```

This creates `manifest.json` and one immutable pbtxt per case. The default full
matrix is 90 runs: 2 rate-control modes × 5 QP profiles × 3 scenarios × 3
repeats. The manifest stores every schedule, expected dropped frame, execution
order, and config hash.

A short smoke matrix:

```bash
python scripts/encoder_rc_probe.py generate /tmp/encoder_rc_probe_smoke \
  --codec c2.qti.hevc.encoder --mime video/hevc \
  --resolution 640x480 --bitrate 2M \
  --modes vbr --qp-profiles unbounded,jcq_27_38 \
  --scenarios hard_start --repeats 1
```

## Build and install Encapp

Use a JDK supported by the repository's Gradle version. On macOS the Android
Studio JBR is known to work:

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew --no-daemon app:testDefaultDebugUnitTest app:assembleDefaultDebug
```

Install using Encapp or adb:

```bash
python scripts/encapp.py install --serial SERIAL
# or
adb -s SERIAL install -r app/build/outputs/apk/default/debug/*.apk
```

The probe client intentionally does not bundle or install an APK. Encapp must be
built from the source containing these changes and installed through the normal
Encapp workflow. The application manifest advertises
`com.facebook.encapp.encoder_rc_probe_contract=3`; the host checks this contract
before any case runs, rather than assuming version-name compatibility.

Verify the installed application explicitly:

```bash
python scripts/encoder_rc_probe.py check-encapp --serial SERIAL
```

## Run

```bash
python scripts/encoder_rc_probe.py run /tmp/encoder_rc_probe/manifest.json \
  --serial SERIAL \
  --encapp-python .venv/bin/python \
  --encapp-script scripts/encapp.py \
  --resume
```

Before creating result files, the runner rejects a missing or incompatible
installed Encapp and records the accepted package version/contract in
`device.json`. Probe cases explicitly use Encapp's app-private files directory,
verified through the app uid with `run-as`, so unattended runs do not depend on
Android's first-launch **All files access** UI or a stale external-workdir cache.
A per-device host lock rejects concurrent `run`/`full` processes before either
can replace the other's Encapp activity. Failure output includes a classified
kind, the Encapp session reason, and the most relevant captured diagnostic path.
One isolated failed case remains resumable; two consecutive identical failures
without a result stop the matrix as a likely infrastructure or concurrency
problem. A session that never starts is treated as a setup failure and stops
immediately instead of repeating the same 120-second failure for every case. By
default, the runner force-stops Encapp before each case so frame-zero behavior
starts from a fresh app/codec instance. It gates each run on Android thermal
status 0 and a maximum active temperature of 70 °C. CPU/GPU/NPU sensors are
preferred; when a device exposes none, a real temperature-class skin, battery,
USB, or power-amplifier sensor is recorded and used as a proxy. Comparator/BCL
pseudo-temperatures are never used. While blocked, the gate prints status,
temperature, sensor basis, elapsed time, and threshold every ten seconds; missing
usable telemetry fails immediately with a remediation. `--ignore-thermal`
bypasses only the pre-case wait as an explicit escape hatch. Thermal state is
still sampled every five seconds, persisted with its sensor basis, and used for
post-run scientific validity. Thermal warnings mark scientific validity without
stopping later cases or analysis. Synthetic frames are submitted as fast as the
codec accepts; measured feed throughput is diagnostic only and is never a
validity criterion or comparison-contract axis. The runner rejects config hash
mismatches, cleans
every non-resumed attempt, rejects incomplete/duplicated/reordered frame sets,
waits for and validates the NAL sidecar, verifies the encoded bitstream contains
only I/P frames with `ffprobe`, and refreshes the encoded file if its device
size changed after Encapp returned. Each case streams the complete session-scoped
Android logcat buffer to its host artifact (including framework/vendor codec
services, without a tag filter), and stores stdout, stderr, pre/post device
properties and thermal state, plus all media/statistics outputs. The configured
test timeout is a no-progress watchdog, not a maximum runtime: manifest changes
and meaningful fake-input/codec/frame progress reset it, while repetitive
"waiting for init" messages do not. Slow devices may therefore run longer than
the timeout window as long as the workload continues to advance.
Isolated case failures do not stop the remainder unless `--no-keep-going` is
used; repeated identical pre-result failures and setup failures stop early.
Resume skips only cases previously marked complete with their sidecar present.

Select a subset with `--case-regex`, for example:

```bash
python scripts/encoder_rc_probe.py run /tmp/encoder_rc_probe/manifest.json \
  --serial SERIAL --case-regex 'hard_start_vbr_(unbounded|jcq_27_38)' \
  --encapp-python .venv/bin/python
```

## Analyze

HEVC QP extraction requires `ffmpeg` and `qpextract` on the host.

```bash
# The result folder is sufficient; manifest.json is also accepted explicitly.
python scripts/encoder_rc_probe.py analyze /tmp/encoder_rc_probe
```

Per-case validation, FFmpeg remuxing, QP extraction, and metric computation run
in parallel. `--jobs` defaults to at most four workers; lower it on
memory-constrained hosts or increase it after measuring available CPU, memory,
and storage throughput. Matplotlib rendering and final report/CSV assembly stay
serial and manifest-ordered for deterministic output.

Add `--allow-missing` to analyze the valid subset when some cases are missing or
have artifact failures. Those cases remain listed in `analysis/REPORT.md`.
Per-configuration QP plots use a one-second trailing PTS-time-weighted mean by
default; set `--smoothing-seconds N`, use `0` for raw values, or add
`--raw-qp-overlay` to retain a faint raw average-QP trace. Aligned repeats are
averaged into one plot per scenario/QP-profile/mode configuration, with the
repeat range shaded. Repeat-level CSV/JSON evidence is always retained; add
`--plot-repeats` only when individual-run plot diagnostics are useful.

Outputs under `analysis/`:

- `REPORT.md`: compact human-readable result tables with embedded per-case and
  aggregate plot galleries using relative links.
- `REPORT.html`: self-contained browser report with all plots embedded; use it
  when a Markdown viewer blocks local relative images.
- `analysis.json`: complete machine-readable metrics.
- `runs.csv`: one row per run, including pre/post thermal state and measured
  feed wall duration/achieved throughput diagnostics.
- `steps.csv`: one row per complexity transition.
- `drops.csv`: one row per named input-omission region.
- `frames.csv`: per-frame bytes, PTS, aligned QP, one-second and 200 ms bitrate,
  bit debt, and VBV delay.
- `plots/*.png`: frame size, one-second bitrate, QP, and VBV delay.

Create cross-run CBR/VBR plots after analysis:

```bash
python scripts/encoder_rc_probe_plots.py /tmp/encoder_rc_probe
```

This writes `analysis/aggregate_plots/` with an eight-page PDF, individual PNGs,
and source CSVs comparing unbounded QP with production QP 27–38 for CBR and
VBR. The package covers peak rolling rate versus averaging window, post-pulse
recovery, matched dynamics time series for both QP configurations, measured
stream QPY low/pixel-area-weighted-mean/high statistics for all, I, and P
frames, I/P frame-size allocation, the every-other-input omission test, and a
rate-conditioned QP view. That final view plots median P-frame QPY against
achieved/requested bitrate and encodes maximum-QP pinning as base marker diameter,
making extra-bit purchases visible without inventing a QP normalization. Lower
median QPY means better typical-frame quality, whereas more maximum-QP pinning
means a worse tail; either can move independently. Coincident series use
display-only concentric marker scales; source coordinates and axis
limits remain unchanged, and legend pinning percentages remain authoritative.
The QPY signal is qpextract's current area-weighted frame measure and includes
skipped blocks. QPY trend lines use a one-second trailing P-frame mean to remove
I-frame and deterministic source-phase noise. Peak rate windows are reported
only after they are fully populated; they describe externally observed rate
allowance and are not assumed to be internal controller constants.

## Compare products

Completed result roots can be compared without device access or re-analysis:

```bash
python scripts/encoder_rc_probe_compare.py /tmp/encoder_comparison \
  --group ProductA=/results/product_a_run_1 \
  --group ProductA=/results/product_a_run_2/manifest.json \
  --group ProductB=/results/product_b_run \
  --group ProductC=/results/product_c_run
```

Repeat a group label to supply multiple devices, builds, or independent result
roots for that product/project/hardware family. Each value may name either the
result folder or its `manifest.json`; missing per-root analyses are generated
automatically and progress is printed per case and sample. Use `--reanalyze` to
refresh existing analysis CSVs. The comparator uses two-stage weighting: it first takes
the median of repeated cases within each result root, then computes the product
median and inter-sample IQR from those root-level values. A product therefore
does not gain statistical weight merely because it has more repeats or result
roots.

The compatibility gate ignores the vendor codec component name but requires an
identical MIME, resolution, frame rate, requested bitrate, GOP interval,
deterministic input contract, complexity/omission schedules, rate-control
modes, and QP-bound matrix. Only scientifically valid runs are accepted.
Incompatibility errors name every differing normalized field and print a scoped
override. To accept a known confounder, repeat an exact path from that error,
for example:

```bash
  --ignore-contract-field global.i_frame_interval \
  --ignore-contract-field input_contract.pixel_format
```

Overrides affect compatibility validation only. They are rejected when unused
or misspelled and are recorded as prominent warnings in `README.md` and under
`contract_overrides` in `comparison.json`.
Response-time aggregation retains both its median and valid/eligible crossing
coverage; missing sustained responses are never converted to zero.

The output directory contains:

- `samples.csv`: product aliases, codec/operating-point fields, and validity
  counts. Local result-root paths and device/build identifiers are omitted by
  default. Pass `--include-source-metadata` only when that provenance should be
  retained in `samples.csv` and `comparison.json`; device serials are never
  copied into comparison outputs.
- `sample_*.csv`: repeat-aggregated values for each device/root sample.
- `product_*.csv`: product medians, IQRs, minima, maxima, and sample counts.
- `comparison.json`: the complete contract and machine-readable aggregates.
- `product_comparison.pdf` plus PNG pages for mean rate, rolling-window peaks,
  backlog, QPY allocation, hard-start I/P ratio, bitrate/QPY response, the
  every-other-input omission test, and rate-conditioned QPY. The latter places
  median P-frame QPY against achieved/requested bitrate and encodes maximum-QP
  pinning as base marker diameter. Lower median QPY means better typical-frame
  quality; more maximum-QP pinning means a worse tail, and movement to the right
  means greater bitrate cost. It does not claim a QP-at-target normalization.
  Coincident product points use display-only concentric marker scales without
  changing source coordinates or axis limits; legend pinning percentages are
  authoritative. QPY is qpextract's current area-weighted frame measure and
  includes skipped blocks.

The installable client exposes the same operation as:

```bash
encoder-rc-probe compare /tmp/encoder_comparison \
  --product ProductA=/results/product_a \
  --product ProductB=/results/product_b
```

## External-use surface

The probe runner, plotter, comparator, and JVM tests use only standard Python,
public PyPI packages, Android/Java APIs, ADB, ffmpeg/ffprobe, and the documented
qpextract/libde265 components. They do not call company-internal services or
require internal repositories, URLs, accounts, packages, or command-line tools.
`com.facebook.encapp` is the existing public Encapp application namespace and
is retained for compatibility with the public project.

The installable package deliberately excludes the Encapp APK. It includes the
Encapp BSD 3-Clause license, exact third-party source revision and license
texts, and native host binaries built with relative loader paths and no
developer home-directory strings. Comparison
outputs omit source paths and device/build identifiers by default. Raw result
roots can contain device-generated metadata and should be reviewed separately
before distribution.

## Definitions

For output frame `i`, configured bitrate `R`, frame size `b_i`, and elapsed PTS
`dt_i`, the tool computes:

```text
error_i   = b_i - R * dt_i
backlog_i = max(0, backlog_(i-1) - R * dt_i) + b_i
VBV delay = backlog_i / R
```

This is the transmit delay of a zero-floor bucket drained at the requested
bitrate. The maximum is the experiment's observable overshoot allowance; it is
not a claim about the encoder's undocumented internal buffer size.

`QP tau63` and `bitrate tau63` are effective response estimates: the first
crossing of 63.2% between the two-second pre-step median and the final steady
median. I-frames are excluded, bitrate uses P-frame bits only, and a crossing
must persist for multiple frames. Bitrate response uses a 200 ms trailing window,
so values near that resolution are measurement-limited, not precise internal
controller constants. Segments shorter than three seconds, signals that barely
change, and responses that do not settle produce `n/a` rather than an invented
time constant.

For HEVC, frame-average QP is qpextract's pixel-area-weighted QPY. It is
accepted only when `qpextract` emits exactly one contiguous row
per output frame and Encapp's NAL sidecar matches the statistics frame-for-frame
by index, PTS, and byte size. Requested static QP bounds are reported as
observed, violated, or unverified; codec input-format echo is recorded but is not
assumed. `qpextract` decodes the full 4 MP stream and can take several minutes
per case.

## Cross-codec use

Generate the same schedule and operating point for each codec. Change only the
codec, MIME, and any codec-specific supported QP parameter keys. At the same
resolution the deterministic source bytes remain identical. Do not enable
per-codec noise scaling: that would confound codec comparison with source
complexity. HEVC gets per-frame QPY through `qpextract`; other codecs retain all
size/timestamp/drop/VBV metrics even when QP extraction is unavailable.
