"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { motion, AnimatePresence } from "motion/react";
import BrandName from "../../components/BrandName";
import { ArrowRight, Clock, Flag, Info, Loader2, Send, Sparkles } from "lucide-react";
import { Button } from "../../components/ui/button";
import { usePageReady } from "../../components/RouteTransition";
import { fetchSharedAgent, reportSharedAgent, sendSharedChat, type ShareErrorCode, type SharedAgentInfo } from "./api";

// /a/<shareId> — the public viewer for a shared sandbox agent.
//
// The audience is a stranger who was handed a link: no login, no console
// chrome, no assumptions. Three rules, inherited from /try and tightened for
// user-generated content:
//   1. Never a raw error. Every backend code (viewer_cap, rate_limited,
//      budget_exhausted, moderated, expired, invalid) maps to an honest
//      labelled sentence — and an honest sentence includes being honest about
//      whether retrying could help, so a permanently dead link never borrows
//      the transient "try again in a moment" copy. Codes that end the
//      conversation replace the composer; codes that only affect one message
//      appear as a labelled notice in the thread.
//   2. Say what this is. The header labels it a shared demo agent built by a
//      VegaDūta user — a visitor must never mistake anonymous user content
//      for something this platform wrote.
//   3. "Report this agent" is ALWAYS visible — user-generated pages don't get
//      to hide their abuse valve behind a failure state.
//
// usePageReady(true) unconditionally, same reasoning as /try: this page's
// loading state is its own designed content, not a flash to hide.

type Bubble = {
  id: string;
  role: "visitor" | "agent" | "notice";
  text: string;
};

// The full honest-copy map for the backend's failure vocabulary. `blocking`
// decides whether the code ends the conversation (composer replaced) or only
// cost one message (labelled notice in the thread, composer stays).
const ERROR_COPY: Record<ShareErrorCode, { text: string; blocking: boolean }> = {
  viewer_cap: {
    text: "This shared agent has reached its 5-visitor limit, so it can't take new conversations.",
    blocking: true,
  },
  rate_limited: {
    text: "Messages are coming in a little fast — give it a moment and try again.",
    blocking: false,
  },
  budget_exhausted: {
    text: "The free demo budget for today is used up, so this agent can't reply until tomorrow.",
    blocking: true,
  },
  moderated: {
    text: "That message couldn't be sent — try rephrasing it.",
    blocking: false,
  },
  expired: {
    text: "This shared agent has expired — build your own in 2 minutes.",
    blocking: true,
  },
  // A 404/403 from the API: no such share, or its owner turned sharing off.
  // PERMANENT, so the copy has to say so. This case used to fall through to
  // "unavailable" below and told a visitor holding a dead link to "try again
  // in a moment" — a retry that can never work, for a condition that will
  // never resolve. Wording matches the sibling viewers that already get this
  // right (/agent/<id> and /w/<id>).
  invalid: {
    text: "This shared agent isn't available any more — its owner may have turned sharing off, or the link is wrong.",
    blocking: true,
  },
  unavailable: {
    // Genuinely transient ONLY: a network error, a timeout, a 5xx, or a code
    // we don't recognise. "Try again in a moment" is honest advice for THIS
    // case and no other — don't let permanent failures land here again.
    text: "We couldn't reach this agent just now — try again in a moment.",
    blocking: false,
  },
};

let idCounter = 0;
function newId(): string {
  idCounter += 1;
  return `s${Date.now()}-${idCounter}`;
}

