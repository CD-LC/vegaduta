// Activepieces action props -> JSON Schema (for MCP tool inputSchema), plus the
// argument coercion / validation / defaulting that the framework's own engine
// does for flow runs and that this host has to redo for MCP calls.
//
// Property type names are the runtime values of PropertyType in
// @activepieces/pieces-framework (MIT), verified 2026-10-04 against
// https://unpkg.com/@activepieces/pieces-framework/src/lib/property/input/property-type.d.ts :
//   SHORT_TEXT LONG_TEXT MARKDOWN DROPDOWN STATIC_DROPDOWN NUMBER CHECKBOX
//   OAUTH2 SECRET_TEXT ARRAY OBJECT BASIC_AUTH JSON MULTI_SELECT_DROPDOWN
//   STATIC_MULTI_SELECT_DROPDOWN DYNAMIC CUSTOM_AUTH OIDC DATE_TIME FILE CUSTOM COLOR
// Property reference: https://www.activepieces.com/docs/build-pieces/piece-reference/properties
// Static dropdown option shape ({ options: { options: [{label, value}] } }):
// https://unpkg.com/@activepieces/pieces-framework/src/lib/property/input/dropdown/common.d.ts

const MAX_DESCRIPTION = 400;
const MAX_DEPTH = 3;
const MAX_ERRORS = 20;

function isPlainObject(v) {
  return v !== null && typeof v === 'object' && !Array.isArray(v);
}

function describeProp(prop) {
  const name = typeof prop.displayName === 'string' ? prop.displayName : '';
  const detail = typeof prop.description === 'string' ? prop.description : '';
  let text = name && detail ? `${name}: ${detail}` : detail || name;
  text = text.replace(/\s+/g, ' ').trim();
  return text.length > MAX_DESCRIPTION ? `${text.slice(0, MAX_DESCRIPTION - 3)}...` : text;
}

function primitiveTypeOf(values) {
  if (values.length === 0) return null;
  if (values.every((v) => typeof v === 'string')) return 'string';
  if (values.every((v) => typeof v === 'number' && Number.isFinite(v))) return 'number';
  if (values.every((v) => typeof v === 'boolean')) return 'boolean';
  return null;
}

function staticOptionValues(prop) {
  const opts = prop && prop.options && prop.options.options;
  return Array.isArray(opts) ? opts.filter((o) => o && typeof o === 'object').map((o) => o.value) : [];
}

function withDefault(schema, prop) {
  const d = prop.defaultValue;
  if (typeof d === 'string' || typeof d === 'boolean' || (typeof d === 'number' && Number.isFinite(d))) {
    schema.default = d;
  }
  return schema;
}

function build(prop, depth) {
  if (!isPlainObject(prop) || typeof prop.type !== 'string') return { skip: 'not a property' };
  const description = describeProp(prop);
  const base = description ? { description } : {};

  switch (prop.type) {
    case 'SHORT_TEXT':
    case 'LONG_TEXT':
    case 'COLOR':
      return { schema: withDefault({ type: 'string', ...base }, prop) };
    case 'DATE_TIME':
      return {
        schema: withDefault(
          { type: 'string', ...base, description: `${description ? `${description} ` : ''}(ISO 8601 date-time)` },
          prop,
        ),
      };
    case 'NUMBER': {
      const schema = { type: 'number', ...base };
      if (typeof prop.min === 'number' && Number.isFinite(prop.min)) schema.minimum = prop.min;
      if (typeof prop.max === 'number' && Number.isFinite(prop.max)) schema.maximum = prop.max;
      return { schema: withDefault(schema, prop) };
    }
    case 'CHECKBOX':
      return { schema: withDefault({ type: 'boolean', ...base }, prop) };
    case 'STATIC_DROPDOWN': {
      const values = staticOptionValues(prop);
      const type = primitiveTypeOf(values);
      if (!type) return { schema: { ...base } };
      return { schema: withDefault({ type, enum: [...new Set(values)], ...base }, prop) };
    }
    case 'STATIC_MULTI_SELECT_DROPDOWN': {
      const values = staticOptionValues(prop);
      const type = primitiveTypeOf(values);
      const items = type ? { type, enum: [...new Set(values)] } : {};
      return { schema: { type: 'array', items, ...base } };
    }
    case 'DROPDOWN':
      return {
        schema: {
          description: `${description ? `${description} ` : ''}(identifier of the chosen option; options are looked up dynamically by the piece, so they are not enumerated here)`,
        },
      };
    case 'MULTI_SELECT_DROPDOWN':
      return {
        schema: {
          type: 'array',
          items: {},
          description: `${description ? `${description} ` : ''}(identifiers; options are not enumerated here)`,
        },
      };
    case 'ARRAY': {
      if (isPlainObject(prop.properties) && depth < MAX_DEPTH) {
        const inner = buildObject(prop.properties, depth + 1);
        return { schema: { type: 'array', items: inner.schema, ...base } };
      }
      return { schema: { type: 'array', items: { type: 'string' }, ...base } };
    }
    case 'OBJECT':
      return { schema: { type: 'object', additionalProperties: true, ...base } };
    case 'JSON':
      return {
        schema: { description: `${description ? `${description} ` : ''}(JSON value; pass an object or array, or its JSON-encoded string)` },
      };
    case 'DYNAMIC':
      return {
        schema: {
          type: 'object',
          additionalProperties: true,
          description: `${description ? `${description} ` : ''}(piece-specific fields resolved at runtime; pass an object keyed by field name)`,
        },
      };
    case 'FILE':
      return {
        schema: {
          type: 'object',
          properties: {
            filename: { type: 'string' },
            base64: { type: 'string', description: 'file content, base64-encoded' },
          },
          required: ['filename', 'base64'],
          additionalProperties: false,
          ...base,
        },
      };
    case 'MARKDOWN':
    case 'CUSTOM':
      return { skip: `${prop.type} is display-only` };
    default:
      return { skip: `unsupported property type ${prop.type}` };
  }
}

