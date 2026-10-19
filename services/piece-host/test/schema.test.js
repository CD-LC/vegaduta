import assert from 'node:assert/strict';
import test from 'node:test';
import { buildInputSchema, coerceArgs, finalizeProps, publishSchema, validateArgs } from '../src/schema.js';

const props = {
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
};

test('buildInputSchema maps types, required/optional and static options to enum', () => {
  const { schema, skipped } = buildInputSchema(props);
  assert.equal(schema.type, 'object');
  assert.equal(schema.additionalProperties, false);
  assert.deepEqual(schema.required, ['text']); // `times` is required but has a default
  assert.equal(schema.properties.text.type, 'string');
  assert.equal(schema.properties.text.description, 'Text: What to echo');
  assert.deepEqual(schema.properties.times, { type: 'number', description: 'Times', minimum: 1, maximum: 5, default: 1 });
  assert.deepEqual(schema.properties.mode.enum, ['upper', 'lower']);
  assert.equal(schema.properties.mode.type, 'string');
  assert.equal(schema.properties.loud.type, 'boolean');
  assert.equal(schema.properties.tags.type, 'array');
  assert.equal(schema.properties.meta.type, 'object');
  assert.equal(schema.properties.who.type, undefined); // dynamic dropdown: not enumerable
  assert.match(schema.properties.who.description, /not enumerated/);
  assert.equal(schema.properties.note, undefined);
  assert.equal(skipped.length, 1);
  assert.match(skipped[0], /^note: /);
});

test('static dropdown with mixed or object values does not produce a lying enum', () => {
  const { schema } = buildInputSchema({
    a: { type: 'STATIC_DROPDOWN', displayName: 'A', required: true, options: { options: [{ label: 'x', value: 1 }, { label: 'y', value: 'two' }] } },
    b: { type: 'STATIC_DROPDOWN', displayName: 'B', required: false, options: { options: [{ label: 'x', value: { id: 1 } }] } },
    c: { type: 'STATIC_DROPDOWN', displayName: 'C', required: false, options: { options: [{ label: 'x', value: 7 }, { label: 'y', value: 8 }] } },
  });
  assert.equal(schema.properties.a.enum, undefined);
  assert.equal(schema.properties.b.enum, undefined);
  assert.deepEqual(schema.properties.c.enum, [7, 8]);
  assert.equal(schema.properties.c.type, 'number');
});

test('multi-select, array-of-objects, dynamic, file and unknown types', () => {
  const { schema, skipped } = buildInputSchema({
    ms: { type: 'STATIC_MULTI_SELECT_DROPDOWN', displayName: 'MS', required: false, options: { options: [{ label: 'a', value: 'a' }] } },
    rows: { type: 'ARRAY', displayName: 'Rows', required: false, properties: { name: { type: 'SHORT_TEXT', displayName: 'Name', required: true } } },
    dyn: { type: 'DYNAMIC', displayName: 'Dyn', required: false },
    file: { type: 'FILE', displayName: 'File', required: true },
    weird: { type: 'SOMETHING_NEW', displayName: 'W' },
  });
  assert.deepEqual(schema.properties.ms.items, { type: 'string', enum: ['a'] });
  assert.equal(schema.properties.rows.items.type, 'object');
  assert.deepEqual(schema.properties.rows.items.required, ['name']);
  assert.equal(schema.properties.dyn.type, 'object');
  assert.deepEqual(schema.properties.file.required, ['filename', 'base64']);
  assert.deepEqual(schema.required, ['file']);
  assert.equal(schema.properties.weird, undefined);
  assert.match(skipped[0], /^weird: unsupported/);
});

test('coerceArgs: numeric strings, booleans, JSON-in-a-string, nulls dropped', () => {
  const out = coerceArgs(props, { text: 'a', times: '3', loud: 'true', tags: '["x","y"]', meta: null, mystery: 'keep' });
  assert.deepEqual(out, { text: 'a', times: 3, loud: true, tags: ['x', 'y'], mystery: 'keep' });
  assert.equal(coerceArgs(props, { times: 'abc' }).times, 'abc'); // left for validation to reject
});

