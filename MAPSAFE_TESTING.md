# MapSafe automated testing

Run the tests from PowerShell at the repository root:

```powershell
.\scripts\run-mapsafe-tests.ps1
```

The default `Unit` suite prints every test as it starts and passes or fails, prints
the MapSafe workflow stages emitted by the scenario tests, and writes an HTML report.

## Suites

```powershell
# Visually follow only the end-to-end workflow scenarios.
.\scripts\run-mapsafe-tests.ps1 -Suite Scenario

# Run all local JVM unit and workflow tests. This is the default.
.\scripts\run-mapsafe-tests.ps1 -Suite Unit

# Run Tier 1 on an attached device or a visible Pixel_9a emulator.
.\scripts\run-mapsafe-tests.ps1 -Suite Device

# Use a different installed Android Virtual Device.
.\scripts\run-mapsafe-tests.ps1 -Suite Device -Avd My_AVD

# Require an already-connected device; do not start an emulator.
.\scripts\run-mapsafe-tests.ps1 -Suite Device -NoStartEmulator

# Deliberately erase app data and local identities before a clean device run.
.\scripts\run-mapsafe-tests.ps1 -Suite Device -ResetAppData

# Run JVM tests followed by connected-device tests.
.\scripts\run-mapsafe-tests.ps1 -Suite All

# Open the generated HTML report when the selected suite finishes.
.\scripts\run-mapsafe-tests.ps1 -Suite Unit -OpenReport
```

For `Device` and `All`, the runner reads `sdk.dir` from `local.properties`. If no
device is connected, it starts the selected AVD in a visible window, waits for Android
to boot, streams `[PASS]` and `[SIMULATED]` stages from logcat, and leaves the emulator
open when the run finishes. Existing app data and MapSafe identities are preserved by
default. Use `-ResetAppData` only when a deliberately clean installation is required;
it removes the app-private identity files and their Android Keystore state. JVM suites
do not require a device.

## Tier 1 device coverage

Tier 1 is deterministic and does not require service credentials. It runs four
instrumentation workflows:

- Real MainActivity controls: MapSafe > Safeguard Features > Anonymise >
  Use sample dataset > Donut Masking and Hexabinning. The test checks
  that both top-level workflows show the early dataset-selection prompt when needed,
  and that loading the sample continues to the originally requested workflow. It checks
  that the bundled sample is created and selected as a compatible vector layer, then checks the resulting vector
  layers, the visible inverted Spruill result, and a second remasking attempt that
  is explicitly anchored to the original precise layer rather than the first output.
  It also checks the compact halo/hexbin overlays, removed controls, result screens,
  their visible retry, Stop, and Next actions, and automatic GeoJSON saves to the
  MapSafe output folder.
- MapSafe activities and map-overlay screens assert that the fixed Back control is
  visible; Security & Sharing also asserts the supplied compact logo in its header.
- A dedicated navigation audit exercises every major MapSafe dialog and activity,
  both visible and Android-system Back actions, guided-workflow branches, the
  halo-result-to-settings route, OpenPGP-to-Safeguard/Access routes, and the
  Security-to-advanced-keys-to-Security route.
- Real Security & Sharing controls: account selection, authentication-group
  selection/creation, local encryption identity, public-key publication/download,
  and fingerprint-review actions are verified in the production activity.
- Real secure OpenPGP screen: locally generated identity, Android Keystore-wrapped
  secret-key reload, recipient selection, signed AES-256-GCM encryption, file
  decryption, integrity/signature confirmation, and exact recovered-byte comparison.
  The fixed MapSafe output folder is represented by a controlled debug provider.
- Selected-map-layer round trip: the UI loads and selects the bundled 30-point layer,
  exports it through `Encrypt selected map layer`, encrypts and decrypts it through the
  production OpenPGP activity, imports all 30 recovered features, and returns to the map
  with the decrypted layer selected. Its automatic output folder is controlled.
- Shared storage: a production-path device check writes and reads a real file through
  Android shared storage and verifies its relative location is `Download/MapSafe/`.
- Complete production-core chain: sample layer, donut masking, Spruill, hexbin,
  WGS84 GeoJSON export, multi-recipient encryption, tamper rejection, decryption,
  signature validation, real layer import, selection, map handoff, and viewing.

Screenshots are captured for non-secret screens and pulled after every run to a
timestamped folder under:

```text
app\build\reports\mapsafe-device\screenshots
```

Android `FLAG_SECURE` is applied while a passphrase is being entered or revealed.
Non-secret MapSafe overview, encryption, decryption and result panels remain capturable
so the safeguard journey can be inspected in test output.

## Live workflow output

Scenario output uses explicit labels:

- `PASS` means production MapSafe core code was exercised successfully.
- `SIMULATED` means the stage uses a test-only dependency.

Blockchain notarisation and verification are currently represented by an in-memory
test ledger because the production blockchain client is not implemented. The scenario
still exercises the production SHA-256 calculation and detects modified artifacts, but
it does not claim that a real blockchain transaction occurred.

NextGIS public-key discovery in Tier 1 uses a controlled directory observation, then
exercises the production fingerprint, trust-state, explicit-acceptance, and recipient
selection code. It is marked `SIMULATED` because no live NextGIS server is contacted.
Live NextGIS authentication/network behaviour and live blockchain submission belong to
Tier 2 integration tests.

The JVM `VIEW HANDOFF` stage stops at a restored GeoJSON artifact. The Tier 1 device
suite continues through actual layer creation, selection, MainActivity rendering, and
the zoom handoff.

## Reports

JVM test report:

```text
app\build\reports\tests\testDebugUnitTest\index.html
```

Connected-device report:

```text
app\build\reports\androidTests\connected\debug\index.html
```
