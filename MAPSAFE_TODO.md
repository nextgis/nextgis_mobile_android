# MapSafe Mobile Development Checklist

## UI integration
- [x] Connect MainActivity menu to MapSafeMainDialog
- [x] Connect anonymise options to GIS workflows
- [x] Add OpenPGP encryption/decryption and key-management screen
- [x] Add visible Security & Sharing setup for NextGIS account, group, identity, publication and synchronisation
- [x] Remove the retired guided Protect & Share route and keep safeguard features independent
- [x] Gate Safeguard and Access at the workflow chooser until an active map dataset is selected
- [x] Add a shared Anonymise -> Encrypt -> Notarise progress indicator to safeguard screens
- [x] Replace the Access Features list with numbered Verify, Decrypt and Access Dataset cards
- [x] Apply the MapSafe green-toolbar/card design to safeguard, identity, encryption and decryption screens
- [x] Keep masking and hexbin controls over the live map while using the new MapSafe visual design
- [x] Limit halo and hexbin configuration/result overlays to the upper half of the screen so the map remains visible
- [x] Let Halo Masking Applied and Hexagonal Binning Applied collapse to a slim top header for full-map review
- [x] Remove the unused masking/binning switches, privacy sliders, legends and explanatory sections
- [x] Add consistent Next/Stop handoffs after anonymisation, encryption, verification and decryption
- [x] Add hierarchy-aware visible and Android-system Back controls to every MapSafe menu, dialog and activity
- [x] Keep a fixed labelled Back control above system bars on every full-screen MapSafe view
- [x] Apply the supplied MapSafe icon and full wordmark to the launcher, headers and workflow chooser
- [ ] Apply the approved visual design to verification after its production workflow is implemented
- [x] Add shared blockchain network settings to Verify and Notarise
- [x] Add a live Access Dataset catalogue for verified decrypted imports
- [x] Complete the verified Decrypt -> Access Dataset catalogue handoff
- [x] Complete the blockchain-verified Verify -> Decrypt handoff with same-file hash rechecks
- [ ] Add production notarisation and verification result dialogs

## Donut masking
- [x] Donut masking helper added
- [x] Create masked layer workflow
- [x] Copy point features
- [x] Insert masked geometries
- [x] Copy attributes
- [x] Save and refresh layer
- [x] Display masking statistics
- [x] Validate the workflow on an emulator with the bundled 30-point sample layer
- [x] Add deliberate blue styling for generated point layers
- [x] Replace free-text masking distances with one bounded two-thumb range slider
- [x] Show the inverted Spruill privacy score after every masking attempt
- [x] Let users stop, continue to encryption, or remask from the original precise layer
- [x] Reduce Encrypt & Protect to the dataset, identity, recipient controls and encryption action

## Sample data
- [x] Bundle a synthetic 30-point GeoJSON dataset around Suva
- [x] Add an in-app loader that creates, selects, and zooms to the sample layer
- [x] Preserve test attributes in the generated NextGIS vector layer
- [x] Verify the loader and donut masking end to end on the Pixel 9a emulator
- [x] Add a prominent Use sample dataset option to the main and Anonymise screens

## Hexabinning
- [x] Hexabinning scaffolding added
- [x] Add a compact Hexagonal Binning Applied result with Bin Again, Stop and Next to encryption
- [x] Add h3-java dependency and package its Android ARM natives
- [x] Convert points to H3 cells
- [x] Generate hexagon polygons
- [x] Create polygon output layer
- [x] Save and refresh layer
- [x] Add a portable x86/x86_64 emulator fallback
- [ ] Validate on an ARM/ARM64 device
- [x] Validate the portable fallback on an x86_64 emulator
- [x] Add deliberate blue styling for generated polygon layers

## Encryption
- [x] Keep legacy AES-GCM helper isolated from recipient sharing
- [x] Add standard Bouncy Castle OpenPGP version-4 RSA keys
- [x] Add streaming multi-recipient encryption and decryption
- [x] Use RFC 9580 v2 SEIPD AES-256-GCM for newly encrypted packages
- [x] Retain decryption support for earlier AES-256/OpenPGP-MDC packages
- [x] Add optional embedded signing and signature verification
- [x] Add recipient public-key import and complete fingerprint display
- [x] Publish public-only key material through existing NextGIS accounts
- [x] Discover keys by NextGIS authentication-group membership
- [x] List the signed-in user's groups instead of requiring a numeric group ID
- [x] Make accepted keys from the selected group the default encryption recipients
- [x] Pin accepted fingerprints and quarantine changed/missing/removed entries
- [x] Require explicit out-of-band fingerprint acceptance before encryption
- [x] Add passphrase-protected public/secret-key import and export
- [x] Protect the local secret keyring with Android Keystore AES-GCM
- [x] Connect Android Storage Access Framework file pickers
- [x] Withhold decrypted output until the integrity check succeeds
- [x] Add generated-key multi-recipient unit tests
- [x] Validate Android Keystore identity creation on the emulator
- [ ] Validate the same package with two Android installations
- [ ] Validate account, group creation, publication and synchronisation against a real NextGIS Web server
- [ ] Validate GnuPG and OpenKeychain interoperability
- [ ] Add key revocation-certificate workflow
- [ ] Add organisation trust/certification policy
- [x] Export a selected NextGIS layer directly into the encryption workflow
- [x] Offer to import GeoJSON after successful decryption and zoom to it
- [x] Validate selected-layer encrypt/decrypt/import end to end on the emulator

## Blockchain
- [x] SHA-256 hash generation
- [x] Add Sepolia, Ethereum Mainnet and custom EVM network profiles
- [x] Validate HTTPS RPC/explorer, chain ID and contract configuration
- [x] Encrypt network metadata in app-private no-backup storage with Android Keystore
- [x] Warn before activating a production network and never collect wallet private keys
- [x] Add a read-only RPC chain-ID and deployed-contract bytecode preflight
- [x] Add ERC-721 and MapSafe function-selector compatibility checks
- [x] Define a versioned, filename-free canonical SHA-256 record format
- [x] Retain read-only parsing of legacy QGIS filename_hash records
- [x] Validate a known legacy QGIS transaction through a live Sepolia RPC
- [ ] Deploy an audited replacement for the QGIS legacy Sepolia destination, which has no current bytecode
- [ ] Publish and audit a reference MapSafe contract
- [ ] Pin approved deployment addresses or runtime-code hashes per network
- [ ] Connect an external wallet for user-approved signing and transaction submission
- [ ] Blockchain notarisation
- [x] Read-only blockchain hash checking for mined `mintNFT(string)` transactions

## Verification
- [x] Select an encrypted document and calculate its local SHA-256 from the content stream
- [x] Validate raw transaction hashes and explorer URLs against the active network profile
- [x] Retrieve a transaction from the active network and compare its recorded hash with the local SHA-256
- [x] Java 21 Gradle compatibility
- [x] Optional local signing configuration
- [x] Donut masking unit tests
- [x] Portable hex-grid unit tests
- [x] Debug APK assembly
- [x] Instrumented UI and layer-output tests

## Cloud integration
- [x] Reuse existing NextGIS accounts for MapSafe key exchange
- [x] Resolve collaborator membership from NextGIS authentication groups
- [x] Store public keys and manifests in NextGIS resource/file buckets
- [x] Synchronise public-key metadata and local trust state
- [x] Add production UI for group selection, missing-key status and fingerprint review
- [ ] Add scheduled/background key-directory synchronisation