test('validateArgs: required, unknown key, enum, type, range', () => {
  const { schema } = buildInputSchema(props);
  assert.deepEqual(validateArgs(schema, { text: 'hi' }), []);
  assert.ok(validateArgs(schema, {}).includes('text is required'));
  assert.ok(validateArgs(schema, { text: 'a', zzz: 1 }).includes('zzz is not a recognised field'));
  assert.ok(validateArgs(schema, { text: 'a', mode: 'x' }).some((m) => m.startsWith('mode must be one of')));
  assert.ok(validateArgs(schema, { text: 5 }).includes('text must be a string'));
  assert.ok(validateArgs(schema, { text: 'a', times: 9 }).includes('times must be <= 5'));
  assert.ok(validateArgs(schema, { text: 'a', loud: 'yes' }).includes('loud must be a boolean'));
  assert.deepEqual(validateArgs(schema, []), ['arguments must be an object']);
});

test('validateArgs checks nested array items', () => {
  const { schema } = buildInputSchema({
    rows: { type: 'ARRAY', displayName: 'Rows', required: true, properties: { name: { type: 'SHORT_TEXT', displayName: 'Name', required: true } } },
  });
  assert.deepEqual(validateArgs(schema, { rows: [{ name: 'a' }] }), []);
  const errs = validateArgs(schema, { rows: [{}] });
  assert.ok(errs.includes('rows[0].name is required'), errs.join('|'));
});

test('finalizeProps applies defaults and converts files', () => {
  const out = finalizeProps(props, { text: 'a' });
  assert.equal(out.times, 1);
  assert.equal('note' in out, false);

  const filed = finalizeProps(
    { file: { type: 'FILE', displayName: 'F', required: true } },
    { file: { filename: 'Report.TXT', base64: Buffer.from('hi').toString('base64') } },
  );
  assert.equal(filed.file.extension, 'txt');
  assert.equal(filed.file.data.length, 2);
  assert.equal(filed.file.filename, 'Report.TXT');
});

test('publishSchema gives every node a type, never an empty items, and no additionalProperties; validation keeps the original', () => {
  const { schema } = buildInputSchema({
    who: { type: 'DROPDOWN', displayName: 'Who', required: false },
    blob: { type: 'JSON', displayName: 'Blob', required: false },
    many: { type: 'MULTI_SELECT_DROPDOWN', displayName: 'Many', required: false },
    odd: { type: 'STATIC_DROPDOWN', displayName: 'Odd', required: false, options: { options: [{ label: 'x', value: { id: 1 } }] } },
    nums: { type: 'STATIC_DROPDOWN', displayName: 'Nums', required: false, options: { options: [{ label: 'x', value: 7 }] } },
    meta: { type: 'OBJECT', displayName: 'Meta', required: false },
    rows: { type: 'ARRAY', displayName: 'Rows', required: false, properties: { name: { type: 'SHORT_TEXT', displayName: 'Name', required: true } } },
    file: { type: 'FILE', displayName: 'File', required: false },
  });
  const pub = publishSchema(schema);

  const missingType = [];
  const extraProps = [];
  const walk = (node, path) => {
    if (typeof node.type !== 'string') missingType.push(path);
    if ('additionalProperties' in node) extraProps.push(path);
    for (const [k, v] of Object.entries(node.properties || {})) walk(v, `${path}.${k}`);
    if (node.items !== undefined) walk(node.items, `${path}[]`);
  };
  walk(pub, '$');
  assert.deepEqual(missingType, []);
  assert.deepEqual(extraProps, []);

  assert.equal(pub.properties.who.type, 'string');
  assert.equal(pub.properties.blob.type, 'string');
  assert.deepEqual(pub.properties.many.items, { type: 'string' });
  assert.equal(pub.properties.odd.type, 'string');
  assert.equal(pub.properties.nums.type, 'number');
  assert.equal(pub.properties.rows.items.properties.name.type, 'string');
  assert.deepEqual(pub.properties.file.required, ['filename', 'base64']);

  // The original is untouched, so the host still validates strictly and still accepts any value for a dynamic dropdown.
  assert.equal(schema.additionalProperties, false);
  assert.equal(schema.properties.who.type, undefined);
  assert.deepEqual(validateArgs(schema, { who: 123 }), []);
  assert.deepEqual(validateArgs(schema, { nope: 1 }), ['nope is not a recognised field']);
});
