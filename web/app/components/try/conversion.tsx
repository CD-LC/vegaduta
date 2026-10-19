"use client";

import { useCallback, useState } from "react";
import Link from "next/link";
import { motion, AnimatePresence, useReducedMotion } from "motion/react";
import { Check, Loader2, Mail, Share2, X } from "lucide-react";
import { Button } from "../ui/button";
import { auth } from "../../lib/auth";
import { emailTranscript } from "./demoClient";
import { useTranslations } from "../../lib/i18n";

// ---------------------------------------------------------------------------
// The conversion pack for /try — every register affordance on the sandbox in
// one place, so they all say the same true things the same way:
//
//   * "Free forever tier · no card needed" — TRUE: signup provisions the FREE
//     plan and collects no payment method. Every CTA carries this sub-line.
//   * One-tap social register (Google / GitHub / Microsoft) — Keycloak
//     identity brokering via auth.login({ idpHint }), same mechanism as
//     signup/SocialLoginRow. Before the OAuth redirect leaves this page, the
//     demo token is persisted to localStorage (VD_DEMO_TOKEN_KEY) so
//     OnboardingGate can hand it to POST /api/onboarding after the round-trip
//     and the demo→real-tenant conversion gets counted.
//   * Banners are dismissible and NEVER come back once dismissed — a
//     sessionStorage flag per banner. A nudge that reappears after being
//     waved away is spam, and spam converts nobody.
//
// Inherits /try's one hard rule: nothing in this file ever shows an error
// surface. Failures resolve into sentences or into silence.
// ---------------------------------------------------------------------------

/**
 * Where the demo token waits out the OAuth redirect. Deliberately a SECOND
 * key, separate from demoClient's DEMO_TOKEN_KEY ("vegaduta.demoToken"):
 * that one means "there is a live sandbox to resume on /try", this one means
 * "a conversion is in flight — attach me to the next signup/onboarding call".
 * OnboardingGate clears this key on success; clearing the other would kill a
 * sandbox that may still have days left.
 */
export const VD_DEMO_TOKEN_KEY = "vd_demo_token";

export function persistDemoToken(token: string | null): void {
  if (!token) return;
  try {
    window.localStorage.setItem(VD_DEMO_TOKEN_KEY, token);
  } catch {
    // Storage blocked (private browsing). The signup link still carries
    // ?demo=<token> in its URL, so the email path survives; only the social
    // round-trip loses the link. Degrade, never break.
  }
}

function readSessionFlag(key: string): boolean {
  if (typeof window === "undefined") return false;
  try {
    return window.sessionStorage.getItem(key) === "1";
  } catch {
    return false;
  }
}

function writeSessionFlag(key: string): void {
  try {
    window.sessionStorage.setItem(key, "1");
  } catch {
    /* see readSessionFlag */
  }
}

// --- social brand marks ------------------------------------------------------
// Same inline SVGs as signup/SocialLoginRow (Google's branding guidelines want
// the multicolor "G" on a light chip even inside a dark button).
// The fills below are Google's and Microsoft's own brand colours, fixed by
// their branding guidelines - not theme tokens, so the hex literals stay
// (A13: brand asset, same posture as BrandIcons.tsx).
/* eslint-disable design-tokens/no-hex-literal -- third-party brand marks */

function GoogleIcon() {
  return (
    <svg viewBox="0 0 18 18" width="13" height="13" aria-hidden="true" focusable="false">
      <path
        fill="#4285F4"
        d="M17.64 9.2c0-.637-.057-1.251-.164-1.84H9v3.481h4.844c-.209 1.125-.843 2.078-1.796 2.717v2.259h2.908c1.702-1.567 2.684-3.874 2.684-6.617z"
      />
      <path
        fill="#34A853"
        d="M9 18c2.43 0 4.467-.806 5.956-2.18l-2.908-2.259c-.806.54-1.837.86-3.048.86-2.344 0-4.328-1.584-5.036-3.711H.957v2.332C2.438 15.983 5.482 18 9 18z"
      />
      <path
        fill="#FBBC05"
        d="M3.964 10.71c-.18-.54-.282-1.117-.282-1.71s.102-1.17.282-1.71V4.958H.957C.348 6.173 0 7.548 0 9s.348 2.827.957 4.042l3.007-2.332z"
      />
      <path
        fill="#EA4335"
        d="M9 3.58c1.321 0 2.508.454 3.44 1.345l2.582-2.58C13.463.891 11.426 0 9 0 5.482 0 2.438 2.017.957 4.958L3.964 7.29C4.672 5.163 6.656 3.58 9 3.58z"
      />
    </svg>
  );
}

