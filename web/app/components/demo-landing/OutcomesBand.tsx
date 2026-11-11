"use client";

// OutcomesBand (2026-09-17) — the band directly under the hero.
//
// The funnel showed the largest single drop between the hero and the next
// band. Whatever the cause, the next band's job is to hold the reader with
// the thing the hero could only promise: a picture of what the product
// DOES. Four outcomes, each a shipped capability, each with its own
// illustration (inline SVG — theme-aware through the marketing tokens, no
// asset fetch, no layout shift), a one-line "you type" brief, and a line on
// what actually happens. No customers, no testimonials, no invented
// numbers: mechanism only, which is the same rule ProofBand was held to.
//
// Honesty ledger:
//   WhatsApp             -> Meta Cloud API or Twilio (official paths only);
//                           "connect your number" is real.
//   voice answers calls  -> ChannelType VOICE; the agent answers, it does
//                           not place outbound calls.
//   workflows            -> core/workflow: schedule/webhook/event triggers,
//                           branches, parallel fan-out, approval node.
//   code                 -> code-intel graph search + sandboxed workspaces;
//                           the diff-before-merge is the real gate.
// Section id "outcomes" is what the nav's "How it works" now points at
// (the previous target, #senses, was unmounted on 2026-09-12).

import type { ReactElement } from "react";
import Link from "next/link";
import { ArrowRight } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { useJourney } from "../../lib/journey";
import { sendLandingEvent } from "./landingBeacon";

type Art = (props: { className?: string }) => ReactElement;

const ACC = "var(--mkt-accent)";
const FLOW = "var(--mkt-flow)";
const POS = "var(--mkt-positive)";
const WARM = "var(--mkt-warm)";
const LINE = "var(--mkt-line)";
const SURF = "var(--mkt-surface)";
const RAISED = "var(--mkt-surface-raised)";
const INK3 = "var(--mkt-ink-3)";

/* A phone with a WhatsApp-style exchange: question in, priced answer out. */
const WhatsAppArt: Art = ({ className }) => {
  const tHC = useTranslations("hcComponentsDemolandingOutcomesBand");
  return (
    <svg viewBox="0 0 320 200" className={className} role="img" aria-hidden="true">
      <defs>
        <linearGradient id="oc-g1" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor={ACC} stopOpacity="0.35" />
          <stop offset="1" stopColor={FLOW} stopOpacity="0.15" />
        </linearGradient>
      </defs>
      <circle cx="240" cy="100" r="86" fill="url(#oc-g1)" />
      <rect x="96" y="14" width="128" height="184" rx="18" fill={RAISED} stroke={LINE} />
      <rect x="96" y="14" width="128" height="30" rx="18" fill="#25D366" opacity="0.9" />
      <rect x="96" y="30" width="128" height="14" fill="#25D366" opacity="0.9" />
      <circle cx="112" cy="29" r="7" fill="#fff" opacity="0.9" />
      <rect x="124" y="24" width="56" height="4" rx="2" fill="#fff" opacity="0.9" />
      <rect x="124" y="32" width="28" height="3" rx="1.5" fill="#fff" opacity="0.6" />
      {/* customer bubble */}
      <rect x="134" y="56" width="80" height="30" rx="8" fill="#25D366" opacity="0.28" />
      <rect x="142" y="63" width="60" height="4" rx="2" fill={INK3} />
      <rect x="142" y="72" width="40" height="4" rx="2" fill={INK3} />
      {/* agent bubble */}
      <rect x="106" y="94" width="96" height="52" rx="8" fill={SURF} stroke={LINE} />
      <rect x="114" y="102" width="70" height="4" rx="2" fill={INK3} />
      <rect x="114" y="111" width="80" height="4" rx="2" fill={INK3} />
      <rect x="114" y="120" width="52" height="4" rx="2" fill={INK3} />
      <rect x="114" y="131" width="44" height="9" rx="4.5" fill={ACC} />
      <text x="136" y="138" fontSize="6.5" fill="#fff" textAnchor="middle" fontFamily="ui-sans-serif, system-ui">
        {tHC("s1")}
      </text>
      {/* typing dots */}
      <circle cx="116" cy="164" r="3" fill={ACC} />
      <circle cx="126" cy="164" r="3" fill={ACC} opacity="0.6" />
      <circle cx="136" cy="164" r="3" fill={ACC} opacity="0.3" />
      {/* side chips: what it read */}
      <rect x="16" y="70" width="70" height="18" rx="9" fill={SURF} stroke={LINE} />
      <circle cx="26" cy="79" r="3" fill={POS} />
      <rect x="34" y="77" width="44" height="4" rx="2" fill={INK3} />
      <rect x="16" y="96" width="70" height="18" rx="9" fill={SURF} stroke={LINE} />
      <circle cx="26" cy="105" r="3" fill={POS} />
      <rect x="34" y="103" width="36" height="4" rx="2" fill={INK3} />
      <rect x="16" y="122" width="70" height="18" rx="9" fill={SURF} stroke={LINE} />
      <circle cx="26" cy="131" r="3" fill={WARM} />
      <rect x="34" y="129" width="40" height="4" rx="2" fill={INK3} />
    </svg>
  );
};

