// Pure helpers for the per-agent tool ARGUMENT policies card. Kept free of React and of the api client so the
// client-side pre-checks (which mirror the server's size caps) and the shaping of the stored document for
// display are testable on their own. The server is the authority: it validates every key and compiles every
// pattern on PUT and answers 400 with a precise reason, which the card shows verbatim.
//
// Wire contract (core ToolArgPolicyController / ToolArgPolicyDtos):
//   GET|PUT /api/agents/{agentId}/tool-policies
//   response: { enabled, policies: { policies: ToolPolicy[] }, limits, updatedBy, updatedAt }
//   PUT body: { policies: ToolPolicy[] }   (an empty list clears)
//   ToolPolicy: { tool, rules: ToolPolicyRule[], denyUnlistedArgs? }
//   ToolPolicyRule: { path, required?, forbidden?, type?, enum?, minLength?, maxLength?, min?, max?, pattern?,
//                     urlHostIn?, pathUnder?, maxItems? }
//
// tsconfig is not strict: results are flat objects with a nullable `error`, never boolean-discriminated unions.

export interface ToolPolicyRule {
  path: string;
  required?: boolean;
  forbidden?: boolean;
  type?: string;
  enum?: unknown[];
  minLength?: number;
  maxLength?: number;
  min?: number;
  max?: number;
  pattern?: string;
  urlHostIn?: string[];
  pathUnder?: string[];
  maxItems?: number;
}

export interface ToolPolicy {
  tool: string;
  rules: ToolPolicyRule[];
  denyUnlistedArgs?: boolean;
}

export interface ToolPolicyDocument {
  policies: ToolPolicy[];
}

export interface ToolPolicyLimits {
  maxPolicies: number;
  maxRulesPerPolicy: number;
  maxDocumentChars: number;
  maxPatternLength: number;
  maxEnumValues: number;
}

export interface ToolPoliciesView {
  /** False = the feature is switched off on this deployment; the card hides. */
  enabled: boolean;
  document: ToolPolicyDocument;
  limits: ToolPolicyLimits;
  updatedBy: string | null;
  updatedAt: string | null;
}

export const DEFAULT_LIMITS: ToolPolicyLimits = {
  maxPolicies: 50,
  maxRulesPerPolicy: 30,
  maxDocumentChars: 65536,
  maxPatternLength: 200,
  maxEnumValues: 100,
};

