// Guards for claims in the SDK docs that have gone stale before: they check the docs against the code
// and manifests they describe. Run with: node --test sdk/test/*.test.mjs
import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
const read = (f) => readFileSync(join(root, f), "utf8");
const docFiles = [
  "sdk/README.md",
  "sdk/java/README.md",
  "sdk/python-agent/README.md",
  "sdk/python/README.md",
  "sdk/typescript/README.md",
  ...readdirSync(join(root, "docs", "sdk")).filter((f) => f.endsWith(".md")).map((f) => `docs/sdk/${f}`),
];

test("no SDK doc calls a released version a SNAPSHOT", () => {
  const pom = read("sdk/java/pom.xml").match(/<version>([^<]+)<\/version>/)[1];
  assert.ok(!pom.endsWith("-SNAPSHOT"), `sdk/java/pom.xml is ${pom}: a development version, so the docs may say SNAPSHOT; drop this guard`);
  const stale = docFiles.filter((f) => /SNAPSHOT/.test(read(f)));
  assert.deepEqual(stale, [], `these docs still name a -SNAPSHOT although the SDK is ${pom}`);
});

test("sdk/README.md does not describe a status drift that the drift file no longer records", () => {
  const drift = JSON.parse(read("contracts/consumers/tools/spec-status-drift.json")).drift;
  const readme = read("sdk/README.md");
  if (drift.length === 0) assert.doesNotMatch(readme, /lists 200 for/, "the drift list is empty, so no operation is an example of drift");
  for (const entry of drift) assert.ok(readme.includes(entry.path), `${entry.path} is in the drift file but not described`);
});

test("while the Python Agent SDK is not on PyPI its README installs it from the source tree", () => {
  const readme = read("sdk/python-agent/README.md");
  assert.match(readme, /not yet on PyPI/);
  assert.doesNotMatch(readme, /pip install vegaduta-agent/, "`pip install vegaduta-agent...` fails until the package is published");
});

test("the scanner is only run with `java -jar` from its runnable -cli jar", () => {
  for (const f of docFiles) {
    for (const m of read(f).matchAll(/java -jar (vegaduta-agent-scanner-\S*?\.jar)/g)) {
      assert.match(m[1], /-cli\.jar$/, `${f}: \`java -jar ${m[1]}\` crashes with NoClassDefFoundError; use the -cli jar`);
    }
  }
});
