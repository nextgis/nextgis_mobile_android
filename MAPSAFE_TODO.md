# MapSafe Mobile Development Checklist

## UI integration
- [x] Connect MainActivity menu to MapSafeMainDialog
- [x] Connect anonymise options to GIS workflows
- [x] Add OpenPGP encryption/decryption and key-management screen
- [x] Add hierarchy-aware Back controls to MapSafe menus and OpenPGP screen
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

## Sample data
- [x] Bundle a synthetic 30-point GeoJSON dataset around Suva
- [x] Add an in-app loader that creates, selects, and zooms to the sample layer
- [x] Preserve test attributes in the generated NextGIS vector layer
- [x] Verify the loader and donut masking end to end on the Pixel 9a emulator

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
- [x] Add optional embedded signing and signature verification
- [x] Add recipient public-key import and complete fingerprint display
- [x] Add passphrase-protected public/secret-key import and export
- [x] Protect the local secret keyring with Android Keystore AES-GCM
- [x] Connect Android Storage Access Framework file pickers
- [x] Withhold decrypted output until the integrity check succeeds
- [x] Add generated-key multi-recipient unit tests
- [x] Validate Android Keystore identity creation on the emulator
- [ ] Validate the same package with two Android installations
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
- [ ] Instrumented UI and layer-output tests

## Cloud integration
- [ ] User accounts
- [ ] Collaborator management
- [ ] Cloud storage
- [ ] Metadata synchronisation