function num(value: unknown): number | null {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

function readRule(raw: unknown): ToolPolicyRule | null {
  if (!raw || typeof raw !== "object") return null;
  const r = raw as Record<string, unknown>;
  if (typeof r.path !== "string") return null;
  // Everything the server stored is passed through untouched; only the shape needed for display is checked.
  return r as unknown as ToolPolicyRule;
}

function readDocument(raw: unknown): ToolPolicyDocument {
  const policies: ToolPolicy[] = [];
  const list = raw && typeof raw === "object" ? (raw as Record<string, unknown>).policies : null;
  if (Array.isArray(list)) {
    for (const p of list) {
      if (!p || typeof p !== "object") continue;
      const o = p as Record<string, unknown>;
      if (typeof o.tool !== "string") continue;
      const rules: ToolPolicyRule[] = [];
      if (Array.isArray(o.rules)) {
        for (const r of o.rules) {
          const rule = readRule(r);
          if (rule) rules.push(rule);
        }
      }
      policies.push(o.denyUnlistedArgs === true ? { tool: o.tool, rules, denyUnlistedArgs: true } : { tool: o.tool, rules });
    }
  }
  return { policies };
}

/**
 * Defensive read of the wire shape. Null when the body is not a tool-policies response at all (the card then
 * hides) - a missing `enabled` is treated as "off", never as "on".
 */
export function normalizeToolPolicies(raw: unknown): ToolPoliciesView | null {
  if (!raw || typeof raw !== "object") return null;
  const r = raw as Record<string, unknown>;
  if (typeof r.enabled !== "boolean") return null;
  const l = r.limits && typeof r.limits === "object" ? (r.limits as Record<string, unknown>) : {};
  return {
    enabled: r.enabled,
    document: readDocument(r.policies),
    limits: {
      maxPolicies: num(l.maxPolicies) ?? DEFAULT_LIMITS.maxPolicies,
      maxRulesPerPolicy: num(l.maxRulesPerPolicy) ?? DEFAULT_LIMITS.maxRulesPerPolicy,
      maxDocumentChars: num(l.maxDocumentChars) ?? DEFAULT_LIMITS.maxDocumentChars,
      maxPatternLength: num(l.maxPatternLength) ?? DEFAULT_LIMITS.maxPatternLength,
      maxEnumValues: num(l.maxEnumValues) ?? DEFAULT_LIMITS.maxEnumValues,
    },
    updatedBy: typeof r.updatedBy === "string" ? r.updatedBy : null,
    updatedAt: typeof r.updatedAt === "string" ? r.updatedAt : null,
  };
}

/** The editor's text for a stored document; an empty document is empty text. */
export function documentToText(doc: ToolPolicyDocument): string {
  return doc && doc.policies && doc.policies.length > 0 ? JSON.stringify(doc, null, 2) : "";
}

export type PolicyTextError = "empty" | "notJson" | "shape" | "tooLarge" | "tooManyPolicies" | "policyShape" | "tooManyRules";

export interface PolicyTextResult {
  document: ToolPolicyDocument | null;
  /** Null when the text passed the client-side checks. */
  error: PolicyTextError | null;
  /** 1-based policy the error is about (0 when it is about the whole document). */
  policy: number;
  /** The limit that was exceeded, for the message. */
  limit: number;
}

/**
 * Client-side pre-check of the editor text: valid JSON, the right outer shape, and the server's size caps. It
 * does NOT validate rules (the server does, with precise messages) - it only avoids a round trip for the common
 * mistakes. Blank text is an error ("empty"): clearing is the explicit Remove-all action, never an accident.
 */
export function parsePolicyText(text: string, limits?: ToolPolicyLimits | null): PolicyTextResult {
  const lim = limits || DEFAULT_LIMITS;
  const t = (text || "").trim();
  if (t === "") return { document: null, error: "empty", policy: 0, limit: 0 };
  if (t.length > lim.maxDocumentChars) return { document: null, error: "tooLarge", policy: 0, limit: lim.maxDocumentChars };
  let parsed: unknown;
  try {
    parsed = JSON.parse(t);
  } catch {
    return { document: null, error: "notJson", policy: 0, limit: 0 };
  }
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) return { document: null, error: "shape", policy: 0, limit: 0 };
  const list = (parsed as Record<string, unknown>).policies;
  if (!Array.isArray(list)) return { document: null, error: "shape", policy: 0, limit: 0 };
  if (list.length > lim.maxPolicies) return { document: null, error: "tooManyPolicies", policy: 0, limit: lim.maxPolicies };
  for (let i = 0; i < list.length; i++) {
    const p = list[i];
    if (!p || typeof p !== "object" || Array.isArray(p)) return { document: null, error: "policyShape", policy: i + 1, limit: 0 };
    const o = p as Record<string, unknown>;
    if (typeof o.tool !== "string" || o.tool.trim() === "") return { document: null, error: "policyShape", policy: i + 1, limit: 0 };
    if (o.rules !== undefined && !Array.isArray(o.rules)) return { document: null, error: "policyShape", policy: i + 1, limit: 0 };
    if (Array.isArray(o.rules) && o.rules.length > lim.maxRulesPerPolicy) {
      return { document: null, error: "tooManyRules", policy: i + 1, limit: lim.maxRulesPerPolicy };
    }
  }
  // Sent as parsed (the server rejects unknown keys); never rewritten here.
  return { document: parsed as ToolPolicyDocument, error: null, policy: 0, limit: 0 };
}

/** True when the editor text differs from what is stored (whitespace and key order of the JSON are ignored). */
export function isTextDirty(text: string, stored: ToolPolicyDocument): boolean {
  const t = (text || "").trim();
  const base = documentToText(stored);
  if (t === "" || base === "") return t !== base;
  try {
    return JSON.stringify(JSON.parse(t)) !== JSON.stringify(JSON.parse(base));
  } catch {
    return t !== base.trim();
  }
}

