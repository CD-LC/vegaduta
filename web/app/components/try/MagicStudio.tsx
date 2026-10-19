"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { Link2, Loader2, Sparkles, Wand2 } from "lucide-react";
import { Button } from "../ui/button";
import { createSession, fetchSession, readStoredToken } from "./demoClient";
import { INDUSTRY_CREATIVE_PACKS } from "../creative/industryPrompts";
import { useTranslations } from "../../lib/i18n";
import { PERSONA_PACKS } from "../creative/personaPrompts";
import { sendLandingEvent } from "../demo-landing/landingBeacon";
import {
  planWorkflow,
  previewWorkflow,
  publishWorkflow,
  type MagicErrorCode,
  type MagicPlan,
  type MagicRunResult,
} from "./magicClient";
import { MAGIC_PREFILL_EVENT, promptFromUrl, takeMagicPrompt } from "./magicPrefill";
import DemoVideoGenerator from "./magic/DemoVideoGenerator";
import RevealPanel, { type RevealTab } from "./magic/RevealPanel";
import RunSharePanel from "./magic/RunSharePanel";
import TailorPickers from "./magic/TailorPickers";

// Magic Studio — the landing section where a visitor types an outcome and
// watches the platform build the workflow FOR them: intent analysis, live
// MCP-registry discovery, model selection, a dynamically created agent —
// then runs it, and publishes it as a public one-tap mobile link (/w/…).
//
// Demo-surface rules apply verbatim (see demoClient.ts): never a raw error,
// simulated results are labelled, and everything the reveal shows is REAL —
// the tools come from the official MCP registry, the model from the live
// platform pool, the agent prompt is the one actually created. The mermaid
// block is the same plan the backend persisted, offered as copyable source
// next to the rendered pipeline.

// Error copy and default suggestions are rendered text, so they resolve
// through i18n keys rather than literal English — but both are referenced at
// module scope (before any component/hook exists to call useTranslations),
// so these are just code->key maps; the actual t() lookup happens inside the
// component where they're used.
const ERROR_KEYS: Record<MagicErrorCode, string> = {
  disabled: "magic_errorDisabled",
  invalid: "magic_errorInvalid",
  expired: "magic_errorExpired",
  workflow_cap: "magic_errorWorkflowCap",
  already_ran: "magic_errorAlreadyRan",
  runner_cap: "magic_errorRunnerCap",
  runs_exhausted: "magic_errorRunsExhausted",
  budget_exhausted: "magic_errorBudgetExhausted",
  moderated: "magic_errorModerated",
  unavailable: "magic_errorUnavailable",
};

const DEFAULT_SUGGESTION_KEYS = ["magic_suggestion1", "magic_suggestion2", "magic_suggestion3", "magic_suggestion4"];

type Phase = "idle" | "planning" | "planned" | "running" | "done";

