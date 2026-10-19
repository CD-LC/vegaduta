'use strict';

// TEST FIXTURE: reproduces the runtime shape of a piece built with
// @activepieces/pieces-framework (createPiece/createAction/Property.*) without
// depending on it, so the host's logic is testable with no network and no
// third-party code. Shapes verified against the framework's published typings:
// property `type` strings (PropertyType), static dropdown `options.options[]`,
// action `{ name, displayName, description, props, run, requireAuth }` and
// `piece.actions()` returning a name -> action map.

function createAction(def) {
  return { requireAuth: true, ...def };
}

class Piece {
  constructor(def) {
    this.displayName = def.displayName;
    this.description = def.description || '';
    this.auth = def.auth;
    this._actions = {};
    for (const a of def.actions) this._actions[a.name] = a;
  }
  actions() {
    return this._actions;
  }
  getAction(name) {
    return this._actions[name];
  }
}

const auth = { type: 'SECRET_TEXT', displayName: 'API key', required: true };

const echo = createAction({
  name: 'echo',
  displayName: 'Echo',
  description: 'Repeats text.',
  props: {
    text: { type: 'SHORT_TEXT', displayName: 'Text', description: 'What to echo', required: true },
    times: { type: 'NUMBER', displayName: 'Times', required: true, defaultValue: 1, min: 1, max: 5 },
    mode: {
      type: 'STATIC_DROPDOWN',
      displayName: 'Mode',
      required: false,
      options: { options: [{ label: 'Upper', value: 'upper' }, { label: 'Lower', value: 'lower' }] },
    },
    loud: { type: 'CHECKBOX', displayName: 'Loud', required: false },
    tags: { type: 'ARRAY', displayName: 'Tags', required: false },
    meta: { type: 'OBJECT', displayName: 'Meta', required: false },
    who: { type: 'DROPDOWN', displayName: 'Who', required: false },
    note: { type: 'MARKDOWN', value: 'display only' },
  },
  async run(ctx) {
    const p = ctx.propsValue;
    let out = String(p.text).repeat(p.times);
    if (p.mode === 'upper') out = out.toUpperCase();
    if (p.mode === 'lower') out = out.toLowerCase();
    if (p.loud) out += '!';
    return { out, times: p.times, tags: p.tags || null, authType: ctx.auth.type, runId: ctx.run.id, step: ctx.step.name };
  },
});

const leak = createAction({
  name: 'leak',
  displayName: 'Leak',
  description: 'Returns its own credential (to prove redaction).',
  props: {},
  async run(ctx) {
    return { echoed: ctx.auth.secret_text };
  },
});

const boom = createAction({
  name: 'boom',
  displayName: 'Boom',
  description: 'Always fails, mentioning the credential in the message.',
  props: {},
  async run(ctx) {
    throw new Error(`upstream rejected key ${ctx.auth.secret_text}`);
  },
});

const big = createAction({
  name: 'big',
  displayName: 'Big',
  description: 'Returns a large string.',
  props: { size: { type: 'NUMBER', displayName: 'Size', required: true } },
  async run(ctx) {
    return { blob: 'x'.repeat(ctx.propsValue.size) };
  },
});

const sleep = createAction({
  name: 'sleep',
  displayName: 'Sleep',
  description: 'Waits.',
  props: { ms: { type: 'NUMBER', displayName: 'Milliseconds', required: true } },
  async run(ctx) {
    await new Promise((resolve) => setTimeout(resolve, ctx.propsValue.ms));
    return 'done';
  },
});

const noauth = createAction({
  name: 'noauth',
  displayName: 'No auth',
  description: 'Runs without credentials.',
  requireAuth: false,
  props: {},
  async run(ctx) {
    return { auth: ctx.auth === undefined ? 'none' : 'present' };
  },
});

const customApiCall = createAction({
  name: 'custom_api_call',
  displayName: 'Custom API Call',
  description: 'Arbitrary HTTP request.',
  props: { url: { type: 'SHORT_TEXT', displayName: 'URL', required: true } },
  async run() {
    return 'should never run through the default policy';
  },
});

const storeRoundtrip = createAction({
  name: 'store_roundtrip',
  displayName: 'Store roundtrip',
  description: 'Writes and reads the in-memory store.',
  props: { value: { type: 'SHORT_TEXT', displayName: 'Value', required: true } },
  async run(ctx) {
    await ctx.store.put('k', ctx.propsValue.value);
    return { read: await ctx.store.get('k'), missing: await ctx.store.get('nope') };
  },
});

const fileLen = createAction({
  name: 'file_len',
  displayName: 'File length',
  description: 'Reports the size of an uploaded file.',
  props: { file: { type: 'FILE', displayName: 'File', required: true } },
  async run(ctx) {
    const f = ctx.propsValue.file;
    return { filename: f.filename, extension: f.extension, bytes: f.data.length };
  },
});

const chatty = createAction({
  name: 'chatty',
  displayName: 'Chatty',
  description: 'Writes to console.log (which must never reach the protocol stream).',
  requireAuth: false,
  props: {},
  async run() {
    console.log('hello from a noisy piece');
    return 'ok';
  },
});

const fake = new Piece({
  displayName: 'Fake Service',
  description: 'Test piece',
  auth,
  actions: [echo, leak, boom, big, sleep, noauth, customApiCall, storeRoundtrip, fileLen, chatty],
});

module.exports = { fake, helper: 42 };
