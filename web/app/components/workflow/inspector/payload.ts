export type Payload = { kind: "none" } | { kind: "tree"; value: unknown; text: string } | { kind: "raw"; text: string };

export function normalizePayload(v: unknown): Payload {
  if (v === undefined || v === null) return { kind: "none" };
  if (typeof v === "string") {
    try {
      const parsed: unknown = JSON.parse(v);
      return { kind: "tree", value: parsed, text: JSON.stringify(parsed, null, 2) };
    } catch {
      // Truncated captures are usually cut mid-object; show them verbatim.
      return { kind: "raw", text: v };
    }
  }
  let text: string;
  try {
    text = JSON.stringify(v, null, 2) ?? String(v);
  } catch {
    text = String(v);
  }
  return { kind: "tree", value: v, text };
}

export function childrenOf(v: unknown): Array<[string | number, unknown]> | null {
  if (Array.isArray(v)) return v.map((c: unknown, i) => [i, c]);
  if (v !== null && typeof v === "object") return Object.entries(v as Record<string, unknown>);
  return null;
}

export const IDENT_RE = /^[A-Za-z_][A-Za-z0-9_]*$/;
export function childPath(parent: string, key: string | number): string {
  if (typeof key === "number") return `${parent}[${key}]`;
  return IDENT_RE.test(key) ? `${parent}.${key}` : `${parent}[${JSON.stringify(key)}]`;
}
