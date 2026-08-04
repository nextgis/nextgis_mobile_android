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

- MapSafe > Load sample points (Suva) creates, selects, and zooms to a local
  layer containing 30 synthetic points in six clusters. Attributes include
  site ID/name, category, sensitivity, and household count.
- The main menu opens the MapSafe dialog hierarchy.
- Donut masking uses bounded minimum (0-1,000 m) and maximum (1,000-5,000 m)
  sliders, creates a blue point layer, copies source attributes, records masking
  metadata, and preserves the source layer.
- Hexabinning creates blue counted polygon layers. ARM/ARM64 devices use H3;
  x86/x86_64 emulators automatically use a native-free Web Mercator hex grid
  with comparable resolution-dependent cell sizes.
- Self-contained Bouncy Castle OpenPGP supports RSA-3072 identity creation,
  protected key backup import/export, recipient public-key import, streaming
  multi-recipient encryption, optional signing, decryption, integrity checking,
  and independent signature status. Local secret keys are passphrase protected
  and additionally wrapped using Android Keystore.
- Encrypt > Encrypt selected map layer exports the active vector layer as WGS84
  GeoJSON and opens recipient selection without a second input-file picker.
- Successfully decrypted GeoJSON can be imported as a local vector layer; the
  imported layer is selected and the map zooms to its extent. Invalidly signed
  data is not offered for import.
- Nested MapSafe screens include Back controls that return to their parent menu.
- The older standalone AES-GCM helper is retained only as legacy/local utility
  code and is not used for OpenPGP sharing.
- Selected-layer packages currently omit attachments, styles, and form metadata.

Feature code remains isolated in this package; only small menu and workflow
bridges are kept in `MainActivity` and `MapFragment`.
