# MapSafe Mobile performance results: Samsung SM-A145F

This directory preserves the performance measurements collected on 29 August
2026 from a physical Samsung Galaxy A14 (`SM-A145F`) running Android 13 on an
Exynos 850. The measurements cover 50, 250, 500, 1,000, and 2,000-point
synthetic field datasets with both typical and rich attribute profiles.

## Protocol and interpretation

- Protocol: `mapsafe-mobile-performance-v3`, Quick mode.
- Build: MapSafe Mobile `3.2.1-DEBUG`.
- Repetitions: one warm-up followed by five measured runs for every operation
  and dataset.
- Halo distance: 100--2,000 metres.
- OpenPGP: signed encryption, one recipient, 3,072-bit RSA identity.
- Timing units: all duration fields are seconds unless a column explicitly
  states otherwise.
- Publication status: preliminary. Quick mode and a debug build are not marked
  as publication eligible by the benchmark harness. A final paper run should
  use the Paper protocol and a release-equivalent build.

The masking measurements deliberately separate three scopes:

1. `mask_core`: coordinate normalisation, cryptographically secure random
   displacement, and spherical destination calculation.
2. `mask_with_spruill`: the same masking candidate plus Spruill's
   nearest-neighbour assessment.
3. `mask_workflow_total`: the complete production operation from reading the
   already selected NextGIS layer through creation, transactional feature
   insertion, spatial-index rebuilding, and saving of the output layer.

Initial dataset import, UI interaction and rendering, attachments, and writing
the benchmark result files are outside the measured workflow.

## Archived runs

### `complete-quick-run`

Run ID `20260829-022612` completed all 30 benchmark cells. It contains:

- `summary.csv`: median and interquartile-range summaries (50 rows).
- `raw-measurements.csv`: all five measured repetitions (250 rows).
- `masking-phase-measurements.csv`: detailed masking phase medians (50 rows).
- `dataset-manifest.csv`: dataset sizes, point counts, profiles, and SHA-256
  identifiers.
- `metadata.json`: device, build, protocol, cryptographic, and thermal context.
- `run-progress.json`: completion checkpoint (`30/30`, complete).
- `paper-table.tex`: table generated directly from the complete run.
- `paper-table-preliminary-consolidated.tex`: recommended preliminary paper
  table, with the verified 1,000-point typical masking values described below.

The third through fifth masking repetitions for `field-1000-typical` in this
complete run experienced transient device contention. They are retained
unchanged in the raw archive for reproducibility.

### `verification-repeat`

Run ID `20260829-024350` was a targeted verification run. The intended cell was
completed with five stable total-workflow measurements between approximately
5.67 and 5.76 seconds. The run was stopped after 12 of 30 cells when the ADB
connection dropped and the device began background processing; it is therefore
preserved as a partial run and must not be treated as a second complete trial.

Only the three `field-1000-typical` masking medians from this repeat replace the
contended values in `paper-table-preliminary-consolidated.tex`. All other
masking values and all encryption/decryption values in that table come from the
complete run.

## Input data and reproduction

The generated GeoJSON inputs are intentionally not duplicated in this results
archive. Their identities, byte sizes, point counts, attribute profiles, and
SHA-256 hashes are preserved in `dataset-manifest.csv` and `metadata.json`.
The benchmark staged the source files from the phone's `Downloads/MapSafe`
folder.

With a physical Android phone connected and authorised for ADB, the Quick run
can be repeated from the repository root with:

```powershell
.\scripts\run-mapsafe-existing-phone-datasets.ps1 -Serial <adb-serial> -Protocol Quick
```

Use the Paper protocol for the final manuscript measurements.
