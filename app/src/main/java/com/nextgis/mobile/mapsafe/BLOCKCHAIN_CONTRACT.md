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
correctly. Before production notarisation is enabled, the project should publish
and audit a reference contract and pin an approved deployment or runtime-code
hash for each supported network.

## Canonical integrity record

New mobile notarisation records use this exact ASCII form:

```text
mapsafe:v1:sha256:<64 lowercase hexadecimal characters>
```

Only the encrypted package's SHA-256 is included. Dataset and file names are
excluded because transaction input is public and permanent. Chain ID, contract
address, transaction sender, and time are already supplied by blockchain context
and are not duplicated in the string.

The older QGIS plugin minted `<filename>_<SHA-256>`. Mobile verification retains
read-only parsing for this legacy form by splitting at the final underscore, but
new records must never emit it.

## Read-only verification workflow

Verification calculates the selected encrypted package's SHA-256 locally, then
retrieves `eth_chainId`, `eth_getTransactionByHash`, and
`eth_getTransactionReceipt` from the active HTTPS RPC. Before any hash comparison,
MapSafe requires the RPC chain to match the profile and the transaction to be a
mined, successful, zero-value call to the configured contract. The transaction
hash, receipt hash, sender, recipient, and block number must agree.

The call data is strictly decoded as one canonical ABI `string` argument to
`mintNFT(string)`. Unexpected selectors, offsets, lengths, UTF-8, padding, or
trailing data are rejected. The decoded value must be either the canonical v1
record or the legacy QGIS form described above. Only then is its SHA-256 compared
with the selected file. A receipt confirms that a transaction was mined, but this
version does not calculate confirmation depth or independently establish finality.

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
address, and it must not be used for new NFT notarisation. A new audited Sepolia
deployment is required before write support can be enabled.

## Future transaction workflow

When wallet integration is added, MapSafe will:

1. calculate the encrypted package SHA-256;
2. build the canonical v1 record;
3. ABI-encode it as the single argument to `mintNFT(string)`;
4. show the network, contract, exact public record, estimated fee, and wallet;
5. hand the unsigned request to an external wallet for explicit approval.

MapSafe will not store a wallet private key or recovery phrase.
