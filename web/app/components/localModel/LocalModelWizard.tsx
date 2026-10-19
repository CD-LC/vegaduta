"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { AlertTriangle, CheckCircle2, Loader2, RefreshCw } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { cn } from "../../lib/utils";
import { Alert } from "../ui/alert";
import { Button } from "../ui/button";
import { Card } from "../ui/card";
import { stepIndex, type WizardState, type WizardStep } from "./wizardMachine";
import { useLocalModelWizard, type UseLocalModelWizardOptions } from "./useLocalModelWizard";
import { GRADE_KEY, ReadyPanel, type T } from "./LocalModelChoices";

export { OLLAMA_INSTALL_LINKS, detectOs } from "./ollamaInstall";

const STEP_ERROR_KEY: Record<WizardStep, "errorTitleDetect" | "errorTitlePull" | "errorTitleProbe" | "errorTitleCreate"> = {
  detect: "errorTitleDetect",
  pull: "errorTitlePull",
  probe: "errorTitleProbe",
  create: "errorTitleCreate",
};

export interface LocalModelWizardProps extends UseLocalModelWizardOptions {
  className?: string;
}

export default function LocalModelWizard({ className, ...options }: LocalModelWizardProps) {
  const t = useTranslations("localModelWizard");
  const { state, choice, select, acknowledge, start, recheck } = useLocalModelWizard({
    agentName: (tag) => t("agentName", { tag }),
    agentPrompt: t("agentPrompt"),
    ...options,
  });
  const announcement = useProgressAnnouncement(state, t);

  const steps = [t("stepDetect"), t("stepDownload"), t("stepTest"), t("stepCreate")];
  const current = stepIndex(state);

  return (
    <Card className={cn("flex flex-col gap-5 p-5 sm:p-6", className)} data-testid="local-model-wizard">
      <header className="flex flex-col gap-1">
        <h2 className="text-lg font-semibold text-foreground">{t("title")}</h2>
        <p className="text-sm text-muted-foreground">{t("subtitle")}</p>
      </header>

      <ol aria-label={t("stepsLabel")} className="flex flex-wrap gap-x-4 gap-y-1 text-xs">
        {steps.map((label, i) => (
          <li
            key={label}
            aria-current={i === current && state.phase !== "done" ? "step" : undefined}
            className={cn(
              "flex items-center gap-1.5",
              i === current && state.phase !== "done" ? "font-semibold text-foreground" : "text-muted-foreground",
            )}
          >
            <span
              aria-hidden="true"
              className={cn(
                "grid size-5 place-items-center rounded-full border text-[11px]",
                i < current || state.phase === "done"
                  ? "border-primary bg-primary text-primary-foreground"
                  : "border-border",
              )}
            >
              {i + 1}
            </span>
            {label}
            {i < current || state.phase === "done" ? <span className="sr-only">({t("stepDone")})</span> : null}
            {i === current && state.phase !== "done" ? <span className="sr-only">({t("stepCurrent")})</span> : null}
          </li>
        ))}
      </ol>

      {/* One polite live region for every phase change and coarse download progress. */}
      <p role="status" aria-live="polite" className="sr-only" data-testid="local-model-live">
        {announcement}
      </p>

      {state.phase === "detecting" && (
        <p className="flex items-center gap-2 text-sm text-muted-foreground" data-testid="lm-detecting">
          <Loader2 className="size-4 animate-spin" aria-hidden="true" />
          {t("detecting")}
        </p>
      )}

      {state.phase === "ready" && state.recommendation && (
        <ReadyPanel state={state} onSelect={select} onAcknowledge={acknowledge} onStart={start} onRecheck={recheck} t={t} />
      )}

      {state.phase === "pulling" && choice && (
        <div className="flex flex-col gap-2" data-testid="lm-pulling">
          <h3 className="text-sm font-semibold">{t("pullingTitle", { tag: choice.tag })}</h3>
          <div
            role="progressbar"
            aria-label={t("pullProgressAria", { tag: choice.tag })}
            aria-valuemin={0}
            aria-valuemax={100}
            aria-valuenow={Math.round(state.percent)}
            className="h-2 w-full overflow-hidden rounded-full bg-muted"
          >
            <div className="h-full bg-primary transition-[width] duration-300" style={{ width: `${state.percent}%` }} />
          </div>
          <p className="text-xs text-muted-foreground tabular-nums">
            {Math.round(state.percent)}%{state.pullMessage ? ` - ${state.pullMessage}` : ""}
          </p>
          <p className="text-xs text-muted-foreground">{t("pullingWait")}</p>
        </div>
      )}

      {state.phase === "probing" && choice && (
        <Busy title={t("probingTitle", { tag: choice.tag })} body={t("probingBody")} testId="lm-probing" />
      )}

      {state.phase === "creating" && choice && (
        <div className="flex flex-col gap-3" data-testid="lm-creating">
          <Busy title={t("creatingTitle")} body={t("creatingBody", { tag: choice.tag })} />
          <ProbeNote state={state} t={t} />
        </div>
      )}

      {state.phase === "done" && state.created && choice && (
        <div className="flex flex-col gap-3" data-testid="lm-done">
          <Alert variant="success">
            <CheckCircle2 aria-hidden="true" />
            <div>
              <p className="font-semibold">{t("doneTitle")}</p>
              <p>{t("doneBody", { name: state.created.agentName, tag: choice.tag })}</p>
            </div>
          </Alert>
          <ProbeNote state={state} t={t} />
          <div className="flex flex-wrap gap-2">
            <Button asChild>
              <Link href={`/agents?agentId=${encodeURIComponent(state.created.agentId)}`}>{t("openAgent")}</Link>
            </Button>
            <Button asChild variant="outline">
              <Link href="/providers">{t("openProviders")}</Link>
            </Button>
          </div>
        </div>
      )}

      {state.phase === "error" && state.error && (
        <div className="flex flex-col gap-3" data-testid="lm-error">
          <Alert variant="destructive">
            <AlertTriangle aria-hidden="true" />
            <div>
              <p className="font-semibold">{t(STEP_ERROR_KEY[state.error.step])}</p>
              <p>{state.error.message || t("errorGeneric")}</p>
            </div>
          </Alert>
          <div>
            <Button variant="outline" onClick={recheck}>
              <RefreshCw size={14} aria-hidden="true" /> {t("tryAgain")}
            </Button>
          </div>
        </div>
      )}
    </Card>
  );
}

