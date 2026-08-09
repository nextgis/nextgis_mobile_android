# MapSafe Mobile Development Checklist

## UI integration
- [x] Connect MainActivity menu to MapSafeMainDialog
- [x] Connect anonymise options to GIS workflows
- [x] Add OpenPGP encryption/decryption and key-management screen
- [x] Add visible Security & Sharing setup for NextGIS account, group, identity, publication and synchronisation
- [x] Add a guided Protect & Share entry that carries the protected layer directly into encryption
- [x] Apply the MapSafe green-toolbar/card design to safeguard, identity, encryption and decryption screens
- [x] Keep masking and hexbin controls over the live map while using the new MapSafe visual design
- [x] Add hierarchy-aware visible and Android-system Back controls to every MapSafe menu, dialog and activity
- [x] Keep a fixed labelled Back control above system bars on every full-screen MapSafe view
- [x] Apply the supplied MapSafe icon and full wordmark to the launcher, headers and workflow chooser
- [ ] Complete the guided workflow after encryption (notarise, sync, verify and view)
- [ ] Apply the approved visual design to verification after its production workflow is implemented
- [ ] Add blockchain dialogs

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
- [x] Replace free-text masking distances with bounded sliders
- [x] Show the inverted Spruill privacy score after every masking attempt
- [x] Let users accept the candidate or remask from the original precise layer

## Sample data
- [x] Bundle a synthetic 30-point GeoJSON dataset around Suva
- [x] Add an in-app loader that creates, selects, and zooms to the sample layer
- [x] Preserve test attributes in the generated NextGIS vector layer
- [x] Verify the loader and donut masking end to end on the Pixel 9a emulator
- [x] Add a prominent Use sample dataset option to the main, Anonymise and guided Protect & Share screens

## Hexabinning
- [x] Hexabinning scaffolding added
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
- [ ] Validate selected-layer encrypt/decrypt/import end to end on the emulator

## Blockchain
- [x] SHA-256 hash generation
- [ ] Blockchain notarisation
- [ ] Hash checking

## Verification
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
