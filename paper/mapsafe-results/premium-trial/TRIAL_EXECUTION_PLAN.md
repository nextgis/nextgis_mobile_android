# MapSafe NextGIS Premium seven-day completion sprint

Prepared: 3 October 2026  
Target Web GIS: `https://mapsafe.nextgis.com`  
Case study: 23-point North Whangārei infected-tree demonstration dataset

## 1. Trial objective

Use the one-month NextGIS Premium period to prove, document, and reproduce the
complete multi-account MapSafe community workflow:

1. a field data custodian publishes a public OpenPGP key;
2. authorised community members publish and discover their public keys;
3. the custodian publishes halo-masked and hexagonal-binned datasets for the
   community;
4. the custodian encrypts the original dataset for explicitly selected recipients;
5. MapSafe uploads the `.pgp` package and its manifest with recipient-specific ACLs;
6. an authorised recipient downloads, verifies, decrypts, and displays the original;
7. an anonymised-only recipient can access derived layers but cannot discover or
   download that protected-original package; and
8. a team member outside the MapSafe community cannot access community resources.

The trial is complete only when both positive and negative permission assertions
pass. A successful upload alone is not sufficient.

## 2. Fixed test identities

| Story identity | Operational role | Team member | `CommunityA` member | Protected-original recipient |
|---|---|---:|---:|---:|
| Steven | Field data custodian and publisher | Yes | Yes | Yes |
| Amber | Authorised researcher and precise-data recipient | Yes | Yes | Yes |
| BMA representative | Anonymised-data recipient for this test release | Yes | Yes | No |
| External non-member | Negative control | Yes | No | No |

The BMA representative's restriction is a decision for this test package, not a
general statement about BMA authority. The BMAs remain the sovereign parties in the
case study.

Use `Community A` as the display name and `CommunityA` as the Web GIS group name or
key. Do not enable automatic membership for all new users: the external account must
remain outside the group.

## 3. Non-negotiable security boundaries

- Never upload a private OpenPGP key, private blockchain key, recovery phrase, or
  passphrase to NextGIS Web.
- Never place passwords, access tokens, local account identifiers, or real email
  addresses in Git, screenshots, test reports, or the manuscript.
- Store only public OpenPGP keys, anonymised layers, encrypted `.pgp` packages,
  SHA-256 values, recipient identifiers/fingerprints, and blockchain references.
- Use the synthetic North Whangārei attributes and reviewed coordinates, not current
  sensitive field data.
- Keep each selected dataset in a separate `.pgp` package.
- Make the package registry readable only by its publisher and the NextGIS users
  mapped to the selected OpenPGP fingerprints.
- Fail closed if a fingerprint cannot be mapped to an accepted current community
  member.
- Use explicit resource ACLs. Do not grant access through `Authenticated`, `Everyone`,
  or `Guest`.
- Do not depend on NextGIS File Bucket. The cloud-compatible implementation stores
  public keys and encrypted packages as arbitrary-file attachments on vector-layer
  registry features.

## 4. Success criteria

The trial succeeds when all of the following are demonstrated and archived:

- The server reports Premium access management and all four team accounts can sign in.
- Steven, Amber, and the BMA representative resolve to the same Web GIS group ID;
  the external account does not.
- Three public-key records are visible to community members and absent for the external
  account.
- The downloaded key bytes and fingerprints exactly match the locally exported keys.
- The halo layer contains 23 displaced points and retains the expected non-spatial
  demonstration attributes.
- The H3 layer contains valid polygon cells and correct per-cell point counts.
- Steven, Amber, and the BMA representative can list and read anonymised layers;
  the external account cannot.
- Steven's protected-original package is visible and downloadable by Steven and Amber.
- The same package is absent from list results and denied by direct-resource requests
  for the BMA representative and external account.
- The downloaded `.pgp` SHA-256 equals the uploaded manifest value.
- Amber's local private key decrypts the package, Steven's signature validates, and the
  recovered GeoJSON bytes equal the pre-encryption original.