function GitHubIcon() {
  return (
    <svg viewBox="0 0 16 16" width="14" height="14" fill="currentColor" aria-hidden="true" focusable="false">
      <path d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.013 8.013 0 0016 8c0-4.42-3.58-8-8-8z" />
    </svg>
  );
}

function MicrosoftIcon() {
  return (
    <svg viewBox="0 0 21 21" width="13" height="13" aria-hidden="true" focusable="false">
      <rect x="1" y="1" width="9" height="9" fill="#F25022" />
      <rect x="11" y="1" width="9" height="9" fill="#7FBA00" />
      <rect x="1" y="11" width="9" height="9" fill="#00A4EF" />
      <rect x="11" y="11" width="9" height="9" fill="#FFB900" />
    </svg>
  );
}
/* eslint-enable design-tokens/no-hex-literal */

const PROVIDERS = [
  { alias: "google", label: "Google", Icon: GoogleIcon, chip: true },
  { alias: "github", label: "GitHub", Icon: GitHubIcon, chip: false },
  { alias: "microsoft", label: "Microsoft", Icon: MicrosoftIcon, chip: false },
];

/** The honest sub-line every register CTA carries. One string, one place. */
export function FreeForeverLine({ className }: { className?: string }) {
  const t = useTranslations("try");
  return <p className={`text-[11px] text-muted-foreground ${className || ""}`}>{t("conv_freeForeverLine")}</p>;
}

/**
 * The one-tap register row: three social buttons + the email fallback link.
 * Each social click persists the demo token to localStorage FIRST — the OAuth
 * redirect is a full page navigation, and without that write the conversion
 * link would be lost the instant the browser leaves for Keycloak.
 */
export function OneTapButtons({
  token,
  signupHref,
  center,
}: {
  token: string | null;
  signupHref: string;
  center?: boolean;
}) {
  const t = useTranslations("try");
  const go = useCallback(
    (alias: string) => {
      persistDemoToken(token);
      void auth.login({ idpHint: alias, redirectUri: `${window.location.origin}/` });
    },
    [token]
  );

  return (
    <div className={`flex flex-col gap-2 ${center ? "items-center" : "items-start"}`}>
      <div className="flex flex-wrap gap-2">
        {PROVIDERS.map(({ alias, label, Icon, chip }) => (
          <Button
            key={alias}
            size="sm"
            variant="outline"
            aria-label={t("conv_continueWith", { label })}
            onClick={() => go(alias)}
          >
            <span
              className={chip ? "grid size-4 shrink-0 place-items-center rounded-sm bg-white" : "inline-flex shrink-0"}
              aria-hidden="true"
            >
              <Icon />
            </span>
            {label}
          </Button>
        ))}
      </div>
      <p className="text-xs text-muted-foreground">
        {t("conv_orWord")}{" "}
        <Link
          href={signupHref}
          onClick={() => persistDemoToken(token)}
          className="text-primary underline-offset-2 hover:underline"
        >
          {t("conv_orSignUpEmail")}
        </Link>
      </p>
      <FreeForeverLine />
    </div>
  );
}

/**
 * The slide-in register nudge, fired at a moment of delight (first real reply;
 * first Telegram reply on the visitor's own phone). Dismissible, and once
 * dismissed it stays dismissed for the whole browser session — the
 * sessionStorage flag is keyed per banner, so waving away the chat nudge does
 * not silence the Telegram one, which is a genuinely different moment.
 */
