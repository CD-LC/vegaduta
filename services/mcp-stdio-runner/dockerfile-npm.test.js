'use strict';

// Static guard for the Node runner images (no Docker needed). The npm CLI that ships inside the
// node:22 base image bundles dependencies (brace-expansion, ip-address, picomatch, pacote,
// sigstore) with published High advisories, and the base tag has not picked up fixes. The
// runners need npm/npx at runtime, so each image that keeps npm replaces it with ONE pinned
// release whose bundled dependencies are fixed (grype --only-fixed shows no High/Critical).
// This test keeps the three Dockerfiles on the same pinned, fixed release, and keeps their
// base images digest-pinned.

const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');

// First npm release whose bundled dependencies scanned clean (2026-10-22). Raise it when a scan
// of a newer release is also clean; never lower it.
const MIN_NPM = [11, 21, 1];

const REPO = path.join(__dirname, '..', '..');
const DOCKERFILES = {
  'services/mcp-stdio-runner/Dockerfile': null,
  'services/workspace-runner/Dockerfile': null,
  'core/Dockerfile': null,
};
for (const rel of Object.keys(DOCKERFILES)) {
  DOCKERFILES[rel] = fs.readFileSync(path.join(REPO, rel), 'utf8');
}

function npmVersionOf(text) {
  const m = text.match(/^ARG NPM_VERSION=(\S+)$/m);
  return m ? m[1] : null;
}

function atLeast(version, floor) {
  const v = version.split('.').map(Number);
  for (let i = 0; i < 3; i++) {
    if (v[i] !== floor[i]) return v[i] > floor[i];
  }
  return true;
}

test('every Node runner Dockerfile pins the same fixed npm release', () => {
  const versions = {};
  for (const [rel, text] of Object.entries(DOCKERFILES)) {
    const v = npmVersionOf(text);
    assert.ok(v, `${rel}: no "ARG NPM_VERSION=<x.y.z>" - the bundled npm of the Node base carries High advisories`);
    assert.match(v, /^\d+\.\d+\.\d+$/, `${rel}: NPM_VERSION must be an exact release, not a range or tag (got ${v})`);
    assert.ok(atLeast(v, MIN_NPM), `${rel}: NPM_VERSION ${v} is older than ${MIN_NPM.join('.')}, the first release scanned clean`);
    assert.match(text, /npm install -g "npm@\$\{NPM_VERSION\}"/, `${rel}: does not install npm@\${NPM_VERSION}`);
    versions[rel] = v;
  }
  assert.equal(new Set(Object.values(versions)).size, 1, `NPM_VERSION differs between images: ${JSON.stringify(versions)}`);
});

test('core-full copies the replaced npm, not the one bundled in the Node image', () => {
  const core = DOCKERFILES['core/Dockerfile'];
  assert.match(core, /COPY --from=node-npm \/usr\/local\/lib\/node_modules \/usr\/local\/lib\/node_modules/);
  assert.doesNotMatch(core, /COPY --from=node-relay \/usr\/local\/lib\/node_modules/);
});

test('the runner Dockerfiles keep digest-pinned base images', () => {
  for (const rel of ['services/mcp-stdio-runner/Dockerfile', 'services/workspace-runner/Dockerfile']) {
    const defaults = [...DOCKERFILES[rel].matchAll(/^ARG (\w+_IMAGE)=(\S+)$/gm)];
    assert.ok(defaults.length > 0, `${rel}: no ARG *_IMAGE default found`);
    for (const [, name, value] of defaults) {
      assert.match(value, /@sha256:[0-9a-f]{64}$/, `${rel}: ${name} default is not digest-pinned (${value})`);
    }
  }
});

test('PUPPETEER_LAUNCH_OPTIONS survives Dockerfile ENV quoting as valid JSON', () => {
  // ENV drops unquoted double quotes: PUPPETEER_LAUNCH_OPTIONS={"a":"b"} became {a:b} in the
  // image and server-puppeteer logged "Failed to parse PUPPETEER_LAUNCH_OPTIONS". The value
  // must be single-quoted so the JSON reaches the process intact.
  for (const rel of ['services/mcp-stdio-runner/Dockerfile', 'core/Dockerfile']) {
    const m = DOCKERFILES[rel].match(/^[ \t]*(?:ENV[ \t]+)?PUPPETEER_LAUNCH_OPTIONS=(\S+)/m);
    assert.ok(m, `${rel}: PUPPETEER_LAUNCH_OPTIONS not found`);
    const raw = m[1];
    assert.match(raw, /^'.*'$/, `${rel}: PUPPETEER_LAUNCH_OPTIONS must be single-quoted (got ${raw})`);
    const parsed = JSON.parse(raw.slice(1, -1));
    assert.match(parsed.executablePath, /\/chrome$/, `${rel}: executablePath should point at the chrome binary`);
  }
});