- A wrong or non-recipient private key is rejected without producing plaintext output.
- The optional Sepolia record decodes to the expected filename-bound SHA-256 assertion.
- App screenshots, browser screenshots, API results, ACL exports, resource IDs,
  timestamps, and SHA-256 manifests are preserved.
- The complete acceptance run passes at least three times after the final code change.

## 5. Preparation before starting the trial clock

Complete every item below before selecting **Start trial**:

### Accounts and governance

- [ ] Confirm four working NextGIS ID accounts for Steven, Amber, the BMA
  representative, and the external non-member.
- [ ] Confirm that each person can access their own email and complete any invitation
  or account verification.
- [ ] Decide who controls each account during the evaluation and record that outside
  the repository.
- [ ] Confirm that the four-account test and its screenshots are ethically acceptable
  for the research record.
- [ ] Prepare a password manager entry for each account; do not share passwords in chat
  or save them in the runbook.

### Device and code

- [ ] Use commit `aa91cc8` or a later explicitly recorded commit.
- [ ] Run `scripts/test-mapsafe-premium-readiness.ps1` and require
  `PREMIUM READINESS: PASS`.
- [ ] Connect one authorised Android device or emulator and record its serial, app
  version, Android version, and clock.
- [ ] Confirm the normal NextGIS Mobile startup sequence and login flow.
- [ ] Generate persistent local OpenPGP identities for Steven, Amber, and the BMA
  representative; export and independently record their public-key fingerprints.
- [ ] Back up the three passphrase-protected private keys outside NextGIS Web.
- [ ] Copy `roles.example.json` to `roles.local.json` and insert only Android account
  identifiers. Never add passwords.

### Hosted state

- [ ] Export or screenshot the existing `mapsafe.nextgis.com` resource tree.
- [ ] Record all pre-existing MapSafe resource IDs and owners.
- [ ] Download any existing test layers or package evidence that must be retained.
- [ ] Decide whether old Free-plan prototype resources will be retained, renamed, or
  replaced. Do not delete them until the Premium test evidence is exported.
- [ ] Confirm at least 1 GiB of free Web GIS storage even though the planned fixture is
  much smaller.

### Evidence workspace

- [ ] Create the ignored local directory `premium-trial/runs/YYYY-MM-DD-HHmm/` for
  each run by using `scripts/new-mapsafe-premium-run.ps1`.
- [ ] Prepare subfolders named `android`, `browser`, `api`, `acl`, `hashes`, and `notes`.
- [ ] Synchronise phone, emulator, workstation, and server-visible time as closely as
  practical.
- [ ] Fix portrait orientation and browser zoom for comparable screenshots.

## 6. Intended hosted hierarchy

MapSafe will create or repair this hierarchy through the authenticated publisher:

```text
Root resource (0)
└── MapSafe
    └── Community A
        ├── Public Keys
        │   ├── Steven-owned public-key registry
        │   ├── Amber-owned public-key registry
        │   └── BMA-representative-owned public-key registry
        ├── Anonymised Datasets
        │   ├── North Whangārei halo-masked vector layer
        │   └── North Whangārei H3 vector layer
        └── Encrypted Packages
            └── Steven-owned registry for one filename-bound `.pgp` package
                ├── manifest fields
                └── encrypted package attachment
```

Public keys and packages use vector-layer registry features and feature attachments.
Anonymised datasets are native vector layers so their points/polygons can be viewed in
NextGIS Web and downloaded through the standard layer API.

## 7. Intended ACL model

NextGIS Web requires read access along every parent path. All MapSafe rules use
non-propagating, explicit permissions so a readable parent does not accidentally expose
every child.

| Resource | Principal | Permissions | Apply to |
|---|---|---|---|
| Root path needed to reach MapSafe | `CommunityA` | Resource: Read | This resource only |
| `MapSafe` | `CommunityA` | Resource: Read | This resource only |
| `Community A` | `CommunityA` | Resource: Read, Create | This resource only |
| Artifact folders | `CommunityA` | Resource: Read, Create | This resource only |
| Public-key registries | `CommunityA` | Resource: Read; Data: Read | This resource only |
| Anonymised layers | `CommunityA` | Resource: Read; Data: Read | This resource only |
| Steven's protected package registry | Steven and Amber user IDs | Resource: Read; Data: Read | This resource only |