export function RegisterBanner({
  id,
  show,
  headline,
  token,
  signupHref,
  className,
}: {
  /** sessionStorage key that records dismissal, e.g. "vd_try_banner_delight". */
  id: string;
  show: boolean;
  headline: string;
  token: string | null;
  signupHref: string;
  className?: string;
}) {
  const t = useTranslations("try");
  const reduceMotion = useReducedMotion();
  const [dismissed, setDismissed] = useState(() => readSessionFlag(id));

  const dismiss = useCallback(() => {
    setDismissed(true);
    writeSessionFlag(id);
  }, [id]);

  return (
    <AnimatePresence>
      {show && !dismissed && (
        <motion.div
          key={id}
          initial={reduceMotion ? { opacity: 0 } : { opacity: 0, y: 14 }}
          animate={{ opacity: 1, y: 0 }}
          exit={reduceMotion ? { opacity: 0 } : { opacity: 0, y: 8 }}
          transition={{ duration: 0.3, ease: [0.16, 1, 0.3, 1] }}
          className={`relative rounded-xl border border-primary/40 bg-primary/10 px-4 py-3.5 ${className || ""}`}
        >
          <button
            type="button"
            onClick={dismiss}
            aria-label={t("conv_dismissAria")}
            // p-1/border-0/bg-transparent beat globals.css's blanket `button {}`
            // padding rule, which otherwise clips icon-only buttons.
            className="absolute right-2 top-2 rounded-md border-0 bg-transparent p-1 text-muted-foreground transition-colors hover:text-foreground"
          >
            <X aria-hidden="true" className="size-3.5" />
          </button>
          <p className="pr-8 text-sm font-medium text-foreground">{headline}</p>
          <div className="mt-3">
            <OneTapButtons token={token} signupHref={signupHref} />
          </div>
        </motion.div>
      )}
    </AnimatePresence>
  );
}

/**
 * What a hit quota turns into: not a dead end, a doorway. Replaces the
 * composer when messages or images run out. The copy states the real number
 * the visitor was given and does not invent registered-tier numbers — the only
 * specifics quoted are real plan_limits (5 agents, 3 workflows, 2 team
 * members), supplied by the caller.
 */
export function CapConversion({
  headline,
  sub,
  token,
  signupHref,
}: {
  headline: string;
  sub?: string;
  token: string | null;
  signupHref: string;
}) {
  return (
    <div className="flex flex-col items-center gap-3 rounded-xl border border-primary/30 bg-primary/5 px-4 py-5 text-center">
      <p className="text-sm text-foreground">{headline}</p>
      {sub && <p className="max-w-md text-xs text-muted-foreground">{sub}</p>}
      <OneTapButtons token={token} signupHref={signupHref} center />
    </div>
  );
}

/**
 * "Email me this conversation" — collapsed to a single quiet line until asked
 * for. Success and the 2-per-sandbox limit both arrive as the server's own
 * sentence ({sent, message}); a network failure resolves to a soft sentence.
 * Never an error wall, per the page's standing rule.
 */
export function TranscriptEmailer({
  token,
  visible,
  sessionId,
}: {
  token: string | null;
  visible: boolean;
  /** The /api/demo/chat sessionId — lets the backend include the web-chat
   *  transcript (Redis history is keyed by it). Optional by design. */
  sessionId?: string | null;
}) {
  const t = useTranslations("try");
  const [open, setOpen] = useState(false);
  const [email, setEmail] = useState("");
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<{ sent: boolean; message: string } | null>(null);

  const send = useCallback(async () => {
    const trimmed = email.trim();
    if (!token || busy || !trimmed) return;
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(trimmed)) {
      setResult({
        sent: false,
        message: t("conv_invalidEmail"),
      });
      return;
    }
    setBusy(true);
    const r = await emailTranscript(token, trimmed, sessionId);
    setResult(r);
    setBusy(false);
    if (r.sent) setOpen(false);
  }, [token, busy, email, sessionId, t]);

  if (!token || !visible) return null;

  return (
    <div className="flex flex-col gap-2 rounded-xl border border-border/60 bg-card/40 px-4 py-3">
      {!open ? (
        <button
          type="button"
          onClick={() => {
            setOpen(true);
            setResult(null);
          }}
          className="flex items-center gap-2 self-start border-0 bg-transparent p-0 text-xs text-muted-foreground transition-colors hover:text-foreground"
        >
          <Mail className="size-3.5" aria-hidden="true" />
          {t("conv_emailConversation")}
        </button>
      ) : (
        <div className="flex flex-wrap items-center gap-2">
          <Mail className="size-3.5 shrink-0 text-muted-foreground" aria-hidden="true" />
          <input
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter") {
                e.preventDefault();
                void send();
              }
            }}
            autoFocus
            maxLength={254}
            placeholder={t("conv_emailPlaceholder")}
            aria-label={t("conv_emailAria")}
            className="min-w-0 flex-1 rounded-md border border-border/70 bg-background/40 px-2.5 py-1.5 text-xs outline-none placeholder:text-muted-foreground"
          />
          <Button size="sm" onClick={send} disabled={busy || !email.trim()}>
            {busy ? <Loader2 aria-hidden="true" className="size-3.5 animate-spin" /> : t("conv_send")}
          </Button>
          <button
            type="button"
            onClick={() => setOpen(false)}
            className="border-0 bg-transparent p-0 text-xs text-muted-foreground transition-colors hover:text-foreground"
          >
            {t("conv_cancel")}
          </button>
        </div>
      )}
      {result && (
        <p className="flex items-center gap-1.5 text-xs text-muted-foreground" role="status">
          {result.sent && <Check className="size-3 shrink-0 text-emerald-400" aria-hidden="true" />}
          {result.message}
        </p>
      )}
    </div>
  );
}