export default function SharedAgentPage() {
  usePageReady(true);
  const params = useParams();
  const rawId = params?.shareId;
  const shareId = typeof rawId === "string" ? rawId : Array.isArray(rawId) ? rawId[0] || "" : "";

  const [phase, setPhase] = useState<"loading" | "ready" | "blocked">("loading");
  const [info, setInfo] = useState<SharedAgentInfo | null>(null);
  // The code that ended (or prevented) the conversation. Only ever a
  // `blocking` code, or "unavailable" when the page itself couldn't load.
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
          // The conversation is over (cap/budget/expiry) — keep the thread
          // visible, replace the composer with the labelled state below.
          setBlockedCode(code);
          setPhase("blocked");
        } else {
          // One message failed (moderation, a rate blip, a network blip) —
          // say so in the thread, honestly labelled, and let them continue.
          setBubbles((prev) => [...prev, { id: newId(), role: "notice", text: copy.text }]);
        }
      }
      setSending(false);
    },
    [shareId, sending]
  );

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
            {/* What this page is, before anything else: a demo agent someone
                built in the VegaDūta sandbox. A stranger must know they are
                talking to user-generated content, not to this platform. */}
            <div className="flex items-start gap-2 rounded-lg border border-border/60 bg-card/40 px-3 py-2">
              <Info className="mt-0.5 size-3.5 shrink-0 text-muted-foreground" aria-hidden="true" />
              <p className="text-xs text-muted-foreground">
                <span className="text-foreground">{info.agentName}</span> is a demo agent someone built in the{" "}
                <BrandName /> sandbox
                {info.personaLabel ? ` for a ${info.personaLabel.toLowerCase()}` : ""}. It answers on its builder&apos;s
                behalf, within free demo limits, until the sandbox expires.
              </p>
            </div>

            <div
              ref={scrollRef}
              className="flex-1 space-y-4 overflow-y-auto rounded-xl border border-border/60 bg-card/40 p-4 sm:p-6"
              style={{ minHeight: "40vh", maxHeight: "56vh" }}
            >
              {bubbles.length === 0 && (
                <p className="py-10 text-center text-sm text-muted-foreground">
                  Say hello — ask {info.agentName} about prices, timings, anything a customer would.
                </p>
              )}
              {bubbles.map((b) => (
                <ChatBubble key={b.id} bubble={b} />
              ))}
              {sending && <ThinkingBubble />}
            </div>

            {phase === "blocked" ? (
              <BlockedComposer code={blockedCode} />
            ) : (
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
            )}
          </motion.div>
        )}

        {/* The abuse valve — visible in EVERY state, including blocked and
            loading-failed. User-generated pages don't get to lose this. */}
        <ReportPanel shareId={shareId} />
      </div>

      <ViewerFooter />
    </main>
  );
}

function ViewerHeader({ info }: { info: SharedAgentInfo | null }) {
  const expiryLabel = expiresLine(info?.expiresAt || null);
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

        {expiryLabel && (
          <span className="hidden items-center gap-1.5 rounded-full border border-border bg-card/60 px-2.5 py-1 text-[11px] text-muted-foreground sm:inline-flex">
            <Clock className="size-3" aria-hidden="true" />
            {expiryLabel}
          </span>
        )}

        <div className="ml-auto flex items-center gap-2">
          <Button asChild size="sm" variant="outline">
            <Link href="/try">
              <span className="hidden sm:inline">Build your own — free</span>
              <span className="sm:hidden">Build yours</span>
            </Link>
          </Button>
        </div>
      </div>
    </header>
  );
}

/** Plain date, no ticking clock — same no-urgency-theatre posture as /try. */
function expiresLine(expiresAt: string | null): string | null {
  if (!expiresAt) return null;
  const date = new Date(expiresAt);
  if (isNaN(date.getTime())) return null;
  return `Available until ${date.toLocaleDateString()}`;
}

function ChatBubble({ bubble }: { bubble: Bubble }) {
  if (bubble.role === "notice") {
    // A labelled in-thread state, not an agent reply and not styled as one —
    // presenting a failure sentence in the agent's bubble would put words in
    // its mouth.
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

/** A blocking code, mid-conversation: the thread stays, the composer becomes
 *  the labelled state plus the only next step that actually works. */
function BlockedComposer({ code }: { code: ShareErrorCode }) {
  return (
    <div className="flex flex-col items-center gap-3 rounded-xl border border-border/70 bg-card/50 px-4 py-5 text-center">
      <p className="text-sm text-muted-foreground">{ERROR_COPY[code].text}</p>
      <Button asChild size="sm">
        <Link href="/try">
          Build your own free agent
          <ArrowRight className="size-4" aria-hidden="true" />
        </Link>
      </Button>
    </div>
  );
}

/** The page-level dead end (link expired/capped/unreachable before anything
 *  loaded). Same no-error-iconography posture as /try's Unavailable. */
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
        <Link href="/try">
          Build your own free agent — no signup
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

/** Always-visible abuse reporting: a quiet link that opens a tiny reason
 *  picker. One tap files it; the thank-you renders regardless of transport
 *  outcome — a safety report is never bounced back to the reporter to retry. */
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

/** The persistent provenance + conversion line. Every visitor to a shared
 *  page is a prospect who just watched the product work. */
function ViewerFooter() {
  return (
    <footer className="border-t border-border/60 bg-background/80">
      <div className="mx-auto flex max-w-3xl flex-col items-center gap-1.5 px-4 py-4 text-center sm:px-6">
        <Link
          href="/try"
          className="inline-flex items-center gap-1.5 text-sm text-primary underline-offset-4 hover:underline"
        >
          Built with <BrandName /> — create yours free
          <ArrowRight className="size-4" aria-hidden="true" />
        </Link>
        <p className="text-[11px] text-muted-foreground">
          No signup, no card — a working AI agent for your business in minutes.
        </p>
      </div>
    </footer>
  );
}
