"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { motion, AnimatePresence } from "motion/react";
import BrandName from "../../components/BrandName";
import { ArrowRight, Flag, Info, Loader2, Send, Sparkles } from "lucide-react";
import { Button } from "../../components/ui/button";
import { usePageReady } from "../../components/RouteTransition";
import { fetchSharedAgent, reportSharedAgent, sendSharedChat, type ShareErrorCode, type SharedAgentInfo } from "./api";
import ShareCallPanel from "./ShareCallPanel";

// /agent/<shareId> - the public viewer for a REAL, registered tenant's own
// shared agent ("even registered consumers should be able to share their
// agents to external world"). Sibling of /a/[shareId] (the anonymous
// demo-sandbox equivalent) - same three rules, adapted for a real tenant's
// agent rather than a throwaway sandbox one:
//   1. Never a raw error - every backend code maps to an honest sentence.
//   2. Say what this is - a stranger must know they're talking to a real
//      tenant's own agent, not something this platform wrote itself.
//   3. "Report this agent" is always visible.
//
// usePageReady(true) unconditionally - this page's own loading state is
// designed content, not a flash to hide, same reasoning as /a/[shareId].
//
// WHITE-LABEL (audit item C2): when the meta response carries the owning
// tenant's brandDisplayName/brandLogoUrl (the backend only sends them while
// the owner's plan includes white-label), this page renders the TENANT's
// brand and drops every VegaDūta mark: header wordmark -> tenant brand,
// "Built with VegaDūta" footer -> gone, marketing CTAs -> gone. The report
// valve stays in every variant - white-label never hides the abuse valve.

type Bubble = { id: string; role: "visitor" | "agent" | "notice"; text: string };

/** The one switch every branded element keys off. Deliberately NOT a type
 *  predicate: `false` doesn't mean `info` is null (a non-white-labeled agent
 *  has info too), and an inferred/declared predicate would narrow the false
 *  branch to `never` under TS 5.5 aliasing. */
function isWhiteLabeled(info: SharedAgentInfo | null): boolean {
  return !!info && (!!info.brandDisplayName || !!info.brandLogoUrl);
}

const ERROR_COPY: Record<ShareErrorCode, { text: string; blocking: boolean }> = {
  rate_limited: {
    text: "Messages are coming in a little fast — give it a moment and try again.",
    blocking: false,
  },
  expired: {
    text: "This shared agent isn't available any more — its owner may have turned sharing off.",
    blocking: true,
  },
  quota_exhausted: {
    text: "The owner is a Free plan user and their daily message quota has been exhausted. It will reset after 24 hours, or they can upgrade to Premium.",
    blocking: false,
  },
  unavailable: {
    text: "Having trouble reaching the agent — our team is looking into it. Please refresh and try again.",
    blocking: false,
  },
};

let idCounter = 0;
function newId(): string {
  idCounter += 1;
  return `s${Date.now()}-${idCounter}`;
}

