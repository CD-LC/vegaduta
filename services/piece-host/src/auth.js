// Piece auth <-> environment mapping.
//
// Pieces declare their auth with PieceAuth.* (SECRET_TEXT, OAUTH2, BASIC_AUTH,
// CUSTOM_AUTH, ...) and read it inside run() as context.auth. Value shapes are
// those of @activepieces/shared's AppConnectionValue (verified 2026-10-04,
// https://unpkg.com/@activepieces/shared/src/lib/automation/app-connection/app-connection.d.ts):
//   SECRET_TEXT { type, secret_text }
//   BASIC_AUTH  { type, username, password }
//   CUSTOM_AUTH { type, props }
//   OAUTH2      { type, access_token, token_type, refresh_token, scope, client_id,
//                 token_url, claimed_at, data, props, ... }
//
// This host receives credentials ONLY through its process environment (the
// platform passes the tenant's values as the registration's env, which the
// mcp-stdio-runner hands to the child and nothing else). They are
// deliberately NOT accepted as tool arguments: arguments pass through the
// model, tool-call journals and the untrusted-output pipeline.
//
// Env naming, with <P> = upper-cased piece key (google-sheets -> GOOGLE_SHEETS):
//   SECRET_TEXT  PIECE_AUTH_<P>                      the secret
//   OAUTH2       PIECE_AUTH_<P>                      an access token you obtained yourself
//                PIECE_AUTH_<P>_REFRESH_TOKEN        optional (this host never refreshes)
//                PIECE_AUTH_<P>_<PROP>               one per extra auth prop the piece declares
//   BASIC_AUTH   PIECE_AUTH_<P>_USERNAME / _PASSWORD
//   CUSTOM_AUTH  PIECE_AUTH_<P>_<PROP>               one per auth prop (camelCase -> SNAKE_CASE)
//   any type     PIECE_AUTH_<P>_JSON                 a JSON object with the fields above,
//                                                    individual vars override it
//   any type     PIECE_AUTH_<P> starting with "{"    the same JSON object, pasted into the one
//                                                    variable a connect form shows (used only when
//                                                    PIECE_AUTH_<P>_JSON is unset and the value
//                                                    parses as a JSON object; otherwise it is the
//                                                    plain secret/token as above)
// `node server.js --describe` prints the exact variables for every installed piece.

import { envToken } from './names.js';

export const AUTH_ENV_PREFIX = 'PIECE_AUTH_';
const MIN_SECRET_LENGTH = 6;
const SECRET_NAME_RE = /(token|secret|key|password|passwd|pass)/i;

function isPlainObject(v) {
  return v !== null && typeof v === 'object' && !Array.isArray(v);
}

export function authEnvBase(pieceKey) {
  return AUTH_ENV_PREFIX + envToken(pieceKey);
}

function alternativesOf(authProp) {
  if (Array.isArray(authProp)) return authProp.filter(isPlainObject);
  return isPlainObject(authProp) ? [authProp] : [];
}

function extraProps(alt) {
  return isPlainObject(alt.props) ? alt.props : {};
}

/** Env variable specs for ONE auth alternative. */
function specsFor(base, alt) {
  const specs = [];
  const push = (name, field, required, secret, doc) => specs.push({ name, field, required, secret, doc });
  switch (alt.type) {
    case 'SECRET_TEXT':
      push(base, 'secret_text', true, true, 'secret / API key / token');
      break;
    case 'OAUTH2':
      push(base, 'access_token', true, true, 'OAuth2 access token (obtained by you; not refreshed by this host)');
      push(`${base}_REFRESH_TOKEN`, 'refresh_token', false, true, 'optional OAuth2 refresh token');
      for (const [key, prop] of Object.entries(extraProps(alt))) {
        push(`${base}_${envToken(key)}`, key, isPlainObject(prop) && prop.required === true, isPlainObject(prop) && prop.type === 'SECRET_TEXT', 'extra auth field');
      }
      break;
    case 'BASIC_AUTH':
      push(`${base}_USERNAME`, 'username', true, false, 'username');
      push(`${base}_PASSWORD`, 'password', true, true, 'password');
      break;
    case 'CUSTOM_AUTH':
      for (const [key, prop] of Object.entries(extraProps(alt))) {
        const secret = (isPlainObject(prop) && prop.type === 'SECRET_TEXT') || SECRET_NAME_RE.test(key);
        push(`${base}_${envToken(key)}`, key, isPlainObject(prop) && prop.required === true, secret, 'auth field');
      }
      break;
    default:
      break;
  }
  return specs;
}

