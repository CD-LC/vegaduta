/**
 * Generative UI spec - the JSON contract an agent emits inside a `json_ui`
 * fenced block (or an AG-UI `CUSTOM` event named `ui_spec`) and
 * `GenerativeUiRenderer` renders.
 *
 * Design rules (A2UI/Open-JSON-UI posture):
 *  - The spec is DATA, never markup: a typed node tree with a closed set of
 *    component types and a whitelist of props each. No html/script/style
 *    nodes, no event-handler props, no arbitrary attribute pass-through -
 *    anything outside the whitelist is dropped at validation, so a hostile or
 *    hallucinated spec cannot inject DOM or JS.
 *  - Interactivity is ONE verb: `action` = {type:"sendMessage", message}.
 *    Buttons and forms can only ask the host to send a message back to the
 *    agent - form submissions carry the collected field values. That is the
 *    whole generative-UI loop and it cannot be turned into arbitrary code.
 *  - Hard caps: MAX_DEPTH / MAX_NODES keep a runaway spec from freezing the
 *    tab; URLs are limited to http(s).
 */

export const UI_SPEC_VERSION = "v1";
export const MAX_DEPTH = 20;
export const MAX_NODES = 200;
export const MAX_TEXT_LEN = 8000;
export const MAX_ROWS = 200;

export type UiAction = { type: "sendMessage"; message: string };

export type UiNode = {
  type: string;
  props?: Record<string, unknown>;
  action?: UiAction;
  children?: UiNode[];
};

export type UiSpec = {
  version: string;
  root: UiNode;
};

/** The closed component set the renderer supports, keyed for O(1) checks. */
export const UI_NODE_TYPES = new Set([
  // layout
  "stack", "row", "grid", "card", "divider", "spacer",
  // content
  "heading", "text", "list", "table", "keyValue", "badge", "image", "link", "code",
  // interactive
  "button", "form", "input", "textarea", "select", "checkbox",
  // live API result (see ApiCallPanel.tsx)
  "apiCall",
]);

const CANONICAL_TYPES = new Map([...UI_NODE_TYPES].map((t) => [t.toLowerCase(), t]));

/** Props each node type is allowed to carry - everything else is stripped. */
const PROP_WHITELIST: Record<string, Set<string>> = {
  stack: new Set(["gap", "align"]),
  row: new Set(["gap", "align", "wrap"]),
  grid: new Set(["columns", "gap"]),
  card: new Set(["title", "subtitle"]),
  divider: new Set([]),
  spacer: new Set(["size"]),
  heading: new Set(["text", "level"]),
  text: new Set(["text", "muted", "markdown"]),
  list: new Set(["items", "ordered"]),
  table: new Set(["columns", "rows"]),
  keyValue: new Set(["entries"]),
  badge: new Set(["text", "tone"]),
  image: new Set(["src", "alt"]),
  link: new Set(["href", "text"]),
  code: new Set(["code", "language"]),
  button: new Set(["label", "tone"]),
  form: new Set(["submitLabel", "title"]),
  input: new Set(["name", "label", "placeholder", "inputType", "defaultValue", "required"]),
  textarea: new Set(["name", "label", "placeholder", "defaultValue", "rows", "required"]),
  select: new Set(["name", "label", "options", "defaultValue", "required"]),
  checkbox: new Set(["name", "label", "defaultChecked"]),
  apiCall: new Set(["method", "path", "query", "body", "title"]),
};

/**
 * An apiCall node asks the console to call THIS install's own API with the signed-in user's session.
 * It is data, so it is pinned down here: a same-origin /api/ path only (no scheme, host, dot segments
 * or backslashes), a fixed verb list, and plain JSON objects for query and body.
 */
const API_METHODS = new Set(["GET", "POST", "PUT", "PATCH", "DELETE"]);