function Busy({ title, body, testId }: { title: string; body: string; testId?: string }) {
  return (
    <div className="flex items-start gap-3" data-testid={testId}>
      <Loader2 className="mt-0.5 size-4 shrink-0 animate-spin" aria-hidden="true" />
      <div>
        <h3 className="text-sm font-semibold">{title}</h3>
        <p className="text-sm text-muted-foreground">{body}</p>
      </div>
    </div>
  );
}

function ProbeNote({ state, t }: { state: WizardState; t: T }) {
  if (state.probe) {
    return (
      <p className="text-xs text-muted-foreground" data-testid="lm-probe-summary">
        {t("probeSummary", { grade: t(GRADE_KEY[state.probe.toolCalling]), ms: Math.round(state.probe.latencyMs) })}
      </p>
    );
  }
  if (state.probeFailed) {
    return (
      <p className="text-xs text-muted-foreground" data-testid="lm-probe-unknown">
        {t("probeUnknown")}
      </p>
    );
  }
  return null;
}

/** Screen-reader text for the live region: phase changes immediately, download progress only every 10 percent. */
function useProgressAnnouncement(state: WizardState, t: T): string {
  const [text, setText] = useState("");
  const { phase, percent, selectedTag, error, created } = state;
  const bucket = Math.floor(percent / 10);
  useEffect(() => {
    const tag = selectedTag ?? "";
    switch (phase) {
      case "detecting":
        return setText(t("detecting"));
      case "pulling":
        return setText(t("pullAnnounce", { tag, percent: bucket * 10 }));
      case "probing":
        return setText(t("probingTitle", { tag }));
      case "creating":
        return setText(t("creatingTitle"));
      case "done":
        return setText(t("doneTitle"));
      case "error":
        return setText(error?.message || t("errorGeneric"));
      default:
        return setText("");
    }
  }, [phase, bucket, selectedTag, error, created, t]);
  return text;
}
