"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { motion, AnimatePresence } from "motion/react";
import BrandName from "../../components/BrandName";
import { ArrowRight, Flag, Info, Loader2, Send, Sparkles } from "lucide-react";
import { Button } from "../../components/ui/button";
import { usePageReady } from "../../components/RouteTransition";
import {
  fetchSharedWorkflow,
  reportSharedWorkflow,
  runSharedWorkflow,
  type ShareErrorCode,
  type SharedWorkflowInfo,
} from "./api";
import WorkflowVoicePanel from "./WorkflowVoicePanel";

// /workflow/<shareId> - the public runner for a REAL, registered tenant's own
// shared WORKFLOW ("even registered consumers should be able to share their
// workflows to external world"). Sibling of /agent/[shareId] (the same share
// surface for a chat AGENT) but structurally different: a workflow has no
// back-and-forth thread - each interaction is one input (typed OR spoken),
// one workflow run, one result shown. Do NOT reuse or touch /w/[shareId] -
// that is the unrelated, already-shipped Magic Studio demo-sandbox feature.
//
// Same three rules as /agent/[shareId]:
//   1. Never a raw error - every backend code maps to an honest sentence.
//   2. Say what this is - a stranger must know they're running a real
//      tenant's own workflow, not something this platform wrote itself.
//   3. "Report this workflow" is always visible.
//
// usePageReady(true) unconditionally - this page's own loading state is
// designed content, not a flash to hide, same reasoning as /agent/[shareId].
//
// WHITE-LABEL: identical convention to /agent/[shareId] - brandDisplayName/
// brandLogoUrl present means render the TENANT's brand and drop every
// VegaDūta mark. The report valve stays in every variant - white-label never
// hides the abuse valve.

/** The one switch every branded element keys off. Deliberately NOT a type
 *  predicate: `false` doesn't mean `info` is null (a non-white-labeled
 *  workflow has info too), same reasoning as /agent/[shareId]/page.tsx's
 *  isWhiteLabeled. */
function isWhiteLabeled(info: SharedWorkflowInfo | null): boolean {
  return !!info && (!!info.brandDisplayName || !!info.brandLogoUrl);
}

const ERROR_COPY: Record<ShareErrorCode, { text: string; blocking: boolean }> = {
  rate_limited: {
    text: "Runs are coming in a little fast — give it a moment and try again.",
    blocking: false,
  },
  expired: {
    text: "This shared workflow isn't available any more — its owner may have turned sharing off.",
    blocking: true,
  },
  unavailable: {
    text: "Having trouble reaching the workflow — our team is looking into it. Please refresh and try again.",
    blocking: false,
  },
};

// Which path produced the last result shown - "voice" never carries an echo
// of what was said (the backend doesn't return a transcript for this
// surface), so the result card labels it generically instead of guessing.
type ResultSource = "text" | "voice";