// --- shareable proof ---------------------------------------------------------
// A clean dark share card drawn entirely client-side on a <canvas> — no
// backend, no html2canvas, no deps. Text replies only: a generated image
// fetched from the API origin would taint the canvas (no CORS headers on that
// endpoint) and make toBlob() throw, which is exactly the kind of surprise
// this page bans.

function wrapLines(ctx: CanvasRenderingContext2D, text: string, maxWidth: number, maxLines: number): string[] {
  const words = text.replace(/\s+/g, " ").trim().split(" ").filter(Boolean);
  const lines: string[] = [];
  let line = "";
  let overflowed = false;
  for (let i = 0; i < words.length; i++) {
    const probe = line ? `${line} ${words[i]}` : words[i];
    if (ctx.measureText(probe).width <= maxWidth) {
      line = probe;
      continue;
    }
    if (!line) {
      // A single word wider than the card — take it anyway rather than loop.
      line = words[i];
      continue;
    }
    if (lines.length + 1 >= maxLines) {
      overflowed = true;
      break;
    }
    lines.push(line);
    line = words[i];
  }
  if (line) lines.push(line);
  if (overflowed && lines.length > 0) {
    let last = lines[lines.length - 1];
    while (last.length > 1 && ctx.measureText(`${last}…`).width > maxWidth) {
      last = last.slice(0, -1);
    }
    lines[lines.length - 1] = `${last}…`;
  }
  return lines;
}

// Share-card palette. The card is rasterised to a PNG and leaves the app
// (Web Share / download), so it is deliberately NOT themed: it always renders
// the sandbox's own dark look whatever data-mode/data-accent the visitor is
// in, and a 2D canvas cannot read var(--...) anyway. `accent` is the fixed
// "Dūta" orange (globals.css --brand-accent, #f97316 in both modes); the rest
// are the card's own neutrals. Kept in one place so the literals are
// visible and the exemption is scoped (A13).
/* eslint-disable design-tokens/no-hex-literal -- exported raster, not UI chrome */
const SHARE_CARD = {
  bg: "#0c0a12",
  wordmark: "#fafafa",
  accent: "#f97316",
  question: "#a1a1aa",
  reply: "#ececf1",
};
/* eslint-enable design-tokens/no-hex-literal */