export default function MagicStudio() {
  const t = useTranslations("landing");
  const errorText = useCallback((code: MagicErrorCode) => t(ERROR_KEYS[code]), [t]);
  const [prompt, setPrompt] = useState("");
  const [link, setLink] = useState("");
  const [showLink, setShowLink] = useState(false);
  const [phase, setPhase] = useState<Phase>("idle");
  const [notice, setNotice] = useState<string | null>(null);
  const [plan, setPlan] = useState<MagicPlan | null>(null);
  const [workflowId, setWorkflowId] = useState<string | null>(null);
  const [result, setResult] = useState<MagicRunResult | null>(null);
  const [shareUrl, setShareUrl] = useState<string | null>(null);
  const [runCap, setRunCap] = useState(3);
  const [publishing, setPublishing] = useState(false);
  const [copied, setCopied] = useState<"link" | "mermaid" | null>(null);
  const [revealTab, setRevealTab] = useState<RevealTab>("diagram");

  // Industry picker — swaps the suggestion chips to industry-flavoured
  // prompts (research: "give them prepopulated set prompts... set of
  // industry what they belong to"). null = the generic defaults above.
  const [industryId, setIndustryId] = useState<string | null>(null);
  const industryPack = useMemo(() => INDUSTRY_CREATIVE_PACKS.find((p) => p.id === industryId) ?? null, [industryId]);

  // Persona/role picker — a second, separately-labeled row ("Or, what do you
  // do?") swapping the same suggestion chips to role-flavoured prompts
  // (personaPrompts.ts). Deliberately NOT merged into the industry row above
  // — "what kind of business" and "what do you do" are two different axes
  // (a developer or a student isn't a business). null = deferred to industry.
  const [personaId, setPersonaId] = useState<string | null>(null);
  const personaPack = useMemo(() => PERSONA_PACKS.find((p) => p.id === personaId) ?? null, [personaId]);

  // Precedence: persona wins over industry when both are picked — it's the
  // more specific signal for "what you'd actually type" (a developer at a
  // restaurant still wants developer prompts, not restaurant posters). Falls
  // back to the generic defaults when neither is picked.
  const suggestions = useMemo(() => {
    if (personaPack) return personaPack.suggestedPrompts;
    if (!industryPack) return DEFAULT_SUGGESTION_KEYS.map((k) => t(k));
    return [
      industryPack.imagePrompts[0]?.prompt,
      industryPack.videoPrompts[0]?.prompt,
      ...industryPack.imagePrompts.slice(1, 3).map((p) => p.prompt),
    ].filter((s): s is string => Boolean(s));
  }, [personaPack, industryPack, t]);

  // Hero handoff (see magicPrefill.ts): the landing hero's build console can
  // drop the spec it just "compiled" straight into this box. Order matters —
  // a queued storage value wins over ?prompt= because it is the click the
  // visitor just made; the URL param is the shareable deep-link fallback.
  // The event covers the already-mounted case (this chunk is lazy-loaded on
  // the landing, so a click can also precede mount — storage covers that).
  useEffect(() => {
    const apply = () => {
      const queued = takeMagicPrompt() ?? promptFromUrl();
      if (queued) setPrompt(queued);
    };
    apply();
    window.addEventListener(MAGIC_PREFILL_EVENT, apply);
    return () => window.removeEventListener(MAGIC_PREFILL_EVENT, apply);
  }, []);

  const busy = phase === "planning" || phase === "running";

  // Returns the real server-provided reason on failure (rate limit, disabled,
  // network blip) rather than collapsing every failure into one generic
  // "couldn't reach the studio" message - a rate-limited visitor deserves to
  // know that's what happened (and that a free account removes the limit),
  // not be told the platform is unreachable when it's actually fine.
  const ensureToken = useCallback(async (): Promise<{ token: string | null; message: string | null }> => {
    const existing = readStoredToken();
    if (existing) {
      const resumed = await fetchSession(existing);
      if (resumed.session && !resumed.session.expired) return { token: existing, message: null };
    }
    const created = await createSession();
    if (created.ok && created.session?.demoToken) {
      return { token: created.session.demoToken, message: null };
    }
    return { token: null, message: created.message || null };
  }, []);

  const handlePlan = useCallback(async () => {
    const cleaned = prompt.trim();
    if (!cleaned || busy) return;
    // Mid-funnel beacon (whitelisted server-side since day one but never
    // fired — the 2026-07-31 funnel audit found the demo-run step was the
    // one unmeasured hop). Landing only: MagicStudio also mounts on /try,
    // and counting runs there would overstate the landing funnel.
    if (typeof window !== "undefined" && window.location.pathname === "/") {
      sendLandingEvent("magic_run_click");
    }
    setNotice(null);
    setResult(null);
    setShareUrl(null);
    setPhase("planning");
    const { token, message } = await ensureToken();
    if (!token) {
      setNotice(message || errorText("unavailable"));
      setPhase("idle");
      return;
    }
    const planned = await planWorkflow(token, cleaned, link || undefined);
    if (!planned.ok || !planned.plan || !planned.workflowId) {
      setNotice(errorText(planned.error || "unavailable"));
      setPhase("idle");
      return;
    }
    setPlan(planned.plan);
    setWorkflowId(planned.workflowId);
    setRevealTab("diagram");
    setPhase("planned");
  }, [prompt, link, busy, ensureToken, errorText]);

  const handleRun = useCallback(async () => {
    if (!workflowId || busy) return;
    setPhase("running");
    setNotice(null);
    const token = readStoredToken();
    if (!token) {
      setNotice(errorText("invalid"));
      setPhase("planned");
      return;
    }
    const values: Record<string, string> = { prompt: prompt.trim() };
    if (link.trim()) values.link = link.trim();
    const run = await previewWorkflow(token, workflowId, values);
    if (!run.ok) {
      setNotice(errorText(run.error || "unavailable"));
      setPhase("planned");
      return;
    }
    setResult(run);
    setPhase("done");
  }, [workflowId, busy, prompt, link, errorText]);

  const handlePublish = useCallback(async () => {
    if (!workflowId || publishing) return;
    setPublishing(true);
    setNotice(null);
    const token = readStoredToken();
    if (!token) {
      setNotice(errorText("invalid"));
      setPublishing(false);
      return;
    }
    const published = await publishWorkflow(token, workflowId);
    if (!published.ok || !published.shareUrl) {
      setNotice(errorText(published.error || "unavailable"));
    } else {
      setShareUrl(published.shareUrl);
      setRunCap(published.runCap ?? 3);
    }
    setPublishing(false);
  }, [workflowId, publishing, errorText]);

  const copyText = useCallback((text: string, which: "link" | "mermaid") => {
    try {
      void navigator.clipboard.writeText(text);
      setCopied(which);
      window.setTimeout(() => setCopied(null), 1600);
    } catch {
      /* clipboard blocked — the text is still selectable on screen */
    }
  }, []);

  return (
    <section
      id="magic-studio"
      aria-labelledby="magic-studio-heading"
      className="relative mx-auto w-full max-w-6xl px-4 py-16 sm:py-20"
    >
      <div className="mx-auto max-w-2xl text-center">
        <p className="mb-3 inline-flex items-center gap-2 rounded-full border border-border/60 bg-card/60 px-3 py-1 text-xs font-medium uppercase tracking-wider text-muted-foreground">
          <Wand2 className="h-3.5 w-3.5 shrink-0" aria-hidden />
          {t("magic_eyebrow")}
        </p>
        <h2 id="magic-studio-heading" className="text-3xl font-semibold tracking-tight sm:text-4xl">
          {t("magic_title")}
        </h2>
        <p className="mt-3 text-base text-muted-foreground">{t("magic_sub")}</p>
      </div>

      {/* Input — leads the section (2026-07-31 CRO redesign: the prompt box
          is the star; 30 taxonomy chips used to stand between the visitor
          and it. The industry/persona pickers now live in a disclosure
          below the suggestion chips they flavour). */}
      <div className="mx-auto mt-8 max-w-2xl rounded-2xl border border-border/60 bg-card/70 p-4 shadow-sm backdrop-blur">
        <label htmlFor="magic-prompt" className="sr-only">
          {t("magic_promptLabel")}
        </label>
        <textarea
          id="magic-prompt"
          value={prompt}
          onChange={(e) => setPrompt(e.target.value)}
          rows={3}
          maxLength={2000}
          placeholder={t("magic_promptPlaceholder")}
          className="w-full resize-none rounded-xl border border-border/50 bg-background/60 px-4 py-3 text-sm outline-none transition focus:border-primary/60 focus:ring-2 focus:ring-primary/20"
          disabled={busy}
        />
        {showLink ? (
          <input
            type="url"
            value={link}
            onChange={(e) => setLink(e.target.value)}
            maxLength={2048}
            placeholder={t("magic_linkPlaceholder")}
            className="mt-2 w-full rounded-xl border border-border/50 bg-background/60 px-4 py-2.5 text-sm outline-none transition focus:border-primary/60 focus:ring-2 focus:ring-primary/20"
            disabled={busy}
          />
        ) : null}
        <div className="mt-3 flex flex-wrap items-center justify-between gap-3">
          <button
            type="button"
            onClick={() => setShowLink((v) => !v)}
            className="inline-flex items-center gap-1.5 border-0 bg-transparent p-0 text-xs text-muted-foreground transition hover:text-foreground"
          >
            <Link2 className="h-3.5 w-3.5 shrink-0" aria-hidden />
            {showLink ? t("magic_removeLink") : t("magic_addLink")}
          </button>
          <Button onClick={handlePlan} disabled={!prompt.trim() || busy} className="gap-2">
            {phase === "planning" ? (
              <>
                <Loader2 className="h-4 w-4 animate-spin shrink-0" aria-hidden />
                {t("magic_planning")}
              </>
            ) : (
              <>
                <Sparkles className="h-4 w-4 shrink-0" aria-hidden />
                {t("magic_buildButton")}
              </>
            )}
          </Button>
        </div>
        <div className="mt-3 flex flex-wrap gap-2">
          {suggestions.map((s) => (
            <button
              key={s}
              type="button"
              onClick={() => setPrompt(s)}
              disabled={busy}
              className="rounded-full border border-border/50 bg-background/40 px-3 py-1 text-xs text-muted-foreground transition hover:border-primary/40 hover:text-foreground"
            >
              {s}
            </button>
          ))}
        </div>

        {/* Industry/persona pickers — collapsed by default (choice-overload
            fix). Opening reveals the same two chip rows as before; they only
            re-flavour the suggestion chips above, so hiding them costs a new
            visitor nothing. Auto-open when a selection is already active so
            state is never invisible. */}
        <TailorPickers
          industryId={industryId}
          onIndustryChange={setIndustryId}
          industryName={industryPack?.name ?? null}
          personaId={personaId}
          onPersonaChange={setPersonaId}
          personaName={personaPack?.name ?? null}
        />

        <DemoVideoGenerator prompt={prompt} unavailableText={errorText("unavailable")} />
      </div>

      {notice ? (
        <p
          role="status"
          className="mx-auto mt-4 max-w-2xl rounded-xl border border-border/60 bg-card/60 px-4 py-3 text-center text-sm text-muted-foreground"
        >
          {notice}
        </p>
      ) : null}

      {/* The reveal — behind the scenes */}
      {plan ? (
        <div className="mx-auto mt-10 grid max-w-5xl gap-6 lg:grid-cols-[1.15fr_1fr]">
          <RevealPanel
            plan={plan}
            revealTab={revealTab}
            onRevealTabChange={setRevealTab}
            copied={copied}
            copyText={copyText}
          />

          <RunSharePanel
            plan={plan}
            running={phase === "running"}
            busy={busy}
            onRun={handleRun}
            publishing={publishing}
            onPublish={handlePublish}
            shareUrl={shareUrl}
            runCap={runCap}
            copied={copied}
            copyText={copyText}
            result={result}
          />
        </div>
      ) : null}
    </section>
  );
}
