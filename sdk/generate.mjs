#!/usr/bin/env node
// SDK generator: reads docs/api/openapi.json and writes the generated halves of the TypeScript and
// Python SDKs. Plain Node (ESM), no dependencies.
//
//   node sdk/generate.mjs                      # write sdk/typescript/src/generated/{types,client}.ts and
//                                              #       sdk/python/vegaduta_sdk/generated/{__init__,models,client}.py
//   node sdk/generate.mjs --spec path.json     # use another spec
//   node sdk/generate.mjs --out some/dir       # write under some/dir instead of sdk/
//   node sdk/generate.mjs --check              # exit 1 if the files on disk differ (CI drift guard)
//
// The hand-written runtimes (typescript/src/runtime.ts, python/vegaduta_sdk/runtime.py) are never touched.

import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

import { buildModel } from "./lib/model.mjs";
import * as py from "./lib/py.mjs";
import * as ts from "./lib/ts.mjs";

/** Pure: OpenAPI document in, `{ relativePath: fileContents }` out. */
export function generate(spec) {
  const model = buildModel(spec);
  // Generated files live under a "generated" directory so the repo's file-size ratchet
  // (scripts/ci/check-file-size.sh) skips them: the client is one method per operation and is large.
  return {
    "typescript/src/generated/types.ts": ts.emitTypes(model),
    "typescript/src/generated/client.ts": ts.emitClient(model),
    "python/vegaduta_sdk/generated/__init__.py": py.emitPackageInit(),
    "python/vegaduta_sdk/generated/models.py": py.emitModels(model),
    "python/vegaduta_sdk/generated/client.py": py.emitClient(model),
  };
}

function parseArgs(argv) {
  const here = dirname(fileURLToPath(import.meta.url));
  const args = { spec: resolve(here, "..", "docs", "api", "openapi.json"), out: here, check: false };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === "--spec") args.spec = resolve(argv[++i] ?? "");
    else if (a === "--out") args.out = resolve(argv[++i] ?? "");
    else if (a === "--check") args.check = true;
    else if (a === "--help" || a === "-h") args.help = true;
    else throw new Error(`unknown argument: ${a}`);
  }
  return args;
}

function main() {
  let args;
  try {
    args = parseArgs(process.argv.slice(2));
  } catch (e) {
    console.error(e.message);
    process.exit(2);
  }
  if (args.help) {
    console.log("usage: node sdk/generate.mjs [--spec <openapi.json>] [--out <dir>] [--check]");
    return;
  }
  if (!existsSync(args.spec)) {
    console.error(
      `OpenAPI spec not found: ${args.spec}\n` +
        "Generate it first (see docs/api/README.md): mvn -pl app -am test -Dtest=OpenApiSpecGeneratorTest -Dopenapi.write=true",
    );
    process.exit(1);
  }
  const files = generate(JSON.parse(readFileSync(args.spec, "utf8")));
  let drift = 0;
  for (const [rel, content] of Object.entries(files)) {
    const target = join(args.out, rel);
    if (args.check) {
      const current = existsSync(target) ? readFileSync(target, "utf8").replace(/\r\n/g, "\n") : null;
      if (current !== content) {
        console.error(`out of date: ${rel}`);
        drift++;
      }
      continue;
    }
    mkdirSync(dirname(target), { recursive: true });
    writeFileSync(target, content, "utf8");
    console.log(`wrote ${rel}`);
  }
  if (args.check) {
    if (drift > 0) {
      console.error("Generated SDK files are stale. Run: node sdk/generate.mjs");
      process.exit(1);
    }
    console.log("SDK files are up to date.");
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main();
}
