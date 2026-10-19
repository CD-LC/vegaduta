// Deny / allow policy over "<piece>__<action>" names.
//
// Patterns are case-insensitive globs where `*` matches any run of characters.
// Evaluation order: a deny match always wins; when an allow list is configured
// an action must additionally match it.
//
// Built-in default deny list (switch off with PIECE_HOST_DENY_DEFAULTS=off):
//   *__custom_api_call
// Nearly every piece ships a "Custom API Call" action that sends an arbitrary
// HTTP request to an arbitrary URL with the connection's credential attached
// (see e.g. the slack piece: https://github.com/activepieces/activepieces/tree/main/packages/pieces/community/slack).
// For a multi-tenant host that is a credential-exfiltration primitive, so it is
// refused unless an operator explicitly opts in.

export const DEFAULT_DENY = Object.freeze(['*__custom_api_call']);

function globToRegExp(glob) {
  const escaped = String(glob)
    .trim()
    .replace(/[.+?^${}()|[\]\\]/g, '\\$&')
    .replace(/\*/g, '.*');
  return new RegExp(`^${escaped}$`, 'i');
}

/** Splits "a, b  c" into ["a","b","c"]. */
export function parsePatternList(text) {
  if (typeof text !== 'string') return [];
  return text
    .split(/[\s,]+/)
    .map((s) => s.trim())
    .filter((s) => s.length > 0);
}

export function createPolicy({ deny = [], allow = [], useDefaults = true } = {}) {
  const denyPatterns = [...(useDefaults ? DEFAULT_DENY : []), ...deny];
  const denyRes = denyPatterns.map((p) => ({ pattern: p, re: globToRegExp(p) }));
  const allowRes = allow.map((p) => ({ pattern: p, re: globToRegExp(p) }));

  return {
    denyPatterns,
    allowPatterns: [...allow],
    /** { allowed: true } or { allowed: false, reason } for an action. */
    check(pieceKey, actionName) {
      const id = `${pieceKey}__${actionName}`;
      const hit = denyRes.find((d) => d.re.test(id));
      if (hit) return { allowed: false, reason: `matches deny pattern '${hit.pattern}'` };
      if (allowRes.length > 0 && !allowRes.some((a) => a.re.test(id))) {
        return { allowed: false, reason: 'not on the configured allow list' };
      }
      return { allowed: true };
    },
  };
}

function offValue(v) {
  return typeof v === 'string' && ['off', 'false', '0', 'no'].includes(v.trim().toLowerCase());
}

/** Reads PIECE_HOST_DENY, PIECE_HOST_ALLOW and PIECE_HOST_DENY_DEFAULTS. */
export function policyFromEnv(env = process.env) {
  return createPolicy({
    deny: parsePatternList(env.PIECE_HOST_DENY),
    allow: parsePatternList(env.PIECE_HOST_ALLOW),
    useDefaults: !offValue(env.PIECE_HOST_DENY_DEFAULTS),
  });
}
