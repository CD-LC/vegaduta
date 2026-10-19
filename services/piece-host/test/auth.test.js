import assert from 'node:assert/strict';
import test from 'node:test';
import { authEnvBase, describeAuth, resolveAuth } from '../src/auth.js';

const secretAuth = { type: 'SECRET_TEXT', displayName: 'Key', required: true };
const basicAuth = { type: 'BASIC_AUTH', displayName: 'Basic', required: true };
const oauth = { type: 'OAUTH2', displayName: 'OAuth', required: true, props: { region: { type: 'SHORT_TEXT', required: true } } };
const customAuth = {
  type: 'CUSTOM_AUTH',
  displayName: 'Custom',
  required: true,
  props: {
    instanceUrl: { type: 'SHORT_TEXT', displayName: 'Instance', required: true },
    apiToken: { type: 'SECRET_TEXT', displayName: 'Token', required: true },
    retries: { type: 'NUMBER', displayName: 'Retries', required: false },
  },
};

test('authEnvBase', () => {
  assert.equal(authEnvBase('google-sheets'), 'PIECE_AUTH_GOOGLE_SHEETS');
});

test('SECRET_TEXT from PIECE_AUTH_<P>', () => {
  const r = resolveAuth('fake', secretAuth, { PIECE_AUTH_FAKE: 'abcdefgh' });
  assert.equal(r.ok, true);
  assert.deepEqual(r.value, { type: 'SECRET_TEXT', secret_text: 'abcdefgh' });
  assert.deepEqual(r.secrets, ['abcdefgh']);
});

test('missing credentials name variables, never values', () => {
  const r = resolveAuth('fake', secretAuth, { OTHER: 'x' });
  assert.equal(r.ok, false);
  assert.match(r.message, /PIECE_AUTH_FAKE/);
  assert.match(r.message, /PIECE_AUTH_FAKE_JSON/);
});

test('JSON form, with individual variable overriding it', () => {
  const json = JSON.stringify({ secret_text: 'from-json-1' });
  assert.equal(resolveAuth('fake', secretAuth, { PIECE_AUTH_FAKE_JSON: json }).value.secret_text, 'from-json-1');
  assert.equal(resolveAuth('fake', secretAuth, { PIECE_AUTH_FAKE_JSON: json, PIECE_AUTH_FAKE: 'from-env-1' }).value.secret_text, 'from-env-1');
});

test('invalid JSON is reported without echoing the (credential) value', () => {
  const bad = 'not-json-SUPER-SECRET';
  const r = resolveAuth('fake', secretAuth, { PIECE_AUTH_FAKE_JSON: bad });
  assert.equal(r.ok, false);
  assert.ok(!r.message.includes('SUPER-SECRET'));
  assert.match(r.message, /not valid JSON/);
});

test('BASIC_AUTH', () => {
  const r = resolveAuth('twilio', basicAuth, { PIECE_AUTH_TWILIO_USERNAME: 'AC123', PIECE_AUTH_TWILIO_PASSWORD: 'tokentoken' });
  assert.deepEqual(r.value, { type: 'BASIC_AUTH', username: 'AC123', password: 'tokentoken' });
  assert.deepEqual(r.secrets, ['tokentoken']); // the username is not treated as secret
  assert.equal(resolveAuth('twilio', basicAuth, { PIECE_AUTH_TWILIO_USERNAME: 'AC123' }).ok, false);
});

test('CUSTOM_AUTH maps camelCase props to SNAKE_CASE env and coerces numbers', () => {
  const env = {
    PIECE_AUTH_JIRA_CLOUD_INSTANCE_URL: 'https://x.atlassian.net',
    PIECE_AUTH_JIRA_CLOUD_API_TOKEN: 'tok-123456789',
    PIECE_AUTH_JIRA_CLOUD_RETRIES: '3',
  };
  const r = resolveAuth('jira-cloud', customAuth, env);
  assert.equal(r.ok, true);
  assert.deepEqual(r.value, { type: 'CUSTOM_AUTH', props: { instanceUrl: 'https://x.atlassian.net', apiToken: 'tok-123456789', retries: 3 } });
  assert.deepEqual(r.secrets, ['tok-123456789']);
});

test('CUSTOM_AUTH accepts the JSON form (flat or under props)', () => {
  const flat = JSON.stringify({ instanceUrl: 'https://x', apiToken: 'tok-123456789' });
  assert.equal(resolveAuth('jira-cloud', customAuth, { PIECE_AUTH_JIRA_CLOUD_JSON: flat }).ok, true);
  const nested = JSON.stringify({ props: { instanceUrl: 'https://x', apiToken: 'tok-123456789' } });
  assert.equal(resolveAuth('jira-cloud', customAuth, { PIECE_AUTH_JIRA_CLOUD_JSON: nested }).ok, true);
  assert.equal(resolveAuth('jira-cloud', customAuth, { PIECE_AUTH_JIRA_CLOUD_INSTANCE_URL: 'https://x' }).ok, false);
});

