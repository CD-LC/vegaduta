import { apiFetch, toJson, toJsonQuietOn, jsonBody } from "./core";
import type {
  BrowserCapabilities,
  BrowserProfile,
  BrowserRecipeDetail,
  BrowserRecipeSummary,
  CreateBrowserProfileInput,
  SaveBrowserRunInput,
} from "./types";

// Browser agent persistence and replay (core tools/browseruse): saved login PROFILES
// (/api/browser/profiles), RECIPES recorded from a finished browser run
// (/api/browser/recipes), and the capability probe (/api/browser/automation/capabilities).
// Every part is behind a core flag that is OFF by default; a switched-off feature answers 404,
// which these calls raise quietly (no global error toast) so the cards can simply hide.
// Nothing here can carry a saved cookie or a secret: a profile is metadata only, a recipe is
// placeholders only.

export type {
  BrowserCapabilities,
  BrowserProfile,
  BrowserProfileScope,
  BrowserRecipeDetail,
  BrowserRecipeSpec,
  BrowserRecipeStep,
  BrowserRecipeSummary,
  CreateBrowserProfileInput,
  SaveBrowserRunInput,
} from "./types";

const QUIET = [401, 403, 404, 500, 502, 503];

export const browserAutomationApi = {
  getBrowserCapabilities: (): Promise<BrowserCapabilities> =>
    apiFetch("/api/browser/automation/capabilities").then(toJsonQuietOn(QUIET)),

  // ---- profiles ----
  listBrowserProfiles: (): Promise<BrowserProfile[]> => apiFetch("/api/browser/profiles").then(toJsonQuietOn(QUIET)),
  createBrowserProfile: (input: CreateBrowserProfileInput): Promise<BrowserProfile> =>
    apiFetch("/api/browser/profiles", { method: "POST", ...jsonBody(input) }).then(toJson<BrowserProfile>),
  updateBrowserProfile: (
    id: string,
    patch: { name?: string; allowedDomains?: string[]; ttlDays?: number },
  ): Promise<BrowserProfile> =>
    apiFetch(`/api/browser/profiles/${encodeURIComponent(id)}`, { method: "PATCH", ...jsonBody(patch) }).then(toJson<BrowserProfile>),
  deleteBrowserProfile: (id: string): Promise<void> =>
    apiFetch(`/api/browser/profiles/${encodeURIComponent(id)}`, { method: "DELETE" }).then(toJson<void>),
  // Forget the saved login but keep the profile and its attachments.
  clearBrowserProfileState: (id: string): Promise<void> =>
    apiFetch(`/api/browser/profiles/${encodeURIComponent(id)}/clear-state`, { method: "POST" }).then(toJson<void>),
  attachBrowserProfile: (profileId: string, agentId: string): Promise<BrowserProfile> =>
    apiFetch(`/api/browser/profiles/${encodeURIComponent(profileId)}/agents/${encodeURIComponent(agentId)}`, {
      method: "PUT",
    }).then(toJson<BrowserProfile>),
  detachBrowserProfile: (profileId: string, agentId: string): Promise<void> =>
    apiFetch(`/api/browser/profiles/${encodeURIComponent(profileId)}/agents/${encodeURIComponent(agentId)}`, {
      method: "DELETE",
    }).then(toJson<void>),

  // ---- recipes ----
  listBrowserRecipes: (agentId?: string): Promise<BrowserRecipeSummary[]> =>
    apiFetch(`/api/browser/recipes${agentId ? `?agentId=${encodeURIComponent(agentId)}` : ""}`).then(toJsonQuietOn(QUIET)),
  getBrowserRecipe: (id: string): Promise<BrowserRecipeDetail> =>
    apiFetch(`/api/browser/recipes/${encodeURIComponent(id)}`).then(toJson<BrowserRecipeDetail>),
  deleteBrowserRecipe: (id: string): Promise<void> =>
    apiFetch(`/api/browser/recipes/${encodeURIComponent(id)}`, { method: "DELETE" }).then(toJson<void>),
  renameBrowserRecipe: (id: string, patch: { name?: string; description?: string }): Promise<BrowserRecipeSummary> =>
    apiFetch(`/api/browser/recipes/${encodeURIComponent(id)}`, { method: "PATCH", ...jsonBody(patch) }).then(toJson<BrowserRecipeSummary>),
  // "Save this run as a recipe": core reads the run's tool calls from its journal.
  saveBrowserRunAsRecipe: (input: SaveBrowserRunInput): Promise<BrowserRecipeDetail> =>
    apiFetch("/api/browser/recipes/from-run", { method: "POST", ...jsonBody(input) }).then(toJson<BrowserRecipeDetail>),
};
