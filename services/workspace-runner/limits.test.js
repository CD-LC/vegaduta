'use strict';

// The runner executes whatever build command the person approves (mvn, npm, gradle, pip) with open
// egress, so the container must bound the processes a runaway command can create, and the image
// must build from the same base every time. These are static checks on the files that say so.

const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..', '..');
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8');

// The text of one top-level service of a compose file: from "  name:" to the next 2-space key.
function serviceBlock(rel, name) {
    const lines = read(rel).split('\n');
    const start = lines.findIndex((l) => l === `  ${name}:`);
    assert.notEqual(start, -1, `${rel}: service ${name} not found`);
    let end = lines.length;
    for (let i = start + 1; i < lines.length; i++) {
        if (/^ {0,2}\S/.test(lines[i]) && !/^ {0,2}#/.test(lines[i])) { end = i; break; }
    }
    return lines.slice(start, end).join('\n');
}

for (const file of ['deploy/compose/personal.yml', 'deploy/compose/lite.yml']) {
    test(`${file}: workspace-runner limits its processes`, () => {
        const block = serviceBlock(file, 'workspace-runner');
        const limits = block.match(/deploy:\s*\n\s+resources:\s*\n\s+limits:\s*\n((?:\s{10,}\S.*\n?)+)/);
        assert.ok(limits, `${file}: workspace-runner has no deploy.resources.limits`);
        assert.match(limits[1], /^\s+memory:/m, `${file}: memory limit missing`);
        assert.match(limits[1], /^\s+pids:\s*\$\{WORKSPACE_RUNNER_PIDS_LIMIT:-\d+\}\s*$/m,
            `${file}: workspace-runner needs pids: \${WORKSPACE_RUNNER_PIDS_LIMIT:-<n>} next to its memory limit`);
    });
}

test('the default pids limit is the same in personal.yml and lite.yml, and roomy enough for a JVM build', () => {
    const dflt = (file) => Number(serviceBlock(file, 'workspace-runner').match(/WORKSPACE_RUNNER_PIDS_LIMIT:-(\d+)/)?.[1]);
    assert.equal(dflt('deploy/compose/personal.yml'), dflt('deploy/compose/lite.yml'));
    // every thread counts as a pid: a Gradle daemon plus a parallel Maven or node build needs hundreds
    assert.ok(dflt('deploy/compose/personal.yml') >= 1024, 'a build would hit the limit');
});

test('both base images of the Dockerfile are pinned by digest', () => {
    const dockerfile = read('services/workspace-runner/Dockerfile');
    for (const arg of ['NODE_IMAGE', 'JDK_IMAGE']) {
        const m = dockerfile.match(new RegExp(`^ARG ${arg}=(\\S+)$`, 'm'));
        assert.ok(m, `ARG ${arg} not found`);
        assert.match(m[1], /@sha256:[0-9a-f]{64}$/, `${arg} default must carry a digest (got ${m[1]})`);
    }
});
