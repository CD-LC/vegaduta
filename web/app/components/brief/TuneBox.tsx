"use client";

import { useEffect, useRef, useState } from "react";
import { Loader2, Wand2 } from "lucide-react";
import { api } from "../../lib/api";
import { useTranslations } from "../../lib/i18n";
import { Alert, AlertDescription } from "../ui/alert";
import { Button } from "../ui/button";
import { Textarea } from "../ui/textarea";
import BriefDiffPreview from "./BriefDiffPreview";
import { summariseApply } from "./logic";
import type { ApplyJob, Brief, TuneResult } from "./types";

const POLL_MS = 1500;

type Phase = "idle" | "proposing" | "preview" | "saving" | "applying" | "done";

/**
 * "Tune with a sentence": say what is wrong, read the proposed change in plain
 * words, apply it, and let the ledger re-score. The score is never predicted
 * here; after applying we only say that scoring has been asked for.
 */
export default function TuneBox({
  briefId,
  brief,
  disabled,
  disabledReason,
  onApplied,
}: {
  briefId: string;
  brief: Brief;
  /** True when the person may not change the envoy (missing permission). */
  disabled?: boolean;
  disabledReason?: string;
  onApplied: () => void;
}) {
  const t = useTranslations("brief");
  const [text, setText] = useState("");
  const [phase, setPhase] = useState<Phase>("idle");
  const [result, setResult] = useState<TuneResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const alive = useRef(true);

  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
    };
  }, []);

  async function propose() {
    const instruction = text.trim();
    if (!instruction) return;
    setError(null);
    setPhase("proposing");
    try {
      const r = await api.tuneBrief(briefId, instruction);
      if (!alive.current) return;
      setResult(r);
      setPhase("preview");
    } catch (e: any) {
      if (!alive.current) return;
      setError(e?.message || t("tune.failed"));
      setPhase("idle");
    }
  }

  async function apply() {
    if (!result) return;
    setError(null);
    try {
      // One call: core saves the proposal as the next version (refusing with 409 if the brief moved on since
      // the proposal) and applies it. It never changes the rung.
      setPhase("applying");
      const { jobId } = await api.applyBrief(briefId, { background: true, brief: result.proposedBrief, baseVersion: result.baseVersion, note: text.trim() });
      for (;;) {
        const job: ApplyJob = await api.getApplyJob(briefId, jobId);
        if (!alive.current) return;
        const s = summariseApply(job);
        if (s.terminal) {
          if (job.status !== "SUCCEEDED") throw new Error(s.failedStep?.error || job.error || t("apply.failedGeneric"));
          break;
        }
        await new Promise((r) => setTimeout(r, POLL_MS));
        if (!alive.current) return;
      }
      setPhase("done");
      setResult(null);
      setText("");
      onApplied();
    } catch (e: any) {
      if (!alive.current) return;
      setError(e?.message || t("apply.failedGeneric"));
      setPhase("preview");
    }
  }

  const busy = phase === "saving" || phase === "applying";

  return (
    <section className="space-y-3" aria-label={t("tune.title")}>
      <h3 className="flex items-center gap-2 text-sm font-semibold text-foreground">
        <Wand2 className="size-4" aria-hidden="true" />
        {t("tune.title")}
      </h3>

      {phase !== "preview" && !busy && (
        <>
          <Textarea
            value={text}
            onChange={(e) => setText(e.target.value)}
            placeholder={t("tune.placeholder")}
            aria-label={t("tune.title")}
            className="min-h-16"
            maxLength={500}
            disabled={disabled || phase === "proposing"}
          />
          {disabled && disabledReason && <p className="text-xs text-muted-foreground">{disabledReason}</p>}
          <Button size="sm" onClick={propose} disabled={disabled || !text.trim() || phase === "proposing"}>
            {phase === "proposing" && <Loader2 className="size-4 animate-spin" aria-hidden="true" />}
            {phase === "proposing" ? t("tune.proposing") : t("tune.propose")}
          </Button>
        </>
      )}

      {result && (phase === "preview" || busy) && (
        <BriefDiffPreview
          diff={result.diff}
          before={brief}
          after={result.proposedBrief}
          narrative={result.narrative}
          busy={busy}
          applyLabel={phase === "saving" ? t("tune.saving") : phase === "applying" ? t("tune.applying") : undefined}
          error={error}
          onApply={apply}
          onDiscard={() => {
            setResult(null);
            setError(null);
            setPhase("idle");
          }}
        />
      )}

      {phase === "done" && <p className="text-sm text-foreground">{t("tune.done")}</p>}

      {error && phase === "idle" && (
        <Alert variant="destructive">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}
    </section>
  );
}
