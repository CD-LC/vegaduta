"use client";

import { useCallback, useEffect, useMemo, useRef, useState, type CSSProperties } from "react";
import { Flag, Info, Loader2, Send, Sparkles, X } from "lucide-react";
import BrandName from "../BrandName";
import { Button } from "../ui/button";
import {
  fetchSharedAgent,
  reportSharedAgent,
  sendSharedChat,
  type ShareErrorCode,
  type SharedAgentInfo,
} from "../../agent/[shareId]/api";
import { isHostMessage, postToHost } from "./embedProtocol";

// The chat surface rendered INSIDE the widget iframe (/a/embed/<shareId>).
//
// This is the framed half of the embeddable widget: the loader in
// web/public/widget/vegaduta-widget.js draws the launcher bubble on the
// customer's page and opens an iframe pointed here. Everything inside the
// frame is our own origin (the app. host), which is the entire
// CORS story - calls to /api/share/agents/** leave from a first-party origin
// already on core's exact-match allowlist, so the widget ships with ZERO
// change to the API's origin policy.
//
// DELIBERATE REUSE, NOT A FORK: the API client is imported straight from
// web/app/agent/[shareId]/api.ts - the SAME module the full-page public
// share viewer uses - so the two surfaces can never drift apart on the
// backend contract (error vocabulary, retry/timeout policy, white-label
// field trust). Importing a colocated module from another route segment is
// unusual but intentional; the alternative was copying 150 lines of client
// that would then rot independently. The UI itself is a compact re-render,
// not a reuse, because the constraints differ hard: a 400x640 panel on
// somebody else's page has no room for the full-page viewer's hero header,
// motion/react entrance animations (deliberately dropped - the embed budget
// note in the loader header), or marketing CTA row.
//
// WHAT CARRIES OVER from /agent/[shareId] unchanged, because they are policy
// rather than layout:
//   1. Never a raw error - every backend code maps to an honest sentence
//      (ERROR_COPY below mirrors the viewer's vocabulary).
//   2. Say what this is - the provenance line ("X is an AI agent...") stays,
//      shrunk to fit.
//   3. "Report this agent" is ALWAYS reachable, including white-label. The
//      abuse valve is the one thing branding never removes.
//
// WHITE-LABEL (audit item C2): same trust model as the viewer - the meta
// endpoint only carries brandDisplayName/brandLogoUrl while the owning
// tenant's plan includes white-label, so presence alone decides whether the
// panel shows the tenant's brand and drops the "Built with VegaDūta"
// footer.

type Bubble = { id: string; role: "visitor" | "agent" | "notice"; text: string };

export type EmbedChatProps = {
  shareId: string;
  /** Pre-sanitized by the page (embedProtocol.sanitizeAccent); null = brand default. */
  accent: string | null;
  /** Host-configured first message shown AS the agent's opener. Local-only:
   *  never sent to the backend, so it costs the owner zero quota. */
  greeting: string | null;
  /** Host-configured panel title; falls back to the agent's own name. */
  title: string | null;
  theme: "light" | "dark" | "auto";
  /** Pre-sanitized postMessage target (embedProtocol.sanitizeParentOrigin);
   *  null = standalone/direct open, all host choreography silently off. */
  parentOrigin: string | null;
};

const ERROR_COPY: Record<ShareErrorCode, { text: string; blocking: boolean }> = {
  rate_limited: {
    text: "Messages are coming in a little fast — give it a moment and try again.",
    blocking: false,
  },
  expired: {
    text: "This chat isn't available any more — its owner may have turned sharing off.",
    blocking: true,
  },
  quota_exhausted: {
    text: "This agent has used up its daily message quota. Please try again later.",
    blocking: false,
  },
  unavailable: {
    text: "Having trouble reaching the agent — please try again in a moment.",
    blocking: false,
  },
};

let idCounter = 0;
function newId(): string {
  idCounter += 1;
  return `e${Date.now()}-${idCounter}`;
}

/** Same non-predicate shape (and reason) as the full-page viewer's
 *  isWhiteLabeled: `false` must not narrow `info` to null. */
function isWhiteLabeled(info: SharedAgentInfo | null): boolean {
  return !!info && (!!info.brandDisplayName || !!info.brandLogoUrl);
}

/**
 * Foreground colour for text sitting ON the custom accent. Only hex accents
 * get a real luminance check (WCAG relative-luminance threshold); rgb()/hsl()
 * /keyword accents keep white, matching --primary-foreground's default. Not
 * worth a full CSS colour parser: a wrong guess here costs contrast on ONE
 * button for the customer who picked both a non-hex syntax AND a pastel -
 * they can switch to hex - while a parser is 100+ lines to audit forever.
 */
