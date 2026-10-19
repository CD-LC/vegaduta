import { apiFetch, toJson, toJsonQuietOn, jsonBody } from "./core";

// Shareable agent files (TemplateBundleController, core/templates, routes under /api/agent-bundles).
// A bundle is one JSON file with an agent's design; the server treats it as untrusted input and never
// grants full autonomy from it. 400/403/404/422/429 are expected, user-facing states the page renders
// inline, so they go through toJsonQuietOn and raise no global toast.

export const BUNDLE_FORMAT = "vegaduta.agent-bundle/1";

export type AgentBundle = {
  format: string;
  title: string;
  summary?: string | null;
  category?: string | null;
  tags?: string[] | null;
  agent: Record<string, unknown>;
};

export type BundleIssue = { code: string; principle: string; message: string; fix: string };

export type AgentBundlePreview = {
  title: string;
  tools: string[];
  autonomyTier: string;
  adjustments: string[];
  readiness?: { ready: boolean; blockers: BundleIssue[]; advisories: BundleIssue[] };
};

export type BundleImportResponse = { kind: string; resourceId: string };

const QUIET = [400, 403, 404, 422, 429];

export const agentBundlesApi = {
  previewAgentBundle: (bundle: AgentBundle): Promise<AgentBundlePreview> =>
    apiFetch("/api/agent-bundles/preview", { method: "POST", ...jsonBody(bundle) }).then(toJsonQuietOn(QUIET)),
  importAgentBundle: (bundle: AgentBundle): Promise<BundleImportResponse> =>
    apiFetch("/api/agent-bundles/import", { method: "POST", ...jsonBody(bundle) }).then(toJsonQuietOn(QUIET)),
  exportAgentBundle: (agentId: string): Promise<AgentBundle> =>
    apiFetch(`/api/agent-bundles/${encodeURIComponent(agentId)}`).then(toJson) as Promise<AgentBundle>,
};
