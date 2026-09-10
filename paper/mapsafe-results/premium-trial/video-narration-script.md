# MapSafe Mobile demonstration script (recording deferred)

Target length: 6–8 minutes. Record the phone in portrait orientation and the Web GIS
browser in landscape. Capture clean video first; add narration afterward so no
passphrase or credential is spoken or exposed.

## 1. Context and source data — 35 seconds

**On screen:** Start NextGIS Mobile normally, open MapSafe from the menu, load the
North Whangārei sample, and collapse the MapSafe card to reveal the 23 source points.

**Narration:** “MapSafe Mobile adds data-protection decisions to the established
NextGIS field workflow. In this fictional biodiversity example, Steven, the field data
custodian, holds a 23-point North Whangārei infected-tree dataset on behalf of the
relevant sovereign parties (BMAs). The published source
coordinates are retained for reproducibility, while the demonstration attributes are
synthetic. MapSafe keeps this precise source unchanged.”

## 2. Anonymised representations — 75 seconds

**On screen:** Set halo bounds, apply masking, collapse the result panel, compare the
red original and blue masked points, expand the score, save; then create and inspect a
resolution-8 hexagonal aggregate.

**Narration:** “Steven can create a separate representation for a more limited spatial
purpose. Halo masking moves every point within his chosen distance interval and reports
the inverted Spruill score. The result can be remasked, saved, or passed to encryption.
Alternatively, hexagonal binning replaces exact coordinates with occupied cells and
point counts. A BMA representative assigned the anonymised-data role for this release
can receive either derived layer but does not need the precise observations.”

## 3. Identity and recipient-controlled encryption — 80 seconds

**On screen:** Show the persistent local identity and community keys, select Steven and
Amber, encrypt the original, and show the saved filename and digest. Never reveal the
passphrase.

**Narration:** “Amber is authorised to work with precise coordinates. Steven selects
Amber’s accepted community public key and normally retains his own checked identity for
recovery. MapSafe encrypts this dataset once with a fresh AES session key and wraps that
key separately for each selected OpenPGP recipient. The output is one signed PGP file,
not a nested multi-level volume. Other selected representations would be encrypted into
separate packages so each can have its own audience.”

## 4. Community publication and Premium permissions — 80 seconds

**On screen:** Confirm the upload audience, then show the Web GIS hierarchy and the
three public-key records, two anonymised layers, and protected package. Switch from
Amber to the BMA-representative account and then the external account to show the
negative cases.

**Narration:** “The NextGIS authentication group defines the community boundary.
Public keys and anonymised layers are readable by Community A. Each encrypted package
has its own non-propagating resource permissions derived from the OpenPGP recipients:
Steven and Amber can list and download this package, the BMA representative is not an
OpenPGP recipient for this test package and cannot see it, and the external account—who
is not in the group—cannot enter the community resources. Private keys and passphrases never
leave their owners’ devices.”

## 5. Optional notarisation — 55 seconds

**On screen:** Calculate the encrypted package hash, show the filename-bound mint
payload, open external-wallet approval without revealing wallet secrets, and return to
the confirmed transaction state.

**Narration:** “MapSafe can optionally anchor the encrypted filename and SHA-256 digest
in a compatible EVM registry. Transaction signing occurs in an external wallet. The
public record contains no plaintext dataset, private key, or passphrase; it supports a
later claim about which named encrypted package was recorded.”

## 6. Authorised access — 75 seconds

**On screen:** As Amber, open Community Packages, download the protected original,
verify its local digest and blockchain record, decrypt with Amber’s local private key,
and display only the recovered layer.

**Narration:** “Amber downloads the same PGP package, recomputes its SHA-256, and can
compare it with the notarised value. After successful verification, Amber’s private key
unwraps the session key and OpenPGP recovers the dataset automatically. MapSafe checks
the package integrity and Steven’s signature, imports the GeoJSON, clears the earlier
comparison layers, and displays the recovered original.”

## 7. Boundary statement — 25 seconds

**On screen:** Return to the Safeguard/Access tabs and end on the unobstructed map.

**Narration:** “MapSafe supports deliberate representation, recipient, and integrity
decisions at the collection device. It does not itself determine legitimate authority
or guarantee anonymity; the data guardian and community remain responsible for the
purpose, parameters, recipients, and governance of every release.”
