# MapSafe Premium trial runbook

The detailed day-by-day execution, evidence, acceptance, and close-out plan is in
[`TRIAL_EXECUTION_PLAN.md`](TRIAL_EXECUTION_PLAN.md). Read it before activating the
one-month trial.

Use [`PRE_ACTIVATION_CHECKLIST.md`](PRE_ACTIVATION_CHECKLIST.md) for the Day-0
go/no-go review. Create each local, ignored evidence workspace with
`scripts/new-mapsafe-premium-run.ps1`; its manifest follows
[`EVIDENCE_MANIFEST_TEMPLATE.md`](EVIDENCE_MANIFEST_TEMPLATE.md).

This directory contains the material needed to complete the multi-account NextGIS
Premium evaluation without spending the trial period on fixture design or test-code
development. Nothing in this directory activates a subscription or contains a
password, API token, private key, or recovery passphrase.

## Prepared case study

The app and instrumentation tests use the 23-point North Whangārei infected-tree
dataset described in the earlier Aotearoa New Zealand case study. The public source
archive contains coordinates but no attribute fields. The reproducible preparation
script preserves the source coordinates exactly and adds eight clearly labelled
synthetic fields for the mobile demonstration. See
`../datasets/north-whangarei/README.md` and `dataset-manifest.json`.

## Test roles

Copy `roles.example.json` to an untracked local file and replace only the Android
account names. Do not add passwords to it.

- **Steven (field data custodian)** publishes his public key, anonymised datasets,
  and an original dataset encrypted for himself and Amber.
- **Amber (authorised researcher and precise-data recipient)** is a community member
  whose accepted public key is selected during encryption. Amber can see and decrypt
  the protected original.
- **A BMA representative (anonymised-data recipient)** is a sovereign-party account
  within the community, but is deliberately not selected as an OpenPGP recipient for
  this test package. The account can see the public-key directory and anonymised
  datasets, but cannot list or download Steven's encrypted package. This test-specific
  release decision does not imply that every BMA representative must have the same
  access in a deployment.
- **An external non-member** has legitimate access to the Web GIS subscription but is
  not a member of the MapSafe authentication group and can access none of that
  community's MapSafe resources.

The first three accounts must be members of the same pre-created NextGIS Web
authentication group. The external account must remain outside it. Add all four accounts to the
Android device through the normal NextGIS account flow before running the test; their
credentials remain in Android AccountManager.

## Before activating Premium

Run:

```powershell
.\scripts\test-mapsafe-premium-readiness.ps1
```

The command regenerates and validates the case-study fixture, runs the pure ACL and
audience-mapping unit tests, and compiles the opt-in Android acceptance and screenshot
tests. A successful result means the remaining dependency is the hosted Premium ACL,
not unfinished local code.

The automated four-account acceptance test creates three temporary test OpenPGP
identities during the run. For the manual workflow and video, use three isolated app
installations or Android profiles because one MapSafe installation intentionally holds
one persistent private identity. The recommended allocation is the physical Samsung
handset for Steven, one emulator/AVD for Amber, and a second emulator/AVD for the BMA
representative. The external control account needs no OpenPGP identity.

## Live Premium status (4 October 2026)

- The reviewed 23-point North Whangārei source and synthetic mobile attributes are
  reproducible from the checked-in preparation script and manifest.
- Per-package recipient ACL generation, fail-closed fingerprint-to-user mapping, and
  migration from the earlier propagated group permissions have unit-test coverage.
- The opt-in four-account Premium acceptance test passed against
  `mapsafe.nextgis.com` with Steven, Amber, the BMA representative, and an external
  non-member. It verified both authorised operations and direct/listing denials.
- All nine isolated Android manuscript scenarios passed on the emulator and produced
  31 checksum-recorded images using the established Steven/Amber identities in
  `../screenshots/manuscript-north-whangarei-20260910-final`.
- Premium is active. The legacy Free-plan public root-read inheritance was removed,
  private member publishing folders were provisioned, and live North Whangārei
  public-key, halo, hexbin, and encrypted-package resources were created.
- The final post-migration test recovered the exact original under Amber's key,
  validated Steven's signature, rejected a non-recipient key and wrong passphrase,
  and passed all assertions in 152.503 seconds. The redacted evidence is under
  `runs/2026-10-03-220129-premium-day1-baseline/`.
- Seven role-specific Premium Android screenshots were generated from the live
  Community A resources. They cover key discovery, multi-item upload selection,
  Steven and Amber access, BMA anonymised-only access, and external-user denial;
  see `../screenshots/premium-community-2026-10-04/`.

## First Premium session

1. Activate the trial only after the four accounts and group membership are ready.
2. Back up any existing `mapsafe.nextgis.com` resources that must be retained.
3. Add the four NextGIS accounts to the test phone or emulator and confirm that Steven,
   Amber, and the BMA representative see the same authentication group while the
   external account does not.
4. Run `run-mapsafe-premium-acl-test.ps1` with the four Android account names. The
   test intentionally leaves timestamped evidence resources online.
5. Inspect `acl-test-matrix.csv`; do not accept a run merely because uploads succeed.
   Every negative permission assertion must also pass.
6. Run the manuscript screenshot script and capture the browser-only Web GIS views
   described in `screenshot-shot-list.md`.
7. Export the test report, screenshots, resource IDs, and server timestamps before
   making any cleanup or permission changes.

When the existing Free-plan hierarchy is first used on Premium, MapSafe removes the
selected group's legacy propagated permissions and installs non-propagating rules.
Anonymised vector layers and public-key records are community-readable. Each encrypted
package is stored in its own registry and is readable only by its publisher and the
NextGIS users mapped to its selected OpenPGP fingerprints.

## Acceptance command

```powershell
.\scripts\run-mapsafe-premium-acl-test.ps1 `
  -GuardianAccount "ANDROID_ACCOUNT_FOR_STEVEN" `
  -PreciseRecipientAccount "ANDROID_ACCOUNT_FOR_AMBER" `
  -AnonymisedRecipientAccount "ANDROID_ACCOUNT_FOR_BMA_REPRESENTATIVE" `
  -OutsiderAccount "ANDROID_ACCOUNT_FOR_EXTERNAL_NON_MEMBER" `
  -CommunityName "Community A"
```

The account-name arguments are identifiers already stored on the device, not login
credentials. The test publishes fresh test-only OpenPGP public keys, two anonymised
North Whangārei layers, and one signed encrypted package, then checks listing,
downloading, digest equality, signature verification, exact decryption, and the
expected access denials for the BMA representative and external non-member.

If an emulator has a stale Android compositor after repeated instrumentation runs,
use `capture-mapsafe-manuscript-screens.ps1 -RestartDevice`. The script waits for a
complete boot, runs each figure in a separate instrumentation process, and restores
the device's original animation settings afterward.
