# MapSafe Mobile module

This package mirrors the MapSafe web and QGIS plugin structure.

## Safeguard features

- Anonymise
  - Donut masking
  - Hexabinning
- Encrypt
- Blockchain notarisation

## Access features

- Verify hash from blockchain
- Decrypt
- View

## Current integration status

- **Use sample dataset** on the workflow chooser, Anonymise screen, and guided
  Protect & Share screen creates,
  selects, and zooms to a compatible local vector layer containing 30 synthetic
  Suva points in six clusters. Attributes include site ID/name, category,
  sensitivity, and household count. Choosing it from Anonymise returns directly
  with that layer selected, avoiding ambiguity when an imported layer is unsupported.
- The main menu opens the MapSafe dialog hierarchy.
- Donut masking uses bounded minimum (0-1,000 m) and maximum (1,000-5,000 m)
  sliders, creates a blue point layer, copies source attributes, records masking
  metadata, and preserves the source layer. Each attempt displays the inverted
  Spruill privacy score (`100 - disclosure risk`) with its parent-nearest breakdown.
  The user can accept the candidate or remask; every remask uses the original precise
  layer and creates a separate candidate, so displacement is never compounded.
- Hexabinning creates blue counted polygon layers. ARM/ARM64 devices use H3;
  x86/x86_64 emulators automatically use a native-free Web Mercator hex grid
  with comparable resolution-dependent cell sizes.
- Self-contained Bouncy Castle OpenPGP supports RFC 9580 AES-256-GCM packages,
  RSA-3072 identity creation, protected key backup import/export, recipient
  public-key import, streaming multi-recipient encryption, optional signing,
  decryption, integrity checking, and independent signature status. Local
  secret keys are passphrase protected and additionally wrapped using Android
  Keystore.
- Existing NextGIS accounts can publish the local public key to a group-specific
  resource directory and synchronise member keys. MapSafe validates bucket
  ownership and current authentication-group membership, pins explicitly accepted
  fingerprints, and blocks changed, missing, revoked, duplicate, or removed-member
  keys from new recipient selection.
- Encrypt > Encrypt selected map layer exports the active vector layer as WGS84
  GeoJSON and opens recipient selection without a second input-file picker.
- Successfully decrypted GeoJSON can be imported as a local vector layer; the
  imported layer is selected and the map zooms to its extent. Invalidly signed
  data is not offered for import.
- Nested MapSafe screens include fixed, labelled Back controls that return to their
  actual parent menu and remain below the Android status bar. Android's system Back
  action follows the same hierarchy. The supplied MapSafe icon is
  used for the launcher and compact headers; the full wordmark appears on the main chooser.
- The older standalone AES-GCM helper is retained only as legacy/local utility
  code. Recipient sharing now uses OpenPGP's standard v2 SEIPD AES-256-GCM
  container rather than a second custom package format.
- Selected-layer packages currently omit attachments, styles, and form metadata.

Feature code remains isolated in this package; only small menu and workflow
bridges are kept in `MainActivity` and `MapFragment`.
