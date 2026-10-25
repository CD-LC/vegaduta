"use client";

import { useCallback, useEffect, useState } from "react";
import { Loader2 } from "lucide-react";
import { api } from "../../lib/api";
import type {
  DesktopRecipeDetail,
  DesktopRecipeMode,
  DesktopRecipeRunResult,
  DesktopRecipeSummary,
} from "../../lib/api/desktopRecipes";
import { useTranslations } from "../../lib/i18n";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { Select } from "../ui/select";
import DesktopMonitor from "./DesktopMonitor";

// Desktop recipes (core /api/desktop/recipes) for one agent: its own recipes plus the workspace-wide
// ones. List, run and delete - recipes are created from a finished run (SaveRunAsDesktopRecipe, in the
// playground). "Run" opens a small panel: parameter values, AUTO/MONITOR, and the irreversible-steps
// confirmation the spec requires; while the run call is in flight the DesktopMonitor pane below streams
// the sidecar's frames and afterwards keeps the last frame plus the step-by-step ticker.
// Self-contained like BrowserRecipesCard: own fetch and busy/error state, `locked` from the parent.

export type DesktopRecipeHealth = "new" | "healthy" | "failing";

/** A recipe whose latest replay failed needs a look; one never replayed is just new. */
export function desktopRecipeHealth(recipe: Pick<DesktopRecipeSummary, "runCount" | "lastFailure">): DesktopRecipeHealth {
  if (recipe.lastFailure) return "failing";
  return recipe.runCount > 0 ? "healthy" : "new";
}

/** "Which recipes may this agent run": its own plus workspace-wide ones, own first. */
export function desktopRecipesForAgent(recipes: DesktopRecipeSummary[], agentId: string): DesktopRecipeSummary[] {
  return recipes
    .filter((r) => r.agentId === null || r.agentId === agentId)
    .sort((a, b) => {
      if ((a.agentId === null) !== (b.agentId === null)) return a.agentId === null ? 1 : -1;
      return a.name.localeCompare(b.name);
    });
}

/** A spec with at least one irreversible step cannot run until the person confirms it. */
export function specHasIrreversible(detail: DesktopRecipeDetail | null): boolean {
  return !!detail?.spec?.steps?.some((s) => s.irreversible);
}