/* A live call: waveform, transcript lines, a language chip. */
const VoiceArt: Art = ({ className }) => (
  <svg viewBox="0 0 320 200" className={className} role="img" aria-hidden="true">
    <defs>
      <linearGradient id="oc-g2" x1="0" y1="0" x2="1" y2="0">
        <stop offset="0" stopColor={FLOW} stopOpacity="0.3" />
        <stop offset="1" stopColor={ACC} stopOpacity="0.1" />
      </linearGradient>
    </defs>
    <rect x="16" y="26" width="288" height="148" rx="16" fill={RAISED} stroke={LINE} />
    <circle cx="44" cy="52" r="11" fill={POS} opacity="0.2" />
    <circle cx="44" cy="52" r="5" fill={POS} />
    <rect x="62" y="46" width="90" height="5" rx="2.5" fill={INK3} />
    <rect x="62" y="56" width="50" height="4" rx="2" fill={INK3} opacity="0.6" />
    <rect x="228" y="42" width="60" height="18" rx="9" fill={ACC} opacity="0.18" />
    <rect x="238" y="49" width="40" height="4" rx="2" fill={ACC} />
    {/* waveform */}
    <rect x="30" y="80" width="260" height="44" rx="10" fill="url(#oc-g2)" />
    {Array.from({ length: 26 }).map((_, i) => {
      const h = [6, 12, 20, 30, 18, 10, 24, 34, 16, 8, 14, 28, 36, 22, 12, 18, 30, 20, 10, 16, 26, 14, 8, 20, 12, 6][i];
      return (
        <rect
          key={i}
          x={40 + i * 9.5}
          y={102 - h / 2}
          width="4"
          height={h}
          rx="2"
          fill={i % 3 === 0 ? ACC : FLOW}
          opacity={0.85}
        />
      );
    })}
    {/* transcript */}
    <rect x="30" y="136" width="160" height="5" rx="2.5" fill={INK3} />
    <rect x="30" y="147" width="120" height="5" rx="2.5" fill={INK3} opacity="0.7" />
    <rect x="30" y="158" width="200" height="5" rx="2.5" fill={ACC} opacity="0.8" />
    <rect x="240" y="136" width="50" height="26" rx="8" fill={SURF} stroke={LINE} />
    <text x="265" y="153" fontSize="9" fill={INK3} textAnchor="middle" fontFamily="ui-sans-serif, system-ui">
      हिंदी
    </text>
  </svg>
);

