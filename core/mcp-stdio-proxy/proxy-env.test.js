'use strict';

// SEC-030 unit tests for proxy.js#buildForwardEnv - no runner needed.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { buildForwardEnv } = require('./proxy');

test('forwards only the JSON-carried target env, never the relay process env', () => {
    const { env, error } = buildForwardEnv({
        MCP_SIDECAR_URL: 'runner:8097',
        MCP_SIDECAR_SECRET: 's',
        NODE_OPTIONS: '--require /tmp/evil.js',
        APP_ENCRYPTION_KEY: 'core-secret',
        MCP_SIDECAR_TARGET_ENV_JSON: JSON.stringify({ GITHUB_TOKEN: 'ghp_x', API_KEY: 'k' }),
    });
    assert.equal(error, undefined);
    assert.deepEqual(env, { GITHUB_TOKEN: 'ghp_x', API_KEY: 'k' });
});

test('missing target env JSON forwards nothing', () => {
    assert.deepEqual(buildForwardEnv({ SOME_TENANT_VAR: 'x' }), { env: {} });
});

test('drops PATH/HOME and control var names from the JSON in any letter case', () => {
    const { env } = buildForwardEnv({
        MCP_SIDECAR_TARGET_ENV_JSON: JSON.stringify({
            PATH: '/evil', Path: '/evil', home: '/x', mcp_sidecar_secret: 'y', KEEP: 'v',
        }),
    });
    assert.deepEqual(env, { KEEP: 'v' });
});

test('rejects malformed JSON, non-objects and non-string values', () => {
    assert.ok(buildForwardEnv({ MCP_SIDECAR_TARGET_ENV_JSON: '{nope' }).error);
    assert.ok(buildForwardEnv({ MCP_SIDECAR_TARGET_ENV_JSON: '[1,2]' }).error);
    assert.ok(buildForwardEnv({ MCP_SIDECAR_TARGET_ENV_JSON: 'null' }).error);
    assert.ok(buildForwardEnv({ MCP_SIDECAR_TARGET_ENV_JSON: '{"A":1}' }).error);
});
