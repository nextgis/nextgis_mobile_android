# MapSafe automated performance evaluation

This protocol runs MapSafe's production masking and OpenPGP core from Android
instrumentation. It requires no manual screen navigation, slider movement, file
selection, key creation, recipient selection, or repeated tapping.

The reusable entry point is:

```powershell
.\scripts\run-mapsafe-performance-benchmark.ps1
```

To run the same operation matrix against the exact ten `field-*.geojson`
datasets already stored in a physical phone's `Downloads/MapSafe` folder, use:

```powershell
# One warm-up and five measurements for every dataset/operation cell
.\scripts\run-mapsafe-existing-phone-datasets.ps1 -Protocol Quick

# Publication protocol: five warm-ups and 30 measurements per cell
.\scripts\run-mapsafe-existing-phone-datasets.ps1 -Protocol Paper
```

The runner requires the 50, 250, 500, 1,000, and 2,000 point `typical` and
`rich` source files. It stages read-only copies in the debug app's private test
directory because Android scoped storage prevents instrumentation from opening
arbitrary shared files reliably. It never modifies the originals, ignores
numbered duplicate files and existing `.pgp` packages, then runs the production
masking workflow, signed encryption, and verified decryption. Each masking run
produces the coordinate-core, core-plus-Spruill, and complete-workflow timings
from the same masked candidate.
Every decrypted byte array must match its original GeoJSON input or the run
fails. Raw and summary results include elapsed seconds.

## What is measured

For each generated dataset, the benchmark records:

1. halo coordinate displacement without Spruill assessment (`mask_core`);
2. the same displacement plus the inverted Spruill calculation
   (`mask_core_plus_spruill`);
3. the complete production masking backend from reading the already selected
   NextGIS source layer through output-layer insertion and map saving
   (`mask_workflow_total`);
4. signed RFC 9580 AES-256-GCM OpenPGP encryption for one RSA-3072 recipient; and
5. private-key unlock, decryption, integrity checking, and signature verification.

The controlled runner imports each fixture as a genuine temporary NextGIS vector
layer before timing begins. Its `masking-phase-measurements.csv` file also records
source reading, Spruill-only calculation, output-feature construction, output
writing, the internal workflow total, an independent outer elapsed time, and
unaccounted orchestration overhead for every measured run. Temporary benchmark
layers are removed after each iteration. Raw, phase, summary, and
`run-progress.json` files are checkpointed after every complete cell so results
already collected remain recoverable if a long wireless ADB session disconnects.

Initial dataset import, key generation, fixture generation, file selection, UI interaction, recipient
discovery, network access, NextGIS upload/download, and blockchain requests are
outside the timed sections. A disposable benchmark identity is generated once
before measurement and is never published or installed as the user's MapSafe
identity.

## Reproducible datasets

The test generates point datasets containing:

```text
50, 250, 500, 1,000, and 2,000 points
```

The 2,000-point pair is a stress-test reference beyond the expected field-
collection range; it need not be included in the main manuscript table.

Every point has the same 18-field schema:

| Field | Example meaning/type |
|---|---|
| `site_id` | Integer identifier |
| `site_name` | Short text |
| `category` | Categorical text |
| `sensitivity` | Categorical text |
| `observer_id` | Collector identifier |
| `sample_code` | Survey/sample identifier |
| `status` | Workflow status |
| `observed_at` | Date-time as epoch milliseconds |
| `horizontal_accuracy_m` | Real number |
| `measurement_value` | Real number |
| `measurement_unit` | Unit text |
| `households` | Integer count |
| `access_method` | Categorical text |
| `consent_code` | Integer/domain value |
| `collection_round` | Integer survey round |
| `notes` | Variable text |
| `stewardship_notes` | Variable text |
| `review_comment` | Nullable text |

Each point count is generated in two profiles:

- `typical` uses ordinary short and medium text values;
- `rich` keeps the same geometry and schema but uses much longer deterministic
  notes, producing a larger file at the same point count.

This gives ten valid WGS84 GeoJSON files. Comparing typical and rich rows at
the same point count helps separate the file-size effect on encryption/decryption
from the feature-count effect on masking. Deterministic varied text is used so
the rich files do not become unrealistically tiny under OpenPGP ZIP compression.

The exact generated files are saved with every run under `datasets/`. Their byte
sizes and SHA-256 values are recorded in `dataset-manifest.csv`, making a run
auditable and the input files reusable.

## What NextGIS Mobile stores and what MapSafe currently exports

A NextGIS vector feature can contain geometry, typed attribute values, and
attachment references. NextGIS Mobile also maintains layer configuration,
styles/forms, synchronisation state, and change tracking outside the ordinary
feature properties.

The current `MapSafeGeoJsonWorkflow` exports:

- feature ID;
- geometry transformed to WGS84; and
- every layer field and its value, including strings, numbers, nulls, and date
  values represented by the layer API.

The current selected-layer package does **not** include:

- photo, audio, or document attachment files;
- attachment metadata;
- layer styles or renderers;
- data-entry form definitions; or
- NextGIS synchronisation/change metadata.

The synthetic fixtures therefore model exactly the geometry-and-attribute
GeoJSON that MapSafe currently encrypts. They do not inflate file size with
photos that the current package would omit. If attachments are added to the
MapSafe package format later, they need a separate benchmark because image and
audio size and compressibility differ substantially from GeoJSON.