function buildObject(props, depth) {
  const properties = {};
  const required = [];
  const skipped = [];
  for (const [key, prop] of Object.entries(props || {})) {
    const built = build(prop, depth);
    if (built.skip) {
      skipped.push(`${key}: ${built.skip}`);
      continue;
    }
    properties[key] = built.schema;
    if (isPlainObject(prop) && prop.required === true && prop.defaultValue === undefined) required.push(key);
  }
  const schema = { type: 'object', properties, additionalProperties: false };
  if (required.length > 0) schema.required = required;
  return { schema, skipped };
}

/** Builds the MCP tool inputSchema for an action's props map. `skipped` lists props that could not be represented. */
export function buildInputSchema(props) {
  return buildObject(props, 0);
}

/**
 * The schema as PUBLISHED in tools/list, derived from the one the host validates with (validateArgs keeps using
 * the original, so it stays permissive for dynamic dropdowns and exact for everything else). Providers differ in
 * what they accept in function declarations: Gemini/Vertex rejects nodes without a "type" (and, depending on API
 * version, "additionalProperties"), and several providers reject an empty "items". Core passes MCP schemas through
 * untouched (SanitizedMcpToolCallback), so the host has to hand out the common denominator itself:
 *   - every node gets a "type" (type-less nodes - dynamic DROPDOWN, JSON, unenumerable STATIC_DROPDOWN - become
 *     "string"; the host still accepts any JSON value there and coerces JSON-in-a-string),
 *   - "items" is never empty,
 *   - "additionalProperties" is omitted (the host enforces unknown-field rejection itself).
 * Returns a deep copy; the input is not modified.
 */
export function publishSchema(schema) {
  const walk = (node) => {
    if (!isPlainObject(node)) return { type: 'string' };
    const out = {};
    for (const [key, value] of Object.entries(node)) {
      if (key === 'additionalProperties') continue;
      if (key === 'properties' && isPlainObject(value)) {
        out.properties = {};
        for (const [name, child] of Object.entries(value)) out.properties[name] = walk(child);
      } else if (key === 'items') {
        out.items = walk(value);
      } else if (Array.isArray(value)) {
        out[key] = [...value];
      } else {
        out[key] = isPlainObject(value) ? { ...value } : value;
      }
    }
    if (typeof out.type !== 'string') {
      out.type = Array.isArray(out.enum) ? primitiveTypeOf(out.enum) || 'string' : 'string';
    }
    return out;
  };
  return walk(schema);
}

// ---------------------------------------------------------------------------
// Argument handling
// ---------------------------------------------------------------------------

function tryParseJson(text) {
  const trimmed = text.trim();
  if (!(trimmed.startsWith('{') || trimmed.startsWith('['))) return text;
  try {
    return JSON.parse(trimmed);
  } catch {
    return text;
  }
}

