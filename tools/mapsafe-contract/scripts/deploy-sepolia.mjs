import path from "node:path";
import { ContractFactory, JsonRpcProvider, Wallet, getAddress } from "ethers";
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
const privateKey = normalizedPrivateKey(requireValue(environment, "PRIVATE_KEY"));
const configuredAddress = getAddress(requireValue(environment, "BLOCKCHAIN_ADDRESS"));
const configuredRpc = environment.NODE_URL?.trim();
const rpcCandidates = [...new Set([configuredRpc, defaultSepoliaRpc].filter(Boolean))];

let provider;
let rpcUrl;
for (const candidate of rpcCandidates) {
  try {
    const attempted = new JsonRpcProvider(candidate, 11155111, { staticNetwork: true });
    const network = await attempted.getNetwork();
    if (network.chainId !== 11155111n) throw new Error(`wrong chain ID ${network.chainId}`);
    await attempted.getBlockNumber();
    provider = attempted;
    rpcUrl = candidate;
    break;
  } catch (error) {
    console.warn(`RPC unavailable (${new URL(candidate).host}): ${error.shortMessage ?? error.message}`);
  }
}
if (!provider || !rpcUrl) throw new Error("No configured Sepolia RPC endpoint was available.");

const signer = new Wallet(privateKey, provider);
if (getAddress(signer.address) !== configuredAddress) {
  throw new Error("PRIVATE_KEY does not match BLOCKCHAIN_ADDRESS; deployment was cancelled.");
}
const balance = await provider.getBalance(signer.address);
if (balance === 0n) throw new Error("The configured Sepolia account has no test ETH.");

const compiled = compileRegistry();
console.log(`Deploying from ${signer.address} through ${new URL(rpcUrl).host}...`);
const factory = new ContractFactory(compiled.abi, compiled.bytecode, signer);
const contract = await factory.deploy();
console.log(`Deployment transaction: ${contract.deploymentTransaction().hash}`);
await contract.waitForDeployment();
const contractAddress = await contract.getAddress();
const deployedCode = await provider.getCode(contractAddress);
if (deployedCode === "0x") throw new Error("Deployment receipt succeeded but no runtime code was found.");

const deployment = {
  schema: "mapsafe-contract-deployment-v1",
  network: "Ethereum Sepolia",
  chainId: 11155111,
  rpcUrl,
  explorerBaseUrl: "https://sepolia.etherscan.io",
  contractAddress,
  deployer: signer.address,
  transactionHash: contract.deploymentTransaction().hash,
  deployedAtUtc: new Date().toISOString(),
  runtimeBytes: (deployedCode.length - 2) / 2,
  interface: {
    mintFunction: "mintNFT(string)",
    mintSelector: "0xfb37e883",
    locationGetter: "locations(uint256)",
    locationSelector: "0xb9e0db35",
    erc721InterfaceId: "0x80ac58cd"
  }
};
const outputPath = path.join(toolRoot, "deployments", "sepolia-latest.json");
writeJson(outputPath, deployment);
console.log(`Contract deployed: ${contractAddress}`);
console.log(`Deployment record: ${outputPath}`);
