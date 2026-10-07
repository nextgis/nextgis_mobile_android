# MapSafe Premium evidence manifest

Copy created by `scripts/new-mapsafe-premium-run.ps1`. Do not record passwords,
private keys, passphrases, wallet secrets, recovery phrases, access tokens, or
unredacted personal email addresses.

## Run identity

| Field | Value |
|---|---|
| Run ID | `{{RUN_ID}}` |
| Purpose | `{{RUN_LABEL}}` |
| Created (local) | `{{CREATED_LOCAL}}` |
| Created (UTC) | `{{CREATED_UTC}}` |
| Git commit | `{{GIT_COMMIT}}` |
| Git branch | `{{GIT_BRANCH}}` |
| App version |  |
| NextGIS server/API version |  |
| Premium expiry shown by NextGIS |  |

## Environments

| Role | Pseudonymous device alias | Android/browser version | NextGIS user ID or pseudonym | OpenPGP fingerprint |
|---|---|---|---|---|
| Steven |  |  |  |  |
| Amber |  |  |  |  |
| BMA representative |  |  |  |  |
| External non-member |  |  |  | n/a |

## Hosted resources

| Purpose | Resource ID | Parent ID | Owner user ID/pseudonym | ACL export file |
|---|---|---|---|---|
| MapSafe root |  |  |  |  |
| Community A |  |  |  |  |
| Public Keys |  |  |  |  |
| Anonymised Datasets |  |  |  |  |
| Encrypted Packages |  |  |  |  |

## Artifact integrity

| Artifact | Local filename/pseudonym | Byte size | SHA-256 | Hosted/downloaded/recovered comparison |
|---|---|---:|---|---|
| Original source |  |  |  |  |
| Halo-masked layer |  |  |  |  |
| H3 layer |  |  |  |  |
| Encrypted package |  |  |  |  |
| Downloaded package |  |  |  |  |
| Recovered original |  |  |  |  |

## Package and notarisation

| Field | Value |
|---|---|
| Filename-bound assertion |  |
| Recipient fingerprints/user IDs |  |
| Signer fingerprint |  |
| Network and chain ID |  |
| Contract address |  |
| Transaction hash and block |  |
| Explorer URL |  |

## Acceptance outcome

Attach the completed copy of `acl-test-matrix.csv` in `acl/` and record:

| Check | Result/evidence |
|---|---|
| Steven lists/downloads protected package |  |
| Amber lists/downloads/decrypts exact original |  |
| BMA can access anonymised outputs |  |
| BMA cannot list/directly download protected package |  |
| External account cannot access MapSafe community resources |  |
| Wrong key/passphrase produces no plaintext |  |
| Downloaded package hash equals manifest |  |
| Signature and filename-bound notarisation verify |  |

## Evidence index and deviations

List screenshots, API exports, ACLs, hashes, test output, defects, retries, and any
deviation from the runbook. Keep untouched originals; put redacted copies in a
clearly named subfolder.

