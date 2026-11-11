"use client";

import { useRef, useState } from "react";
import { api } from "../../lib/api";
import type { DesktopRecipeDetail, DesktopRecipeMode } from "../../lib/api/desktopRecipes";
import { buildParameterMap, type ParamProblemKind, type ParamRow } from "../../lib/browserAutomation";
import { useTranslations } from "../../lib/i18n";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { Select } from "../ui/select";
import { useDesktopAvailability } from "./useDesktopAvailability";

// "Save this run as a desktop recipe": shown under a finished turn that drove the desktop agent (two or
// more desktop_* action calls) when desktop recipes are switched on. Core reads the run's tool calls
// from its journal (POST /api/desktop/recipes/from-run); the person only names the recipe and lists the
// values they supplied so those become parameters. Those values are sent once for substitution and are
// never stored. Mirrors SaveRunAsRecipe; the parameter rules (names, reserved words, minimum length)
// are the same, so the shared buildParameterMap is reused as-is.

// The local_desktop_* calls that DO something on screen - the ones worth recording into a recipe.
// Pure reads (local_desktop_screen / local_desktop_elements) and notifications don't count toward
// "the run drove the desktop".
const DESKTOP_ACTION_TOOLS = new Set([
  "local_desktop_open",
  "local_desktop_click",
  "local_desktop_type",
  "local_desktop_press",
  "local_desktop_key",
  "local_desktop_activate",
]);

/** Any local_desktop_* step at all - including the read calls, since the agent looking at the
 * screen is exactly when the person wants the live view too. */
export function usesDesktopTools(steps: TraceStepLike[] | null | undefined): boolean {
  return !!steps && steps.some((s) => s.type === "tool_call" && !!s.tool && s.tool.startsWith("local_desktop_"));
}

interface TraceStepLike {
  type: string;
  tool?: string;
}

/** How many desktop actions (open / click / type / press / key / activate) a run's activity trace shows. */
export function desktopActionCount(steps: TraceStepLike[] | null | undefined): number {
  if (!steps) return 0;
  return steps.filter((s) => s.type === "tool_call" && !!s.tool && DESKTOP_ACTION_TOOLS.has(s.tool)).length;
}

/** True when a finished run drove the desktop, so offering "save this run as a recipe" makes sense. */
export function isDesktopRun(steps: TraceStepLike[] | null | undefined): boolean {
  return desktopActionCount(steps) >= 2;
}

const PROBLEM_KEY: Record<ParamProblemKind, string> = {
  badName: "paramBadName",
  reservedName: "paramReserved",
  duplicateName: "paramDuplicate",
  shortValue: "paramShort",
};

