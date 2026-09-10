# Premium evidence and manuscript screenshot list

Use portrait orientation on one Android device and the same browser zoom for all Web
GIS captures. Keep real passwords, private keys, passphrases, wallet balances, email
addresses, and access tokens out of every frame. Preserve the original PNG files and
record their SHA-256 values.

## Automated Android set

Run `scripts/capture-mapsafe-manuscript-screens.ps1`. It captures the normal NextGIS
startup, combined Safeguard/Access interface, North Whangārei source layer, halo and
hexagonal results, identity and recipient screens, encryption, notarisation,
verification, decryption, and the recovered map. The new filenames begin with
`ms2026-north-whangarei-20260909-`; existing Suva screenshots are not overwritten.
The validated final offline run contains 31 PNG files plus SHA-256 values in
`../screenshots/manuscript-north-whangarei-20260910-final`. Use the script's
`-RestartDevice` option if an emulator has a stale transition surface.

## Premium-only Android evidence

Capture these after `MapSafePremiumAclDeviceTest` passes:

1. Steven (field data custodian): Security & Sharing with `Community A` selected and the
   three community public-key identities visible.
2. Steven: recipient confirmation showing only Steven and Amber for the original dataset.
3. Steven: Upload to Community confirmation distinguishing community-readable
   anonymised items from the recipient-restricted encrypted package.
4. Amber (authorised researcher and precise-data recipient): Community Packages showing the protected original,
   then successful hash checking, decryption, and the recovered North Whangārei map.
5. BMA representative (anonymised-data recipient for this test): Community Packages
   showing halo/hexagonal layers but not Steven's protected-original package.
6. External non-member: a clear access-denied or empty-resource result for Community A.

## Browser evidence on mapsafe.nextgis.com

1. Resource tree expanded as `MapSafe / Community A / Public Keys`, `Anonymised
   Datasets`, and `Encrypted Packages`.
2. Public Keys showing separate records owned by Steven, Amber, and the BMA representative.
3. Anonymised Datasets showing both North Whangārei halo-masked and hexagonal layers;
   open their maps to confirm 23 points and multiple polygon cells respectively.
4. Encrypted Packages while signed in as Steven or Amber, showing the filename-bound
   package record, SHA-256, recipient IDs/fingerprints, network, and transaction fields.
5. The same folder while signed in as the BMA representative, demonstrating that the
   test package is absent.
6. The Community A path while signed in as the external non-member, demonstrating denied access.

For the manuscript, use a three-panel community figure: the expanded resource tree,
the anonymised layers, and Amber's authorised protected-package view. Keep the BMA
representative/external-user negative evidence in the evaluation material unless space
permits a fourth panel.
