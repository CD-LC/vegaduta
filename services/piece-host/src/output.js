// Result serialisation, secret redaction and the result size cap.
//
// Order matters and is enforced by callers: stringify -> redact -> truncate.
// Redacting before truncating guarantees a secret straddling the cut point can
// never leak as a recognisable prefix.

const REDACTED = '[REDACTED]';

/** Returns a function that replaces every occurrence of any secret with [REDACTED]. */
export function createRedactor(secrets = []) {
  const unique = [...new Set(secrets.filter((s) => typeof s === 'string' && s.length > 0))].sort((a, b) => b.length - a.length);
  if (unique.length === 0) return (text) => text;
  return (text) => {
    let out = String(text);
    for (const secret of unique) out = out.split(secret).join(REDACTED);
    return out;
  };
}

/**
 * JSON.stringify that survives circular references, BigInt, Buffers and
 * Errors (which stringify to "{}" by default). Strings are returned as-is.
 */
export function stringifyResult(value) {
  if (value === undefined || value === null) return 'OK (the action returned no data)';
  if (typeof value === 'string') return value;
  const ancestors = [];
  const text = JSON.stringify(
    value,
    function replacer(key, val) {
      if (typeof val === 'bigint') return val.toString();
      if (val instanceof Error) return { name: val.name, message: val.message };
      // Buffer#toJSON runs BEFORE the replacer, so look at the original value.
      const original = this === undefined || this === null ? val : this[key];
      if (Buffer.isBuffer(original) || original instanceof Uint8Array) {
        return `<binary ${original.length} bytes omitted>`;
      }
      if (val !== null && typeof val === 'object') {
        // Standard "ancestors" cycle detection (see MDN JSON.stringify): only a
        // value that is its own ancestor is circular; a repeated sibling is fine.
        while (ancestors.length > 0 && ancestors[ancestors.length - 1] !== this) ancestors.pop();
        if (ancestors.includes(val)) return '[Circular]';
        ancestors.push(val);
      }
      return val;
    },
    2,
  );
  return text === undefined ? String(value) : text;
}

/** Truncates `text` to at most `maxBytes` UTF-8 bytes, appending a marker. */
export function truncateText(text, maxBytes) {
  const total = Buffer.byteLength(text, 'utf8');
  if (!Number.isFinite(maxBytes) || maxBytes <= 0 || total <= maxBytes) {
    return { text, truncated: false, bytes: total };
  }
  let cut = Buffer.from(text, 'utf8').subarray(0, maxBytes).toString('utf8');
  // A cut inside a multi-byte character decodes to U+FFFD at the end.
  while (cut.endsWith('�')) cut = cut.slice(0, -1);
  return {
    text: `${cut}\n[truncated: showing ${Buffer.byteLength(cut, 'utf8')} of ${total} bytes; narrow the request (filters, limit, fields) to see the rest]`,
    truncated: true,
    bytes: total,
  };
}
