"use client";

import { useId, useMemo, useRef, useState, type ChangeEvent } from "react";
import { ShieldAlert, TriangleAlert } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import type { EffectiveSetting, SettingCatalogEntry } from "../../lib/api/settings";
import {
  AlertDialog,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "../../components/ui/alert-dialog";
import { Button } from "../../components/ui/button";
import { Checkbox } from "../../components/ui/checkbox";
import { Label } from "../../components/ui/label";
import { Textarea } from "../../components/ui/textarea";
import { useSettingsText } from "../../components/settings/useSettingsText";
import { needsReason } from "../../components/settings/settingsModel";
import { parseOverridesText, planImport, type ImportPlan, type ImportProblem } from "./overridesModel";

/** Read a picked file as text (Blob.text where it exists, FileReader otherwise). */
export function readFileText(file: File): Promise<string> {
  if (typeof file.text === "function") return file.text();
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result ?? ""));
    reader.onerror = () => reject(reader.error ?? new Error("read failed"));
    reader.readAsText(file);
  });
}

/**
 * Import of workspace overrides from a JSON file. Step 1: choose a file or paste its text. Step 2: a review of
 * exactly what would change (current value to new value for each setting, the overrides that would be removed if
 * asked, and every entry of the file that cannot be applied with the reason). Nothing is written until Apply; a
 * change to a high-risk or security setting then asks for its reason like any other edit.
 */
