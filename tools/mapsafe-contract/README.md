# MapSafe integrity-registry tools

These scripts compile, deploy, and test the EVM contract used by MapSafe Mobile.
They read development credentials only from the repository-root `.env`, which is
ignored by Git. The private key is never written to an artifact or printed.

The contract stores `<safe encrypted-package basename>_<64 lowercase hex>` and
rejects device paths, unsupported filename characters, uppercase hashes, and other
metadata. It implements ERC-721 through OpenZeppelin, `mintNFT(string)`, and the
`locations(uint256)` getter expected by the Android preflight and verification code.

```powershell
cd tools/mapsafe-contract
npm ci
npm run compile
npm run deploy:sepolia
npm run test:live
```

Deployment and live-test receipts are saved in `deployments/`. Treat the contract
as a research prototype until it has received an independent smart-contract audit.

The current Sepolia research deployment is
`0xdF7efaA8f01B5674e41534Da2bA4D7C56f65A0F2`. Its deployment transaction and
the authorised live-test mint are recorded in `deployments/sepolia-latest.json`
and `deployments/sepolia-live-mint-latest.json`, respectively. The preceding
hash-only deployment and live test are retained as `sepolia-hash-only-v1*.json`.

The filename/hash pair is a public, permanent assertion attributable to the signing
wallet. It does not reveal the encrypted content or device path, prevent additional
transactions from recording other assertions, or independently prove the human
identity controlling the wallet.
