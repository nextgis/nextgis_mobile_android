# MapSafe Mobile demonstration video transcript

Target running time: **3 minutes 30 seconds**. A natural narration rate of
approximately 125--135 words per minute should keep the finished video between three
and four minutes.

Record the phone in portrait orientation and NextGIS Web in landscape. Capture the
screen actions first and add narration afterward. Cut all loading delays, wallet waits,
and passphrase entry. Never show a password, passphrase, private key, recovery phrase,
access token, personal email address, or wallet balance.

## 0:00--0:20 — purpose and case study

**On screen:** Open NextGIS Mobile normally, choose MapSafe from the menu, and show the
North Whangārei source layer with its 23 points. Add the title: *Safeguard and controlled
community access*.

**Narration:**

“MapSafe extends NextGIS Mobile with safeguarding and controlled-sharing tools for
sensitive geospatial data. In this fictional example, Steven is the field data
custodian for a 23-point North Whangārei infected-tree dataset. The BMAs remain the
sovereign parties, while Amber is a researcher authorised to receive precise locations.
MapSafe preserves Steven’s original dataset unchanged.”

## 0:20--0:55 — create an anonymised representation

**On screen:** Open **Halo Masking**, set the minimum and maximum distances, apply it,
collapse the result card, and show original and displaced points together. Briefly cut
to the completed hexagonal-binning map.

**Narration:**

“Steven can first create an anonymised representation for recipients who need spatial
patterns but not exact coordinates. Halo masking moves each point within the selected
distance range and reports an inverted Spruill privacy score. Hexagonal binning offers
an alternative by replacing precise points with occupied H3 cells and counts. These are
new shareable layers; neither operation overwrites the authoritative source.”

## 0:55--1:20 — community identities and public keys

**On screen:** Open **Security & Sharing**, show Community A and Steven’s persistent
identity, then refresh the community key list. Show Steven, Amber, and the BMA
representative without exposing email addresses.

**Narration:**

“Community A has been prepared in NextGIS Web. Steven, Amber, and a BMA representative
each keep a passphrase-protected private key on their own device and publish only the
corresponding public key. MapSafe checks the full fingerprints before accepted keys can
be selected for encryption. Private keys and passphrases never enter the community
store.”

## 1:20--1:55 — recipient-controlled encryption

**On screen:** Open **Encrypt**, retain Steven’s checked identity, select Amber, enable
signing, and encrypt the original. Show the saved `.pgp` filename and SHA-256, but skip
the passphrase-entry footage.

**Narration:**

“For the protected original, Steven selects himself and Amber as recipients. MapSafe
encrypts the dataset once with a fresh AES session key, then wraps that key separately
for each selected OpenPGP public key. The result is one signed PGP package. Other
datasets are encrypted separately so every package can have its own recipients and
purpose.”

## 1:55--2:25 — publish and notarise

**On screen:** Open **Upload to Community**, select the masked layer, binned layer, public
key, and encrypted package, then show the completed NextGIS Web resource tree. Cut to
the notarisation screen and a confirmed external-wallet transaction.

**Narration:**

“Steven uploads the anonymised layers for Community A and the encrypted package for its
selected recipients. NextGIS permissions allow the community to read the derived
layers, while only Steven and Amber can list or download this protected package.
Optionally, MapSafe asks an external wallet to record the encrypted filename and its
SHA-256 digest on Sepolia. No plaintext data or cryptographic secret is written to the
blockchain.”

## 2:25--3:05 — verify, decrypt, and display

**On screen:** Switch to Amber. Open **Access**, choose **Community Packages**, download
the package, show local hash verification, decrypt it with Amber’s local identity, and
finish on the recovered North Whangārei points with no other layers visible.

**Narration:**

“Amber opens Community Packages and downloads the protected original. MapSafe
recalculates its SHA-256 and, when a transaction is supplied, compares the local
filename and digest with the blockchain record. Amber’s private key then unwraps the
session key. OpenPGP verifies package integrity and Steven’s signature before MapSafe
imports the recovered GeoJSON, clears the previous comparison layers, and displays
only the decrypted original.”

## 3:05--3:25 — demonstrate the access boundary

**On screen:** Show the BMA representative viewing the anonymised layers but no
protected package, followed by the external non-member receiving an empty or denied
Community A view. Use labels: *Anonymised access* and *No community access*.

**Narration:**

“For this release, the BMA representative can use the anonymised layers but was not
selected for the protected original, so that package remains absent. The external
non-member cannot access Community A at all. These positive and negative checks confirm
that community membership and package recipients serve different purposes.”

## 3:25--3:35 — closing statement

**On screen:** Return to the MapSafe Safeguard and Access tabs, then end on the logo and
the unobstructed map.

**Narration:**

“MapSafe keeps representation, recipient, integrity, and access decisions within one
mobile workflow, while leaving authority over every release with the data custodian and
sovereign community.”

## Production notes

- Use short cuts or two-to-four-times speed for uploads, downloads, encryption,
  decryption, and wallet confirmation; do not imply that processing is instantaneous.
- Keep the original screen recording and create a separate edited copy.
- Use captions for names, roles, filenames, and major actions; do not duplicate the full
  narration as permanent on-screen text.
- Display full fingerprints only in the evidence version. For a public video, show the
  final eight characters or blur the middle characters.
- Show the Sepolia label clearly so viewers do not mistake the demonstration for a
  production blockchain deployment.
- Add a final disclosure: *Fictional case study; demonstration attributes are
  synthetic; no private keys or precise operational field data are hosted publicly.*
- Export at 1080p, 30 frames per second, H.264 video with AAC audio, and verify that all
  small mobile text remains legible before publication.