function accentForeground(accent: string): string {
  if (!accent.startsWith("#")) return "#ffffff";
  let hex = accent.slice(1);
  if (hex.length === 3 || hex.length === 4) {
    hex = hex
      .split("")
      .map((c) => c + c)
      .join("");
  }
  if (hex.length !== 6 && hex.length !== 8) return "#ffffff";
  const r = parseInt(hex.slice(0, 2), 16) / 255;
  const g = parseInt(hex.slice(2, 4), 16) / 255;
  const b = parseInt(hex.slice(4, 6), 16) / 255;
  const lin = (c: number) => (c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4));
  const luminance = 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b);
  return luminance > 0.45 ? "#111827" : "#ffffff";
}

export default function EmbedChat({
  shareId,
  accent,
  greeting,
  title,
  theme,
  parentOrigin,
}: EmbedChatProps) {
  const [phase, setPhase] = useState<"loading" | "ready" | "blocked">("loading");
  const [info, setInfo] = useState<SharedAgentInfo | null>(null);
  const [blockedCode, setBlockedCode] = useState<ShareErrorCode>("unavailable");

  const [bubbles, setBubbles] = useState<Bubble[]>([]);
  const [input, setInput] = useState("");
  const [sending, setSending] = useState(false);

  const scrollRef = useRef<HTMLDivElement | null>(null);
  const startedRef = useRef(false);
  // Mirrors the loader's panel state (host "opened"/"closed" messages).
  // A ref, not state: it is only ever READ at the moment a reply lands, and
  // routing it through state would re-render the whole transcript every time
  // the visitor toggles the panel.
  const panelOpenRef = useRef(false);

  // --- Theme ---------------------------------------------------------------
  // "auto" follows the VISITOR's OS preference via prefers-color-scheme, live
  // (a customer site has visitors of both persuasions; the embed should match
  // each one, not the site owner's own setting). Forced light/dark is for the
  // host page whose design demands one look. Dark rides the app's own `.dark`
  // class-on-a-wrapper convention (globals.css elevation ladder) - NOT the
  // <html> element, which belongs to the app around us and which an embed
  // page must never mutate.
  const [dark, setDark] = useState(theme === "dark");
  useEffect(() => {
    if (theme !== "auto") {
      setDark(theme === "dark");
      return undefined;
    }
    const mq = window.matchMedia("(prefers-color-scheme: dark)");
    setDark(mq.matches);
    const onChange = (event: MediaQueryListEvent) => setDark(event.matches);
    mq.addEventListener("change", onChange);
    return () => mq.removeEventListener("change", onChange);
  }, [theme]);

  // --- Accent --------------------------------------------------------------
  // shadcn-theme.css maps utilities via `@theme inline`, so bg-primary
  // compiles to `background-color: var(--primary)` and an inline override on
  // this wrapper re-tints every primary-coloured element in the subtree.
  // BOTH --primary and --accent-500 are overridden: --primary is declared at
  // :root as `var(--accent-500)` and custom-property substitution happens at
  // the declaring element, so overriding only --accent-500 down here would
  // NOT ripple into --primary (the classic custom-property gotcha).
  const accentStyle = useMemo<CSSProperties | undefined>(() => {
    if (!accent) return undefined;
    return {
      "--accent-500": accent,
      "--primary": accent,
      "--primary-foreground": accentForeground(accent),
    } as CSSProperties;
  }, [accent]);

  // --- Host choreography ---------------------------------------------------
  useEffect(() => {
    const onMessage = (event: MessageEvent) => {
      // Both checks load-bearing, mirroring the loader's own inbound policy:
      // origin pins the message to the page we were configured for, shape
      // check (discriminator) keeps a lookalike object from another script
      // on that page from driving our unread accounting.
      if (!parentOrigin || event.origin !== parentOrigin) return;
      if (!isHostMessage(event.data)) return;
      panelOpenRef.current = event.data.type === "opened";
    };
    const onKeyDown = (event: KeyboardEvent) => {
      // A keydown inside a cross-origin iframe is invisible to the host
      // document, so Escape-to-close must be forwarded from in here.
      if (event.key === "Escape") postToHost(parentOrigin, "close");
    };
    window.addEventListener("message", onMessage);
    window.addEventListener("keydown", onKeyDown);
    // Announce readiness AFTER the listener is attached - the loader replies
    // to "ready" with the current open/closed state, and attaching second
    // would drop that very first reply.
    postToHost(parentOrigin, "ready");
    return () => {
      window.removeEventListener("message", onMessage);
      window.removeEventListener("keydown", onKeyDown);
    };
  }, [parentOrigin]);

  // --- Meta load -----------------------------------------------------------
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
        if (greeting) {
          // The greeting renders as the agent's opening bubble so the panel
          // never opens onto a blank void, but it is host-page copy: local
          // only, no backend turn, no quota spent, and it deliberately does
          // NOT enter the conversation history the backend sees.
          setBubbles([{ id: newId(), role: "agent", text: greeting }]);
        }
      } else {
        setBlockedCode(result.error || "unavailable");
        setPhase("blocked");
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [shareId, greeting]);

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
        // The visitor may have closed the panel while the agent was thinking
        // (turns run up to 45s); tell the launcher so it can show its dot.
        if (!panelOpenRef.current) postToHost(parentOrigin, "unread");
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
    [shareId, sending, parentOrigin]
  );

  const whiteLabeled = isWhiteLabeled(info);
  const panelTitle = title || info?.agentName || "Chat";

  return (
    <div
      className={
        "flex h-dvh w-full flex-col overflow-hidden bg-background text-foreground antialiased" +
        (dark ? " dark" : "")
      }
      style={accentStyle}
    >
      {/* Header: brand lockup + close. h-12 not h-16 - every vertical pixel
          in a 640px-max panel is transcript space. */}
      <header className="flex h-12 shrink-0 items-center gap-2 border-b border-border/60 bg-background/90 px-3">
        {whiteLabeled ? (
          <>
            {info?.brandLogoUrl && (
              // eslint-disable-next-line @next/next/no-img-element
              <img
                src={info.brandLogoUrl}
                alt=""
                className="h-5 w-auto max-w-20 shrink-0 object-contain"
              />
            )}
            <span className="truncate text-sm font-semibold tracking-tight">
              {title || info?.brandDisplayName || info?.agentName}
            </span>
          </>
        ) : (
          <>
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src="/logo-mark.png" alt="" className="h-5 w-auto shrink-0 object-contain" />
            <span className="truncate text-sm font-semibold tracking-tight">{panelTitle}</span>
          </>
        )}
        <span className="inline-flex shrink-0 items-center gap-1 rounded-full border border-primary/40 bg-primary/10 px-2 py-0.5 text-[10px] font-medium text-primary">
          <Sparkles className="size-2.5" />
          AI
        </span>
        {parentOrigin && (
          // Only rendered when a host is actually listening - a directly
          // opened /a/embed URL (no loader) has nothing to close into.
          <button
            type="button"
            onClick={() => postToHost(parentOrigin, "close")}
            aria-label="Close chat"
            className="ml-auto inline-flex size-8 items-center justify-center rounded-md border-0 bg-transparent p-0 text-muted-foreground transition-colors hover:bg-card hover:text-foreground"
          >
            <X className="size-4" />
          </button>
        )}
      </header>

      {phase === "loading" && (
        <div className="flex flex-1 flex-col items-center justify-center gap-3 text-center">
          <Loader2 className="size-5 animate-spin text-primary" />
          <p className="text-xs text-muted-foreground">Opening this chat…</p>
        </div>
      )}

      {phase === "blocked" && !info && (
        <div className="flex flex-1 flex-col items-center justify-center gap-3 px-6 text-center">
          <div className="rounded-full border border-border bg-card/60 p-2.5">
            <Info className="size-4 text-muted-foreground" />
          </div>
          <p className="text-sm text-muted-foreground">{ERROR_COPY[blockedCode].text}</p>
        </div>
      )}

      {(phase === "ready" || (phase === "blocked" && info)) && info && (
        <>
          {/* Provenance, rule 2 - shrunk but never dropped. */}
          <div className="flex items-start gap-1.5 border-b border-border/40 bg-card/30 px-3 py-1.5">
            <Info className="mt-0.5 size-3 shrink-0 text-muted-foreground" />
            <p className="text-[11px] leading-snug text-muted-foreground">
              {whiteLabeled ? (
                <>
                  <span className="text-foreground">{info.agentName}</span> is an AI agent answering
                  on behalf of{" "}
                  <span className="text-foreground">{info.brandDisplayName || "its owner"}</span>.
                </>
              ) : (
                <>
                  <span className="text-foreground">{info.agentName}</span> is an AI agent, built and
                  shared by its owner on <BrandName />.
                </>
              )}
            </p>
          </div>

          <div ref={scrollRef} className="flex-1 space-y-3 overflow-y-auto px-3 py-3">
            {bubbles.length === 0 && (
              <p className="py-8 text-center text-xs text-muted-foreground">
                Say hello — ask {info.agentName} anything.
              </p>
            )}
            {bubbles.map((b) => (
              <ChatBubble key={b.id} bubble={b} />
            ))}
            {sending && <ThinkingBubble />}
          </div>

          {phase === "blocked" ? (
            <div className="shrink-0 border-t border-border/60 bg-card/40 px-4 py-4 text-center">
              <p className="text-xs text-muted-foreground">{ERROR_COPY[blockedCode].text}</p>
            </div>
          ) : (
            <div className="shrink-0 border-t border-border/60 bg-background/90 p-2">
              <div className="flex items-end gap-1.5 rounded-lg border border-border/70 bg-card/50 p-1.5">
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
                  className="max-h-28 min-h-9 flex-1 resize-none bg-transparent px-2 py-1.5 text-sm outline-none placeholder:text-muted-foreground"
                />
                <Button
                  size="icon"
                  onClick={() => send(input)}
                  disabled={sending || !input.trim()}
                  aria-label="Send"
                >
                  <Send className="size-4" />
                </Button>
              </div>
            </div>
          )}
        </>
      )}

      {/* Footer strip: report valve always; conversion line only unbranded.
          Rendered in every phase (including blocked) - a broken or abusive
          agent is precisely when the report link matters most. */}
      <footer className="flex shrink-0 items-center justify-between gap-2 border-t border-border/40 bg-background/90 px-3 py-1.5">
        <ReportControl shareId={shareId} />
        {!whiteLabeled && (
          <a
            href="/"
            target="_blank"
            rel="noopener noreferrer"
            className="truncate text-[10px] text-muted-foreground underline-offset-2 hover:text-foreground hover:underline"
          >
            Built with <BrandName />
          </a>
        )}
      </footer>
    </div>
  );
}

