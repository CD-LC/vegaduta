import { apiFetch, friendlyStatusFallback, jsonBody, toJsonQuietOn } from "./core";
import type { ImportReport } from "./agentImport";
import { filenameFromContentDisposition } from "../contentDisposition";
import { isKnownMissing, rememberMissing } from "./negativeCache";

// Workflow portability: export a workflow as the VegaDuta workflow DSL (JSON)
// and import one from a DSL file, an n8n export or a Dify DSL as a DRAFT
// (WorkflowExportController / WorkflowImportController in core/workflow; the
// format is documented in docs/api/workflow-dsl-v1.md).
//
// Both import routes take the SAME envelope and are authenticated-only (same
// bar as POST /api/workflows). 400/403/404/413 are expected, user-facing states
// the page renders inline (a file that cannot be read, the plan's workflow cap,
// the feature switched off or an older core, an oversize body), so they route
// through toJsonQuietOn and raise no global toast.
//
// The file is user data: it is only ever sent in the request body, never
// logged, cached or persisted by this client.

export type PortabilitySource = "auto" | "vegaduta" | "n8n" | "dify";
export type DetectedPortabilitySource = Exclude<PortabilitySource, "auto">;

export type PortabilityEnvelope = {
  source: PortabilitySource;
  /** The file's text (JSON or YAML) exactly as the person pasted or dropped it. */
  payload: string;
  overrides?: { name?: string };
  /** Placeholder key -> id of an existing resource (agents, knowledge bases, workflows, groups, devices). */
  mappings?: Record<string, string>;
};

export type PortabilityPlaceholderUse = { nodeId: string; field: string };

export type PortabilityPlaceholder = {
  key: string;
  /** agent | kb | workflow | group | device | destination | credential | secret | unknown */
  kind: string;
  label: string;
  mappable: boolean;
  resolved: boolean;
  mappedTo: string;
  usedBy: PortabilityPlaceholderUse[];
};

export type PortabilityFidelityLevel = "high" | "partial" | "low";

export type PortabilityFidelity = {
  level: PortabilityFidelityLevel;
  sourceNodes: number;
  exact: number;
  needsReview: number;
  placeholders: number;
  skipped: number;
  sourceConnections: number;
  connectionsKept: number;
  connectionsDropped: number;
};

export type PortabilityWorkflow = {
  name: string;
  description: string;
  graph: { nodes: unknown[]; edges: unknown[] };
};

export type WorkflowImportPreview = {
  source: string;
  detectedSource: DetectedPortabilitySource;
  workflow: PortabilityWorkflow;
  placeholders: PortabilityPlaceholder[];
  validationIssues: string[];
  fidelity: PortabilityFidelity;
  report: ImportReport;
};

export type WorkflowImportResult = {
  workflowId: string;
  status: string;
  validationIssues: string[];
  placeholders: PortabilityPlaceholder[];
  fidelity: PortabilityFidelity;
  report: ImportReport;
};

export type WorkflowExportFile = { blob: Blob; filename: string };

// Statuses the page handles inline - see the header comment.
const QUIET = [400, 403, 404, 413];
// Generous client-side ceiling: a hung request must not spin forever.
const TIMEOUT_MS = 90_000;

/**
 * The file name in a Content-Disposition header, or {@code fallback}. Delegates to the shared parser, which
 * prefers filename*=UTF-8''... over the plain filename and decodes a MIME encoded-word: this function used to
 * take whichever parameter came first, so an export was saved as "=?UTF-8?Q?name.vegaduta-workflow.json?=".
 */
export function filenameFromDisposition(disposition: string | null | undefined, fallback: string): string {
  return filenameFromContentDisposition(disposition, fallback);
}

const PORTABILITY_OFF_KEY = "workflow-portability";

export const workflowPortabilityApi = {
  previewWorkflowImport: (envelope: PortabilityEnvelope): Promise<WorkflowImportPreview> =>
    apiFetch("/api/workflows/import/preview", { method: "POST", timeoutMs: TIMEOUT_MS, ...jsonBody(envelope) }).then(
      toJsonQuietOn(QUIET)
    ),
  importWorkflowFile: (envelope: PortabilityEnvelope): Promise<WorkflowImportResult> =>
    apiFetch("/api/workflows/import", { method: "POST", timeoutMs: TIMEOUT_MS, ...jsonBody(envelope) }).then(
      toJsonQuietOn(QUIET)
    ),
  // Is the feature switched on? POSTs an empty envelope to the preview route: 404 = switched off (or an older core);
  // the 400 it answers for a missing payload = on. Reads and creates nothing, so it is safe to call on every page load.
  portabilityEnabled: async (): Promise<boolean> => {
    // A switched-off deployment stays switched off while the tab is open: remember the 404 for the browser session so
    // later page loads do not POST again just to log another 404 (negativeCache.ts).
    if (isKnownMissing(PORTABILITY_OFF_KEY)) return false;
    try {
      const res = await apiFetch("/api/workflows/import/preview", { method: "POST", timeoutMs: 15_000, ...jsonBody({}) });
      if (res.status === 404) rememberMissing(PORTABILITY_OFF_KEY);
      return res.status === 400;
    } catch {
      return false;
    }
  },
  // GET /workflows/{id}/export - the file as a Blob plus the server's suggested name. A non-2xx (404 = the feature is
  // switched off or this core predates it) throws an Error carrying .status so the caller can say so quietly.
  exportWorkflowDsl: async (workflowId: string): Promise<WorkflowExportFile> => {
    const res = await apiFetch(`/api/workflows/${encodeURIComponent(workflowId)}/export`);
    if (!res.ok) {
      const err: any = new Error(friendlyStatusFallback(res.status));
      err.status = res.status;
      throw err;
    }
    const filename = filenameFromDisposition(res.headers.get("Content-Disposition"), "workflow.vegaduta-workflow.json");
    return { blob: await res.blob(), filename };
  },
};