export default function SharedWorkflowPage() {
  usePageReady(true);
  const params = useParams();
  const rawId = params?.shareId;
  const shareId = typeof rawId === "string" ? rawId : Array.isArray(rawId) ? rawId[0] || "" : "";

  const [phase, setPhase] = useState<"loading" | "ready" | "blocked">("loading");
  const [info, setInfo] = useState<SharedWorkflowInfo | null>(null);
  const [blockedCode, setBlockedCode] = useState<ShareErrorCode>("unavailable");

  const [input, setInput] = useState("");
  const [running, setRunning] = useState(false);
  const [lastInput, setLastInput] = useState<string | null>(null);
  const [lastSource, setLastSource] = useState<ResultSource>("text");
  const [resultText, setResultText] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const startedRef = useRef(false);

  useEffect(() => {
    if (startedRef.current) return undefined;
    startedRef.current = true;
    let cancelled = false;
    void (async () => {
      const result = await fetchSharedWorkflow(shareId);
      if (cancelled) return;
      if (result.ok && result.info) {
        setInfo(result.info);
        setPhase("ready");
      } else {
        setBlockedCode(result.error || "unavailable");
        setPhase("blocked");
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [shareId]);

  const run = useCallback(
    async (text: string) => {
      const trimmed = text.trim();
      if (!trimmed || running) return;
      setInput("");
      setRunning(true);
      setNotice(null);
      const result = await runSharedWorkflow(shareId, trimmed);
      if (result.ok && typeof result.resultText === "string") {
        setLastInput(trimmed);
        setLastSource("text");
        setResultText(result.resultText);
      } else {
        const code = result.error || "unavailable";
        const copy = ERROR_COPY[code];
        if (copy.blocking) {
          setBlockedCode(code);
          setPhase("blocked");
        } else {
          setNotice(copy.text);
        }
      }
      setRunning(false);
    },
    [shareId, running]
  );

  // WorkflowVoicePanel owns its own record/send/playback lifecycle end to
  // end - this page only ever receives the finished resultText, exactly like
  // the typed path replacing the last result rather than appending to it.
  const onVoiceResult = useCallback((text: string) => {
    setLastInput(null);
    setLastSource("voice");
    setResultText(text);
    setNotice(null);
  }, []);

  const whiteLabeled = isWhiteLabeled(info);

  return (
    <main className="vd-pub relative flex min-h-screen w-full flex-col overflow-x-clip bg-background text-foreground antialiased">
      <ViewerHeader info={info} />

      <div className="mx-auto flex w-full max-w-2xl flex-1 flex-col gap-4 px-4 py-6 sm:px-6">
        {phase === "loading" && (
          <div className="flex flex-1 flex-col items-center justify-center gap-3 py-24 text-center">
            <Loader2 className="size-6 animate-spin text-primary" aria-hidden="true" />
            <p className="text-sm text-muted-foreground">Opening this shared workflow…</p>
          </div>
        )}

        {phase === "blocked" && !info && <BlockedScreen code={blockedCode} />}

        {(phase === "ready" || (phase === "blocked" && info)) && info && (
          <motion.div
            initial={{ opacity: 0, y: 8 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.35, ease: [0.16, 1, 0.3, 1] }}
            className="flex flex-1 flex-col gap-4"
          >
            <div className="flex items-start gap-2 rounded-lg border border-border/60 bg-card/40 px-3 py-2">
              <Info className="mt-0.5 size-3.5 shrink-0 text-muted-foreground" aria-hidden="true" />
              {whiteLabeled ? (
                // White-labeled: the provenance line stays honest (it's a
                // real workflow run on the owner's behalf) but carries the
                // tenant's name, not this platform's.
                <p className="text-xs text-muted-foreground">
                  <span className="text-foreground">{info.workflowName}</span> is a workflow you can run, shared by{" "}
                  <span className="text-foreground">{info.brandDisplayName || "its owner"}</span>.
                </p>
              ) : (
                <p className="text-xs text-muted-foreground">
                  <span className="text-foreground">{info.workflowName}</span> is a real workflow, built and shared by
                  its owner on <BrandName />. Type or speak an input to run it once.
                </p>
              )}
            </div>

            <div
              className="flex-1 space-y-4 rounded-xl border border-border/60 bg-card/40 p-4 sm:p-6"
              style={{ minHeight: "30vh" }}
            >
              {!resultText && !running && !notice && (
                <p className="py-10 text-center text-sm text-muted-foreground">
                  Type or speak an input to run {info.workflowName}.
                </p>
              )}

              {!running && resultText && (
                <div className="flex justify-end">
                  <div className="max-w-[85%] rounded-2xl rounded-br-sm bg-primary px-4 py-2.5 text-sm text-primary-foreground">
                    <p className="whitespace-pre-wrap leading-relaxed">
                      {lastSource === "voice" ? "Voice input" : lastInput}
                    </p>
                  </div>
                </div>
              )}

              {running && <ThinkingBubble />}

              {resultText && !running && (
                <div className="flex justify-start">
                  <div className="max-w-[85%] rounded-2xl rounded-bl-sm border border-border/60 bg-card/70 px-4 py-2.5 text-sm">
                    <p className="whitespace-pre-wrap leading-relaxed">{resultText}</p>
                  </div>
                </div>
              )}

              {notice && (
                <div className="flex justify-center">
                  <p className="flex items-center gap-1.5 rounded-full border border-border/70 bg-background/50 px-3 py-1 text-[11px] text-muted-foreground">
                    <Info className="size-3 shrink-0" aria-hidden="true" />
                    {notice}
                  </p>
                </div>
              )}
            </div>

            {phase === "blocked" ? (
              <BlockedComposer code={blockedCode} whiteLabeled={whiteLabeled} />
            ) : (
              <>
                {info.supportsMediaInput && <WorkflowVoicePanel shareId={shareId} onResult={onVoiceResult} />}
                <div className="flex items-end gap-2 rounded-xl border border-border/70 bg-card/50 p-2">
                  <input
                    value={input}
                    onChange={(e) => setInput(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === "Enter") {
                        e.preventDefault();
                        void run(input);
                      }
                    }}
                    maxLength={2000}
                    placeholder={`Run ${info.workflowName}…`}
                    aria-label={`Input for ${info.workflowName}`}
                    className="min-h-10 flex-1 bg-transparent px-2 py-2 text-sm outline-none placeholder:text-muted-foreground"
                  />
                  <Button size="icon" onClick={() => run(input)} disabled={running || !input.trim()} aria-label="Run">
                    <Send className="size-4" aria-hidden="true" />
                  </Button>
                </div>
              </>
            )}
          </motion.div>
        )}

        <ReportPanel shareId={shareId} />
      </div>

      {/* White-label: the "Built with VegaDūta" conversion footer is exactly
          the "powered by" line the entitlement removes. */}
      {!whiteLabeled && <ViewerFooter />}
    </main>
  );
}

function ViewerHeader({ info }: { info: SharedWorkflowInfo | null }) {
  if (isWhiteLabeled(info)) {
    // The owning tenant's brand instead of VegaDūta's - a static lockup, not
    // a link (the only "home" this header could link to is VegaDūta's, which
    // is exactly what white-label removes). No marketing CTA either.
    return (
      <header className="sticky top-0 z-40 border-b border-border/60 bg-background/80 backdrop-blur-xl">
        <div className="mx-auto flex h-16 max-w-2xl items-center gap-3 px-4 sm:px-6">
          <div className="flex items-center gap-2">
            {info.brandLogoUrl && (
              <img src={info.brandLogoUrl} alt="" className="h-7 w-auto max-w-28 shrink-0 object-contain" />
            )}
            {info.brandDisplayName && (
              <span className="text-lg font-semibold tracking-tight">{info.brandDisplayName}</span>
            )}
          </div>

          <span className="inline-flex items-center gap-1.5 rounded-full border border-primary/40 bg-primary/10 px-2.5 py-1 text-[11px] font-medium text-primary">
            <Sparkles className="size-3" aria-hidden="true" />
            Workflow
          </span>
        </div>
      </header>
    );
  }
  return (
    <header className="sticky top-0 z-40 border-b border-border/60 bg-background/80 backdrop-blur-xl">
      <div className="mx-auto flex h-16 max-w-2xl items-center gap-3 px-4 sm:px-6">
        <Link href="/" className="flex items-center gap-2" aria-label="VegaDūta home">
          <img src="/logo-mark.png" alt="" width={38} height={28} className="h-7 w-auto shrink-0 object-contain" />
          <span className="brand-name hidden text-lg font-semibold tracking-tight sm:inline">
            Vega<span className="text-primary">Dūta</span>
          </span>
        </Link>

        <span className="inline-flex items-center gap-1.5 rounded-full border border-primary/40 bg-primary/10 px-2.5 py-1 text-[11px] font-medium text-primary">
          <Sparkles className="size-3" aria-hidden="true" />
          Shared workflow
        </span>

        <div className="ml-auto flex items-center gap-2">
          <Button asChild size="sm" variant="outline">
            <Link href="/">
              <span className="hidden sm:inline">Build your own workflow</span>
              <span className="sm:hidden">Build yours</span>
            </Link>
          </Button>
        </div>
      </div>
    </header>
  );
}

function ThinkingBubble() {
  return (
    <div className="flex justify-start">
      <div className="flex items-center gap-2 rounded-2xl rounded-bl-sm border border-border/60 bg-card/70 px-4 py-3">
        {[0, 1, 2].map((i) => (
          <motion.span
            key={i}
            className="size-1.5 rounded-full bg-muted-foreground"
            animate={{ opacity: [0.25, 1, 0.25] }}
            transition={{ duration: 1.1, repeat: Infinity, delay: i * 0.18 }}
          />
        ))}
      </div>
    </div>
  );
}

function BlockedComposer({ code, whiteLabeled }: { code: ShareErrorCode; whiteLabeled: boolean }) {
  return (
    <div className="flex flex-col items-center gap-3 rounded-xl border border-border/70 bg-card/50 px-4 py-5 text-center">
      <p className="text-sm text-muted-foreground">{ERROR_COPY[code].text}</p>
      {/* The conversion CTA is VegaDūta marketing - removed under white-label. */}
      {!whiteLabeled && (
        <Button asChild size="sm">
          <Link href="/">
            Build your own workflow
            <ArrowRight className="size-4" aria-hidden="true" />
          </Link>
        </Button>
      )}
    </div>
  );
}

function BlockedScreen({ code }: { code: ShareErrorCode }) {
  return (
    <motion.div
      initial={{ opacity: 0, y: 8 }}
      animate={{ opacity: 1, y: 0 }}
      className="mx-auto flex max-w-md flex-1 flex-col items-center justify-center gap-4 py-24 text-center"
    >
      <div className="rounded-full border border-border bg-card/60 p-3">
        <Info className="size-5 text-muted-foreground" aria-hidden="true" />
      </div>
      <p className="text-sm text-muted-foreground">{ERROR_COPY[code].text}</p>
      <Button asChild>
        <Link href="/">
          Build your own workflow
          <ArrowRight className="size-4" aria-hidden="true" />
        </Link>
      </Button>
    </motion.div>
  );
}

const REPORT_REASONS = [
  { id: "spam", label: "Spam or scam" },
  { id: "abusive", label: "Abusive or harmful" },
  { id: "impersonation", label: "Impersonating someone" },
  { id: "other", label: "Something else" },
];

function ReportPanel({ shareId }: { shareId: string }) {
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState(false);

  const report = useCallback(
    async (reason: string) => {
      if (busy || done) return;
      setBusy(true);
      await reportSharedWorkflow(shareId, reason);
      setBusy(false);
      setDone(true);
      setOpen(false);
    },
    [shareId, busy, done]
  );

  return (
    <div className="flex flex-col items-center gap-2 py-1">
      {done ? (
        <p className="text-xs text-muted-foreground" role="status">
          Thanks — your report has been logged and this workflow will be reviewed.
        </p>
      ) : !open ? (
        <button
          type="button"
          onClick={() => setOpen(true)}
          className="inline-flex items-center gap-1.5 border-0 bg-transparent p-0 text-xs text-muted-foreground underline-offset-4 transition-colors hover:text-foreground hover:underline"
        >
          <Flag className="size-3" aria-hidden="true" />
          Report this workflow
        </button>
      ) : (
        <AnimatePresence>
          <motion.div
            initial={{ opacity: 0, y: 4 }}
            animate={{ opacity: 1, y: 0 }}
            className="flex flex-col items-center gap-2 rounded-xl border border-border/60 bg-card/40 px-4 py-3"
          >
            <p className="text-xs text-muted-foreground">What&apos;s wrong with this workflow?</p>
            <div className="flex flex-wrap justify-center gap-2">
              {REPORT_REASONS.map((r) => (
                <button
                  key={r.id}
                  type="button"
                  disabled={busy}
                  onClick={() => report(r.id)}
                  className="rounded-full border border-border/70 bg-card/50 px-3 py-1 text-xs text-muted-foreground transition-colors hover:border-primary/50 hover:text-foreground disabled:cursor-not-allowed disabled:opacity-50"
                >
                  {r.label}
                </button>
              ))}
            </div>
            <button
              type="button"
              onClick={() => setOpen(false)}
              className="border-0 bg-transparent p-0 text-[11px] text-muted-foreground transition-colors hover:text-foreground"
            >
              Cancel
            </button>
          </motion.div>
        </AnimatePresence>
      )}
    </div>
  );
}

function ViewerFooter() {
  return (
    <footer className="border-t border-border/60 bg-background/80">
      <div className="mx-auto flex max-w-2xl flex-col items-center gap-1.5 px-4 py-4 text-center sm:px-6">
        <Link
          href="/"
          className="inline-flex items-center gap-1.5 text-sm text-primary underline-offset-4 hover:underline"
        >
          Built with <BrandName /> — create your own workflow
          <ArrowRight className="size-4" aria-hidden="true" />
        </Link>
      </div>
    </footer>
  );
}