export default function SharedRealAgentPage() {
  usePageReady(true);
  const params = useParams();
  const rawId = params?.shareId;
  const shareId = typeof rawId === "string" ? rawId : Array.isArray(rawId) ? rawId[0] || "" : "";

  const [phase, setPhase] = useState<"loading" | "ready" | "blocked">("loading");
  const [info, setInfo] = useState<SharedAgentInfo | null>(null);
  const [blockedCode, setBlockedCode] = useState<ShareErrorCode>("unavailable");

  const [bubbles, setBubbles] = useState<Bubble[]>([]);
  const [input, setInput] = useState("");
  const [sending, setSending] = useState(false);
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const startedRef = useRef(false);

  useEffect(() => {
    if (startedRef.current) return undefined;
    startedRef.current = true;
    let cancelled = false;
    void (async () => {
      const result = await fetchSharedAgent(shareId);
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

  useEffect(() => {
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: "smooth" });
  }, [bubbles, sending]);

  const send = useCallback(
    async (text: string) => {
      const trimmed = text.trim();
      if (!trimmed || sending) return;
      setInput("");
      setSending(true);
      setBubbles((prev) => [...prev, { id: newId(), role: "visitor", text: trimmed }]);

      const result = await sendSharedChat(shareId, trimmed);
      if (result.ok && typeof result.reply === "string") {
        setBubbles((prev) => [...prev, { id: newId(), role: "agent", text: result.reply as string }]);
      } else {
        const code = result.error || "unavailable";
        const copy = ERROR_COPY[code];
        if (copy.blocking) {
          setBlockedCode(code);
          setPhase("blocked");
        } else {
          setBubbles((prev) => [...prev, { id: newId(), role: "notice", text: copy.text }]);
        }
      }
      setSending(false);
    },
    [shareId, sending]
  );

  const whiteLabeled = isWhiteLabeled(info);

  return (
    <main className="vd-pub relative flex min-h-screen w-full flex-col overflow-x-clip bg-background text-foreground antialiased">
      <ViewerHeader info={info} />

      <div className="mx-auto flex w-full max-w-3xl flex-1 flex-col gap-4 px-4 py-6 sm:px-6">
        {phase === "loading" && (
          <div className="flex flex-1 flex-col items-center justify-center gap-3 py-24 text-center">
            <Loader2 className="size-6 animate-spin text-primary" aria-hidden="true" />
            <p className="text-sm text-muted-foreground">Opening this shared agent…</p>
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
                // White-labeled: the provenance line stays honest ("an AI
                // agent answering on X's behalf") but carries the tenant's
                // name, not this platform's.
                <p className="text-xs text-muted-foreground">
                  <span className="text-foreground">{info.agentName}</span> is an AI agent answering on behalf of{" "}
                  <span className="text-foreground">{info.brandDisplayName || "its owner"}</span>.
                </p>
              ) : (
                <p className="text-xs text-muted-foreground">
                  <span className="text-foreground">{info.agentName}</span> is a real AI agent, built and shared by its
                  owner on <BrandName />. It&apos;s answering on their behalf.
                </p>
              )}
            </div>

            <div
              ref={scrollRef}
              className="flex-1 space-y-4 overflow-y-auto rounded-xl border border-border/60 bg-card/40 p-4 sm:p-6"
              style={{ minHeight: "40vh", maxHeight: "56vh" }}
            >
              {bubbles.length === 0 && (
                <p className="py-10 text-center text-sm text-muted-foreground">
                  Say hello — ask {info.agentName} anything.
                </p>
              )}
              {bubbles.map((b) => (
                <ChatBubble key={b.id} bubble={b} />
              ))}
              {sending && <ThinkingBubble />}
            </div>

            {phase === "blocked" ? (
              <BlockedComposer code={blockedCode} whiteLabeled={whiteLabeled} />
            ) : (
              <>
                {/* Unconditional, matching the authenticated console's CallPanel:
                    voice rides speech-to-text -> LLM -> text-to-speech, so the
                    agent's MODEL needing native audio input was the wrong gate -
                    it hid the mic/call buttons for every text-model agent. Only
                    the camera toggle stays capability-gated (supportsImageInput). */}
                <ShareCallPanel
                  shareId={shareId}
                  agentName={info.agentName}
                  supportsImageInput={info.supportsImageInput}
                />
                <div className="flex items-end gap-2 rounded-xl border border-border/70 bg-card/50 p-2">
                  <textarea
                    value={input}
                    onChange={(e) => setInput(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === "Enter" && !e.shiftKey) {
                        e.preventDefault();
                        void send(input);
                      }
                    }}
                    rows={1}
                    maxLength={2000}
                    placeholder={`Message ${info.agentName}…`}
                    aria-label={`Message ${info.agentName}`}
                    className="max-h-40 min-h-10 flex-1 resize-none bg-transparent px-2 py-2 text-sm outline-none placeholder:text-muted-foreground"
                  />
                  <Button size="icon" onClick={() => send(input)} disabled={sending || !input.trim()} aria-label="Send">
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

function ViewerHeader({ info }: { info: SharedAgentInfo | null }) {
  if (isWhiteLabeled(info)) {
    // The owning tenant's brand instead of VegaDūta's - a static lockup, not
    // a link (the only "home" this header could link to is VegaDūta's, which
    // is exactly what white-label removes). No marketing CTA either.
    return (
      <header className="sticky top-0 z-40 border-b border-border/60 bg-background/80 backdrop-blur-xl">
        <div className="mx-auto flex h-16 max-w-3xl items-center gap-3 px-4 sm:px-6">
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
            AI agent
          </span>
        </div>
      </header>
    );
  }
  return (
    <header className="sticky top-0 z-40 border-b border-border/60 bg-background/80 backdrop-blur-xl">
      <div className="mx-auto flex h-16 max-w-3xl items-center gap-3 px-4 sm:px-6">
        <Link href="/" className="flex items-center gap-2" aria-label="VegaDūta home">
          <img src="/logo-mark.png" alt="" className="h-7 w-auto shrink-0 object-contain" />
          <span className="brand-name hidden text-lg font-semibold tracking-tight sm:inline">
            Vega<span className="text-primary">Dūta</span>
          </span>
        </Link>

        <span className="inline-flex items-center gap-1.5 rounded-full border border-primary/40 bg-primary/10 px-2.5 py-1 text-[11px] font-medium text-primary">
          <Sparkles className="size-3" aria-hidden="true" />
          Shared agent
        </span>

        <div className="ml-auto flex items-center gap-2">
          <Button asChild size="sm" variant="outline">
            <Link href="/">
              <span className="hidden sm:inline">Build your own agent</span>
              <span className="sm:hidden">Build yours</span>
            </Link>
          </Button>
        </div>
      </div>
    </header>
  );
}

function ChatBubble({ bubble }: { bubble: Bubble }) {
  if (bubble.role === "notice") {
    return (
      <div className="flex justify-center">
        <p className="flex items-center gap-1.5 rounded-full border border-border/70 bg-background/50 px-3 py-1 text-[11px] text-muted-foreground">
          <Info className="size-3 shrink-0" aria-hidden="true" />
          {bubble.text}
        </p>
      </div>
    );
  }
  const isVisitor = bubble.role === "visitor";
  return (
    <motion.div
      initial={{ opacity: 0, y: 6 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.25, ease: [0.16, 1, 0.3, 1] }}
      className={isVisitor ? "flex justify-end" : "flex justify-start"}
    >
      <div
        className={
          isVisitor
            ? "max-w-[85%] rounded-2xl rounded-br-sm bg-primary px-4 py-2.5 text-sm text-primary-foreground"
            : "max-w-[85%] rounded-2xl rounded-bl-sm border border-border/60 bg-card/70 px-4 py-2.5 text-sm"
        }
      >
        <p className="whitespace-pre-wrap leading-relaxed">{bubble.text}</p>
      </div>
    </motion.div>
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
            Build your own agent
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
          Build your own agent
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
      await reportSharedAgent(shareId, reason);
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
          Thanks — your report has been logged and this agent will be reviewed.
        </p>
      ) : !open ? (
        <button
          type="button"
          onClick={() => setOpen(true)}
          className="inline-flex items-center gap-1.5 border-0 bg-transparent p-0 text-xs text-muted-foreground underline-offset-4 transition-colors hover:text-foreground hover:underline"
        >
          <Flag className="size-3" aria-hidden="true" />
          Report this agent
        </button>
      ) : (
        <AnimatePresence>
          <motion.div
            initial={{ opacity: 0, y: 4 }}
            animate={{ opacity: 1, y: 0 }}
            className="flex flex-col items-center gap-2 rounded-xl border border-border/60 bg-card/40 px-4 py-3"
          >
            <p className="text-xs text-muted-foreground">What&apos;s wrong with this agent?</p>
            <div className="flex flex-wrap justify-center gap-2">
              {REPORT_REASONS.map((r) => (
                <button
                  key={r.id}
                  type="button"
                  disabled={busy}
                  onClick={() => report(r.id)}
                  className="rounded-full border border-border/70 bg-card/50 px-3 py-1 text-xs text-muted-foreground transition-colors hover:border-primary/50 hover:text-foreground disabled:cursor-not-allowed disabled:text-muted-foreground"
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
      <div className="mx-auto flex max-w-3xl flex-col items-center gap-1.5 px-4 py-4 text-center sm:px-6">
        <Link
          href="/"
          className="inline-flex items-center gap-1.5 text-sm text-primary underline-offset-4 hover:underline"
        >
          Built with <BrandName /> — create your own agent
          <ArrowRight className="size-4" aria-hidden="true" />
        </Link>
      </div>
    </footer>
  );
}
