import fs from "node:fs";
import path from "node:path";
import { Contract, JsonRpcProvider, Wallet, getAddress, sha256, toUtf8Bytes } from "ethers";
import {
  compileRegistry,
  defaultSepoliaRpc,
  loadLocalEnvironment,
  normalizedPrivateKey,
  requireValue,
  toolRoot,
  writeJson,
} from "./common.mjs";

const environment = loadLocalEnvironment();
const deploymentPath = path.join(toolRoot, "deployments", "sepolia-latest.json");
if (!fs.existsSync(deploymentPath)) throw new Error("Deploy the Sepolia contract first.");
const deployment = JSON.parse(fs.readFileSync(deploymentPath, "utf8"));
const provider = new JsonRpcProvider(deployment.rpcUrl || defaultSepoliaRpc, 11155111, { staticNetwork: true });
const wallet = new Wallet(normalizedPrivateKey(requireValue(environment, "PRIVATE_KEY")), provider);
if (getAddress(wallet.address) !== getAddress(requireValue(environment, "BLOCKCHAIN_ADDRESS"))) {
  throw new Error("PRIVATE_KEY does not match BLOCKCHAIN_ADDRESS; mint was cancelled.");
}

const sampleDigest = sha256(toUtf8Bytes(`MapSafe live development test ${new Date().toISOString()}`)).slice(2);
const sampleFileName = "mapsafe-live-test.pgp";
const canonicalRecord = `${sampleFileName}_${sampleDigest}`;
const contract = new Contract(deployment.contractAddress, compileRegistry().abi, wallet);
const transaction = await contract.mintNFT(canonicalRecord);
console.log(`Submitted live-test mint: ${transaction.hash}`);
const receipt = await transaction.wait();
if (receipt.status !== 1) throw new Error("The live-test mint was mined but failed.");
const result = {
  schema: "mapsafe-live-mint-v1",
  network: deployment.network,
  chainId: deployment.chainId,
  contractAddress: deployment.contractAddress,
  transactionHash: transaction.hash,
  blockNumber: receipt.blockNumber,
  record: canonicalRecord,
  completedAtUtc: new Date().toISOString()
};
const outputPath = path.join(toolRoot, "deployments", "sepolia-live-mint-latest.json");
writeJson(outputPath, result);
console.log(`Live-test mint confirmed in block ${receipt.blockNumber}.`);
console.log(`Result: ${outputPath}`);