The external account receives no MapSafe rule. The BMA representative receives no rule
on this protected-package registry. Do not add explicit deny rules unless a verified
allow inherited from outside MapSafe cannot be removed; NextGIS applies deny after
allow, so an unnecessary deny can mask a legitimate rule.

## 8. Seven-day critical-path schedule

The Premium subscription remains available for one month, but the project target is to
finish implementation validation, evidence capture, and manuscript integration within
the first seven calendar days. The remaining subscription period is contingency, not
planned development time. This target is realistic only if all Day-0 prerequisites are
complete and the four account operators are available for invitations, logins, and
wallet approvals when required.

If the trial is activated on 4 October 2026 in New Zealand, the target completion window
is 4--10 October 2026. Record the actual expiry shown by NextGIS rather than calculating
it from these dates.

### Day 0 — final go/no-go review

Do this before activation:

1. Run the readiness script.
2. Verify the four accounts and local OpenPGP identities.
3. Confirm the evidence directory, device, and backup.
4. Confirm that no real sensitive dataset will be used.
5. Start the trial only if all checks pass.

### Day 1 — activate Premium, configure accounts, and exchange keys

**Morning**

1. Activate the one-month Premium trial.
2. Screenshot the subscription page and record the exact expiry date/time shown by
   NextGIS; do not estimate it from the start date.
3. Record plan limits, storage use, server version, and supported API routes.
4. Invite Amber, the BMA representative, and the external account to Steven's team.
5. Have each account accept the invitation and sign in once.
6. Create `Community A` / `CommunityA` without automatic new-user membership.
7. Add Steven, Amber, and the BMA representative; leave the external account outside.

**Afternoon**

8. Add all four accounts to Android through the normal NextGIS Mobile account flow.
9. Select `Community A` under the three member accounts.
10. Publish the public keys of Steven, Amber, and the BMA representative separately.
11. Refresh the directory under every account and compare complete fingerprints.
12. Test external list and direct-resource denial.
13. Restart the app and repeat discovery to exercise membership and key caches.
14. Export Day-1 resource IDs, ACLs, API responses, screenshots, and hashes.

**Day-1 exit criterion:** four working team users, exactly three `CommunityA` members,
three correct public-key records, no private key material, and external access denied.

### Day 2 — publish and verify anonymised community layers

1. Load the 23-point North Whangārei source as Steven.
2. Create one halo-masked layer using the manuscript parameters and save it.
3. Create one H3 layer using the manuscript resolution and save it.
4. Upload both through **Upload to Community**.
5. As Steven, Amber, and the BMA representative, list, open, and download both layers.
6. As the external account, test both list discovery and direct resource IDs.
7. In NextGIS Web, verify geometry, feature/cell counts, bounds, fields, ownership, and
   effective permissions.
8. Capture browser and Android evidence before changing anything.
9. Export layer GeoJSON and compare point/cell counts and SHA-256 values locally.
10. Record any network or rendering defect immediately so it can be fixed on Day 5.

**Day-2 exit criterion:** community members receive correct anonymised representations;
external list and direct access are denied.

### Day 3 — recipient-restricted encryption and access

1. As Steven, choose the original North Whangārei GeoJSON.
2. Refresh community keys and visually compare Amber's fingerprint.
3. Select Steven and Amber only, enable signing, and encrypt once.
4. Record plaintext SHA-256, encrypted SHA-256, filename, size, recipient fingerprints,
   signer fingerprint, and local save time.
5. Upload the `.pgp` package and manifest.
6. Confirm the server registry ACL names only Steven and Amber user IDs.
7. As Amber, list and download the package; verify the local SHA-256; decrypt with
   Amber's local private key; verify Steven's signature; compare recovered bytes.
8. Repeat as Steven.
9. As the BMA representative and external account, verify the package is absent from
   lists and direct resource/feature/attachment requests are denied.
10. Try the BMA private key against a separately obtained local copy and confirm that
    decryption fails without creating an output layer.
