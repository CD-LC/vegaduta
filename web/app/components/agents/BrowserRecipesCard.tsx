"use client";

import { useCallback, useEffect, useState } from "react";
import { Check, Eye, Loader2, X } from "lucide-react";
import { api } from "../../lib/api";
import { apiFetch, toJson } from "../../lib/api/core";
import type { ToolCallLogEntry } from "../../lib/api/types";
import type { BrowserRecipeSummary } from "../../lib/api/browserAutomation";
import { recipeHealth, recipesForAgent } from "../../lib/browserAutomation";
import { useTranslations } from "../../lib/i18n";
import { Button } from "../ui/button";

// Recorded tasks (core /api/browser/recipes) for one agent: its own recipes plus the workspace-wide ones.
// List, watch and delete - recipes are created from a finished run (SaveRunAsRecipe, in the playground) and
// hold placeholders, never a secret or a value the user typed. Self-contained like BrowserProfilesCard.
//
// "Watch" is read-only: a replay is run by the agent itself inside a chat session (the run_browser_recipe
// tool), so while it is in flight this polls the agent's tool-call log (GET /api/agents/{id}/tool-calls,
// skipGetCache - the 15s GET cache would freeze the ticker) and shows this recipe's replays as a live
// step ticker: running (no completedAt yet), done, or failed (errorMessage set).

const WATCH_POLL_MS = 2_500;
const RUN_RECIPE_TOOL = "run_browser_recipe";

/** The recipeId the agent passed to run_browser_recipe, when the input parses (it is the tool's JSON
 * args: {"recipeId": "<id or name>"}). */
function replayRef(toolInput: string | null): string | null {
  if (!toolInput) return null;
  try {
    const ref = (JSON.parse(toolInput) as { recipeId?: unknown }).recipeId;
    return typeof ref === "string" && ref ? ref : null;
  } catch {
    return null;
  }
}

/** This recipe's replays out of the agent's tool-call log, oldest first: run_browser_recipe calls
 * addressed to this recipe. The agent may pass the id or the name, so both are matched; a non-JSON
 * input falls back to a substring check rather than silently dropping the row. */
export function replayEntriesForRecipe(
  entries: ToolCallLogEntry[],
  recipe: Pick<BrowserRecipeSummary, "id" | "name">
): ToolCallLogEntry[] {
  const matches = (e: ToolCallLogEntry): boolean => {
    const ref = replayRef(e.toolInput);
    if (ref !== null) return ref === recipe.id || ref === recipe.name;
    const input = e.toolInput ?? "";
    return input.includes(recipe.id) || (recipe.name.length > 0 && input.includes(recipe.name));
  };
  return entries.filter((e) => e.toolName === RUN_RECIPE_TOOL && matches(e)).sort((a, b) => a.startedAt.localeCompare(b.startedAt));
}

