import { apiFetch, jsonBody } from "./core";

// Data pinning (spec docs/plans/WORKFLOW-EXPRESSIONS-AND-PINNING-SPEC.md, section 5).
// A pin freezes one node's output on the DRAFT graph; canvas "Run" (mode
// "draft") and step tests honour it, production entry points never do (the
// engine enforces that by run mode, not by anything the client sends).
//
// These wrappers never throw and never toast: the backend ships separately,
// so a missing route (`unsupported`) hides the feature and a network error is
// a plain failure the caller can retry.

export type PinSource = "test" | "run" | "manual" | "trigger_sample";

/** GET /api/workflows/{id}/pins row. `data` is null when the caller may not read pinned data (RUNNER). */
export type WorkflowPin = {
  nodeId: string;
  source: PinSource;
  sourceRunId: string | null;
  pinnedBy: string;
  pinnedAt: string;
  sizeChars: number;
  configHash: string | null;
  data: string | null;
};

export type PutPinBody = {
  data: string;
  source: PinSource;
  sourceRunId?: string;
  configHash?: string;
};

/** Run modes the run endpoint accepts. Absent = production. Only the canvas Run button sends "draft". */
export type RunMode = "production" | "draft" | "test";

// Mirrors the server caps (contract: 400 with a message beyond any of these).
export const PIN_MAX_CHARS = 256_000;
export const PIN_MAX_COUNT = 30;
export const PIN_MAX_TOTAL_CHARS = 2_000_000;

export type PinFailureKind =
  | "too-large"
  | "too-many"
  | "total-size"
  | "invalid"
  | "forbidden"
  | "not-found"
  | "unsupported"
  | "network"
  | "error";

export type PinResult<T> =
  | { ok: true; data: T; kind?: undefined; message?: undefined; status?: undefined }
  | { ok: false; kind: PinFailureKind; message: string; status: number; data?: undefined };

const MISSING_ROUTE = new Set([405, 501]);

/** Classify a failed pin call. The 400 message decides which of the three documented limits was hit. */
export function classifyPinFailure(status: number, serverMessage: string): PinFailureKind {
  const m = serverMessage.toLowerCase();
  if (status === 400 || status === 413 || status === 422) {
    if (/2[, ]?000[, ]?000|total|combined|all pins/.test(m)) return "total-size";
    if (/\b30\b|too many|max(imum)? (of )?\d+ pins|pin count/.test(m)) return "too-many";
    if (/256|too large|too long|exceed|size|chars/.test(m)) return "too-large";
    return "invalid";
  }
  if (status === 401 || status === 403) return "forbidden";
  if (status === 404) return "not-found";
  if (MISSING_ROUTE.has(status)) return "unsupported";
  return "error";
}

async function failure(res: Response): Promise<PinResult<never>> {
  let message = "";
  try {
    const body = await res.json();
    if (body && typeof body === "object") {
      const o = body as Record<string, unknown>;
      message = typeof o.message === "string" ? o.message : typeof o.error === "string" ? o.error : "";
    }
  } catch {
    // Non-JSON error body (proxy page); the classification falls back to the status.
  }
  // Older core: an unmapped route answers a bare 400, or a 404 with only the generic reason. Core's own 404
  // (node not in the saved graph) and 400s always carry their reason, so those stay not-found / a limit kind.
  const generic = /^(|not found|bad request|no (static )?resource\b.*)$/i.test(message.trim());
  if ((res.status === 404 || res.status === 400) && generic) {
    return { ok: false, kind: "unsupported", message: "", status: res.status };
  }
  return { ok: false, kind: classifyPinFailure(res.status, message), message, status: res.status };
}

function networkFailure(): PinResult<never> {
  return { ok: false, kind: "network", message: "", status: 0 };
}

const base = (workflowId: string) => `/api/workflows/${encodeURIComponent(workflowId)}`;

function asPin(raw: unknown): WorkflowPin | null {
  if (!raw || typeof raw !== "object") return null;
  const o = raw as Record<string, unknown>;
  if (typeof o.nodeId !== "string" || !o.nodeId) return null;
  const source: PinSource =
    o.source === "test" || o.source === "run" || o.source === "trigger_sample" ? o.source : "manual";
  const data = typeof o.data === "string" ? o.data : null;
  return {
    nodeId: o.nodeId,
    source,
    sourceRunId: typeof o.sourceRunId === "string" ? o.sourceRunId : null,
    pinnedBy: typeof o.pinnedBy === "string" ? o.pinnedBy : "",
    pinnedAt: typeof o.pinnedAt === "string" ? o.pinnedAt : "",
    sizeChars: typeof o.sizeChars === "number" && Number.isFinite(o.sizeChars) ? o.sizeChars : (data?.length ?? 0),
    configHash: typeof o.configHash === "string" ? o.configHash : null,
    data,
  };
}

/** GET /pins. skipGetCache: pins change under the author's hands and the canvas re-reads after every edit. */
export async function listPins(workflowId: string): Promise<PinResult<WorkflowPin[]>> {
  try {
    const res = await apiFetch(`${base(workflowId)}/pins`, { skipGetCache: true });
    // The canvas already loaded this workflow, so a 404 on the list route means core predates pins
    // (Spring's unmapped-route 404), not a missing workflow. PUT/DELETE keep 404 = not-found.
    if (res.status === 404) return { ok: false, kind: "unsupported", message: "", status: 404 };
    if (!res.ok) return failure(res);
    let json: unknown;
    try {
      json = await res.json();
    } catch {
      // A 200 that is not JSON is a proxy/SPA fallback page, not this API.
      return { ok: false, kind: "unsupported", message: "", status: res.status };
    }
    if (!Array.isArray(json)) return { ok: false, kind: "unsupported", message: "", status: res.status };
    return { ok: true, data: json.map(asPin).filter((p): p is WorkflowPin => p !== null) };
  } catch {
    return networkFailure();
  }
}

/** PUT /nodes/{nodeId}/pin - create or replace this node's pin. */
export async function putPin(workflowId: string, nodeId: string, body: PutPinBody): Promise<PinResult<WorkflowPin>> {
  try {
    const res = await apiFetch(`${base(workflowId)}/nodes/${encodeURIComponent(nodeId)}/pin`, {
      method: "PUT",
      ...jsonBody(body),
    });
    if (!res.ok) return failure(res);
    let pin: WorkflowPin | null = null;
    try {
      pin = asPin(await res.json());
    } catch {
      pin = null;
    }
    return { ok: true, data: pin ?? optimisticPin(nodeId, body) };
  } catch {
    return networkFailure();
  }
}

/** DELETE /nodes/{nodeId}/pin - 204. */
export async function deletePin(workflowId: string, nodeId: string): Promise<PinResult<null>> {
  try {
    const res = await apiFetch(`${base(workflowId)}/nodes/${encodeURIComponent(nodeId)}/pin`, { method: "DELETE" });
    // A missing pin is already the state the caller wanted.
    if (res.ok || res.status === 404) return { ok: true, data: null };
    return failure(res);
  } catch {
    return networkFailure();
  }
}

/** The pin as the UI shows it before the server has answered (also the fallback for an empty 2xx body). */
export function optimisticPin(nodeId: string, body: PutPinBody, pinnedBy = ""): WorkflowPin {
  return {
    nodeId,
    source: body.source,
    sourceRunId: body.sourceRunId ?? null,
    pinnedBy,
    pinnedAt: new Date().toISOString(),
    sizeChars: body.data.length,
    configHash: body.configHash ?? null,
    data: body.data,
  };
}