11. Repeat the complete authorised path after an app restart.
12. Export the package registry, attachment metadata, ACLs, hashes, and screenshots.

**Day-3 exit criterion:** exact recovery for Steven and Amber; discovery, download, and
decryption denial for non-recipients.

### Day 4 — notarisation and high-risk failure tests

1. Connect the approved external wallet to Sepolia.
2. Verify network, chain ID, contract, filename, and local package SHA-256 before signing.
3. Approve one transaction and retain its receipt, block number, transaction hash, and
   explorer URL.
4. Retrieve and decode the transaction through MapSafe.
5. Confirm both the bound filename and digest match the local package.
6. Update the community package manifest with network, contract, and transaction fields.
7. Test one deliberately incorrect transaction reference and one filename/hash mismatch.
8. Run the highest-risk controlled failures:
   - direct access by a user who knows the resource ID;
   - corrupted package download and hash mismatch;
   - package publication with an unmapped fingerprint, which must fail closed;
   - removed community member and stale local membership cache;
   - wrong private key and wrong passphrase;
   - interrupted upload/download followed by retry; and
   - expired Android authentication followed by re-login.
9. Run the remaining lower-risk checks if the high-risk set passes: duplicate key,
   replacement fingerprint, Unicode filename, device restart, and server-error recovery.

For membership-removal testing, publish a fresh disposable package. Removing a user
from NextGIS must prevent future server access, but it cannot revoke a package that the
user already downloaded or decrypt a ciphertext created for a different key.

**Day-4 exit criterion:** the correct notarisation record passes, mismatches fail,
recipient restrictions survive lifecycle changes, and no failure produces plaintext or
widens access.

### Day 5 — defect correction and repeatability

1. Triage Days 1--4 results into critical, manuscript-blocking, and cosmetic defects.
2. Fix critical and manuscript-blocking defects only; defer decorative changes.
3. Add a regression test before each production-code correction.
4. Rerun the readiness gate and the affected negative ACL assertions after every fix.
5. Run the complete acceptance suite once on the emulator and once on the physical
   Samsung handset with fresh timestamped resources.
6. Record upload, listing, download, hash, verification, and decryption times
   separately; do not combine network and local cryptographic measurements.
7. Investigate every intermittent failure rather than selecting only the fastest run.

**Day-5 exit criterion:** no critical or manuscript-blocking defect remains and the full
workflow passes on both target environments.

### Day 6 — final evidence and manuscript integration

1. Run the screenshot checklist under fixed orientation and browser zoom.
2. Capture all six Premium-only Android states and six browser states.
3. Prepare the three-panel community figure: resource tree, anonymised layers, and
   Amber's authorised package view.
4. Preserve the BMA and external negative evidence in the evaluation archive.
5. Calculate SHA-256 for every original screenshot and record device/server timestamps.
6. Redact only copies; preserve untouched originals in the research archive.
7. Replace provisional manuscript wording with observed results.
8. Insert the final Premium screenshots and update captions.
9. Compile and visually inspect the manuscript PDF.
10. Prepare a concise reproducibility appendix with server version, app commit, test
   commands, resource schema, and anonymised evidence hashes.

**Day-6 exit criterion:** the evidence archive and manuscript contain only observed,
traceable claims and final screenshots.

### Day 7 — release candidate, final repetitions, and archive

1. Freeze a release-candidate commit and record its full hash.
2. Run the complete four-account acceptance suite three times without code changes.
3. Require every row of `acl-test-matrix.csv` to pass in all three runs.
4. Export the full MapSafe resource hierarchy, native layers, registry features,
   attachments, ACL JSON, user/group IDs, resource IDs, and test reports.
5. Confirm exported package hashes against the recorded manifests.
6. Save two verified evidence backups in separate locations.
7. Commit and push the final code, runbook, non-sensitive evidence manifest, manuscript
   source, and PDF.
8. Record unresolved cosmetic work separately so it cannot delay completion.
9. If cleaning up, remove only timestamped test resources after recording their IDs and
   confirming the final paper no longer depends on the live URL.