## Quick preliminary emulator run

An Android emulator can verify the complete automated pipeline and provide
preliminary comparisons:

```powershell
.\scripts\run-mapsafe-performance-benchmark.ps1 -Protocol Quick -AllowEmulator
```

Quick mode performs one warm-up and five measured runs per dataset/operation.
The script and result metadata explicitly mark an emulator run as preliminary
and `publication_eligible=false`. Emulator timings must not be described as
physical mobile performance because they depend on the host computer and
emulator allocation.

To target a particular running emulator:

```powershell
.\scripts\run-mapsafe-performance-benchmark.ps1 `
    -Serial emulator-5554 `
    -Protocol Quick `
    -AllowEmulator
```

## Paper protocol on a physical phone

Connect and unlock one Android phone with USB debugging enabled, then run:

```powershell
.\scripts\run-mapsafe-performance-benchmark.ps1 -Protocol Paper
```

Paper mode performs five warm-ups and 30 measured runs per cell. It rejects an
emulator unless `-AllowEmulator` is deliberately supplied. A physical Paper run
is marked `publication_eligible=true` when the instrumentation confirms that the
target is not an emulator.

When several devices are connected, select one explicitly:

```powershell
.\scripts\run-mapsafe-performance-benchmark.ps1 `
    -Serial <adb-serial> `
    -Protocol Paper
```

The phone should be charged, disconnected from unnecessary workloads, and kept
below Android's severe thermal state. The benchmark records battery, thermal,
Android, processor, memory, app-build, and device information at run time.

## Outputs

Every run is pulled into:

```text
app/build/reports/mapsafe-performance/<UTC run ID>/
```

The directory contains:

| Output | Purpose |
|---|---|
| `datasets/*.geojson` | Exact reusable synthetic inputs |
| `dataset-manifest.csv` | Dataset profile, points, fields, byte sizes, encrypted sizes, and SHA-256 |
| `raw-measurements.csv` | Every measured duration in nanoseconds and seconds |
| `summary.csv` | Median, quartiles, mean, minimum, maximum, and MiB/s |
| `paper-table.tex` | Ready-to-review LaTeX table using median seconds |
| `metadata.json` | Protocol, eligibility, device, environment, parameters, and fixture hashes |

The median is the primary table statistic because it is less sensitive than the
mean to occasional Android scheduling pauses. Raw measurements and quartiles
must be retained alongside any reported table so the results remain auditable.

## Timings captured during normal phone use

The production app also appends successful operations to:

```text
Downloads/MapSafe/mapsafe-performance-log.csv
```

This is a cumulative CSV: its header is written once and every later successful
halo-masking, OpenPGP encryption, or verified-decryption operation adds a row.
Durations are stored directly in the `duration_seconds` column with six decimal
places. Existing MapSafe logs that used milliseconds are converted to seconds
the next time the app appends a measurement. One halo action adds three rows
from the same generated candidate:

- `mask_core` is the measured coordinate-masking stage;
- `mask_core_plus_spruill` is that masking duration plus the subsequent Spruill
  assessment duration;
- `mask_workflow_total` measures the complete successful masking backend from
  reading the source through candidate generation, Spruill assessment, output
  layer creation, feature insertion, and map-layer saving.

The app does not mask a second time merely to create these rows. The total
workflow row excludes subsequent UI rendering, zooming, and dialog interaction.
Encryption and decryption rows measure the production OpenPGP calls, excluding dialogs,
passphrase typing, recipient selection, and performance-log writing. Input and
output byte sizes are recorded when Android exposes them. Point count is carried
into encryption when the original layer was exported by MapSafe; it may be blank
for a file chosen directly from Android storage and for a package decrypted on a
different device.

Each row includes a UTC timestamp, operation, dataset/file name, optional point
count, input and output bytes, elapsed seconds, masking bounds, recipient
count, signing state, device manufacturer/model, Android release, and app
version. The file contains no coordinates or attribute values. Because it is a
plain CSV in shared storage, dataset names should not themselves contain
sensitive information.

This normal-use log is useful for checking real workflows and gathering repeated
physical-phone observations. For the manuscript's controlled comparison, use
the Paper protocol above so every dataset receives the same warm-up count,
repeat count, ordering seed, and environmental checks.

With the phone connected and USB debugging enabled, retrieve and summarise the
log with:

```powershell
.\scripts\pull-mapsafe-phone-performance-log.ps1
```

If several devices are connected, add `-Serial <adb-serial>`. The script leaves
the phone copy untouched and writes the retrieved raw CSV, a per-operation
summary CSV, and a readable Markdown table under
`app/build/reports/mapsafe-phone-performance/<UTC run ID>/`.

## Interpretation

- Masking and Spruill timings should be interpreted primarily against point
  count. The benchmark times coordinate operations rather than Android UI or
  map-layer rendering.
- Encryption and decryption should be interpreted against `plaintext_bytes` and
  `encrypted_bytes`, not point count alone.
- OpenPGP encryption includes ZIP compression, session-key generation, AES-256-GCM
  content encryption, recipient session-key wrapping, and signing.
- Decryption includes private-key unlock, session-key unwrapping, decompression,
  integrity checking, and signature verification.
- Storage picker time, shared-folder I/O, NextGIS transfer time, and blockchain
  latency are intentionally excluded because they measure different subsystems.
