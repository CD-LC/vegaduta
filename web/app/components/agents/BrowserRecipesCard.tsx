"use client";

import { useCallback, useEffect, useState } from "react";
import { Loader2 } from "lucide-react";
import { api } from "../../lib/api";
import type { BrowserRecipeSummary } from "../../lib/api/browserAutomation";
import { recipeHealth, recipesForAgent } from "../../lib/browserAutomation";
import { useTranslations } from "../../lib/i18n";
import { Button } from "../ui/button";

// Recorded tasks (core /api/browser/recipes) for one agent: its own recipes plus the workspace-wide ones.
// List and delete only - recipes are created from a finished run (SaveRunAsRecipe, in the playground) and
// hold placeholders, never a secret or a value the user typed. Self-contained like BrowserProfilesCard.

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
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
