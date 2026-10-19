import { apiFetch, apiFetchOnce, jsonBody } from "./core";
import type { FeatureResult } from "./workflows";

// Workflow expression endpoints (spec: docs/plans/WORKFLOW-EXPRESSIONS-AND-PINNING-SPEC.md).
// The editor ships ahead of (or against an older) core, so like the other canvas
// feature wrappers none of these throw and none toast: `unsupported` = the route
// is missing and the UI hides the feature for the session; a plain failure just
// means "unavailable right now" and the field keeps working as plain text.
// Reads go through apiFetchOnce/skipGetCache: evaluate/lint are POSTs that only
// read, and apiFetch's write path would flush the whole GET cache per keystroke.

const MISSING_ROUTE = new Set([404, 405, 501]);

/** True when an error response carries no usable message (older core: an unmapped route can answer a bare 400). */
async function hasNoErrorBody(res: Response): Promise<boolean> {
  try {
    const text = (await res.text()).trim();
    if (!text) return true;
    const body = JSON.parse(text) as unknown;
    if (!body || typeof body !== "object") return true;
    const o = body as Record<string, unknown>;
    const msg = typeof o.message === "string" ? o.message : typeof o.error === "string" ? o.error : "";
    return msg.trim() === "";
  } catch {
    // Non-JSON body (proxy page): not one of this API's error bodies either.
    return true;
  }
}

/** The message of a JSON error body, or "" when there is none. */
async function errorMessage(res: Response): Promise<string> {
  try {
    const body = JSON.parse((await res.text()).trim()) as unknown;
    if (!body || typeof body !== "object") return "";
    const o = body as Record<string, unknown>;
    const msg = typeof o.message === "string" ? o.message : typeof o.error === "string" ? o.error : "";
    return msg.trim();
  } catch {
    return "";
  }
}

// What a framework-level "no such route" 404 says. Any other 404 is the feature answering ("Node 'n7' is not
// in this workflow's saved graph - save the canvas first.") and must not switch the feature off for the session.
const ROUTE_MISSING_TEXT = /^(not found|no endpoint matches.*|no (static )?resource.*|no handler.*)$/i;

async function featureJson<T>(res: Response): Promise<FeatureResult<T>> {
  if (!res.ok) {
    if (res.status === 404) {
      const msg = await errorMessage(res);
      return { ok: false, unsupported: msg === "" || ROUTE_MISSING_TEXT.test(msg) };
    }
    if (MISSING_ROUTE.has(res.status)) return { ok: false, unsupported: true };
    // A real 400 from core always says why ({"error": "..."}); a bare one is an older core's unmapped route.
    if (res.status === 400) return { ok: false, unsupported: await hasNoErrorBody(res) };
    return { ok: false, unsupported: false };
  }
  try {
    return { ok: true, data: (await res.json()) as T };
  } catch {
    // A 200 that is not JSON is a proxy/SPA fallback page, not this API.
    return { ok: false, unsupported: true };
  }
}

export type ExpressionValueType = "string" | "number" | "boolean" | "object" | "array" | "null";
export type ExpressionDataSource = "pinned" | "lastRun" | "supplied";

export type EvaluateRequest = {
  /** Text with {{ }} segments, exactly as stored in a node config field. */
  template: string;
  nodeId?: string;
  dataSource: ExpressionDataSource;
  supplied?: { input?: string; nodes?: Record<string, string> };
};

export type ExpressionError = { message: string; start: number; end: number };

export type EvaluateResponse = {
  ok: boolean;
  value: unknown;
  type: ExpressionValueType;
  rendered: string;
  error: ExpressionError | null;
  /** Node ids whose data was read. */
  sources: string[];
  warnings: string[];
};

export type LintItem = { nodeId: string; field: string; template: string };
export type ExpressionDiagnosticSeverity = "error" | "warning";
export type ExpressionDiagnostic = {
  nodeId: string;
  field: string;
  severity: ExpressionDiagnosticSeverity;
  /** One of LINT_CODES (core may add more; unknown codes are kept and shown with the server message). */
  code: string;
  message: string;
  start: number;
  end: number;
};
export type LintResponse = { diagnostics: ExpressionDiagnostic[] };

/** Codes core emits for expression lint (WorkflowExpressionService + expression.ExpressionEngine). */
export const LINT_CODES = [
  "PARSE_ERROR",
  "UNKNOWN_FUNCTION",
  "WRONG_ARGUMENT_COUNT",
  "UNKNOWN_ROOT",
  "UNKNOWN_NODE",
  "NOT_UPSTREAM",
  "AMBIGUOUS_LABEL",
  "LEGACY_UNKNOWN_NAME",
  "STALE_PIN",
] as const;

