import { apiFetch, toJsonQuietOn, jsonBody } from "./core";

// Migration kits: bring an agent over from another platform
// (AgentImportController, core/agents, routes under /api/agents/import).
//
// Both routes take the SAME envelope and are authenticated-only (same bar as
// POST /api/agents). 400/403/404/413 are expected, user-facing states the page
// renders inline (bad paste, plan agent cap, import switched off or an older
// core, oversize body), so they route through toJsonQuietOn and raise no global
// toast; anything else (5xx) still goes through the normal toast + beacon.
//
// The export payload is user data: it is only ever sent in the request body,
// never logged, cached or persisted by this client.

export type ImportSource = "auto" | "openai-assistant" | "openai-agents" | "flowise";
export type DetectedImportSource = Exclude<ImportSource, "auto">;

export type ImportEnvelope = {
  source: ImportSource;
  payload: Record<string, unknown>;
  overrides?: { name?: string };
};

export type ImportReportConverted = { what: string; detail: string };
export type ImportReportNeedsManual = { what: string; why: string; hint: string };
export type ImportReportUnsupported = { what: string; why: string };

export type ImportReport = {
  converted: ImportReportConverted[];
  needsManual: ImportReportNeedsManual[];
  unsupported: ImportReportUnsupported[];
  warnings: string[];
};

export type ImportPreviewAgent = {
  name: string;
  description: string;
  instructions: string;
  modelProvider: string;
  model: string;
  temperature: number | null;
  topP: number | null;
  suggestedCapabilities: string[];
  suggestedTools: string[];
};

export type ImportPreviewResponse = {
  source: string;
  detectedSource: DetectedImportSource;
  agent: ImportPreviewAgent;
  report: ImportReport;
};

export type ImportCreateResponse = { agentId: string; report: ImportReport };

// Statuses the page handles inline - see the header comment.
const QUIET = [400, 403, 404, 413];
// Generous client-side ceiling: a hung request must not spin forever.
const TIMEOUT_MS = 90_000;

export const agentImportApi = {
  previewAgentImport: (envelope: ImportEnvelope): Promise<ImportPreviewResponse> =>
    apiFetch("/api/agents/import/preview", { method: "POST", timeoutMs: TIMEOUT_MS, ...jsonBody(envelope) }).then(
      toJsonQuietOn(QUIET)
    ),
  importAgent: (envelope: ImportEnvelope): Promise<ImportCreateResponse> =>
    apiFetch("/api/agents/import", { method: "POST", timeoutMs: TIMEOUT_MS, ...jsonBody(envelope) }).then(
      toJsonQuietOn(QUIET)
    ),
};