export default function ImportOverridesDialog({
  open,
  onOpenChange,
  entries,
  values,
  onApply,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  entries: SettingCatalogEntry[];
  values: Record<string, EffectiveSetting>;
  onApply: (plan: ImportPlan) => Promise<void>;
}) {
  const t = useTranslations("runtimeSettings");
  const { label, formatValue } = useSettingsText();
  const [text, setText] = useState("");
  const [fileName, setFileName] = useState("");
  const [overrides, setOverrides] = useState<Record<string, unknown> | null>(null);
  const [error, setError] = useState("");
  const [resetMissing, setResetMissing] = useState(false);
  const [busy, setBusy] = useState(false);
  const fileRef = useRef<HTMLInputElement>(null);
  const fileId = useId();
  const textId = useId();
  const errorId = useId();

  const plan = useMemo(
    () => (overrides ? planImport(entries, values, overrides, { resetMissing }) : null),
    [overrides, entries, values, resetMissing]
  );
  const byKey = useMemo(() => new Map(entries.map((e) => [e.key, e])), [entries]);

  const close = () => {
    if (busy) return;
    setText("");
    setFileName("");
    setOverrides(null);
    setError("");
    setResetMissing(false);
    onOpenChange(false);
  };

  const review = (source: string) => {
    const parsed = parseOverridesText(source);
    if ("error" in parsed) {
      setOverrides(null);
      setError(t(`import.fileError.${parsed.error}`));
      return;
    }
    setError("");
    setOverrides(parsed.overrides);
  };

  const onFile = async (e: ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    try {
      const content = await readFileText(file);
      setFileName(file.name);
      setText(content);
      review(content);
    } catch {
      setOverrides(null);
      setError(t("import.fileError.unreadable"));
    }
  };

  const bound = (entry: SettingCatalogEntry | undefined, n: number | undefined): string =>
    n == null ? "" : !entry || entry.type === "string" || entry.type === "text" ? String(n) : formatValue(entry, n);

  const problemText = (p: ImportProblem): string =>
    t(`import.problem.${p.code}`, { min: bound(byKey.get(p.key), p.min), max: bound(byKey.get(p.key), p.max) });

  const total = plan ? plan.changes.length + plan.resets.length : 0;
  const reasonNeeded = plan ? plan.changes.some((c) => needsReason(c.entry)) : false;

  const apply = async () => {
    if (!plan || total === 0) return;
    setBusy(true);
    try {
      await onApply(plan);
      setText("");
      setFileName("");
      setOverrides(null);
      setResetMissing(false);
      onOpenChange(false);
    } catch {
      // The page reports per-setting failures itself; keep the review open so nothing is lost.
    } finally {
      setBusy(false);
    }
  };

  return (
    <AlertDialog open={open} onOpenChange={(o) => (o ? onOpenChange(true) : close())}>
      <AlertDialogContent className="max-w-2xl" data-testid="import-dialog">
        <AlertDialogHeader>
          <AlertDialogTitle>{t("import.title")}</AlertDialogTitle>
          <AlertDialogDescription>{plan ? t("import.reviewIntro") : t("import.intro")}</AlertDialogDescription>
        </AlertDialogHeader>

        {!plan ? (
          <div className="space-y-3">
            <div className="space-y-1.5">
              <Label htmlFor={fileId}>{t("import.fileLabel")}</Label>
              <input
                ref={fileRef}
                id={fileId}
                type="file"
                accept="application/json,.json"
                onChange={(e) => void onFile(e)}
                className="block w-full text-sm file:me-3 file:rounded-md file:border file:border-border file:bg-secondary file:px-3 file:py-1.5 file:text-sm file:font-medium"
                data-testid="import-file"
              />
              {fileName ? <p className="text-xs text-muted-foreground">{fileName}</p> : null}
            </div>
            <div className="space-y-1.5">
              <Label htmlFor={textId}>{t("import.pasteLabel")}</Label>
              <Textarea
                id={textId}
                rows={7}
                className="font-mono text-xs"
                spellCheck={false}
                value={text}
                aria-invalid={error ? true : undefined}
                aria-describedby={error ? errorId : undefined}
                onChange={(e) => {
                  setText(e.target.value);
                  setError("");
                }}
                data-testid="import-text"
              />
            </div>
            <p id={errorId} role="alert" className="min-h-5 text-sm text-destructive" data-testid="import-error">
              {error}
            </p>
          </div>
        ) : (
          <div className="space-y-4" data-testid="import-review">
            <p className="text-sm" role="status" aria-live="polite" data-testid="import-summary">
              {t("import.summary", {
                changes: plan.changes.length,
                unchanged: plan.unchanged.length,
                problems: plan.problems.length,
              })}
            </p>

            {plan.changes.length > 0 ? (
              <section aria-labelledby={`${errorId}-c`} className="space-y-1.5">
                <h3 id={`${errorId}-c`} className="text-sm font-semibold">
                  {t("import.changesHeading", { count: plan.changes.length })}
                </h3>
                <ul
                  className="divide-y divide-border rounded-md border border-border text-sm"
                  data-testid="import-changes"
                >
                  {plan.changes.map((c) => (
                    <li
                      key={c.entry.key}
                      className="flex flex-col gap-0.5 p-2"
                      data-testid={`import-change-${c.entry.key}`}
                    >
                      <span className="font-medium">
                        {label(c.entry)}
                        {c.entry.risk === "security" || c.entry.risk === "high" ? (
                          <ShieldAlert
                            aria-label={t(`risk.${c.entry.risk}`)}
                            className="ms-1.5 inline size-3.5 text-destructive"
                          />
                        ) : null}
                      </span>
                      <span className="text-xs">
                        <span className="sr-only">{t("import.before")} </span>
                        <del className="text-muted-foreground">{formatValue(c.entry, c.from)}</del>
                        <span aria-hidden="true"> → </span>
                        <span className="sr-only">{t("import.after")} </span>
                        <ins className="font-medium no-underline">{formatValue(c.entry, c.to)}</ins>
                      </span>
                    </li>
                  ))}
                </ul>
              </section>
            ) : null}

            <div className="flex items-start gap-2">
              <Checkbox
                id={`${fileId}-reset`}
                checked={resetMissing}
                onCheckedChange={setResetMissing}
                aria-describedby={`${fileId}-reset-help`}
                data-testid="import-reset-missing"
              />
              <div>
                <Label htmlFor={`${fileId}-reset`}>{t("import.resetMissing")}</Label>
                <p id={`${fileId}-reset-help`} className="text-xs text-muted-foreground">
                  {t("import.resetMissingHelp")}
                </p>
              </div>
            </div>

            {plan.resets.length > 0 ? (
              <section aria-labelledby={`${errorId}-r`} className="space-y-1.5">
                <h3 id={`${errorId}-r`} className="text-sm font-semibold">
                  {t("import.resetsHeading", { count: plan.resets.length })}
                </h3>
                <ul
                  className="divide-y divide-border rounded-md border border-border text-sm"
                  data-testid="import-resets"
                >
                  {plan.resets.map((r) => (
                    <li key={r.entry.key} className="flex flex-col gap-0.5 p-2">
                      <span className="font-medium">{label(r.entry)}</span>
                      <span className="text-xs text-muted-foreground">
                        {t("import.resetFrom", { value: formatValue(r.entry, r.from) })}
                      </span>
                    </li>
                  ))}
                </ul>
              </section>
            ) : null}

            {plan.problems.length > 0 ? (
              <section aria-labelledby={`${errorId}-p`} className="space-y-1.5">
                <h3 id={`${errorId}-p`} className="flex items-center gap-1.5 text-sm font-semibold text-destructive">
                  <TriangleAlert aria-hidden="true" className="size-4" />
                  {t("import.problemsHeading", { count: plan.problems.length })}
                </h3>
                <ul
                  className="divide-y divide-border rounded-md border border-destructive/40 text-sm"
                  data-testid="import-problems"
                >
                  {plan.problems.map((p) => (
                    <li key={p.key} className="flex flex-col gap-0.5 p-2" data-testid={`import-problem-${p.key}`}>
                      <span className="font-mono text-xs">{p.key}</span>
                      <span className="text-xs text-muted-foreground">{problemText(p)}</span>
                    </li>
                  ))}
                </ul>
              </section>
            ) : null}

            {total === 0 ? (
              <p className="text-sm text-muted-foreground" data-testid="import-nothing">
                {t("import.nothing")}
              </p>
            ) : reasonNeeded ? (
              <p className="text-xs text-muted-foreground">{t("import.reasonNote")}</p>
            ) : null}
          </div>
        )}

        <AlertDialogFooter>
          {plan ? (
            <Button type="button" variant="outline" disabled={busy} onClick={() => setOverrides(null)}>
              {t("import.back")}
            </Button>
          ) : null}
          <AlertDialogCancel asChild>
            <Button type="button" variant="outline" disabled={busy} onClick={close}>
              {t("import.cancel")}
            </Button>
          </AlertDialogCancel>
          {plan ? (
            <Button
              type="button"
              disabled={busy || total === 0}
              onClick={() => void apply()}
              data-testid="import-apply"
            >
              {t("import.apply", { count: total })}
            </Button>
          ) : (
            <Button
              type="button"
              disabled={text.trim() === ""}
              onClick={() => review(text)}
              data-testid="import-review-button"
            >
              {t("import.review")}
            </Button>
          )}
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
