"use client";

import { useState } from "react";
import { Braces } from "lucide-react";
import { Button } from "../../components/ui/button";
import { Input } from "../../components/ui/input";
import { Textarea } from "../../components/ui/textarea";
import { api } from "../../lib/api";
import { useTranslations } from "../../lib/i18n";
import { cn } from "../../lib/utils";
import {
  buildSpec,
  EMPTY_FORM,
  formFromSpec,
  isFormDirty,
  TRAJECTORY_MODES,
  type EditorForm,
  type FormError,
  type TrajectoryMode,
} from "./trajectoryEval";
import { useTrajectoryConfig } from "./useTrajectoryConfig";
import { Select } from "../../components/ui/select";

interface LoadedCase {
  trajectory: unknown;
  hasOtherGrader: boolean;
}

const MODE_KEYS: Record<TrajectoryMode, string> = {
  exact: "modeExact",
  in_order: "modeInOrder",
  any_order: "modeAnyOrder",
  f1: "modeF1",
};

/**
 * The expected tool-call trajectory of ONE dataset case: which tools the agent should call, in what order,
 * with which arguments, and how strictly to compare. Mounted inside each case row of the eval-datasets section;
 * it renders nothing at all unless the trajectory feature is on for this deployment and plan.
 *
 * Nothing is fetched until the row is opened (a dataset can have a hundred cases); the config check is shared.
 * Steps are typed one per line - a tool name, then optionally a JSON object of argument matchers - and the server
 * validates the whole spec (regexes included) on save, its message shown verbatim.
 */
