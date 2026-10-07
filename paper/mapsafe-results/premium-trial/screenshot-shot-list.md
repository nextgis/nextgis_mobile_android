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

The automated Premium set was captured on 4 October 2026 after
`MapSafePremiumAclDeviceTest` passed. The seven PNG files and their SHA-256 manifest
are in `../screenshots/premium-community-2026-10-04/`. Reproduce them with
`scripts/capture-mapsafe-premium-screens.ps1`; the script retains all earlier
manuscript screenshots.

1. `00-steven-security-community-keys`: local protected identity plus the three
   discovered community public-key identities.
2. `01-steven-upload-community`: Steven's public key, halo layer, and hexbin layer
   selected together; stored encrypted packages remain separately selectable.
3. `02-steven-community-public-keys` and `03-steven-community-datasets`: the live
   Community Packages browser at its key and anonymised-dataset sections.
4. `04-amber-authorised-community-packages`: Amber can see the protected original and
   its `Download & verify` action alongside community-readable anonymised layers.
5. `05-bma-anonymised-community-access`: the BMA representative can download the
   halo/hexbin outputs and sees `No encrypted packages published`.
6. `06-external-user-community-denied`: the external account receives a clear
   membership/resource-permission denial.

The existing North Whangārei verification, decryption, and recovered-map images remain
the UI evidence for the post-download cryptographic stages; the Premium acceptance log
provides the exact live Amber download/decryption/signature assertions.

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