function coerceValue(prop, value) {
  if (!isPlainObject(prop) || typeof value !== 'string') return value;
  switch (prop.type) {
    case 'NUMBER': {
      const trimmed = value.trim();
      const n = Number(trimmed);
      return trimmed !== '' && Number.isFinite(n) ? n : value;
    }
    case 'CHECKBOX':
      if (value === 'true') return true;
      if (value === 'false') return false;
      return value;
    case 'JSON':
    case 'OBJECT':
    case 'ARRAY':
    case 'DYNAMIC':
    case 'MULTI_SELECT_DROPDOWN':
    case 'STATIC_MULTI_SELECT_DROPDOWN':
    case 'FILE':
      return tryParseJson(value);
    default:
      return value;
  }
}

/** Light, model-friendly coercion (numeric strings, "true"/"false", JSON-in-a-string) before validation. */
export function coerceArgs(props, args) {
  const out = {};
  for (const [key, value] of Object.entries(args || {})) {
    // Models routinely send null for "not provided"; treat it as omitted.
    if (value === null || value === undefined) continue;
    out[key] = Object.prototype.hasOwnProperty.call(props || {}, key) ? coerceValue(props[key], value) : value;
  }
  return out;
}

function checkValue(schema, value, path, errors, depth) {
  if (errors.length >= MAX_ERRORS || !isPlainObject(schema)) return;
  if (Array.isArray(schema.enum) && !schema.enum.some((e) => e === value)) {
    const shown = schema.enum.slice(0, 20).map((e) => JSON.stringify(e)).join(', ');
    errors.push(`${path} must be one of [${shown}${schema.enum.length > 20 ? ', ...' : ''}]`);
    return;
  }
  switch (schema.type) {
    case 'string':
      if (typeof value !== 'string') errors.push(`${path} must be a string`);
      return;
    case 'number':
      if (typeof value !== 'number' || !Number.isFinite(value)) {
        errors.push(`${path} must be a number`);
        return;
      }
      if (typeof schema.minimum === 'number' && value < schema.minimum) errors.push(`${path} must be >= ${schema.minimum}`);
      if (typeof schema.maximum === 'number' && value > schema.maximum) errors.push(`${path} must be <= ${schema.maximum}`);
      return;
    case 'boolean':
      if (typeof value !== 'boolean') errors.push(`${path} must be a boolean`);
      return;
    case 'array':
      if (!Array.isArray(value)) {
        errors.push(`${path} must be an array`);
        return;
      }
      if (isPlainObject(schema.items) && depth < MAX_DEPTH + 2) {
        value.forEach((item, i) => checkValue(schema.items, item, `${path}[${i}]`, errors, depth + 1));
      }
      return;
    case 'object': {
      if (!isPlainObject(value)) {
        errors.push(`${path} must be an object`);
        return;
      }
      const properties = isPlainObject(schema.properties) ? schema.properties : {};
      for (const req of Array.isArray(schema.required) ? schema.required : []) {
        if (value[req] === undefined || value[req] === null) errors.push(`${path}.${req} is required`);
      }
      for (const [key, v] of Object.entries(value)) {
        if (Object.prototype.hasOwnProperty.call(properties, key)) {
          checkValue(properties[key], v, `${path}.${key}`, errors, depth + 1);
        } else if (schema.additionalProperties === false) {
          errors.push(`${path}.${key} is not a recognised field`);
        }
      }
      return;
    }
    default:
      return;
  }
}

/** Returns a list of human-readable problems (empty when the arguments satisfy the schema). */
export function validateArgs(schema, args) {
  const errors = [];
  if (!isPlainObject(args)) return ['arguments must be an object'];
  checkValue(schema, args, 'arguments', errors, 0);
  return errors.map((e) => e.replace(/^arguments\./, ''));
}

function fileExtension(filename) {
  const dot = String(filename).lastIndexOf('.');
  return dot > 0 && dot < filename.length - 1 ? filename.slice(dot + 1).toLowerCase() : '';
}

function toApFile(value) {
  if (!isPlainObject(value) || typeof value.base64 !== 'string') return value;
  const filename = typeof value.filename === 'string' ? value.filename : 'file';
  return { filename, extension: fileExtension(filename), base64: value.base64, data: Buffer.from(value.base64, 'base64') };
}

/** Applies prop defaults for missing values and converts FILE values to the framework's ApFile shape. */
export function finalizeProps(props, args) {
  const out = { ...args };
  for (const [key, prop] of Object.entries(props || {})) {
    if (!isPlainObject(prop)) continue;
    if (out[key] === undefined && prop.defaultValue !== undefined && prop.type !== 'MARKDOWN') {
      out[key] = prop.defaultValue;
    }
    if (prop.type === 'FILE' && out[key] !== undefined) out[key] = toApFile(out[key]);
  }
  return out;
}