/* A workflow canvas: trigger → agent → condition → approval → action. */
const WorkflowArt: Art = ({ className }) => (
  <svg viewBox="0 0 320 200" className={className} role="img" aria-hidden="true">
    <rect x="16" y="20" width="288" height="160" rx="16" fill={RAISED} stroke={LINE} />
    <g stroke={LINE} strokeWidth="1" opacity="0.5">
      {Array.from({ length: 6 }).map((_, i) => (
        <line key={`v${i}`} x1={40 + i * 48} y1="24" x2={40 + i * 48} y2="176" />
      ))}
      {Array.from({ length: 3 }).map((_, i) => (
        <line key={`h${i}`} x1="20" y1={60 + i * 40} x2="300" y2={60 + i * 40} />
      ))}
    </g>
    {/* edges */}
    <path d="M84 70 C 110 70, 110 70, 134 70" stroke={ACC} strokeWidth="2" fill="none" />
    <path d="M186 70 C 210 70, 210 70, 232 70" stroke={ACC} strokeWidth="2" fill="none" />
    <path d="M258 90 C 258 120, 230 130, 200 130" stroke={WARM} strokeWidth="2" fill="none" />
    <path d="M148 130 C 120 130, 110 130, 84 130" stroke={POS} strokeWidth="2" fill="none" />
    {/* nodes */}
    <rect x="36" y="56" width="48" height="28" rx="8" fill={SURF} stroke={FLOW} />
    <rect x="44" y="66" width="32" height="4" rx="2" fill={FLOW} />
    <rect x="134" y="56" width="52" height="28" rx="8" fill={SURF} stroke={ACC} />
    <rect x="142" y="66" width="36" height="4" rx="2" fill={ACC} />
    <rect x="232" y="52" width="52" height="36" rx="8" fill={SURF} stroke={ACC} transform="rotate(0)" />
    <path d="M258 58 L 274 70 L 258 82 L 242 70 Z" fill={ACC} opacity="0.25" stroke={ACC} />
    <rect x="148" y="116" width="52" height="28" rx="8" fill={SURF} stroke={WARM} />
    <circle cx="160" cy="130" r="4" fill={WARM} />
    <rect x="168" y="128" width="26" height="4" rx="2" fill={WARM} />
    <rect x="36" y="116" width="48" height="28" rx="8" fill={SURF} stroke={POS} />
    <rect x="44" y="126" width="32" height="4" rx="2" fill={POS} />
    {/* run status */}
    <rect x="36" y="156" width="120" height="12" rx="6" fill={POS} opacity="0.18" />
    <circle cx="44" cy="162" r="3" fill={POS} />
    <rect x="52" y="160" width="80" height="4" rx="2" fill={POS} />
  </svg>
);

/* A code panel with a diff and a call-graph blob beside it. */
const CodeArt: Art = ({ className }) => (
  <svg viewBox="0 0 320 200" className={className} role="img" aria-hidden="true">
    <rect x="16" y="20" width="200" height="160" rx="14" fill={RAISED} stroke={LINE} />
    <rect x="16" y="20" width="200" height="22" rx="14" fill={SURF} />
    <circle cx="30" cy="31" r="3" fill={WARM} opacity="0.8" />
    <circle cx="40" cy="31" r="3" fill={INK3} opacity="0.5" />
    <circle cx="50" cy="31" r="3" fill={POS} opacity="0.8" />
    {[
      ["-", 60, 100, WARM],
      ["+", 72, 130, POS],
      ["+", 84, 90, POS],
      [" ", 96, 110, INK3],
      [" ", 108, 70, INK3],
      ["+", 120, 120, POS],
      [" ", 132, 60, INK3],
      [" ", 144, 100, INK3],
      ["✓", 160, 90, POS],
    ].map(([sign, y, w, c], i) => (
      <g key={i}>
        <text x="28" y={(y as number) + 4} fontSize="9" fill={c as string} fontFamily="ui-monospace, monospace">
          {sign as string}
        </text>
        <rect
          x="40"
          y={y as number}
          width={w as number}
          height="5"
          rx="2.5"
          fill={c as string}
          opacity={sign === " " ? 0.45 : 0.85}
        />
      </g>
    ))}
    {/* call graph */}
    <g stroke={ACC} strokeWidth="1.5" opacity="0.8">
      <line x1="262" y1="60" x2="240" y2="100" />
      <line x1="262" y1="60" x2="286" y2="100" />
      <line x1="240" y1="100" x2="230" y2="140" />
      <line x1="240" y1="100" x2="262" y2="140" />
      <line x1="286" y1="100" x2="292" y2="140" />
    </g>
    <circle cx="262" cy="60" r="9" fill={ACC} />
    <circle cx="240" cy="100" r="7" fill={SURF} stroke={ACC} />
    <circle cx="286" cy="100" r="7" fill={SURF} stroke={ACC} />
    <circle cx="230" cy="140" r="6" fill={SURF} stroke={FLOW} />
    <circle cx="262" cy="140" r="6" fill={SURF} stroke={FLOW} />
    <circle cx="292" cy="140" r="6" fill={SURF} stroke={FLOW} />
    <rect x="228" y="160" width="68" height="14" rx="7" fill={SURF} stroke={LINE} />
    <rect x="236" y="165" width="52" height="4" rx="2" fill={INK3} />
  </svg>
);