export default function DesktopRecipesCard({
  agentId,
  locked,
  editLockedTooltip,
}: {
  agentId: string;
  locked: boolean;
  editLockedTooltip?: string;
}) {
  const t = useTranslations("desktopAutomation");
  const [recipes, setRecipes] = useState<DesktopRecipeSummary[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [confirmId, setConfirmId] = useState<string | null>(null);
  // Run panel: at most one open; its detail fetch is cached per recipe while the panel stays open.
  const [openRunId, setOpenRunId] = useState<string | null>(null);
  const [detail, setDetail] = useState<DesktopRecipeDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [paramValues, setParamValues] = useState<Record<string, string>>({});
  const [mode, setMode] = useState<DesktopRecipeMode>("AUTO");
  const [confirmIrreversible, setConfirmIrreversible] = useState(false);
  const [runningId, setRunningId] = useState<string | null>(null);
  const [result, setResult] = useState<{ recipeId: string; res: DesktopRecipeRunResult } | null>(null);

  const load = useCallback(() => {
    setFailed(false);
    api
      .listDesktopRecipes(agentId)
      .then((list: DesktopRecipeSummary[]) => setRecipes(Array.isArray(list) ? desktopRecipesForAgent(list, agentId) : []))
      .catch(() => setFailed(true));
  }, [agentId]);

  useEffect(() => {
    setRecipes(null);
    load();
  }, [load]);

  const remove = async (id: string) => {
    if (busy || locked) return;
    setBusy(true);
    setError(null);
    try {
      await api.deleteDesktopRecipe(id);
      if (openRunId === id) setOpenRunId(null);
      if (result?.recipeId === id) setResult(null);
    } catch (e: unknown) {
      setError(e instanceof Error && e.message ? e.message : t("actionFailed"));
    } finally {
      setBusy(false);
      setConfirmId(null);
      load();
    }
  };

  const toggleRun = (r: DesktopRecipeSummary) => {
    if (openRunId === r.id) {
      setOpenRunId(null);
      return;
    }
    setOpenRunId(r.id);
    setDetail(null);
    setParamValues({});
    setMode(r.defaultMode === "MONITOR" ? "MONITOR" : "AUTO");
    setConfirmIrreversible(false);
    setError(null);
    setDetailLoading(true);
    api
      .getDesktopRecipe(r.id)
      .then((d) => setDetail(d))
      .catch(() => setError(t("loadFailed")))
      .finally(() => setDetailLoading(false));
  };

  const run = async (r: DesktopRecipeSummary) => {
    if (busy || runningId || locked) return;
    const params = detail?.spec?.params ?? [];
    for (const p of params) {
      if (p.required && !(paramValues[p.name] ?? "").trim()) {
        setError(t("needParam", { name: p.name }));
        return;
      }
    }
    const irreversible = specHasIrreversible(detail);
    setRunningId(r.id);
    setResult(null);
    setError(null);
    try {
      const res = await api.runDesktopRecipe(r.id, {
        params: Object.fromEntries(Object.entries(paramValues).filter(([, v]) => v.trim() !== "")),
        confirmIrreversible: irreversible ? confirmIrreversible : undefined,
        mode,
      });
      setResult({ recipeId: r.id, res });
      if (!res.ok) setError(res.failure || t("runFailed"));
    } catch (e: unknown) {
      setError(e instanceof Error && e.message ? e.message : t("actionFailed"));
    } finally {
      setRunningId(null);
      load();
    }
  };

  const disabled = locked || busy;

  return (
    <div className="mb-4" data-testid="desktop-recipes-card">
      <div className="text-sm font-medium text-foreground">{t("recipesTitle")}</div>
      <p className="mt-0.5 text-xs text-muted-foreground">{t("recipesHelp")}</p>

      {recipes === null && !failed && (
        <div className="mt-2 flex items-center gap-2 text-xs text-muted-foreground">
          <Loader2 className="size-3.5 animate-spin" aria-hidden="true" />
          {t("loading")}
        </div>
      )}
      {failed && <p className="mt-2 text-xs text-destructive">{t("loadFailed")}</p>}
      {error && (
        <p role="alert" className="mt-2 text-xs text-destructive">
          {error}
        </p>
      )}

      {recipes && recipes.length === 0 && <p className="mt-2 text-xs text-muted-foreground">{t("recipesEmpty")}</p>}

      {recipes && recipes.length > 0 && (
        <ul className="mt-2 space-y-2">
          {recipes.map((r) => {
            const health = desktopRecipeHealth(r);
            const runOpen = openRunId === r.id;
            return (
              <li
                key={r.id}
                className="rounded-md border border-border bg-card px-3 py-2"
                data-testid={`desktop-recipe-${r.id}`}
              >
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <span className="text-sm font-medium text-foreground">
                    {r.name}{" "}
                    <span className="rounded-full border border-border px-1.5 py-px text-[11px] uppercase tracking-wide text-muted-foreground">
                      {health === "failing"
                        ? t("healthFailing")
                        : health === "healthy"
                          ? t("healthHealthy")
                          : t("healthNew")}
                    </span>
                    <span className="ml-1 rounded-full border border-border px-1.5 py-px text-[11px] uppercase tracking-wide text-muted-foreground">
                      {r.defaultMode === "MONITOR" ? t("modeMonitor") : t("modeAuto")}
                    </span>
                    {r.agentId === null && (
                      <span className="ml-1 rounded-full border border-border px-1.5 py-px text-[11px] uppercase tracking-wide text-muted-foreground">
                        {t("recipeScopeAll")}
                      </span>
                    )}
                  </span>
                  <span className="flex flex-wrap gap-1.5">
                    <Button
                      type="button"
                      variant="outline"
                      size="sm"
                      disabled={disabled || runningId !== null}
                      title={locked ? editLockedTooltip : undefined}
                      onClick={() => toggleRun(r)}
                    >
                      {runOpen ? t("closeRun") : t("runRecipe")}
                    </Button>
                    {confirmId === r.id ? (
                      <Button
                        type="button"
                        variant="destructive"
                        size="sm"
                        disabled={disabled}
                        title={locked ? editLockedTooltip : undefined}
                        onClick={() => void remove(r.id)}
                      >
                        {t("confirmDelete")}
                      </Button>
                    ) : (
                      <Button
                        type="button"
                        variant="outline"
                        size="sm"
                        disabled={disabled}
                        title={locked ? editLockedTooltip : undefined}
                        onClick={() => setConfirmId(r.id)}
                      >
                        {t("deleteRecipe")}
                      </Button>
                    )}
                  </span>
                </div>
                {r.description && <div className="mt-0.5 text-xs text-muted-foreground">{r.description}</div>}
                <div className="mt-0.5 text-xs text-muted-foreground">
                  {t("recipeSteps", { count: r.stepCount })} ·{" "}
                  {r.runCount > 0 ? t("recipeRuns", { runs: r.runCount, fails: r.failCount }) : t("recipeNeverRun")}
                </div>
                {r.lastFailure && (
                  <div className="mt-0.5 text-xs text-destructive">{t("recipeLastFailure", { reason: r.lastFailure })}</div>
                )}

                {runOpen && (
                  <div className="mt-2 space-y-2 rounded-md border border-dashed border-border px-3 py-2" data-testid={`desktop-run-${r.id}`}>
                    {detailLoading && (
                      <div className="flex items-center gap-2 text-xs text-muted-foreground">
                        <Loader2 className="size-3.5 animate-spin" aria-hidden="true" />
                        {t("loading")}
                      </div>
                    )}
                    {detail && (
                      <>
                        {(detail.spec?.params ?? []).map((p) => (
                          <label key={p.name} className="block text-xs text-muted-foreground">
                            {p.name}
                            {p.required ? ` ${t("paramRequired")}` : ""}
                            <Input
                              className="mt-1 h-8 text-xs"
                              value={paramValues[p.name] ?? ""}
                              disabled={disabled || runningId !== null}
                              onChange={(e) => setParamValues((v) => ({ ...v, [p.name]: e.target.value }))}
                            />
                          </label>
                        ))}
                        <div className="text-xs text-muted-foreground">
                          {t("modeLabel")}
                          <Select
                            className="mt-1 [&_button]:h-8 [&_button]:text-xs"
                            aria-label={t("modeLabel")}
                            value={mode}
                            disabled={disabled || runningId !== null}
                            onValueChange={(v) => setMode(v === "MONITOR" ? "MONITOR" : "AUTO")}
                            options={[
                              { value: "AUTO", label: t("modeAuto") },
                              { value: "MONITOR", label: t("modeMonitor") },
                            ]}
                          />
                          <span className="mt-0.5 block text-[11px] text-muted-foreground">
                            {mode === "MONITOR" ? t("modeMonitorHint") : t("modeAutoHint")}
                          </span>
                        </div>
                        {specHasIrreversible(detail) && (
                          <label className="flex items-start gap-2 text-xs text-muted-foreground">
                            <input
                              type="checkbox"
                              className="mt-0.5"
                              checked={confirmIrreversible}
                              disabled={disabled || runningId !== null}
                              onChange={(e) => setConfirmIrreversible(e.target.checked)}
                            />
                            <span>{t("confirmIrreversible")}</span>
                          </label>
                        )}
                        {detail.warnings.length > 0 && (
                          <ul className="list-disc pl-4 text-[11px] text-muted-foreground">
                            {detail.warnings.map((w) => (
                              <li key={w}>{w}</li>
                            ))}
                          </ul>
                        )}
                        <Button
                          type="button"
                          size="sm"
                          disabled={disabled || runningId !== null || (specHasIrreversible(detail) && !confirmIrreversible)}
                          title={locked ? editLockedTooltip : undefined}
                          onClick={() => void run(r)}
                        >
                          {runningId === r.id ? t("running") : t("runNow")}
                        </Button>
                      </>
                    )}
                    <DesktopMonitor
                      active={runningId === r.id}
                      steps={result?.recipeId === r.id ? result.res.stepResults : null}
                    />
                    {result?.recipeId === r.id && (
                      <p
                        role="status"
                        className={result.res.ok ? "text-[11px] text-muted-foreground" : "text-[11px] text-destructive"}
                      >
                        {result.res.ok
                          ? t("runDone", { done: result.res.stepsRun, total: result.res.recipe.steps })
                          : t("runFailedAt", { reason: result.res.failure ?? "-" })}
                      </p>
                    )}
                  </div>
                )}
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