function ChatBubble({ bubble }: { bubble: Bubble }) {
  if (bubble.role === "notice") {
    return (
      <div className="flex justify-center">
        <p className="flex items-center gap-1.5 rounded-full border border-border/70 bg-background/50 px-3 py-1 text-[10px] text-muted-foreground">
          <Info className="size-3 shrink-0" aria-hidden="true" />
          {bubble.text}
        </p>
      </div>
    );
  }
  const isVisitor = bubble.role === "visitor";
  return (
    <div className={isVisitor ? "flex justify-end" : "flex justify-start"}>
      <div
        className={
          isVisitor
            ? "max-w-[85%] rounded-2xl rounded-br-sm bg-primary px-3 py-2 text-sm text-primary-foreground"
            : "max-w-[85%] rounded-2xl rounded-bl-sm border border-border/60 bg-card/70 px-3 py-2 text-sm"
        }
      >
        <p className="whitespace-pre-wrap leading-relaxed">{bubble.text}</p>
      </div>
    </div>
  );
}

/** CSS-only thinking indicator - the full-page viewer's motion/react version
 *  was the one thing keeping that library in this bundle, and animate-pulse
 *  with stagger via animationDelay reads identically at this size. */
function ThinkingBubble() {
  return (
    <div className="flex justify-start">
      <div className="flex items-center gap-1.5 rounded-2xl rounded-bl-sm border border-border/60 bg-card/70 px-3 py-2.5">
        {[0, 1, 2].map((i) => (
          <span
            key={i}
            className="size-1.5 animate-pulse rounded-full bg-muted-foreground"
            style={{ animationDelay: `${i * 180}ms` }}
          />
        ))}
      </div>
    </div>
  );
}