export default function TrajectoryExpectedEditor({
  agentId,
  datasetId,
  caseId,
  toolExecutionEnabled,
}: {
  agentId: string;
  datasetId: string;
  caseId: string;
  /** The dataset replays with tools; without them a trajectory is saved but cannot be scored. */
  toolExecutionEnabled: boolean;
}) {
  const t = useTranslations("trajectoryEval");
  const config = useTrajectoryConfig(agentId);
  const [open, setOpen] = useState(false);
  const [loaded, setLoaded] = useState<LoadedCase | null>(null);
  const [loadFailed, setLoadFailed] = useState(false);
  const [form, setForm] = useState<EditorForm>(EMPTY_FORM);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ kind: "error" | "saved"; text: string } | null>(null);

  if (!config) return null;

  function load() {
    setLoadFailed(false);
    api
      .getTrajectoryEvalCase(agentId, datasetId, caseId)
      .then((raw: any) => {
        const next: LoadedCase = {
          trajectory: raw && raw.trajectory ? raw.trajectory : null,
          hasOtherGrader: !!(raw && raw.hasOtherGrader),
        };
        setLoaded(next);
        setForm(formFromSpec(next.trajectory));
      })
      .catch(() => setLoadFailed(true));
  }

  function toggle() {
    const next = !open;
    setOpen(next);
    setMessage(null);
    if (next && !loaded) load();
  }

  function errorText(error: FormError, line: number): string {
    switch (error) {
      case "minScore":
        return t("errorMinScore");
      case "maxExtraCalls":
        return t("errorMaxExtra");
      case "minEfficiency":
        return t("errorMinEfficiency");
      case "argsJson":
        return t("errorArgsJson", { line: String(line) });
      case "argsObject":
        return t("errorArgsObject", { line: String(line) });
      case "argKey":
        return t("errorArgKey", { line: String(line) });
      case "tooManySteps":
        return t("errorTooManySteps", { line: String(line) });
      case "tooManyArgs":
        return t("errorTooManyArgs", { line: String(line) });
      default:
        return t("errorToolName", { line: String(line) });
    }
  }

  async function persist(trajectory: unknown, successKey: "saved" | "cleared") {
    if (busy) return;
    setBusy(true);
    setMessage(null);
    try {
      const raw: any = await api.setTrajectoryEvalCase(agentId, datasetId, caseId, trajectory);
      const next: LoadedCase = {
        trajectory: raw && raw.trajectory ? raw.trajectory : null,
        hasOtherGrader: !!(raw && raw.hasOtherGrader),
      };
      setLoaded(next);
      setForm(formFromSpec(next.trajectory));
      setMessage({ kind: "saved", text: t(successKey) });
    } catch (err: any) {
      // 400 carries the server's precise reason; 409 says the case already has a response grader.
      setMessage({ kind: "error", text: err?.message || t("saveFailed") });
    } finally {
      setBusy(false);
    }
  }

  const built = buildSpec(form, config);
  const dirty = loaded ? isFormDirty(form, loaded.trajectory) : false;
  const inlineError = built.error ? errorText(built.error, built.line) : null;
  const fieldId = `trajectory-${caseId}`;

  return (
    <div className="mt-1" data-testid={`trajectory-editor-${caseId}`}>
      <Button
        type="button"
        variant="ghost"
        size="sm"
        className="h-6 px-1.5 text-xs text-muted-foreground"
        aria-expanded={open}
        aria-controls={`${fieldId}-panel`}
        onClick={toggle}
      >
        <Braces size={12} aria-hidden="true" /> {t("editToggle")}
        {loaded && loaded.trajectory ? (
          <span className="ms-1 text-foreground">({formFromSpec(loaded.trajectory).mode})</span>
        ) : null}
      </Button>

      {open && (
        <div id={`${fieldId}-panel`} className="mt-2 space-y-2 rounded-md border border-border p-3">
          <p className="text-xs text-muted-foreground">{t("intro")}</p>
          {loadFailed && (
            <p className="text-xs text-destructive" role="alert">
              {t("loadFailed")}{" "}
              <Button type="button" variant="outline" size="sm" onClick={load}>
                {t("retry")}
              </Button>
            </p>
          )}
          {!loaded && !loadFailed && <p className="text-xs text-muted-foreground">{t("loading")}</p>}
          {loaded && loaded.hasOtherGrader && (
            <p className="text-xs text-muted-foreground" data-testid={`${fieldId}-other-grader`}>
              {t("otherGrader")}
            </p>
          )}
          {loaded && !toolExecutionEnabled && <p className="text-xs text-muted-foreground">{t("toollessHint")}</p>}

          {loaded && !loaded.hasOtherGrader && (
            <>
              <div className="grid gap-2 sm:grid-cols-[minmax(0,2fr)_minmax(0,1fr)_minmax(0,1fr)_minmax(0,1fr)]">
                <div>
                  <label htmlFor={`${fieldId}-mode`} className="mb-1 block text-xs text-muted-foreground">
                    {t("modeLabel")}
                  </label>
                  <Select
                    id={`${fieldId}-mode`}
                    className="[&_button]:h-9 [&_button]:text-xs"
                    value={form.mode}
                    disabled={busy}
                    onValueChange={(v) => setForm({ ...form, mode: v as TrajectoryMode })}
                    options={TRAJECTORY_MODES.map((m) => ({ value: m, label: t(MODE_KEYS[m]) }))}
                  />
                </div>
                <div>
                  <label htmlFor={`${fieldId}-min-score`} className="mb-1 block text-xs text-muted-foreground">
                    {t("minScoreLabel")}
                  </label>
                  <Input
                    id={`${fieldId}-min-score`}
                    className="h-9 text-xs"
                    inputMode="decimal"
                    value={form.minScore}
                    disabled={busy}
                    onChange={(e) => setForm({ ...form, minScore: e.target.value })}
                  />
                </div>
                <div>
                  <label htmlFor={`${fieldId}-max-extra`} className="mb-1 block text-xs text-muted-foreground">
                    {t("maxExtraLabel")}
                  </label>
                  <Input
                    id={`${fieldId}-max-extra`}
                    className="h-9 text-xs"
                    inputMode="numeric"
                    placeholder={t("maxExtraPlaceholder")}
                    value={form.maxExtraCalls}
                    disabled={busy}
                    onChange={(e) => setForm({ ...form, maxExtraCalls: e.target.value })}
                  />
                </div>
                <div>
                  <label htmlFor={`${fieldId}-min-eff`} className="mb-1 block text-xs text-muted-foreground">
                    {t("minEfficiencyLabel")}
                  </label>
                  <Input
                    id={`${fieldId}-min-eff`}
                    className="h-9 text-xs"
                    inputMode="decimal"
                    placeholder={t("minEfficiencyPlaceholder")}
                    value={form.minEfficiency}
                    disabled={busy}
                    onChange={(e) => setForm({ ...form, minEfficiency: e.target.value })}
                  />
                </div>
              </div>

              <div>
                <label htmlFor={`${fieldId}-steps`} className="mb-1 block text-xs text-muted-foreground">
                  {t("stepsLabel")}
                </label>
                <Textarea
                  id={`${fieldId}-steps`}
                  className={cn("min-h-24 font-mono text-xs")}
                  rows={5}
                  spellCheck={false}
                  placeholder={t("stepsPlaceholder")}
                  value={form.stepsText}
                  disabled={busy}
                  aria-invalid={inlineError ? true : undefined}
                  onChange={(e) => setForm({ ...form, stepsText: e.target.value })}
                />
                <p className="mt-1 text-xs text-muted-foreground">{t("stepsHelp")}</p>
              </div>

              {inlineError && (
                <p className="text-xs text-destructive" role="alert" data-testid={`${fieldId}-invalid`}>
                  {inlineError}
                </p>
              )}

              <div className="flex flex-wrap items-center gap-2">
                <Button
                  type="button"
                  size="sm"
                  disabled={busy || !dirty || built.spec == null}
                  onClick={() => {
                    if (built.spec) void persist(built.spec, "saved");
                  }}
                >
                  {t("save")}
                </Button>
                {loaded.trajectory ? (
                  <Button
                    type="button"
                    size="sm"
                    variant="outline"
                    disabled={busy}
                    onClick={() => void persist(null, "cleared")}
                  >
                    {t("clear")}
                  </Button>
                ) : null}
              </div>
            </>
          )}

          {message && (
            <p
              className={message.kind === "error" ? "text-xs text-destructive" : "text-xs text-muted-foreground"}
              role={message.kind === "error" ? "alert" : "status"}
            >
              {message.text}
            </p>
          )}
        </div>
      )}
    </div>
  );
}
