#!/usr/bin/env node
// Evaluation CLI.
//   node tests/eval/run.mjs [--adapter adapters/vegaduta.mjs] [--suite tasks/suite.v1.json]
//                           [--repeats 3] [--only qa-01,tool-02] [--out results/] [--list]
// Exit code: 0 when the run completed (pass/fail is data, not a gate); 2 on setup errors.
import { mkdirSync, writeFileSync } from "node:fs";
import { execSync } from "node:child_process";
import { resolve, dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { loadSuite } from "./lib/suite.mjs";
import { runSuite } from "./lib/runner.mjs";
import { buildResults, renderMarkdown } from "./lib/report.mjs";

const here = dirname(fileURLToPath(import.meta.url));

function parseArgs(argv) {
  const o = { adapter: "adapters/vegaduta.mjs", suite: "tasks/suite.v1.json", repeats: 1, out: "results", only: [], list: false };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === "--list") o.list = true;
    else if (["--adapter", "--suite", "--out", "--repeats", "--only"].includes(a)) {
      const v = argv[++i];
      if (v === undefined) throw new Error(`${a} needs a value`);
      if (a === "--repeats") o.repeats = Number.parseInt(v, 10);
      else if (a === "--only") o.only = v.split(",").filter(Boolean);
      else o[a.slice(2)] = v;
    } else throw new Error(`unknown argument ${a}`);
  }
  if (!(o.repeats >= 1 && o.repeats <= 50)) throw new Error("--repeats must be 1..50");
  return o;
}

function gitCommit() {
  try {
    return execSync("git rev-parse --short HEAD", { cwd: here, stdio: ["ignore", "pipe", "ignore"] }).toString().trim();
  } catch {
    return null;
  }
}

async function main() {
  const o = parseArgs(process.argv.slice(2));
  const suite = loadSuite(resolve(here, o.suite));
  if (o.list) {
    for (const t of suite.tasks) console.log(`${t.id}\t${t.category}\t${t.role}`);
    return;
  }
  const adapter = (await import(pathToFileURL(resolve(here, o.adapter)).href)).default;
  const ctx = await adapter.init(process.env);
  const desc = adapter.describe(ctx);
  const startedAt = new Date().toISOString();
  console.error(`target: ${desc.target}\nsuite: ${suite.name} v${suite.version}, repeats ${o.repeats}`);
  const records = await runSuite({
    suite,
    adapter,
    ctx,
    repeats: o.repeats,
    only: o.only,
    onRun: (r) => console.error(`${r.success ? "PASS" : "FAIL"} ${r.taskId} #${r.repeat} ${r.latencyMs} ms${r.success ? "" : `  ${r.failures.join("; ")}`}`),
  });
  await adapter.close?.(ctx);
  const results = buildResults(
    {
      system: adapter.name,
      target: desc.target,
      model: desc.model ?? null,
      suite: { name: suite.name, version: suite.version, taskCount: o.only.length || suite.tasks.length },
      repeats: o.repeats,
      startedAt,
      finishedAt: new Date().toISOString(),
      harnessCommit: gitCommit(),
    },
    records,
  );
  const outDir = resolve(process.cwd(), o.out);
  mkdirSync(outDir, { recursive: true });
  writeFileSync(join(outDir, "results.json"), `${JSON.stringify(results, null, 2)}\n`);
  const md = renderMarkdown(results);
  writeFileSync(join(outDir, "results.md"), md);
  console.log(md);
  console.error(`wrote ${join(outDir, "results.json")} and results.md`);
}

main().catch((e) => {
  console.error(`eval: ${e.message}`);
  process.exit(2);
});
