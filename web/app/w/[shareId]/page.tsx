"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { ArrowRight, Loader2, Play, Sparkles } from "lucide-react";
import BrandName from "../../components/BrandName";
import { Button } from "../../components/ui/button";
import { usePageReady } from "../../components/RouteTransition";
import {
  absoluteImageUrl,
  fetchRunMeta,
  runWorkflow,
  type MagicErrorCode,
  type MagicFormField,
  type MagicRunMeta,
  type MagicRunResult,
} from "../../components/try/magicClient";

// /w/<shareId> — the public one-tap run page for a Magic Studio workflow.
//
// The audience is a stranger on a phone who was handed a link. The page
// contains ONLY what the product rule says it may: the creator's input form
// and the result. No console chrome, no plan, no agent internals — those
// live on the owner's landing-page reveal, never here.
//
// Rules inherited from /a/<shareId> and /try:
//   1. Never a raw error — every backend code maps to an honest sentence.
//   2. Say what this is — anonymous user content built on VegaDūta, labelled.
//   3. One run per person (and a small total cap) — the page says so up
//      front instead of surprising people at submit time.

const ERROR_COPY: Record<MagicErrorCode, { text: string; blocking: boolean }> = {
  disabled: {
    text: "Live workflow runs are taking a short break — try again in a while.",
    blocking: true,
  },
  invalid: {
    text: "This link doesn't point to a live workflow anymore.",
    blocking: true,
  },
  expired: {
    text: "This workflow has expired — its creator's sandbox ended.",
    blocking: true,
  },
  workflow_cap: {
    text: "This workflow isn't taking runs right now.",
    blocking: true,
  },
  already_ran: {
    text: "You've already used your run on this link — it's one per person, on purpose.",
    blocking: true,
  },
  runner_cap: {
    text: "This link has reached its visitor limit (a few people, one run each).",
    blocking: true,
  },
  runs_exhausted: {
    text: "This link has used up all of its runs.",
    blocking: true,
  },
  budget_exhausted: {
    text: "Today's free demo budget is used up — this workflow can run again tomorrow.",
    blocking: true,
  },
  moderated: {
    text: "That input couldn't be sent — try rephrasing it.",
    blocking: false,
  },
  unavailable: {
    text: "We couldn't reach this workflow just now — try again in a moment.",
    blocking: false,
  },
};