const SUPPORTED_TYPES = new Set(['SECRET_TEXT', 'OAUTH2', 'BASIC_AUTH', 'CUSTOM_AUTH']);

/** Describes the env a piece needs: { required, alternatives: [{ type, supported, env: [...], jsonEnv }] }. */
export function describeAuth(pieceKey, authProp) {
  const base = authEnvBase(pieceKey);
  const alts = alternativesOf(authProp);
  return {
    required: alts.length > 0,
    alternatives: alts.map((alt) => ({
      type: String(alt.type),
      supported: SUPPORTED_TYPES.has(alt.type),
      env: specsFor(base, alt),
      jsonEnv: `${base}_JSON`,
    })),
  };
}

function coerceField(prop, value) {
  if (!isPlainObject(prop) || typeof value !== 'string') return value;
  if (prop.type === 'NUMBER') {
    const n = Number(value);
    return value.trim() !== '' && Number.isFinite(n) ? n : value;
  }
  if (prop.type === 'CHECKBOX') return value === 'true' ? true : value === 'false' ? false : value;
  return value;
}

function parseJsonEnv(env, name) {
  const raw = env[name];
  if (raw === undefined || raw === '') return { ok: true, json: {} };
  try {
    const parsed = JSON.parse(raw);
    if (!isPlainObject(parsed)) return { ok: false, message: `${name} must be a JSON object` };
    return { ok: true, json: parsed };
  } catch {
    // Never echo the raw value: it is a credential.
    return { ok: false, message: `${name} is not valid JSON` };
  }
}

/** The value of `name` when it is a JSON object literal ("{...}"), else null. Never throws, never echoes the value. */
function inlineJsonObject(env, name) {
  const raw = env[name];
  if (typeof raw !== 'string' || !raw.trimStart().startsWith('{')) return null;
  try {
    const parsed = JSON.parse(raw);
    return isPlainObject(parsed) ? parsed : null;
  } catch {
    return null;
  }
}