export default function BrowserRecipesCard({
  agentId,
  locked,
  editLockedTooltip,
}: {
  agentId: string;
  locked: boolean;
  editLockedTooltip?: string;
}) {
  const t = useTranslations("browserAutomation");
  const [recipes, setRecipes] = useState<BrowserRecipeSummary[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [confirmId, setConfirmId] = useState<string | null>(null);
  // Watch state: which recipe's replays are on screen and the log rows seen so far.
  const [watch, setWatch] = useState<{ id: string; name: string } | null>(null);
  const [watchEntries, setWatchEntries] = useState<ToolCallLogEntry[]>([]);

  const load = useCallback(() => {
    setFailed(false);
    api
      .listBrowserRecipes(agentId)
      .then((list: BrowserRecipeSummary[]) => setRecipes(Array.isArray(list) ? recipesForAgent(list, agentId) : []))
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
      await api.deleteBrowserRecipe(id);
    } catch (e: unknown) {
      setError(e instanceof Error && e.message ? e.message : t("actionFailed"));
    } finally {
      setBusy(false);
      setConfirmId(null);
      load();
    }
  };

  // Poll the tool-call log while watching: a replay in flight has no completedAt yet. Failures are
  // ignored per tick - the next poll retries; the ticker just keeps the last good read.
  useEffect(() => {
    if (!watch) return undefined;
    let stopped = false;
    const read = async () => {
      if (typeof document !== "undefined" && document.hidden) return;
      try {
        const list = await apiFetch(`/api/agents/${encodeURIComponent(agentId)}/tool-calls`, {
          skipGetCache: true,
        }).then(toJson<ToolCallLogEntry[]>);
        if (!stopped) setWatchEntries(Array.isArray(list) ? replayEntriesForRecipe(list, watch) : []);
      } catch {
        /* a failed read changes nothing; the next tick asks again */
      }
    };
    void read();
    const timer = setInterval(() => void read(), WATCH_POLL_MS);
    return () => {
      stopped = true;
      clearInterval(timer);
    };
  }, [watch, agentId]);

  const toggleWatch = (r: BrowserRecipeSummary) => {
    setWatchEntries([]);
    setWatch((current) => (current?.id === r.id ? null : { id: r.id, name: r.name }));
  };

  return (
    <div className="mb-4" data-testid="browser-recipes-card">
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
            const health = recipeHealth(r);
            return (
              <li
                key={r.id}
                className="rounded-md border border-border bg-card px-3 py-2"
                data-testid={`browser-recipe-${r.id}`}
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
                      aria-pressed={watch?.id === r.id}
                      onClick={() => toggleWatch(r)}
                    >
                      <Eye aria-hidden="true" />
                      {watch?.id === r.id ? t("watchStop") : t("watch")}
                    </Button>
                    {confirmId === r.id ? (
                      <Button
                        type="button"
                        variant="destructive"
                        size="sm"
                        disabled={busy || locked}
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
                        disabled={busy || locked}
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
                  {r.parameters.length > 0
                    ? t("recipeParams", { names: r.parameters.join(", ") })
                    : t("recipeNoParams")}{" "}
                  · {r.runCount > 0 ? t("recipeRuns", { runs: r.runCount, fails: r.failCount }) : t("recipeNeverRun")}
                </div>
                {r.lastFailure && (
                  <div className="mt-0.5 text-xs text-destructive">
                    {t("recipeLastFailure", { reason: r.lastFailure })}
                  </div>
                )}
                {watch?.id === r.id && (
                  <div
                    className="mt-2 rounded-md border border-dashed border-border px-3 py-2 text-[11px]"
                    data-testid={`browser-recipe-watch-${r.id}`}
                  >
                    <div className="flex items-center gap-2 font-medium text-foreground">
                      <Loader2 className="size-3.5 animate-spin text-muted-foreground" aria-hidden="true" />
                      {t("watchTitle")}
                    </div>
                    {watchEntries.length === 0 ? (
                      <p className="mt-1 text-muted-foreground">{t("watchEmpty")}</p>
                    ) : (
                      <ul className="mt-1 space-y-0.5" aria-live="polite">
                        {watchEntries.map((e) => {
                          const running = e.completedAt === null;
                          const failedEntry = !!e.errorMessage;
                          return (
                            <li key={e.id} className={failedEntry ? "text-destructive" : "text-muted-foreground"}>
                              <span className="mr-1 inline-flex align-middle">
                                {running ? (
                                  <Loader2 className="size-3 animate-spin" aria-hidden="true" />
                                ) : failedEntry ? (
                                  <X className="size-3" aria-hidden="true" />
                                ) : (
                                  <Check className="size-3" aria-hidden="true" />
                                )}
                              </span>
                              {running ? t("watchRunning") : failedEntry ? t("watchFailed") : t("watchDone")}
                              {` · ${new Date(e.startedAt).toLocaleTimeString()}`}
                              {failedEntry && e.errorMessage ? ` — ${e.errorMessage}` : ""}
                            </li>
                          );
                        })}
                      </ul>
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