const REPORT_REASONS = [
  { id: "spam", label: "Spam or scam" },
  { id: "abusive", label: "Abusive or harmful" },
  { id: "impersonation", label: "Impersonating someone" },
  { id: "other", label: "Something else" },
];

/** Compact report affordance - same reasons and same fire-and-thank flow as
 *  the full-page viewer's ReportPanel, collapsed to footer scale. */
function ReportControl({ shareId }: { shareId: string }) {
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

  if (done) {
    return (
      <p className="text-[10px] text-muted-foreground" role="status">
        Thanks — this agent will be reviewed.
      </p>
    );
  }

  if (!open) {
    return (
      <button
        type="button"
        onClick={() => setOpen(true)}
        className="inline-flex items-center gap-1 border-0 bg-transparent p-0 text-[10px] text-muted-foreground underline-offset-2 transition-colors hover:text-foreground hover:underline"
      >
        <Flag className="size-2.5" aria-hidden="true" />
        Report
      </button>
    );
  }

  return (
    <div className="flex flex-wrap items-center gap-1">
      {REPORT_REASONS.map((r) => (
        <button
          key={r.id}
          type="button"
          disabled={busy}
          onClick={() => report(r.id)}
          className="rounded-full border border-border/70 bg-card/50 px-2 py-0.5 text-[10px] text-muted-foreground transition-colors hover:border-primary/50 hover:text-foreground disabled:cursor-not-allowed disabled:opacity-50"
        >
          {r.label}
        </button>
      ))}
      <button
        type="button"
        onClick={() => setOpen(false)}
        className="border-0 bg-transparent p-0 text-[10px] text-muted-foreground transition-colors hover:text-foreground"
      >
        Cancel
      </button>
    </div>
  );
}