export function isSafeApiPath(path: unknown): path is string {
  return (
    typeof path === "string" &&
    /^\/api\/[A-Za-z0-9._~!$&'()*+,;=:@%/{}-]*$/.test(path) &&
    !path.includes("//") &&
    !path.split("/").some((seg) => seg === "." || seg === "..") &&
    !/%2e|%2f|%5c/i.test(path)
  );
}

function normalizeApiCall(props: Record<string, unknown>, errors: string[]): boolean {
  const method = typeof props.method === "string" ? props.method.toUpperCase() : "GET";
  if (!API_METHODS.has(method) || !isSafeApiPath(props.path)) {
    errors.push("apiCall needs a method and a /api/... path");
    return false;
  }
  props.method = method;
  const isObject = (v: unknown) => v !== null && typeof v === "object" && !Array.isArray(v);
  if (props.query !== undefined && !isObject(props.query)) delete props.query;
  if (props.body !== undefined && props.body !== null && typeof props.body !== "object") delete props.body;
  return true;
}

export function isHttpUrl(value: unknown): boolean {
  if (typeof value !== "string") return false;
  try {
    const scheme = new URL(value).protocol;
    return scheme === "https:" || scheme === "http:";
  } catch {
    return false;
  }
}

function sanitizeAction(raw: unknown): UiAction | undefined {
  if (!raw || typeof raw !== "object") return undefined;
  const a = raw as Record<string, unknown>;
  if (a.type === "sendMessage" && typeof a.message === "string" && a.message.trim()) {
    return { type: "sendMessage", message: a.message.slice(0, MAX_TEXT_LEN) };
  }
  return undefined;
}

function sanitizeNode(raw: unknown, depth: number, budget: { count: number }, errors: string[]): UiNode | null {
  if (depth > MAX_DEPTH) {
    errors.push(`depth exceeds ${MAX_DEPTH}`);
    return null;
  }
  if (!raw || typeof raw !== "object" || typeof (raw as UiNode).type !== "string") {
    errors.push("node missing string 'type'");
    return null;
  }
  budget.count += 1;
  if (budget.count > MAX_NODES) {
    errors.push(`node count exceeds ${MAX_NODES}`);
    return null;
  }
  const src = raw as Record<string, unknown>;
  // Canonical spelling by case-insensitive lookup: lower-casing alone would turn keyValue / apiCall
  // (camelCase in the contract) into names that are not in the set.
  const requested = (src.type as string).toLowerCase();
  const type = CANONICAL_TYPES.get(requested) ?? requested;
  if (!UI_NODE_TYPES.has(type)) {
    // Unknown nodes are kept as a typed placeholder rather than dropped - the
    // renderer shows an explicit "unsupported element" fallback, which is more
    // honest than silently losing content a model emitted.
    return { type: "__unsupported__", props: { type } };
  }
  const allowed = PROP_WHITELIST[type];
  const props: Record<string, unknown> = {};
  const rawProps = (src.props && typeof src.props === "object" ? src.props : {}) as Record<string, unknown>;
  for (const key of Object.keys(rawProps)) {
    if (allowed.has(key)) {
      const v = rawProps[key];
      props[key] = typeof v === "string" && v.length > MAX_TEXT_LEN ? v.slice(0, MAX_TEXT_LEN) : v;
    }
  }
  // URL-bearing props must be http(s) - blocks javascript:/data: injection.
  for (const urlProp of ["src", "href"]) {
    if (urlProp in props && !isHttpUrl(props[urlProp])) {
      delete props[urlProp];
      errors.push(`${type}.${urlProp} must be an http(s) URL`);
    }
  }
  if (type === "apiCall" && !normalizeApiCall(props, errors)) {
    return { type: "__unsupported__", props: { type: "apiCall (invalid request)" } };
  }
  const children: UiNode[] = [];
  if (Array.isArray(src.children)) {
    for (const child of src.children) {
      const c = sanitizeNode(child, depth + 1, budget, errors);
      if (c) children.push(c);
    }
  }
  const node: UiNode = { type };
  if (Object.keys(props).length) node.props = props;
  const action = sanitizeAction(src.action);
  if (action) node.action = action;
  if (children.length) node.children = children;
  return node;
}

/**
 * Validates + sanitizes a raw spec. Always returns a result - a spec with
 * errors is still rendered (with the offending bits stripped/placeholdered)
 * unless `spec` is null, which means the input was unusable at all.
 */
export function validateUiSpec(raw: unknown): { spec: UiSpec | null; errors: string[] } {
  const errors: string[] = [];
  if (!raw || typeof raw !== "object") {
    return { spec: null, errors: ["spec is not an object"] };
  }
  const src = raw as Record<string, unknown>;
  const root = sanitizeNode(src.root, 0, { count: 0 }, errors);
  if (!root) {
    return { spec: null, errors: errors.length ? errors : ["spec has no usable root node"] };
  }
  return { spec: { version: typeof src.version === "string" ? src.version : UI_SPEC_VERSION, root }, errors };
}
