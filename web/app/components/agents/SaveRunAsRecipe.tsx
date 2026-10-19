"use client";

import { useRef, useState } from "react";
import { api } from "../../lib/api";
import type { BrowserRecipeDetail } from "../../lib/api/browserAutomation";
import { buildParameterMap, isBrowserRun, type ParamProblemKind, type ParamRow } from "../../lib/browserAutomation";
import { useTranslations } from "../../lib/i18n";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { useBrowserCapabilities } from "./useBrowserCapabilities";

// "Save this run as a recipe": shown under a finished turn that drove the browser (two or more
// browser_navigate / browser_click / browser_type calls) when recipes are switched on. Core reads the
// run's tool calls from its journal (POST /api/browser/recipes/from-run); the person only names the
// recipe and lists the values they supplied in this run so those become parameters. Those values are
// sent once for substitution and are never stored.

const PROBLEM_KEY: Record<ParamProblemKind, string> = {
  badName: "paramBadName",
  reservedName: "paramReserved",
  duplicateName: "paramDuplicate",
  shortValue: "paramShort",
};

export default function SaveRunAsRecipe({
  agentId,
  sessionId,
  steps,
}: {
  agentId: string;
  sessionId: string | null | undefined;
  steps: { type: string; tool?: string }[] | undefined;
}) {
  const t = useTranslations("browserAutomation");
  const caps = useBrowserCapabilities();
  const nextRowId = useRef(1);
  const [open, setOpen] = useState(false);
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [rows, setRows] = useState<ParamRow[]>([]);
  const [allowLiteral, setAllowLiteral] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState<BrowserRecipeDetail | null>(null);

  if (!caps || !caps.recipesEnabled || !sessionId || !isBrowserRun(steps)) return null;
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
      const detail = await api.saveBrowserRunAsRecipe({
        agentId,
        sessionId: runSessionId,
        name: recipeName,
        description: description.trim() || undefined,
        parameters,
        allowLiteralText: allowLiteral,
      });
      setSaved(detail);
      setOpen(false);
      setName("");
      setDescription("");
      setRows([]);
      setAllowLiteral(false);
    } catch (e: unknown) {
      // api.ts has raised the toast (with the server's reason, e.g. which step typed an unlabelled literal);
      // repeat it inline so it is still there after the toast fades.
      setError(e instanceof Error && e.message ? e.message : t("actionFailed"));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="w-full max-w-[85%] text-xs" data-testid="save-run-as-recipe">
      {saved && (
        <div role="status" className="mb-1 rounded-md border border-border bg-secondary/30 px-3 py-2">
          <div className="text-foreground">{t("recipeSaved", { name: saved.summary.name, count: saved.summary.stepCount })}</div>
          {saved.warnings.length > 0 && (
            <div className="mt-1 text-muted-foreground">
              <div className="font-medium">{t("saveWarnings")}</div>
              <ul className="mt-0.5 list-disc pl-4">
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
            <Input className="mt-1 h-8 text-xs" value={description} disabled={busy} onChange={(e) => setDescription(e.target.value)} />
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
            <input type="checkbox" className="mt-0.5" checked={allowLiteral} disabled={busy} onChange={(e) => setAllowLiteral(e.target.checked)} />
            <span>{t("allowLiteral")}</span>
          </label>

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