export default function WorkflowRunPage() {
  usePageReady(true);
  const params = useParams<{ shareId: string }>();
  const shareId = typeof params?.shareId === "string" ? params.shareId : "";

  const [meta, setMeta] = useState<MagicRunMeta | null>(null);
  const [loadError, setLoadError] = useState<MagicErrorCode | null>(null);
  const [values, setValues] = useState<Record<string, string>>({});
  const [running, setRunning] = useState(false);
  const [runNotice, setRunNotice] = useState<string | null>(null);
  const [blocked, setBlocked] = useState(false);
  const [result, setResult] = useState<MagicRunResult | null>(null);

  useEffect(() => {
    if (!shareId) return undefined;
    let cancelled = false;
    void fetchRunMeta(shareId).then((loaded) => {
      if (cancelled) return;
      if (!loaded.ok || !loaded.meta) {
        setLoadError(loaded.error || "unavailable");
        return;
      }
      setMeta(loaded.meta);
      if (loaded.meta.runsRemaining <= 0) {
        setBlocked(true);
        setRunNotice(ERROR_COPY.runs_exhausted.text);
      }
    });
    return () => {
      cancelled = true;
    };
  }, [shareId]);

  const setValue = useCallback((id: string, value: string) => {
    setValues((prev) => ({ ...prev, [id]: value }));
  }, []);

  const canSubmit =
    !!meta && !running && !blocked && meta.fields.every((f) => !f.required || (values[f.id] || "").trim().length > 0);

  const handleRun = useCallback(async () => {
    if (!canSubmit || !meta) return;
    setRunning(true);
    setRunNotice(null);
    const payload: Record<string, string> = {};
    for (const field of meta.fields) {
      const v = (values[field.id] || "").trim();
      if (v) payload[field.id] = v;
    }
    const run = await runWorkflow(shareId, payload);
    if (!run.ok) {
      const copy = ERROR_COPY[run.error || "unavailable"];
      setRunNotice(copy.text);
      if (copy.blocking) setBlocked(true);
      setRunning(false);
      return;
    }
    setResult(run);
    setRunning(false);
  }, [canSubmit, meta, values, shareId]);

  return (
    <main className="vd-pub min-h-screen bg-background text-foreground">
      <div className="mx-auto flex min-h-screen w-full max-w-lg flex-col px-4 py-6">
        {/* Who/what this is — always, before anything else. */}
        <header className="mb-6 text-center">
          <p className="text-xs uppercase tracking-widest text-muted-foreground">
            A live AI workflow, built by a <BrandName /> user
          </p>
          {meta ? <h1 className="mt-2 text-xl font-semibold leading-snug">{meta.title}</h1> : null}
          {meta && !result && !blocked ? (
            <p className="mt-1 text-xs text-muted-foreground">
              One run per person · {Math.max(0, meta.runsRemaining)} run
              {meta.runsRemaining === 1 ? "" : "s"} left on this link
            </p>
          ) : null}
        </header>

        {/* Load failure — an honest full-page state. */}
        {loadError ? (
          <div className="flex flex-1 flex-col items-center justify-center gap-4 text-center">
            <p className="text-sm text-muted-foreground">{ERROR_COPY[loadError].text}</p>
            <BuildYourOwnCta />
          </div>
        ) : null}

        {/* Loading */}
        {!loadError && !meta ? (
          <div className="flex flex-1 items-center justify-center">
            <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" aria-hidden />
            <span className="sr-only">Loading workflow…</span>
          </div>
        ) : null}

        {/* The form — the creator's fields, nothing else. */}
        {meta && !result ? (
          <div className="flex flex-col gap-4">
            {meta.fields.map((field) => (
              <FormFieldInput
                key={field.id}
                field={field}
                value={values[field.id] || ""}
                onChange={(v) => setValue(field.id, v)}
                disabled={running || blocked}
              />
            ))}

            {runNotice ? (
              <p
                role="status"
                className="rounded-xl border border-border/60 bg-card/60 px-4 py-3 text-sm text-muted-foreground"
              >
                {runNotice}
              </p>
            ) : null}

            {!blocked ? (
              <Button onClick={handleRun} disabled={!canSubmit} className="h-12 gap-2 text-base">
                {running ? (
                  <>
                    <Loader2 className="h-5 w-5 animate-spin" aria-hidden />
                    Running the workflow…
                  </>
                ) : (
                  <>
                    <Play className="h-5 w-5" aria-hidden />
                    Run it
                  </>
                )}
              </Button>
            ) : (
              <BuildYourOwnCta />
            )}
          </div>
        ) : null}

        {/* The result — the payoff, then the conversion door. */}
        {result ? (
          <div className="flex flex-col gap-4">
            <div className="rounded-2xl border border-border/60 bg-card/70 p-4">
              <h2 className="mb-3 flex items-center gap-2 text-sm font-semibold uppercase tracking-wider text-muted-foreground">
                <Sparkles className="h-4 w-4" aria-hidden />
                Result
                {result.simulated ? (
                  <span className="rounded-full bg-[color-mix(in_srgb,var(--state-warning)_15%,transparent)] px-2 py-0.5 text-xs font-semibold uppercase tracking-wide text-[var(--state-warning)]">
                    sample
                  </span>
                ) : null}
              </h2>
              {result.type === "image" && result.imageUrl ? (
                // eslint-disable-next-line @next/next/no-img-element -- runtime API origin, not a static asset
                <img
                  src={absoluteImageUrl(result.imageUrl)}
                  alt="Generated by this workflow"
                  className="w-full rounded-xl border border-border/50"
                />
              ) : (
                <div className="max-h-[60vh] overflow-auto whitespace-pre-wrap text-sm leading-relaxed">
                  {result.text}
                </div>
              )}
            </div>
            <p className="text-center text-xs text-muted-foreground">
              That was your run on this link — one per person, on purpose.
            </p>
            <BuildYourOwnCta />
          </div>
        ) : null}

        <footer className="mt-auto pt-8 text-center text-xs text-muted-foreground">
          Anonymous user content, rate-limited and moderated ·{" "}
          <Link href="/" className="underline-offset-2 hover:underline">
            What is <BrandName />?
          </Link>
        </footer>
      </div>
    </main>
  );
}

function FormFieldInput({
  field,
  value,
  onChange,
  disabled,
}: {
  field: MagicFormField;
  value: string;
  onChange: (v: string) => void;
  disabled: boolean;
}) {
  const inputId = `wf-${field.id}`;
  const common =
    "w-full rounded-xl border border-input bg-card/60 px-4 py-3 text-sm outline-none transition focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/40";
  return (
    <div>
      <label htmlFor={inputId} className="mb-1.5 block text-sm font-medium">
        {field.label}
        {field.required ? <span className="text-muted-foreground"> *</span> : null}
      </label>
      {field.type === "textarea" ? (
        <textarea
          id={inputId}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          rows={4}
          maxLength={2000}
          placeholder={field.placeholder || undefined}
          className={`${common} resize-none`}
          disabled={disabled}
        />
      ) : (
        <input
          id={inputId}
          type={field.type === "url" ? "url" : "text"}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          maxLength={field.type === "url" ? 2048 : 2000}
          placeholder={field.placeholder || undefined}
          className={common}
          disabled={disabled}
        />
      )}
    </div>
  );
}

function BuildYourOwnCta() {
  return (
    <Link
      href="/try"
      className="mx-auto inline-flex items-center gap-2 rounded-full border border-primary/40 bg-primary/10 px-5 py-2.5 text-sm font-medium text-primary transition hover:bg-primary/15"
    >
      Build your own — free, no signup
      <ArrowRight className="h-4 w-4" aria-hidden />
    </Link>
  );
}
