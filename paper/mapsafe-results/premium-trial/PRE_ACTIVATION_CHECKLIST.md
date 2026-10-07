# MapSafe Premium pre-activation checklist

Prepared: 3 October 2026  
Trial state: **not activated**  
Baseline commit: `aa91cc8` or a later recorded commit

Latest local readiness run: **PASS**, 3 October 2026. The complete transcript is in
the ignored preflight workspace
`runs/2026-10-03-135854-preflight/notes/readiness-gate.txt`.

Do not activate Premium until every **GO** item below is complete. This worksheet
contains identifiers and fingerprints only; passwords, passphrases, private keys,
wallet secrets, recovery phrases, and access tokens must never be entered here.

## Current readiness summary

| Area | Current state | GO condition |
|---|---|---|
| Local implementation | Prepared | Readiness script ends with `PREMIUM READINESS: PASS` on the final pre-trial commit |
| Case-study fixture | Prepared | The reproducible North Whangarei fixture validates as 23 points with synthetic demonstration attributes |
| Four NextGIS accounts | User action required | Steven, Amber, BMA representative, and external-control accounts each sign in successfully |
| Account-to-role mapping | Waiting for account identifiers | `roles.local.json` contains the four Android account names and no passwords |
| Persistent OpenPGP identities | Device action required | Steven, Amber, and BMA each have a distinct key fingerprint in an isolated app installation/profile |
| Protected key backups | Device and user action required | Each passphrase-protected private-key backup has two verified copies outside NextGIS Web |
| Android environments | Not connected at the 3 October audit | Three isolated MapSafe installations/profiles are available for the manual/video workflow |
| Automated acceptance identities | Prepared | The instrumentation test generates three temporary test identities independently of manual identities |
| Evidence workspace | Prepared by script | A timestamped ignored run directory and manifest exist before activation |
| Hosted Free-plan state | Must be captured immediately before activation | Resource tree, IDs, owners, storage use, and retained files are recorded without deletion |

## A. Four NextGIS accounts

Record only the account identifiers used by Android AccountManager. Account holders
should keep passwords in their own password manager and complete verification or team
invitation themselves.

| Story role | Android account identifier | Can sign in | Email verified | Operator confirmed | Community membership after activation |
|---|---|---:|---:|---:|---|
| Steven (field data custodian) |  | [ ] | [ ] | [ ] | `CommunityA` member |
| Amber (authorised researcher) |  | [ ] | [ ] | [ ] | `CommunityA` member |
| BMA representative (anonymised-data recipient) |  | [ ] | [ ] | [ ] | `CommunityA` member |
| External non-member (negative control) |  | [ ] | [ ] | [ ] | Team member, not in `CommunityA` |

After the identifiers are known, copy `roles.example.json` to the ignored
`roles.local.json` and replace only the four placeholder account names. Do not add
password fields. The external account must be a real separate account; signing the
same account into another browser profile does not test access isolation.

## B. Android environment allocation

One MapSafe installation stores one persistent local identity in its private,
non-backed-up application directory. Assign separate installations or Android
profiles so switching NextGIS accounts cannot accidentally reuse another role's
private identity.

| Environment | Role | Device/AVD serial alias | Android version | App version/commit | Ready |
|---|---|---|---|---|---:|
| Physical Samsung handset | Steven |  |  |  | [ ] |
| Emulator/AVD 1 | Amber |  |  |  | [ ] |
| Emulator/AVD 2 | BMA representative |  |  |  | [ ] |
| Separate browser profile or disposable Android profile | External non-member |  |  | n/a or same build | [ ] |

For each Android environment:

- [ ] Confirm automatic date/time and time zone.
- [ ] Install the same signed debug/research build from the final pre-trial commit.
- [ ] Confirm the normal NextGIS Mobile startup screen appears before opening MapSafe.
- [ ] Add only the intended role's NextGIS account for manual/video evidence.
- [ ] Record a pseudonymous serial alias; keep raw serials out of manuscript images.
- [ ] Disable notification previews and remove unrelated personal accounts before
  screenshots or recording.

