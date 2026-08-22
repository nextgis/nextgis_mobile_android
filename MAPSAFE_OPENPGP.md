# MapSafe OpenPGP design

MapSafe implements standard, streaming OpenPGP internally with Bouncy Castle.
It does not require OpenKeychain and it does not invent a custom encrypted file
format.

## Compatibility profile

- OpenPGP version 4 transferable keys
- RSA-3072 signing/certification primary key
- RSA-3072 encryption subkey
- AES-256-GCM content encryption using the RFC 9580 v2 SEIPD profile
- SHA-256 signatures
- ZIP compression
- chunked authenticated-encryption packets with a final summary tag
- binary `.pgp` encrypted packages
- ASCII-armoured public and protected secret-key exports

New packages use RFC 9580 v6 PKESK packets and a v2 SEIPD AES-256-GCM payload.
The content is still encrypted once, with one recipient-specific encrypted
session-key packet for every selected recipient. Decryption remains compatible
with the earlier AES-256/OpenPGP-MDC MapSafe packages. External GnuPG and
OpenKeychain clients must support RFC 9580 before they can open newly created
packages, so cross-client validation remains a release gate.

## Local key custody

The transferable OpenPGP secret keyring is encrypted with the user's recovery
passphrase. MapSafe's app-private copy is additionally encrypted using AES-GCM
with a non-exportable key held by Android Keystore.

New identities use an explicitly strengthened iterated-and-salted SHA-256 S2K
for the transferable secret key. The recovery passphrase is not stored. A
passphrase-protected secret-key export is therefore essential before an app
uninstall, device reset, or device loss. MapSafe cannot recover a forgotten
passphrase.

Private-key material must be unlocked in application memory for signing and
decryption. Byte and character arrays are cleared where practical, but the
Android/JVM runtime cannot guarantee removal of every transient memory copy.

## Plaintext handling

Encryption streams directly from the selected source document to the fixed shared
`Files > Downloads > MapSafe` folder. When a map layer is selected, MapSafe first exports it as
WGS84 GeoJSON in app-private cache storage, supplies that file to the same
OpenPGP workflow, and deletes the temporary export after use. Decryption first
streams to an app-cache temporary file. Only after OpenPGP integrity verification
succeeds is that temporary plaintext copied to `Downloads/MapSafe`. The temporary
file is deleted in `finally`, and failed partial shared-storage outputs are removed.
Masking and hex-binning exports use the same folder; Android destination pickers are
therefore not shown for normal MapSafe dataset outputs.

After a successful on-chain hash comparison, Verify can hand the same encrypted
document URI and expected SHA-256 directly to Decrypt. The continuation remains
disabled for pending, invalid, or mismatched records. Decrypt hashes the carried
document again before requesting the private-key passphrase and binds the final
decryption stream to that hash; if the document changes, no plaintext output is
released and the user must verify it again.

After a `.geojson` or `.json` package passes the integrity check, MapSafe offers
to import it as a local NextGIS vector layer and zoom to its extent. A package
with an invalid signature is not offered for import. Unsigned packages and
packages from an unknown signer display their trust limitation before the user
chooses whether to import them.

The OpenPGP screen uses `FLAG_SECURE` so passphrases and key-management screens
are excluded from normal screenshots and screen recording.

## Signature meaning

MapSafe reports these states independently from successful decryption:

- valid signature from an imported key;
- invalid signature;
- signature made by an unknown key;
- unsigned package.

Importing a public key does not establish human or organisational trust. Users
must compare the complete displayed fingerprint through an independent channel.

## NextGIS public-key exchange

MapSafe can use an existing NextGIS account to publish and discover public keys
for a NextGIS authentication group. Authentication and storage remain separate:
the authentication group supplies the current member IDs, while a resource group
named `mapsafe_keys_<group-id>` stores one `file_bucket` per publishing member.
Each bucket contains only `public-key.asc` and `key-metadata.json`. Private keys
and passphrases are rejected from this workflow and are never uploaded.

The directory resource grants the authentication group propagated resource/data
read access and permission to create child resources. Each member-owned bucket
can only be updated by its owner under normal NextGIS ownership rules. During
synchronisation, MapSafe verifies all of the following before caching a key:

- the manifest group is the selected authentication group;
- the manifest user is a current group member;
- the same user owns the NextGIS file bucket;
- there is exactly one bucket for the user;
- the downloaded OpenPGP fingerprint matches the manifest;
- the key has a currently usable encryption key.

Server storage is discovery, not trust. A newly discovered fingerprint must be
confirmed through an independent channel before it is offered for encryption.
An accepted fingerprint is pinned locally. A different fingerprint, a removed
member, a missing bucket, a revoked entry, or duplicate buckets blocks that
identity from new encryption until the state is resolved. Superseded key files
remain locally available for historical signature verification.

Offline encryption can use the last accepted cache. Group membership and key
changes affect future packages and cannot revoke access to packages already
encrypted for an earlier recipient.

## Manual two-recipient test

1. On device A, create an identity and export its public key and protected
   secret-key backup.
2. Create a second disposable identity on another MapSafe installation or with a
   compatible OpenPGP client, then import its public key on device A.
3. Encrypt the bundled Suva GeoJSON or another 1-5 MB document for both keys and
   enable signing.
4. Confirm device A can decrypt the package and reports a valid signature.
5. Import the second recipient's protected secret key on device B and confirm it
   can decrypt the exact same `.pgp` file.
6. Confirm a third, non-recipient identity cannot decrypt it.
7. Flip a byte in a test copy and confirm MapSafe rejects the modified package.

Never use production private keys for automated tests. Unit tests generate
disposable identities at runtime.

## Current limitations

- one local identity per MapSafe installation;
- no revocation-certificate UI yet;
- NextGIS directory fingerprints still require explicit out-of-band confirmation;
- no organisation certification authority or centrally signed-key policy yet;
- NextGIS `file_bucket` availability and permissions depend on the server edition
  and administrator configuration;
- selected-layer sharing currently uses GeoJSON and does not include attachments,
  renderer configuration, or edit-form definitions;
- external GnuPG/OpenKeychain interoperability still requires manual validation.
