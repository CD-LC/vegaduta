// Desktop agent recipes (core /api/desktop/*): recorded desktop tasks the agent (or a person, straight
// from the console) replays step by step against the desktop-agent sidecar, plus the monitor-frame
// endpoint the run view polls. Metadata only - a recipe's typed text is placeholders, never a secret.
// The calls live in ../desktopRecipes.ts (which re-exports these names).
export type DesktopRecipeMode = "AUTO" | "MONITOR";

/** One row of GET /api/desktop/recipes. */
export interface DesktopRecipeSummary {
  id: string;
  /** Owning agent, or null for a workspace-wide recipe. */
  agentId: string | null;
  name: string;
  description: string | null;
  stepCount: number;
  runCount: number;
  failCount: number;
  lastRunAt: string | null;
  lastFailure: string | null;
  /** How a run behaves when nobody chooses: AUTO replays at full speed, MONITOR streams frames. */
  defaultMode: DesktopRecipeMode;
  /** Present on newer cores; the detail's spec is the authority when absent. */
  allowedApps?: string[];
  parameters?: string[];
}

export interface DesktopRecipeStepExpect {
  elementLabel?: string;
  appFrontmost?: string;
}

export interface DesktopRecipeStep {
  action: string;
  app?: string;
  label?: string;
  role?: string;
  x?: number;
  y?: number;
  text?: string;
  key?: string;
  target?: string;
  waitMs?: number;
  expect?: DesktopRecipeStepExpect;
  timeoutMs?: number;
  irreversible?: boolean;
}

export interface DesktopRecipeParam {
  name: string;
  required: boolean;
}

export interface DesktopRecipeSpec {
  version: number;
  allowedApps: string[];
  params: DesktopRecipeParam[];
  steps: DesktopRecipeStep[];
}

/** GET /api/desktop/recipes/{id} and both 201 creates. */
export interface DesktopRecipeDetail {
  summary: DesktopRecipeSummary;
  spec: DesktopRecipeSpec;
  warnings: string[];
}

/** POST /api/desktop/recipes. */
export interface CreateDesktopRecipeInput {
  agentId?: string;
  name: string;
  description?: string;
  defaultMode?: DesktopRecipeMode;
  spec: DesktopRecipeSpec;
}

/** POST /api/desktop/recipes/from-run. `parameters` maps a name to the value supplied in this run -
 * the value is used once for substitution and is never stored. */
export interface SaveDesktopRunInput {
  agentId: string;
  sessionId: string;
  name: string;
  description?: string;
  parameters: Record<string, string>;
  allowLiteralText?: boolean;
  defaultMode?: DesktopRecipeMode;
}

/** POST /api/desktop/recipes/{id}/run. */
export interface RunDesktopRecipeInput {
  params?: Record<string, string>;
  /** True only after the person approved the steps flagged irreversible. */
  confirmIrreversible?: boolean;
  /** 1-based step to resume at (after a confirmation pause). */
  fromStep?: number;
  mode?: DesktopRecipeMode;
}

export interface DesktopRecipeStepResult {
  index: number;
  action: string;
  ok: boolean;
  note?: string;
}

/**
 * POST /api/desktop/recipes/{id}/run response, normalized by the client. Two wire shapes exist across
 * core versions - {ok, recipe:{id,name,steps}, mode, stepResults} and the flatter
 * {recipeId, name, success, stepsRun, stepsTotal, failure, steps} - and runDesktopRecipe maps both into
 * this one shape, so components never see the difference.
 */
export interface DesktopRecipeRunResult {
  ok: boolean;
  recipe: { id: string; name: string; steps: number };
  mode?: DesktopRecipeMode;
  stepsRun: number;
  failure?: string;
  stepResults: DesktopRecipeStepResult[];
}