const OUTCOMES: Array<{ id: string; art: Art }> = [
  { id: "wa", art: WhatsAppArt },
  { id: "voice", art: VoiceArt },
  { id: "flow", art: WorkflowArt },
  { id: "code", art: CodeArt },
];

export default function OutcomesBand() {
  const t = useTranslations("landing");
  const journey = useJourney();
  const tried = journey.stage === "keep";

  return (
    <section id="outcomes" className="mkt-band mkt-band--ruled scroll-mt-16">
      <div className="mkt-band__inner">
        <div className="mkt-head mkt-reveal">
          <p className="mkt-eyebrow">{t("oc_eyebrow")}</p>
          <h2 className="mkt-h2">{t("oc_title")}</h2>
          <p className="mkt-section-sub mkt-ink-2">{t("oc_sub")}</p>
        </div>

        <div className="mkt-reveal-group mt-10 grid gap-5 md:grid-cols-2">
          {OUTCOMES.map(({ id, art: Art }) => (
            <article key={id} className="mkt-card mkt-card--interactive mkt-reveal flex flex-col overflow-hidden p-0">
              <div
                className="border-b px-4 pt-4"
                style={{ borderColor: LINE, background: "color-mix(in oklab, var(--mkt-accent) 4%, transparent)" }}
              >
                <Art className="mx-auto h-auto w-full max-w-[420px]" />
              </div>
              <div className="flex flex-1 flex-col p-5">
                <h3 className="mkt-h3">{t(`oc_${id}_title`)}</h3>
                <p className="mkt-body mkt-ink-2 mt-2">{t(`oc_${id}_body`)}</p>
                <p className="mkt-caption mkt-ink-3 mt-4">
                  <span className="mkt-ui-label">{t("h2_youType")}</span>{" "}
                  <span className="italic">“{t(`oc_${id}_brief`)}”</span>
                </p>
              </div>
            </article>
          ))}
        </div>

        <div className="mt-10 flex flex-col items-center gap-3 sm:flex-row sm:justify-center">
          <Link
            href={journey.signupHref}
            onClick={() => sendLandingEvent("outcomes_signup_click")}
            className="mkt-btn mkt-btn-primary mkt-btn-lg w-full sm:w-auto"
          >
            {tried ? t("hero_ctaKeepAgent") : t("oc_ctaSignup")} <ArrowRight className="size-4" aria-hidden="true" />
          </Link>
          <Link
            href={journey.tryHref}
            onClick={() => sendLandingEvent("outcomes_try_click")}
            className="mkt-btn mkt-btn-secondary mkt-btn-lg w-full sm:w-auto"
          >
            {tried ? t("hero_ctaReopenSandbox") : t("oc_ctaTry")}
          </Link>
        </div>
        <p className="mkt-caption mkt-ink-3 mt-3 text-center">{t("oc_note")}</p>
      </div>
    </section>
  );
}
