// Design-token values are spliced into src/theme.css, so they must never carry
// anything that could close the declaration or the rule (; { } / quotes / $).

const HEX = /^#[0-9a-fA-F]{6}$/;
const LENGTH = /^(\d{1,3}(\.\d{1,3})?|\.\d{1,3})(px|rem|em|%)?$/;

/** Strip a typed rounding value down to digits, ".", and unit letters/%. */
export function sanitizeTypedToken(key: string, raw: string): string {
  if (key === "--radius") return String(raw ?? "").replace(/[^0-9a-z.%]/gi, "").slice(0, 10);
  return String(raw ?? "").slice(0, 9);
}

/** The value safe to write, or null when it is incomplete/invalid (skip it). */
export function validTokenValue(key: string, value: string): string | null {
  const v = String(value ?? "").trim();
  if (key === "--radius") return LENGTH.test(v) ? v : null;
  return HEX.test(v) ? v : null;
}
