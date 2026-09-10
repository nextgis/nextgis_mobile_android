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
- Access original, masked, or hexbin datasets

## Current integration status

- **Use sample dataset** on the workflow chooser and Anonymise screen creates,
  selects, and zooms to a compatible local vector layer containing 23 North
  Whangārei infected-tree points from the published case-study archive. Source
  coordinates are preserved and the demonstration attributes are explicitly
  synthetic. Choosing it from Anonymise returns directly
  with that layer selected, avoiding ambiguity when an imported layer is unsupported.
- The main menu opens the MapSafe dialog hierarchy.
- Safeguard and Access validate that an active map dataset is selected at the
  workflow chooser. If not, one early prompt lets the user return to the map or
  load the sample dataset and continue directly to the requested workflow.
- Donut masking uses one compact two-thumb 0-5,000 m range slider for the minimum
  and maximum displacement, creates a blue point layer, copies source attributes, records masking
  metadata, and preserves the source layer. Each attempt displays the inverted
  Spruill privacy score (`100 - disclosure risk`) with its parent-nearest breakdown.
  The user can stop, continue to encryption, or remask; every remask uses the original precise
  layer and creates a separate candidate, so displacement is never compounded.
- Hexabinning creates blue counted polygon layers. ARM/ARM64 devices use H3;
  x86/x86_64 emulators automatically use a native-free Web Mercator hex grid
  with comparable resolution-dependent cell sizes. Its result screen supports
  binning again from the same source, stopping, or continuing to encryption.
- Halo and hexbin configuration/result overlays occupy the upper half of the screen,
  keep the live map visible below, and scroll only their compact essential content.
  Applied-result panels can retract upwards to a slim expandable header for an
  almost full-map review. Unused switches, privacy sliders, legends, and explanatory
  sections were removed.
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
- Encrypt & Protect keeps the normal workflow focused on the dataset name, local
  identity, recipient-key controls, and the encryption action. Algorithm details and
  key backup/import remain outside this screen in Security & Sharing.
- The selected-layer path has been exercised end to end on the Pixel 9a emulator:
  a live 30-feature map layer was exported, encrypted, decrypted, re-imported, and
  selected again on the map with all features preserved.
- Successfully decrypted GeoJSON can be imported as a local vector layer; the
  imported layer is selected and the map zooms to its extent. Invalidly signed
  data is not offered for import.
- Nested MapSafe screens include fixed, labelled Back controls that return to their
  actual parent menu and remain below the Android status bar. Android's system Back
  action follows the same hierarchy. The supplied MapSafe icon is
  used for the launcher and compact headers; the full wordmark appears on the main chooser.
- Safeguard screens share an informational `Anonymise -> Encrypt -> Notarise`
  progress indicator. It shows context without forcing a guided workflow, and it is
  not displayed on the independent decryption path.
- Access Features uses the same numbered card layout as Safeguard Features. Access
  Dataset now builds a live catalogue from GeoJSON layers imported after successful
  OpenPGP integrity checking. It distinguishes original-detail, masked-point, and
  hexagon-aggregate representations and can select and zoom directly to each layer.
- Verify, Decrypt, and Access screens share a visible access-progress indicator.
  After a verified GeoJSON import, users can continue directly to the catalogue to
  compare available detail levels or open the imported layer immediately on the map.
- Optional workflow handoffs use the same `Stop` and `Next` actions after
  anonymisation, encryption, verification, and decryption.
- Verify can select an encrypted document through Android's document picker and
  calculate its SHA-256 directly from the content stream without copying the package
  into app storage. After local transaction-reference validation, it retrieves the
  transaction and receipt from the active HTTPS RPC, confirms the configured chain
  and contract, and compares the ABI-decoded record with the local hash.
- Sepolia, Ethereum Mainnet, and a custom EVM profile are available from the shared
  Verify/Notarise network-settings screen. Network name, environment, chain ID,
  HTTPS RPC endpoint, explorer origin, contract address, and contract interface are
  encrypted with Android Keystore in app-private, no-backup storage. Production
  profiles require an explicit warning confirmation. Wallet recovery phrases and
  private keys are neither requested nor stored.
- The Sepolia preset uses the public PublicNode RPC and the filename-bound MapSafe
  integrity registry at `0xdF7efaA8f01B5674e41534Da2bA4D7C56f65A0F2`.
  The QGIS plugin's former address remains available only for read-only legacy
  transaction-input verification. No QGIS private key or RPC credential is copied
  into the mobile app.
- The network-settings screen includes a read-only preflight. It calls
  `eth_chainId`, blocks the contract check if the returned chain differs from the
  profile, and then calls `eth_getCode` at the configured address. For the Location
  NFT v1 profile it also detects the `mintNFT(string)` and `locations(uint256)`
  selectors and uses a read-only `eth_call` to query ERC-721 support through
  ERC-165. Redirects are disabled and responses are bounded. Selector detection
  is compatibility evidence, not proof of contract behaviour.
- New records have the public form
  `<safe encrypted-package basename>_<64 lowercase hex characters>`. MapSafe
  verifies both values, which binds the asserted package name to its content hash;
  device paths and plaintext data remain excluded. The earlier hash-only
  `mapsafe:v1:sha256:<SHA-256>` form remains readable. The full profile, public
  filename trade-off, and evidential limits are documented in `BLOCKCHAIN_CONTRACT.md`.
- Verify strictly validates either a raw Ethereum transaction hash or a canonical
  transaction URL for the active profile's configured explorer. Other explorer
  origins, insecure URLs, credentials, mismatched ports, queries, fragments, and
  malformed paths are rejected locally before a blockchain request. Retrieval then
  accepts only a mined, successful, zero-value `mintNFT(string)` call to the configured
  contract. Pending, failed, wrong-chain, wrong-contract, malformed, and unrelated
  transactions are reported without treating them as hash comparisons. A successful
  receipt proves mining status but the app does not yet assess confirmation depth.
- Notarise connects through Reown WalletConnect to an installed Trust Wallet or
  MetaMask. MapSafe constructs a zero-value transaction containing the package
  basename and SHA-256, opens the external wallet for explicit approval, and verifies
  the returned transaction receipt before reporting success. Ethereum private keys
  and recovery phrases remain entirely outside MapSafe.
- The older standalone AES-GCM helper is retained only as legacy/local utility
  code. Recipient sharing now uses OpenPGP's standard v2 SEIPD AES-256-GCM
  container rather than a second custom package format.
- Selected-layer packages currently omit attachments, styles, and form metadata.

Feature code remains isolated in this package; only small menu and workflow
bridges are kept in `MainActivity` and `MapFragment`.
