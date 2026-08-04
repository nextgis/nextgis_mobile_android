# MapSafe OpenPGP design

MapSafe implements standard, streaming OpenPGP internally with Bouncy Castle.
It does not require OpenKeychain and it does not invent a custom encrypted file
format.

## Compatibility profile

- OpenPGP version 4 transferable keys
- RSA-3072 signing/certification primary key
- RSA-3072 encryption subkey
- AES-256 content encryption
- SHA-256 signatures
- ZIP compression
- integrity-protected encrypted data packets
- binary `.pgp` encrypted packages
- ASCII-armoured public and protected secret-key exports

The content is encrypted once with a fresh random AES session key. Bouncy Castle
adds one public-key-encrypted session-key packet for every selected recipient.
AEAD/version-6 output is deliberately disabled until interoperability has been
validated with the organisation's supported GnuPG and OpenKeychain versions.

## Local key custody

The transferable OpenPGP secret keyring is encrypted with the user's recovery
passphrase. MapSafe's app-private copy is additionally encrypted using AES-GCM
with a non-exportable key held by Android Keystore.

The recovery passphrase is not stored. A passphrase-protected secret-key export
is therefore essential before an app uninstall, device reset, or device loss.
MapSafe cannot recover a forgotten passphrase.

Private-key material must be unlocked in application memory for signing and
decryption. Byte and character arrays are cleared where practical, but the
Android/JVM runtime cannot guarantee removal of every transient memory copy.

## Plaintext handling

Encryption streams directly from the selected source document to the selected
encrypted output. When a map layer is selected, MapSafe first exports it as
WGS84 GeoJSON in app-private cache storage, supplies that file to the same
OpenPGP workflow, and deletes the temporary export after use. Decryption first
streams to an app-cache temporary file. Only after OpenPGP integrity verification
succeeds is that temporary plaintext copied to the user's selected destination.
The temporary file is deleted in `finally`. Failed/cancelled output documents
are truncated where their document provider supports it.

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
- no organisation trust or key-certification database yet;
- no automatic key directory or server upload;
- selected-layer sharing currently uses GeoJSON and does not include attachments,
  renderer configuration, or edit-form definitions;
- external GnuPG/OpenKeychain interoperability still requires manual validation.