function drawShareCard(question: string, reply: string, tagline: string): HTMLCanvasElement | null {
  const W = 1080;
  const H = 1080;
  const M = 96; // margin
  const canvas = document.createElement("canvas");
  canvas.width = W;
  canvas.height = H;
  const ctx = canvas.getContext("2d");
  if (!ctx) return null;

  // Ground — the sandbox's own dark, plus a faint violet bloom up top.
  ctx.fillStyle = SHARE_CARD.bg;
  ctx.fillRect(0, 0, W, H);
  const glow = ctx.createRadialGradient(W / 2, 0, 0, W / 2, 0, 720);
  glow.addColorStop(0, "rgba(139,131,248,0.18)");
  glow.addColorStop(1, "rgba(139,131,248,0)");
  ctx.fillStyle = glow;
  ctx.fillRect(0, 0, W, H);

  ctx.textBaseline = "top";
  const stack = "'Segoe UI', system-ui, -apple-system, sans-serif";

  // Wordmark — white "Vega", orange "Dūta".
  ctx.font = `600 58px ${stack}`;
  ctx.fillStyle = SHARE_CARD.wordmark;
  ctx.fillText("Vega", M, M);
  const vegaWidth = ctx.measureText("Vega").width;
  ctx.fillStyle = SHARE_CARD.accent;
  ctx.fillText("Dūta", M + vegaWidth, M);

  // The visitor's question, quoted and muted.
  let y = M + 128;
  ctx.font = `400 34px ${stack}`;
  ctx.fillStyle = SHARE_CARD.question;
  for (const l of wrapLines(ctx, `“${question}”`, W - M * 2, 3)) {
    ctx.fillText(l, M, y);
    y += 48;
  }

  // Divider.
  y += 22;
  ctx.fillStyle = "rgba(255,255,255,0.12)";
  ctx.fillRect(M, y, W - M * 2, 2);
  y += 40;

  // The agent's reply — the star of the card.
  ctx.font = `400 40px ${stack}`;
  ctx.fillStyle = SHARE_CARD.reply;
  for (const l of wrapLines(ctx, reply, W - M * 2, 11)) {
    ctx.fillText(l, M, y);
    y += 58;
  }

  // Footer claim — true (signup provisions the FREE plan, no payment method).
  ctx.font = `500 32px ${stack}`;
  ctx.fillStyle = SHARE_CARD.accent;
  ctx.fillText(tagline, M, H - M - 32);

  return canvas;
}

async function shareReplyCard(
  question: string,
  reply: string,
  tagline: string,
  nativeShareTitle: string
): Promise<void> {
  const canvas = drawShareCard(question, reply, tagline);
  if (!canvas) return;
  const blob: Blob | null = await new Promise((resolve) => {
    try {
      canvas.toBlob((b) => resolve(b), "image/png");
    } catch {
      resolve(null);
    }
  });
  if (!blob) return;

  // Native share sheet where the platform has one (phones, mostly) — a card
  // that lands straight in a WhatsApp group is worth ten downloads. If the
  // visitor cancels the sheet, that is a decision, not a failure: stop there
  // rather than forcing a download they just declined.
  try {
    const nav: any = navigator;
    if (typeof File !== "undefined" && nav.share && nav.canShare) {
      const file = new File([blob], "vegaduta-agent.png", { type: "image/png" });
      if (nav.canShare({ files: [file] })) {
        await nav.share({
          files: [file],
          title: nativeShareTitle,
          text: tagline,
        });
        return;
      }
    }
  } catch {
    // Share sheet dismissed or unavailable — nothing to report.
    return;
  }

  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = "vegaduta-agent.png";
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 4000);
}

/** The small share affordance under a real (non-simulated) text reply. */
export function ShareReplyButton({ question, reply }: { question: string; reply: string }) {
  const t = useTranslations("try");
  const [state, setState] = useState<"idle" | "busy" | "done">("idle");

  const onShare = useCallback(async () => {
    if (state === "busy") return;
    setState("busy");
    try {
      await shareReplyCard(question, reply, t("conv_shareCardTagline"), t("conv_shareCardNativeTitle"));
      setState("done");
      setTimeout(() => setState("idle"), 1800);
    } catch {
      // Whatever happened, it is not the visitor's problem.
      setState("idle");
    }
  }, [question, reply, state, t]);

  return (
    <button
      type="button"
      onClick={onShare}
      title={t("conv_shareReplyTitle")}
      aria-label={t("conv_shareReplyTitle")}
      className="mt-1.5 inline-flex items-center gap-1 border-0 bg-transparent p-0 text-[10px] text-muted-foreground transition-colors hover:text-foreground"
    >
      {state === "busy" ? (
        <Loader2 className="size-3 animate-spin" aria-hidden="true" />
      ) : state === "done" ? (
        <Check className="size-3" aria-hidden="true" />
      ) : (
        <Share2 className="size-3" aria-hidden="true" />
      )}
      {t("conv_share")}
    </button>
  );
}
