import path from "node:path";
import { compileRegistry, toolRoot, writeJson } from "./common.mjs";

const compiled = compileRegistry();
const outputPath = path.join(toolRoot, "build", "MapSafeIntegrityRegistry.json");
writeJson(outputPath, compiled);
console.log(`Compiled MapSafeIntegrityRegistry (${compiled.deployedBytecode.length / 2 - 1} runtime bytes).`);
console.log(`Artifact: ${outputPath}`);