**Day-7 completion criterion:** three clean end-to-end runs, two verified evidence
backups, final manuscript PDF, and a pushed release-candidate commit.

### Remaining trial period — contingency only

Keep the remaining subscription days available for reviewer-requested screenshots,
unexpected NextGIS changes, or a narrowly scoped defect discovered after the sprint.
Do not deliberately postpone a required acceptance test beyond Day 7. Before the trial
expires, record the subscription status and decide whether to subscribe or allow it to
end; do not delete hosted evidence until the two backups have been verified.

## 9. Commands

### Offline readiness

```powershell
.\scripts\test-mapsafe-premium-readiness.ps1
```

### Four-account acceptance

```powershell
.\scripts\run-mapsafe-premium-acl-test.ps1 `
  -GuardianAccount "ANDROID_ACCOUNT_FOR_STEVEN" `
  -PreciseRecipientAccount "ANDROID_ACCOUNT_FOR_AMBER" `
  -AnonymisedRecipientAccount "ANDROID_ACCOUNT_FOR_BMA_REPRESENTATIVE" `
  -OutsiderAccount "ANDROID_ACCOUNT_FOR_EXTERNAL_NON_MEMBER" `
  -CommunityName "Community A"
```

The arguments are Android account identifiers, not passwords. The test deliberately
retains timestamped evidence resources.

### Stable manuscript screenshots

```powershell
.\scripts\capture-mapsafe-manuscript-screens.ps1 -RestartDevice
```

## 10. Evidence required for every acceptance run

Create a short manifest containing:

- run ID and UTC/local timestamps;
- Git commit and app version;
- device serial alias, model, and Android version;
- NextGIS Web server/API version;
- trial expiry date shown by the account;
- role-to-NextGIS-user-ID mapping using pseudonymous labels;
- community group ID and resource IDs;
- public-key fingerprints;
- source, anonymised, encrypted, downloaded, and recovered SHA-256 values;
- package filename, recipient IDs/fingerprints, signer fingerprint, and byte size;
- transaction hash, network, chain ID, and contract address, if notarised;
- ACL export for every hierarchy resource;
- outcome for every row of `acl-test-matrix.csv`;
- screenshots and their SHA-256 values; and
- defects, retries, and deviations from this plan.

Never record a password, private key, passphrase, recovery phrase, wallet seed, access
token, or unredacted personal email address.

## 11. Stop/go rules

Stop the trial run and fix the cause before continuing if:

- an external or anonymised-only account can list or directly read a protected package;
- a private key or passphrase appears in a request, log, screenshot, or hosted resource;
- an encrypted package is uploaded without a complete recipient-to-user mapping;
- the downloaded SHA-256 differs from the manifest;
- decryption succeeds for a non-recipient identity;
- source and recovered bytes differ unexpectedly;
- the app silently falls back from restricted to community-wide access;
- trial expiry or account limits are unclear; or
- a test modifies pre-existing resources outside the MapSafe hierarchy.

The trial may continue after an expected negative assertion returns a clear 401, 403,
404, empty listing, or MapSafe denial, provided the result is captured and the server
does not disclose protected metadata.

## 12. Official platform assumptions to verify on Day 1

- Premium includes team/access management and multiple users.
- Resource ACLs are available on Premium and are distinct from global Web GIS
  administration permissions.
- Users added to a team receive no resource access unless explicitly covered by a
  user/group rule.
- Read access to a child depends on read access to every parent resource.
- `Allow` rules are applied before `Deny`, so `Deny` has priority.
- Vector layers support feature attachments and attachment download endpoints for
  arbitrary file types.
- File Bucket is not required by MapSafe and must not be assumed available on the
  cloud Premium instance.

References:

- https://nextgis.com/pricing-base/
- https://docs.nextgis.com/docs_ngweb/source/users.html
- https://docs.nextgis.com/docs_ngweb/source/permissions.html
- https://docs.nextgis.com/docs_ngweb_dev/doc/developer/create.html
- https://docs.nextgis.com/docs_ngweb_dev/doc/developer/resource.html
- https://docs.nextgis.com/docs_ngweb_dev/doc/developer/file_upload.html
