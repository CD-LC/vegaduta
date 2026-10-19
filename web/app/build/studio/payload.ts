// The no-code request/response mapping a builder configures on each
// connection - the TypeScript mirror of core's StudioAppPayload (keep the
// semantics identical; tests pin both sides).
//
// Request template placeholders: {{input}} (the caller's text), {{user.name}}
// / {{user.email}} / {{user.id}} (the signed-in end user - empty when
// anonymous), {{data.<key>}} (a field of the call's data object). Unknown
// placeholders are left untouched, never blanked.
//
// Response path: dotted lookup (reply, resultText, data.items.0.name) into a
// JSON body; missing path falls back to the raw text - a misconfigured path
// degrades the answer, it never eats it.

const PLACEHOLDER = /\{\{\s*([a-zA-Z0-9_.-]+)\s*\}\}/g;

export interface AppUserView {
  id?: string;
  email?: string;
  name?: string;
}

export function renderConnectionTemplate(
  template: string | null | undefined,
  input: string,
  data?: Record<string, unknown>,
  user?: AppUserView | null
): string {
  if (!template || !template.trim()) return input ?? "";
  return template.replace(PLACEHOLDER, (whole, key: string) => {
    switch (key) {
      case "input":
        return input ?? "";
      case "user.name":
        return user?.name ?? "";
      case "user.email":
        return user?.email ?? "";
      case "user.id":
        return user?.id ?? "";
      default:
        if (key.startsWith("data.")) {
          const v = data?.[key.slice(5)];
          return v == null ? "" : String(v);
        }
        return whole;
    }
  });
}

/** Dotted path with numeric segments for arrays: a.b.0.c */
export function extractResponsePath(path: string | null | undefined, defaultField: string, body: string): string {
  if (body == null) return "";
  const effective = (path && path.trim()) || defaultField;
  if (!effective) return body;
  let parsed: unknown;
  try {
    parsed = JSON.parse(body);
  } catch {
    return body;
  }
  let node: unknown = parsed;
  for (const seg of effective.split(".")) {
    if (node == null) return body;
    if (Array.isArray(node) && /^\d+$/.test(seg)) {
      node = node[Number(seg)];
    } else if (typeof node === "object") {
      node = (node as Record<string, unknown>)[seg];
    } else {
      return body;
    }
  }
  if (node == null) return body;
  return typeof node === "string" ? node : JSON.stringify(node);
}

/** Alias for vd.call - lowercase, hyphen/underscore word chars, starts with a letter. */
export function aliasOf(name: string, taken: Set<string> = new Set()): string {
  let base = name
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .replace(/^[^a-z]+/, "")
    .slice(0, 40);
  if (!base) base = "service";
  let alias = base;
  let n = 2;
  while (taken.has(alias)) {
    alias = `${base}-${n}`.slice(0, 40);
    n += 1;
  }
  return alias;
}