test('OAUTH2 builds a connection value from an access token (no refresh performed)', () => {
  const r = resolveAuth('zoho-crm', oauth, { PIECE_AUTH_ZOHO_CRM: 'xoxb-1234567', PIECE_AUTH_ZOHO_CRM_REGION: 'eu' });
  assert.equal(r.ok, true);
  assert.equal(r.value.type, 'OAUTH2');
  assert.equal(r.value.access_token, 'xoxb-1234567');
  assert.equal(r.value.token_type, 'Bearer');
  assert.deepEqual(r.value.props, { region: 'eu' });
  assert.equal(resolveAuth('zoho-crm', oauth, { PIECE_AUTH_ZOHO_CRM: 'xoxb-1234567' }).ok, false); // required extra prop missing
});

test('alternative auth methods: first resolvable one wins', () => {
  const r = resolveAuth('multi', [secretAuth, basicAuth], { PIECE_AUTH_MULTI_USERNAME: 'u', PIECE_AUTH_MULTI_PASSWORD: 'password1' });
  assert.equal(r.ok, true);
  assert.equal(r.value.type, 'BASIC_AUTH');
});

test('no auth declared -> ok with undefined value; unsupported type is a clear failure', () => {
  assert.deepEqual(resolveAuth('x', undefined, {}), { ok: true, value: undefined, secrets: [] });
  const r = resolveAuth('x', { type: 'OIDC' }, {});
  assert.equal(r.ok, false);
  assert.match(r.message, /not supported/);
});

test('short values are never registered as secrets to redact (avoids shredding output)', () => {
  const r = resolveAuth('fake', secretAuth, { PIECE_AUTH_FAKE: 'abc' });
  assert.equal(r.ok, true);
  assert.deepEqual(r.secrets, []);
});

test('describeAuth lists the exact variables', () => {
  const d = describeAuth('jira-cloud', customAuth);
  assert.equal(d.required, true);
  assert.equal(d.alternatives[0].type, 'CUSTOM_AUTH');
  assert.deepEqual(
    d.alternatives[0].env.map((e) => e.name),
    ['PIECE_AUTH_JIRA_CLOUD_INSTANCE_URL', 'PIECE_AUTH_JIRA_CLOUD_API_TOKEN', 'PIECE_AUTH_JIRA_CLOUD_RETRIES'],
  );
  assert.equal(d.alternatives[0].jsonEnv, 'PIECE_AUTH_JIRA_CLOUD_JSON');
  assert.equal(describeAuth('x', undefined).required, false);
});

test('PIECE_AUTH_<P> holding a JSON object is read as the JSON form (one-variable connect forms)', () => {
  const custom = resolveAuth('jira-cloud', customAuth, {
    PIECE_AUTH_JIRA_CLOUD: JSON.stringify({ instanceUrl: 'https://x.atlassian.net', apiToken: 'tok-123456' }),
  });
  assert.equal(custom.ok, true);
  assert.deepEqual(custom.value, { type: 'CUSTOM_AUTH', props: { instanceUrl: 'https://x.atlassian.net', apiToken: 'tok-123456' } });
  assert.deepEqual(custom.secrets, ['tok-123456']);

  const basic = resolveAuth('mail', basicAuth, { PIECE_AUTH_MAIL: '{"username":"u","password":"password1"}' });
  assert.equal(basic.ok, true);
  assert.equal(basic.value.username, 'u');

  // The explicit _JSON variable wins; a plain token that merely starts with "{" but is not JSON stays a token.
  const both = resolveAuth('fake', secretAuth, { PIECE_AUTH_FAKE_JSON: '{"secret_text":"from-json-1"}', PIECE_AUTH_FAKE: '{"secret_text":"ignored"}' });
  assert.equal(both.value.secret_text, '{"secret_text":"ignored"}');
  const notJson = resolveAuth('fake', secretAuth, { PIECE_AUTH_FAKE: '{not-json-secret' });
  assert.equal(notJson.value.secret_text, '{not-json-secret');
  // A JSON array is not a credential object either.
  assert.equal(resolveAuth('fake', secretAuth, { PIECE_AUTH_FAKE: '[1,2]' }).value.secret_text, '[1,2]');
});