/** A starter document the "Insert example" button fills in. */
export const EXAMPLE_DOCUMENT: ToolPolicyDocument = {
  policies: [
    {
      tool: "fetch_web_page",
      rules: [{ path: "url", required: true, urlHostIn: ["example.com", "*.docs.example.com"], maxLength: 2048 }],
    },
    {
      tool: "mcp__files__*",
      rules: [{ path: "path", pathUnder: ["/workspace"] }],
    },
  ],
};

export interface RuleSummary {
  path: string;
  /** Short, code-like descriptions of each check, e.g. "type integer", "max 50", "host in a.com, b.com". */
  checks: string[];
}

export interface PolicySummary {
  tool: string;
  denyUnlistedArgs: boolean;
  rules: RuleSummary[];
}

function clip(text: string, max = 40): string {
  return text.length <= max ? text : text.slice(0, max) + "…";
}

function list(values: unknown[], shown = 4): string {
  const parts = values.slice(0, shown).map((v) => clip(typeof v === "string" ? v : JSON.stringify(v)));
  return parts.join(", ") + (values.length > shown ? ` (+${values.length - shown})` : "");
}

/** What a rule checks, as short tokens for the read-only summary (not translated: they name the schema keys). */
export function describeRule(rule: ToolPolicyRule): string[] {
  const out: string[] = [];
  if (rule.required) out.push("required");
  if (rule.forbidden) out.push("forbidden");
  if (rule.type) out.push(`type ${rule.type}`);
  if (Array.isArray(rule.enum)) out.push(`one of ${list(rule.enum)}`);
  if (num(rule.minLength) != null) out.push(`min length ${rule.minLength}`);
  if (num(rule.maxLength) != null) out.push(`max length ${rule.maxLength}`);
  if (num(rule.min) != null) out.push(`min ${rule.min}`);
  if (num(rule.max) != null) out.push(`max ${rule.max}`);
  if (num(rule.maxItems) != null) out.push(`max ${rule.maxItems} items`);
  if (typeof rule.pattern === "string") out.push(`pattern ${clip(rule.pattern)}`);
  if (Array.isArray(rule.urlHostIn)) out.push(`host in ${list(rule.urlHostIn)}`);
  if (Array.isArray(rule.pathUnder)) out.push(`under ${list(rule.pathUnder)}`);
  return out;
}

export function policySummaries(doc: ToolPolicyDocument): PolicySummary[] {
  return ((doc && doc.policies) || []).map((p) => ({
    tool: p.tool,
    denyUnlistedArgs: p.denyUnlistedArgs === true,
    rules: (p.rules || []).map((r) => ({ path: r.path, checks: describeRule(r) })),
  }));
}

/** Total number of rules across all policies (for the card's one-line summary). */
export function countRules(doc: ToolPolicyDocument): number {
  return ((doc && doc.policies) || []).reduce((n, p) => n + (p.rules ? p.rules.length : 0), 0);
}

// --- "Last changed by" (found in local QA: the card printed the raw Keycloak user id) -----------------------------

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export interface UpdatedByWho {
  /** The signed-in user's id (token `sub`), display name and login. */
  selfId?: string | null;
  selfName?: string | null;
  /** id -> display name, when the viewer may list workspace members. */
  names?: ReadonlyMap<string, string>;
}

/**
 * What to print after "Last changed by": a name when one is known, "you" for the viewer's own edit, the raw value
 * when it is already human-readable (an email or login), and null for an opaque id nobody can resolve - the caller
 * then says "a teammate" instead of showing a UUID.
 */
export function updatedByLabel(
  updatedBy: string | null | undefined,
  who: UpdatedByWho = {},
): { kind: "you" | "name" | "unknown"; name: string | null } | null {
  const raw = (updatedBy ?? "").trim();
  if (!raw) return null;
  if (who.selfId && raw === who.selfId) return { kind: "you", name: who.selfName || null };
  const known = who.names?.get(raw);
  if (known) return { kind: "name", name: known };
  if (UUID_RE.test(raw)) return { kind: "unknown", name: null };
  return { kind: "name", name: raw };
}
