import { apiFetch, toJson, jsonBody } from "./core";

// Every instruction the platform gives the model, owner-editable
// (core PromptDirectiveController, /api/prompt-directives). Layering, lowest to
// highest precedence: built-in directives < tenant custom instructions < the
// agent's own System prompt.

export const MAX_DIRECTIVE_CHARS = 4000;
export const MAX_CUSTOM_INSTRUCTIONS_CHARS = 8000;

export interface PromptDirective {
  key: string;
  title: string;
  description: string;
  /** Editing or disabling it can weaken safety; the server demands acknowledgeRisk. */
  securityCritical: boolean;
  /** Tokens such as "{date}" the platform fills in at runtime. */
  placeholders: string[];
  enabled: boolean;
  overridden: boolean;
  defaultText: string;
  overrideText: string | null;
  effectiveText: string;
}

export interface CustomInstructions {
  enabled: boolean;
  text: string | null;
}

export interface PromptDirectivesOverview {
  directives: PromptDirective[];
  customInstructions: CustomInstructions;
  assembledPreview: string;
}

export interface SavePromptDirectiveRequest {
  enabled: boolean;
  /** Blank or null means "use the default text". */
  text: string | null;
  acknowledgeRisk: boolean;
}

const BASE = "/api/prompt-directives";

export const promptDirectivesApi = {
  getPromptDirectives: () => apiFetch(BASE).then(toJson<PromptDirectivesOverview>),
  savePromptDirective: (key: string, body: SavePromptDirectiveRequest) =>
    apiFetch(`${BASE}/${encodeURIComponent(key)}`, { method: "PUT", ...jsonBody(body) }).then(toJson),
  resetPromptDirective: (key: string) =>
    apiFetch(`${BASE}/${encodeURIComponent(key)}`, { method: "DELETE" }).then(toJson),
  saveCustomInstructions: (body: { enabled: boolean; text: string | null }) =>
    apiFetch(`${BASE}/custom-instructions`, { method: "PUT", ...jsonBody(body) }).then(toJson),
};