/**
 * Defensive read of a lint response: severity is lower-cased (contract: "error" | "warning"; anything else
 * is shown as a warning), the engine's own AMBIGUOUS_NODE is folded into AMBIGUOUS_LABEL, rows without a
 * node/field are dropped, and offsets are clamped to non-negative integers.
 */
export function normalizeDiagnostics(raw: unknown): ExpressionDiagnostic[] {
  if (!Array.isArray(raw)) return [];
  const out: ExpressionDiagnostic[] = [];
  for (const r of raw) {
    if (!r || typeof r !== "object") continue;
    const o = r as Record<string, unknown>;
    if (typeof o.nodeId !== "string" || typeof o.field !== "string") continue;
    const code = typeof o.code === "string" && o.code ? o.code.toUpperCase() : "PARSE_ERROR";
    const num = (v: unknown) => (typeof v === "number" && Number.isFinite(v) ? Math.max(0, Math.trunc(v)) : 0);
    out.push({
      nodeId: o.nodeId,
      field: o.field,
      severity: typeof o.severity === "string" && o.severity.toLowerCase() === "error" ? "error" : "warning",
      code: code === "AMBIGUOUS_NODE" ? "AMBIGUOUS_LABEL" : code,
      message: typeof o.message === "string" ? o.message : "",
      start: num(o.start),
      end: num(o.end),
    });
  }
  return out;
}

export type SchemaPath = {
  /** e.g. items[0].price */
  path: string;
  type: ExpressionValueType;
  sample: string | null;
};
export type UpstreamSchemaNode = {
  nodeId: string;
  label: string;
  source: "pinned" | "lastRun" | "none";
  paths: SchemaPath[];
};
export type UpstreamSchemaResponse = { nodes: UpstreamSchemaNode[] };

export type ExpressionFunctionCategory = "string" | "number" | "array" | "object" | "date" | "type" | "logic";
export type ExpressionFunctionDoc = {
  name: string;
  category: ExpressionFunctionCategory;
  signature: string;
  doc: string;
  example: string;
};

const enc = encodeURIComponent;

export const workflowExpressionsApi = {
  // POST /workflows/{id}/expressions/evaluate
  evaluateExpression: async (workflowId: string, body: EvaluateRequest): Promise<FeatureResult<EvaluateResponse>> => {
    try {
      const res = await apiFetchOnce(`/api/workflows/${enc(workflowId)}/expressions/evaluate`, {
        method: "POST",
        ...jsonBody(body),
      });
      return featureJson<EvaluateResponse>(res);
    } catch {
      return { ok: false, unsupported: false };
    }
  },
  // POST /workflows/{id}/expressions/lint
  lintExpressions: async (workflowId: string, items: LintItem[]): Promise<FeatureResult<LintResponse>> => {
    try {
      const res = await apiFetchOnce(`/api/workflows/${enc(workflowId)}/expressions/lint`, {
        method: "POST",
        ...jsonBody({ items }),
      });
      const r = await featureJson<LintResponse>(res);
      if (!r.ok) return r;
      return { ok: true, data: { diagnostics: normalizeDiagnostics((r.data as { diagnostics?: unknown } | null)?.diagnostics) } };
    } catch {
      return { ok: false, unsupported: false };
    }
  },
  // GET /workflows/{id}/nodes/{nodeId}/upstream-schema
  getUpstreamSchema: async (workflowId: string, nodeId: string): Promise<FeatureResult<UpstreamSchemaResponse>> => {
    try {
      const res = await apiFetch(`/api/workflows/${enc(workflowId)}/nodes/${enc(nodeId)}/upstream-schema`, {
        skipGetCache: true,
      });
      return featureJson<UpstreamSchemaResponse>(res);
    } catch {
      return { ok: false, unsupported: false };
    }
  },
  // GET /api/workflow-expression-functions - the registry that also feeds the docs page. Deliberately not
  // under /api/workflows/: there "expression-functions" would be read as a workflow id.
  getExpressionFunctions: async (): Promise<FeatureResult<ExpressionFunctionDoc[]>> => {
    try {
      const res = await apiFetch(`/api/workflow-expression-functions`);
      return featureJson<ExpressionFunctionDoc[]>(res);
    } catch {
      return { ok: false, unsupported: false };
    }
  },
};