function resolveOne(base, alt, env) {
  const specs = specsFor(base, alt);
  const jsonName = `${base}_JSON`;
  const parsed = parseJsonEnv(env, jsonName);
  if (!parsed.ok) return { ok: false, message: parsed.message };
  let json = parsed.json;

  // A catalog connect form shows exactly one variable, PIECE_AUTH_<P>. Accept the JSON object there too
  // (only when _JSON is not set), so a piece whose real auth has several fields still works.
  const jsonSet = typeof env[jsonName] === 'string' && env[jsonName] !== '';
  const inline = jsonSet ? null : inlineJsonObject(env, base);
  if (inline) json = inline;

  // Individual env vars override the JSON blob.
  const fromEnv = {};
  for (const spec of specs) {
    if (inline && spec.name === base) continue; // that variable carried the JSON object, not a plain value
    const value = env[spec.name];
    if (typeof value === 'string' && value !== '') fromEnv[spec.field] = value;
  }

  const secrets = [];
  const noteSecret = (field, value) => {
    const spec = specs.find((s) => s.field === field);
    const sensitive = spec ? spec.secret : SECRET_NAME_RE.test(field);
    if (sensitive && typeof value === 'string' && value.length >= MIN_SECRET_LENGTH) secrets.push(value);
  };

  const missing = (fieldsObj) => specs.filter((s) => s.required && (fieldsObj[s.field] === undefined || fieldsObj[s.field] === ''));
  const failure = (fieldsObj) => ({
    ok: false,
    message: `missing ${missing(fieldsObj).map((s) => s.name).join(', ')} (or provide them as a JSON object in ${jsonName} or ${base})`,
  });

  switch (alt.type) {
    case 'SECRET_TEXT': {
      const secretText = fromEnv.secret_text !== undefined ? fromEnv.secret_text : json.secret_text;
      const fields = { secret_text: secretText };
      if (typeof secretText !== 'string' || secretText === '') return failure(fields);
      noteSecret('secret_text', secretText);
      return { ok: true, value: { type: 'SECRET_TEXT', secret_text: secretText }, secrets };
    }
    case 'BASIC_AUTH': {
      const fields = {
        username: fromEnv.username !== undefined ? fromEnv.username : json.username,
        password: fromEnv.password !== undefined ? fromEnv.password : json.password,
      };
      if (missing(fields).length > 0) return failure(fields);
      noteSecret('password', fields.password);
      return { ok: true, value: { type: 'BASIC_AUTH', username: fields.username, password: fields.password }, secrets };
    }
    case 'CUSTOM_AUTH': {
      const declared = extraProps(alt);
      const jsonProps = isPlainObject(json.props) ? json.props : json;
      const props = {};
      for (const key of Object.keys(declared)) {
        const v = fromEnv[key] !== undefined ? fromEnv[key] : jsonProps[key];
        if (v !== undefined && v !== '') props[key] = coerceField(declared[key], v);
      }
      if (missing(props).length > 0) return failure(props);
      for (const [k, v] of Object.entries(props)) noteSecret(k, v);
      return { ok: true, value: { type: 'CUSTOM_AUTH', props }, secrets };
    }
    case 'OAUTH2': {
      const accessToken = fromEnv.access_token !== undefined ? fromEnv.access_token : json.access_token;
      const declared = extraProps(alt);
      const props = isPlainObject(json.props) ? { ...json.props } : {};
      for (const key of Object.keys(declared)) {
        if (fromEnv[key] !== undefined) props[key] = coerceField(declared[key], fromEnv[key]);
      }
      const fields = { access_token: accessToken, ...props };
      if (missing(fields).length > 0) return failure(fields);
      const refresh = fromEnv.refresh_token !== undefined ? fromEnv.refresh_token : json.refresh_token;
      noteSecret('access_token', accessToken);
      noteSecret('refresh_token', refresh);
      return {
        ok: true,
        value: {
          type: 'OAUTH2',
          access_token: accessToken,
          token_type: typeof json.token_type === 'string' ? json.token_type : 'Bearer',
          refresh_token: typeof refresh === 'string' ? refresh : '',
          scope: typeof json.scope === 'string' ? json.scope : '',
          client_id: '',
          token_url: '',
          claimed_at: Math.floor(Date.now() / 1000),
          data: isPlainObject(json.data) ? json.data : {},
          props,
        },
        secrets,
      };
    }
    default:
      return { ok: false, message: `auth type ${alt.type} is not supported by piece-host` };
  }
}

/**
 * Resolves the connection value for a piece from `env`.
 * Returns { ok: true, value, secrets } (value is undefined when the piece has no auth)
 * or { ok: false, message }. The message names variables, never values.
 */
export function resolveAuth(pieceKey, authProp, env = process.env) {
  const alts = alternativesOf(authProp);
  if (alts.length === 0) return { ok: true, value: undefined, secrets: [] };
  const base = authEnvBase(pieceKey);
  const problems = [];
  for (const alt of alts) {
    const result = resolveOne(base, alt, env);
    if (result.ok) return result;
    problems.push(`${alt.type}: ${result.message}`);
  }
  return {
    ok: false,
    message: `Credentials for piece '${pieceKey}' are not configured - ${problems.join('; ')}.`,
  };
}
