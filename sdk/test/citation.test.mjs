// CITATION.cff must say what the repository says: the platform licence from LICENSE (BSL-1.1) and a
// version and date that name a release that exists in CHANGELOG.md. The SDK licence (Apache-2.0,
// sdk/LICENSE) is stated in the file's comments, because CFF carries one licence per work.
// Run with: node --test sdk/test/*.test.mjs
// Schema validation (cffconvert --validate) is not run here; CI has no Python tool for it.

import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
const read = (f) => readFileSync(join(root, f), "utf8");

/** Top-level `key: value` scalars of a CFF file, unquoted. Enough for this file; not a YAML parser. */
function scalars(cff) {
  const out = {};
  for (const m of cff.matchAll(/^([a-z-]+):[ \t]+(?:"([^"]*)"|([^\s#][^#\n]*?))[ \t]*(?:#.*)?$/gm)) {
    out[m[1]] = m[2] ?? m[3];
  }
  return out;
}

/** SPDX id for a licence file, from its first line (the only two this repository ships). */
function spdxOf(licenseText) {
  const first = licenseText.split(/\r?\n/).find((l) => l.trim()).trim();
  if (first === "Business Source License 1.1") return "BUSL-1.1";
  if (first === "Apache License") return "Apache-2.0";
  assert.fail(`unrecognised licence heading: ${first}`);
}

const cff = scalars(read("CITATION.cff"));

test("CITATION.cff declares the platform licence in LICENSE, not the SDK licence", () => {
  assert.equal(spdxOf(read("LICENSE")), "BUSL-1.1");
  assert.equal(spdxOf(read("sdk/LICENSE")), "Apache-2.0");
  assert.equal(cff.license, spdxOf(read("LICENSE")));
  assert.match(read("CITATION.cff"), /sdk\/ directory is Apache-2\.0/, "the comment that explains the split is gone");
});

test("CITATION.cff names a released version and its date from CHANGELOG.md", () => {
  assert.match(cff["cff-version"], /^1\.2\.0$/);
  assert.match(cff.version ?? "", /^\d+\.\d+\.\d+$/, "CITATION.cff has no version");
  const heading = read("CHANGELOG.md").match(new RegExp(`^## \\[${cff.version.replaceAll(".", "\\.")}\\] - (\\d{4}-\\d{2}-\\d{2})$`, "m"));
  assert.ok(heading, `CHANGELOG.md has no released section for ${cff.version}`);
  assert.equal(cff["date-released"], heading[1]);
});