The automated four-account instrumentation test is different: it may use one
authorised test device containing all four AccountManager accounts and creates its
own temporary identities. Those temporary keys are evidence fixtures, not the three
persistent identities used in the manual demonstration.

## C. Three OpenPGP identities and backups

Generate the identities inside MapSafe through **Security and Sharing -> Encryption
Identity**. Use role-specific labels, a unique passphrase of at least 12 characters,
and the eye controls to compare both entries. The human operator retains the
passphrase; it must not be shared in chat, screenshots, logs, or the repository.

| Role | Full public-key fingerprint | Public key exported | Protected private backup filename | Backup SHA-256 recorded | Restore tested | Ready |
|---|---|---:|---|---:|---:|---:|
| Steven |  | [ ] |  | [ ] | [ ] | [ ] |
| Amber |  | [ ] |  | [ ] | [ ] | [ ] |
| BMA representative |  | [ ] |  | [ ] | [ ] | [ ] |

For every identity:

1. Export the ASCII-armoured public key and record its complete fingerprint through a
   second channel or independent visual comparison.
2. Export the passphrase-protected private-key backup. The exported key remains
   protected by its OpenPGP passphrase; the app-private working copy is also wrapped
   with a non-exportable Android Keystore key.
3. Save two backup copies in separate controlled locations, such as encrypted
   removable storage and an organisation-managed encrypted archive. Never upload the
   private backup to NextGIS Web.
4. Calculate and record the backup file's SHA-256 without opening or printing its
   contents.
5. Test restoration on a disposable app installation/profile using the retained
   passphrase. Compare the restored fingerprint, then clear that disposable profile.
6. Publish only the public key during the Premium trial.

## D. Evidence and backups

- [ ] Create the preflight workspace:

  ```powershell
  .\scripts\new-mapsafe-premium-run.ps1 -RunLabel preflight
  ```

- [ ] Open its `manifest.md` and fill in non-secret device, account-role, fingerprint,
  timestamp, and commit fields.
- [ ] Confirm that `paper/mapsafe-results/premium-trial/runs/` is ignored by Git.
- [ ] Prepare two independent encrypted destinations for the final evidence archive.
- [ ] Fix screenshot portrait orientation, browser zoom, and display scaling.
- [ ] Synchronise workstation, phone, emulators, and browser-visible time.
- [ ] Save untouched originals before making any redacted publication copies.

## E. Hosted-state capture before activation

- [ ] Screenshot the current `mapsafe.nextgis.com` resource tree.
- [ ] Export a list of pre-existing resource IDs, owners, types, and parent IDs.
- [ ] Record current plan, server version, storage used/free, and capture time.
- [ ] Download any existing anonymised layers or package evidence that must be kept.
- [ ] Record whether each old prototype resource will be retained, renamed, or later
  removed. Do not delete it during preflight.
- [ ] Confirm that the North Whangarei fixture is the only case-study source used for
  the trial; no live sensitive field data is permitted.

## F. Final readiness gate

Run from the repository root:

```powershell
.\scripts\test-mapsafe-premium-readiness.ps1
```

Record the output file in the preflight evidence directory. The go/no-go decision is:

- **GO** only when the script passes, all four accounts can sign in, three persistent
  identities and verified backups exist, device assignments are complete, and the
  pre-existing hosted state has been captured.
- **NO-GO** if any account, private-key backup, device/profile, evidence destination,
  or hosted-state record is missing.

## Final approval record

| Item | Value |
|---|---|
| Preflight run ID |  |
| Final Git commit |  |
| Readiness result |  |
| Four accounts confirmed by |  |
| Three identity fingerprints independently checked by |  |
| Backup restore tests confirmed by |  |
| Hosted-state snapshot completed at |  |
| Premium activation authorised by |  |
| Decision (`GO` / `NO-GO`) |  |
