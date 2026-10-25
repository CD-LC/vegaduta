import { apiFetch, toJson, toJsonQuietOn, jsonBody, friendlyStatusFallback, type ApiError } from "./core";
import type {
  CreateDesktopRecipeInput,
  DesktopRecipeDetail,
  DesktopRecipeRunResult,
  DesktopRecipeSummary,
  RunDesktopRecipeInput,
  SaveDesktopRunInput,
} from "./types";

// Desktop recipes (core /api/desktop/recipes + /api/desktop/monitor/frame): recorded desktop tasks an
// agent replays step by step through the desktop-agent sidecar. Mirrored on the browser-recipe surface
// (./browserAutomation.ts): the whole feature ships OFF, so a switched-off deployment answers 404/503,
// which the list call raises quietly (no global error toast) and the cards treat as "not available".
// A recipe stores placeholders only - never a password or a value the user typed.
//
// Monitor frames are binary (image/jpeg or image/png), so fetchDesktopMonitorFrame does not go through
// toJson; a 404/503 there means "monitor off / sidecar down", an expected state the pane shows inline.

export type {
  CreateDesktopRecipeInput,
  DesktopRecipeDetail,
  DesktopRecipeMode,
  DesktopRecipeParam,
  DesktopRecipeRunResult,
  DesktopRecipeSpec,
  DesktopRecipeStep,
  DesktopRecipeStepExpect,
  DesktopRecipeStepResult,
  DesktopRecipeSummary,
  RunDesktopRecipeInput,
  SaveDesktopRunInput,
} from "./types/desktop";

const QUIET = [401, 403, 404, 500, 502, 503];

const enc = encodeURIComponent;

/** Maps the two run-response wire shapes (see DesktopRecipeRunResult's doc) onto one client shape. */
export function normalizeRunResult(raw: Record<string, unknown> | null | undefined): DesktopRecipeRunResult {
  const r = (raw ?? {}) as Record<string, unknown>;
  const recipe = (r.recipe ?? {}) as { id?: unknown; name?: unknown; steps?: unknown };
  const stepList = (Array.isArray(r.stepResults) ? r.stepResults : Array.isArray(r.steps) ? r.steps : []) as {
    index?: unknown;
    action?: unknown;
    ok?: unknown;
    note?: unknown;
  }[];
  return {
    ok: typeof r.ok === "boolean" ? r.ok : r.success === true,
    recipe: {
      id: String(recipe.id ?? r.recipeId ?? ""),
      name: String(recipe.name ?? r.name ?? ""),
      steps: Number(recipe.steps ?? r.stepsTotal ?? 0) || 0,
    },
    mode: r.mode === "MONITOR" ? "MONITOR" : r.mode === "AUTO" ? "AUTO" : undefined,
    stepsRun: Number(r.stepsRun ?? stepList.length) || 0,
    failure: typeof r.failure === "string" ? r.failure : undefined,
    stepResults: stepList.map((s, i) => ({
      index: Number(s.index ?? i + 1) || i + 1,
      action: String(s.action ?? ""),
      ok: s.ok === true,
      note: typeof s.note === "string" ? s.note : undefined,
    })),
  };
}

function listQuery(agentId?: string, limit?: number, offset?: number): string {
  const q = new URLSearchParams();
  if (agentId) q.set("agentId", agentId);
  if (typeof limit === "number" && Number.isFinite(limit)) q.set("limit", String(limit));
  if (typeof offset === "number" && Number.isFinite(offset)) q.set("offset", String(offset));
  const qs = q.toString();
  return qs ? `?${qs}` : "";
}

export const desktopRecipesApi = {
  listDesktopRecipes: (agentId?: string, limit?: number, offset?: number): Promise<DesktopRecipeSummary[]> =>
    apiFetch(`/api/desktop/recipes${listQuery(agentId, limit, offset)}`).then(toJsonQuietOn<DesktopRecipeSummary[]>(QUIET)),

  getDesktopRecipe: (id: string): Promise<DesktopRecipeDetail> =>
    apiFetch(`/api/desktop/recipes/${enc(id)}`).then(toJson<DesktopRecipeDetail>),

  createDesktopRecipe: (input: CreateDesktopRecipeInput): Promise<DesktopRecipeDetail> =>
    apiFetch("/api/desktop/recipes", { method: "POST", ...jsonBody(input) }).then(toJson<DesktopRecipeDetail>),

  // "Save this run as a recipe": core reads the run's desktop_* tool calls from its journal.
  saveDesktopRunAsRecipe: (input: SaveDesktopRunInput): Promise<DesktopRecipeDetail> =>
    apiFetch("/api/desktop/recipes/from-run", { method: "POST", ...jsonBody(input) }).then(toJson<DesktopRecipeDetail>),

  runDesktopRecipe: (id: string, input: RunDesktopRecipeInput): Promise<DesktopRecipeRunResult> =>
    apiFetch(`/api/desktop/recipes/${enc(id)}/run`, { method: "POST", ...jsonBody(input) })
      .then(toJson<Record<string, unknown>>)
      .then(normalizeRunResult),

  updateDesktopRecipe: (
    id: string,
    patch: { name?: string; description?: string; defaultMode?: "AUTO" | "MONITOR" }
  ): Promise<DesktopRecipeSummary> =>
    apiFetch(`/api/desktop/recipes/${enc(id)}`, { method: "PATCH", ...jsonBody(patch) }).then(
      toJson<DesktopRecipeSummary>
    ),

  deleteDesktopRecipe: (id: string): Promise<void> =>
    apiFetch(`/api/desktop/recipes/${enc(id)}`, { method: "DELETE" }).then(toJson<void>),

  /**
   * One monitor frame as a Blob (jpeg or png per Content-Type). Throws an ApiError carrying .status -
   * 404/503 mean the monitor or the desktop sidecar is off, which the pane renders as an inline state,
   * not a toast. skipGetCache: frames change every second; a cached copy would freeze the view.
   */
  fetchDesktopMonitorFrame: async (): Promise<Blob> => {
    const res = await apiFetch("/api/desktop/monitor/frame", { skipGetCache: true });
    if (!res.ok) {
      const err: ApiError = new Error(friendlyStatusFallback(res.status));
      err.status = res.status;
      throw err;
    }
    const type = res.headers.get("content-type") ?? "";
    if (!type.toLowerCase().startsWith("image/")) {
      const err: ApiError = new Error(friendlyStatusFallback(res.status));
      err.status = res.status;
      throw err;
    }
    return res.blob();
  },
};
