# MapSafe blockchain contract profile

## Location NFT v1 interface

The compatibility profile follows the `Location.json` artifact used by the
[MapSafe QGIS plugin](https://github.com/sharmapn/MapSafe-QGIS-plugin/blob/main/abis/Location.json):

- write function: `mintNFT(string)`
- function selector: `0xfb37e883`
- return value: `uint256`
- mutability: non-payable
- stored-value getter: `locations(uint256)`
- getter selector: `0xb9e0db35`
- required ERC-165 interface: ERC-721 (`0x80ac58cd`)

The mobile preflight first confirms deployed bytecode exists. It then checks
that the two MapSafe selectors occur in the runtime bytecode and uses a read-only
`eth_call` to query ERC-721 support through `supportsInterface(bytes4)`.

Selector detection is compatibility evidence, not a proof of source code or
behaviour. Proxy contracts may not expose selectors in the proxy runtime, and a
malicious contract can contain expected selectors without implementing MapSafe
correctly. The published reference contract and deployment tooling are in
`tools/mapsafe-contract`; an independent audit and pinned runtime-code hash are
still required before production use.

## Current Sepolia deployment

The current filename-bound research deployment is:

- contract: `0xdF7efaA8f01B5674e41534Da2bA4D7C56f65A0F2`;
- deployment transaction: `0xe6d0c11c5d6caed9d1f7d52ff7121dd4dfb3bf60f17f1e024a07f3420d3ea12a`;
- verified live test mint: `0xbf22807cf1b7345d6df9a3a48179b3dd1f9d54c62e89164c640da57bc729a60e` (block `11660048`).

The live test stored
`mapsafe-live-test.pgp_ea87365faf7e885463329aae555eef03c9f7d3d3041e53a0e0e2d842b95b5625`.
Public receipts are archived in `tools/mapsafe-contract/deployments/`; no deployment
private key is present in those files or in the Android application. The preceding
hash-only registry remains documented there for historical verification.

## Canonical integrity record

New mobile notarisation records use this ASCII form:

```text
<safe encrypted-package basename>_<64 lowercase hexadecimal characters>
```

The safe filename is limited to 120 ASCII letters, digits, spaces, hyphens,
periods, and underscores; it may not begin or end with a space or period. MapSafe
strips the device path and normalises unsupported characters before minting. The
final underscore separates the filename from the 64-character lowercase SHA-256.
Chain ID, contract address, transaction sender, and time are supplied by blockchain
context and are not duplicated in the string.

This public pair records that the signing wallet asserted a relationship between
that package name and content digest at the transaction time. It improves the
evidential context of the hash, but does not stop other transactions from recording
additional or conflicting assertions, prove the human identity controlling the
wallet, or make the user-selected filename an intrinsic property of the bytes.

The earlier MapSafe registry minted `mapsafe:v1:sha256:<SHA-256>` without a
filename. Mobile verification retains read-only parsing for those records. The
older QGIS plugin used the same final-underscore filename/hash shape as the current
record, although its filename-validation policy differed.

## Read-only verification workflow

Verification calculates the selected encrypted package's SHA-256 locally, then
retrieves `eth_chainId`, `eth_getTransactionByHash`, and
`eth_getTransactionReceipt` from the active HTTPS RPC. Before any hash comparison,
MapSafe requires the RPC chain to match the profile and the transaction to be a
mined, successful, zero-value call to the configured contract. The transaction
hash, receipt hash, sender, recipient, and block number must agree.

The call data is strictly decoded as one canonical ABI `string` argument to
`mintNFT(string)`. Unexpected selectors, offsets, lengths, UTF-8, padding, or
trailing data are rejected. The decoded value must be either the current
filename/hash record or the earlier hash-only MapSafe form described above. For
current records, both the normalised selected filename and its SHA-256 must match.
A hash-only historical record can establish only a digest match. A receipt confirms
that a transaction was mined, but this version does not calculate confirmation depth
or independently establish finality.

### Legacy QGIS Sepolia profile

The QGIS plugin's public test configuration used sender
`0x244EAbEf05ACF009746Ce91fE1712Daf3857e620` and destination
`0x8dD5Ca941A9F839062b6589A2E3f701458B011A9`. Historical Sepolia
transactions from this sender contain successful `mintNFT(string)`-encoded legacy
records and remain independently readable from transaction input.

A live check on 12 August 2026 found no deployed bytecode at that destination;
independent RPC providers and Blockscout agreed that it is not currently a
contract. The mobile app therefore supports those transactions only as legacy
transaction-input records. Its contract preflight deliberately fails for this
address, and it must not be used for new NFT notarisation.

## External-wallet transaction workflow

MapSafe now:

1. calculate the encrypted package SHA-256;
2. normalise the encrypted-package basename and bind it to the SHA-256;
3. ABI-encode it as the single argument to `mintNFT(string)`;
4. show the network and connected wallet;
5. hand the zero-value request to Trust Wallet or MetaMask for explicit approval;
6. retrieve the receipt and verify the mined call against the local filename and file hash.

MapSafe does not store a wallet private key or recovery phrase. The wallet displays
and approves any applicable gas fee before it signs the transaction.
