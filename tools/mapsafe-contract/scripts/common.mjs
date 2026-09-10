import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import dotenv from "dotenv";
import solc from "solc";

export const toolRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
export const repositoryRoot = path.resolve(toolRoot, "..", "..");
export const defaultSepoliaRpc = "https://ethereum-sepolia-rpc.publicnode.com";

export function loadLocalEnvironment() {
  const environmentPath = path.join(repositoryRoot, ".env");
  if (!fs.existsSync(environmentPath)) {
    throw new Error(`Missing local environment file: ${environmentPath}`);
  }
  return dotenv.parse(fs.readFileSync(environmentPath));
}

export function requireValue(environment, name) {
  const value = environment[name]?.trim();
  if (!value) throw new Error(`${name} is missing from the local .env file.`);
  return value;
}

export function normalizedPrivateKey(value) {
  const key = value.trim();
  const normalized = key.startsWith("0x") ? key : `0x${key}`;
  if (!/^0x[0-9a-fA-F]{64}$/.test(normalized)) {
    throw new Error("PRIVATE_KEY must be a 32-byte hexadecimal EVM private key.");
  }
  return normalized;
}

export function compileRegistry() {
  const sourcePath = path.join(toolRoot, "contracts", "MapSafeIntegrityRegistry.sol");
  const input = {
    language: "Solidity",
    sources: {
      "contracts/MapSafeIntegrityRegistry.sol": {
        content: fs.readFileSync(sourcePath, "utf8"),
      },
    },
    settings: {
      optimizer: { enabled: true, runs: 200 },
      outputSelection: {
        "*": { "*": ["abi", "evm.bytecode.object", "evm.deployedBytecode.object"] },
      },
    },
  };
  const output = JSON.parse(solc.compile(JSON.stringify(input), { import: resolveImport }));
  const errors = (output.errors ?? []).filter((entry) => entry.severity === "error");
  if (errors.length) {
    throw new Error(errors.map((entry) => entry.formattedMessage).join("\n"));
  }
  const contract = output.contracts["contracts/MapSafeIntegrityRegistry.sol"]
    ?.MapSafeIntegrityRegistry;
  if (!contract) throw new Error("The MapSafe contract was not produced by solc.");
  return {
    abi: contract.abi,
    bytecode: `0x${contract.evm.bytecode.object}`,
    deployedBytecode: `0x${contract.evm.deployedBytecode.object}`,
  };
}

function resolveImport(importPath) {
  const candidates = [
    path.join(toolRoot, importPath),
    path.join(toolRoot, "node_modules", importPath),
  ];
  const resolved = candidates.find((candidate) => fs.existsSync(candidate));
  return resolved
    ? { contents: fs.readFileSync(resolved, "utf8") }
    : { error: `Import not found: ${importPath}` };
}

export function writeJson(filePath, value) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, `${JSON.stringify(value, null, 2)}\n`, "utf8");
}