export default function SaveRunAsDesktopRecipe({
  agentId,
  sessionId,
  steps,
}: {
  agentId: string;
  sessionId: string | null | undefined;
  steps: { type: string; tool?: string }[] | undefined;
}) {
  const t = useTranslations("desktopAutomation");
  const available = useDesktopAvailability();
  const nextRowId = useRef(1);
  const [open, setOpen] = useState(false);
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [rows, setRows] = useState<ParamRow[]>([]);
  const [allowLiteral, setAllowLiteral] = useState(false);
  const [defaultMode, setDefaultMode] = useState<DesktopRecipeMode>("AUTO");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState<DesktopRecipeDetail | null>(null);

  if (!available || !sessionId || !isDesktopRun(steps)) return null;
  const runSessionId: string = sessionId;

  const addRow = () => {
    const id = `row-${nextRowId.current++}`;
    setRows((current) => [...current, { id, name: "", value: "" }]);
  };

  const patchRow = (id: string, patch: Partial<ParamRow>) =>
    setRows((current) => current.map((r) => (r.id === id ? { ...r, ...patch } : r)));

  const submit = async () => {
    if (busy) return;
    const recipeName = name.trim();
    if (!recipeName) {
      setError(t("needRecipeName"));
      return;
    }
    const { parameters, problems } = buildParameterMap(rows);
    if (problems.length > 0) {
      setError(t(PROBLEM_KEY[problems[0].kind]));
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const detail = await api.saveDesktopRunAsRecipe({
        agentId,
        sessionId: runSessionId,
        name: recipeName,
        description: description.trim() || undefined,
        parameters,
        allowLiteralText: allowLiteral,
        defaultMode,
      });
      setSaved(detail);
      setOpen(false);
      setName("");
      setDescription("");
      setRows([]);
      setAllowLiteral(false);
      setDefaultMode("AUTO");
    } catch (e: unknown) {
      // api.ts has raised the toast (with the server's reason, e.g. which step typed an unlabelled
      // literal); repeat it inline so it is still there after the toast fades.
      setError(e instanceof Error && e.message ? e.message : t("actionFailed"));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="w-full max-w-[85%] text-xs" data-testid="save-run-as-desktop-recipe">
      {saved && (
        <div role="status" className="mb-1 rounded-md border border-border bg-secondary/30 px-3 py-2">
          <div className="text-foreground">
            {t("recipeSaved", { name: saved.summary.name, count: saved.summary.stepCount })}
          </div>
          {saved.warnings.length > 0 && (
            <div className="mt-1 text-muted-foreground">
              <div className="font-medium">{t("saveWarnings")}</div>
              <ul className="mt-0.5 list-disc ps-4">
                {saved.warnings.map((w) => (
                  <li key={w}>{w}</li>
                ))}
              </ul>
            </div>
          )}
        </div>
      )}

      {!open ? (
        <Button type="button" variant="outline" size="sm" onClick={() => setOpen(true)}>
          {t("saveRun")}
        </Button>
      ) : (
        <div className="space-y-2 rounded-md border border-border bg-card px-3 py-2">
          <div className="font-medium text-foreground">{t("saveRunTitle")}</div>
          <p className="text-muted-foreground">{t("saveRunIntro")}</p>
          <label className="block text-muted-foreground">
            {t("recipeName")}
            <Input
              className="mt-1 h-8 text-xs"
              value={name}
              maxLength={120}
              placeholder={t("recipeNamePlaceholder")}
              disabled={busy}
              onChange={(e) => setName(e.target.value)}
            />
          </label>
          <label className="block text-muted-foreground">
            {t("recipeDescription")}
            <Input
              className="mt-1 h-8 text-xs"
              value={description}
              disabled={busy}
              onChange={(e) => setDescription(e.target.value)}
            />
          </label>

          <div>
            <div className="font-medium text-foreground">{t("paramsTitle")}</div>
            <p className="text-muted-foreground">{t("paramsHelp")}</p>
            {rows.map((row) => (
              <div key={row.id} className="mt-1.5 grid grid-cols-[minmax(0,1fr)_minmax(0,2fr)_auto] items-center gap-2">
                <Input
                  className="h-8 text-xs"
                  aria-label={t("paramName")}
                  placeholder={t("paramName")}
                  value={row.name}
                  disabled={busy}
                  onChange={(e) => patchRow(row.id, { name: e.target.value })}
                />
                <Input
                  className="h-8 text-xs"
                  aria-label={t("paramValue")}
                  placeholder={t("paramValue")}
                  value={row.value}
                  disabled={busy}
                  onChange={(e) => patchRow(row.id, { value: e.target.value })}
                />
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  disabled={busy}
                  onClick={() => setRows((current) => current.filter((r) => r.id !== row.id))}
                >
                  {t("removeParam")}
                </Button>
              </div>
            ))}
            <Button type="button" variant="outline" size="sm" className="mt-1.5" disabled={busy} onClick={addRow}>
              {t("addParam")}
            </Button>
          </div>

          <label className="flex items-start gap-2 text-muted-foreground">
            <input
              type="checkbox"
              className="mt-0.5"
              checked={allowLiteral}
              disabled={busy}
              onChange={(e) => setAllowLiteral(e.target.checked)}
            />
            <span>{t("allowLiteral")}</span>
          </label>

          <div className="text-muted-foreground">
            {t("defaultModeLabel")}
            <Select
              className="mt-1 [&_button]:h-8 [&_button]:text-xs"
              aria-label={t("defaultModeLabel")}
              value={defaultMode}
              disabled={busy}
              onValueChange={(v) => setDefaultMode(v === "MONITOR" ? "MONITOR" : "AUTO")}
              options={[
                { value: "AUTO", label: t("modeAuto") },
                { value: "MONITOR", label: t("modeMonitor") },
              ]}
            />
            <span className="mt-0.5 block text-[11px] text-muted-foreground">
              {defaultMode === "MONITOR" ? t("modeMonitorHint") : t("modeAutoHint")}
            </span>
          </div>

          {error && (
            <p role="alert" className="text-destructive">
              {error}
            </p>
          )}
          <div className="flex items-center gap-2">
            <Button type="button" size="sm" disabled={busy} onClick={() => void submit()}>
              {busy ? t("savingRecipe") : t("saveRecipe")}
            </Button>
            <Button type="button" variant="ghost" size="sm" disabled={busy} onClick={() => setOpen(false)}>
              {t("cancel")}
            </Button>
          </div>
        </div>
      )}
    </div>
  );
}
