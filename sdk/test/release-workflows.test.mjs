// Structure checks on the SDK release workflows and package manifests. They cannot run a release;
// they pin the mistakes a release run would only reveal at publish time.
// Run with: node --test sdk/test/*.test.mjs
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
const read = (f) => readFileSync(join(root, f), "utf8");

/** The text of one top-level job of a workflow, without comment lines (no YAML parser here: jobs sit at two-space indent). */
function job(workflow, name) {
  const lines = read(`.github/workflows/${workflow}`).split("\n");
  const start = lines.findIndex((l) => l === `  ${name}:`);
  assert.ok(start >= 0, `${workflow} has no job ${name}`);
  let end = lines.findIndex((l, i) => i > start && /^  [A-Za-z0-9_-]+:\s*$/.test(l));
  if (end < 0) end = lines.length;
  return lines.slice(start, end).filter((l) => !/^\s*#/.test(l)).join("\n"); // comments may name what the job must not do
}

test("the npm job publishes the tarball the build job attested, not a rebuild of the source tree", () => {
  const npm = job("rest-sdk-release.yml", "npm");
  assert.match(npm, /download-artifact/, "the npm job must download the packed tarball");
  assert.match(npm, /name: rest-sdk-dists/);
  assert.match(npm, /npm publish "\.\/\$tarball"/, "publish the tarball, not the working directory");
  assert.match(npm, /package\/dist\/index\.js/, "refuse a tarball without dist/index.js");
  assert.doesNotMatch(npm, /--ignore-scripts/, "a fresh checkout has no dist/: --ignore-scripts skips the build that makes it");
  assert.doesNotMatch(npm, /actions\/checkout/, "the npm job must not publish from a checkout");
});

test("the build job drops `private` before packing and checks the tarball contents", () => {
  const build = job("rest-sdk-release.yml", "build");
  const del = build.indexOf("npm pkg delete private");
  const pack = build.indexOf("npm pack");
  assert.ok(del >= 0 && pack > del, "`npm pkg delete private` must run before `npm pack` (npm publish refuses a private manifest, also in a tarball)");
  for (const file of ["package/dist/index.js", "package/dist/index.d.ts", "package/LICENSE"]) {
    assert.ok(build.includes(file), `the build job must check that the tarball holds ${file}`);
  }
});

test("sdk/typescript is publishable once the release workflow removes the private guard", () => {
  const pkg = JSON.parse(read("sdk/typescript/package.json"));
  assert.equal(pkg.private, true, "keep the guard in the tree; the workflow removes it from the copy it packs");
  assert.equal(pkg.license, "Apache-2.0");
  assert.ok(pkg.files.includes("dist") && pkg.files.includes("LICENSE"));
  for (const entry of [pkg.main, pkg.types, pkg.exports["."].default, pkg.exports["."].types]) assert.match(entry, /dist\//);
  assert.match(pkg.scripts.prepack, /build/, "packing must build dist/");
  assert.equal(pkg.repository.directory, "sdk/typescript");
});

test("the scanner CLI jar built by sdk/java reaches the SDK release", () => {
  const release = read(".github/workflows/sdk-release.yml");
  assert.match(release, /vegaduta-agent-scanner-\$\{\{ needs\.verify\.outputs\.version \}\}-cli\.jar/, "sdk-release.yml must collect the -cli jar: its other globs end in <version>.jar");
  assert.match(read("sdk/java/agent-scanner/pom.xml"), /<shadedClassifierName>cli<\/shadedClassifierName>/);
});
