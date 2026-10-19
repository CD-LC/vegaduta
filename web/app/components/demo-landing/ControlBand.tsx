"use client";

import Link from "next/link";
import {
  ArrowRight,
  Check,
  KeyRound,
  MessageSquareText,
  ShieldCheck,
  SlidersHorizontal,
  Wallet,
  MessagesSquare,
  ScrollText,
  Database,
  GitFork,
  UserCheck,
  type LucideIcon,
} from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { sendLandingEvent } from "./landingBeacon";
import BrandText from "../BrandText";

// ---------------------------------------------------------------------------
// CONTROL BAND — "why this, not the AI you already have" (2026-09-25, direct
// instruction: visitors should feel the real agentic-AI difference and know
// exactly what they can control — answer "why not Claude Desktop, ChatGPT,
// Hermes, OpenClaw — why VegaDūta?").
//
// THE QUESTION THIS ANSWERS. CategoryBand (the band above) positions the
// platform against BUILDER tools (n8n/Dify/Botpress) — but the audience pivot
// brought in a different comparison: the visitor whose reference point is a
// chat assistant or a self-hosted agent runner. This band is that half of
// the "where does this fit" argument. It sits directly after `category` so
// the two read as one positioning move, not two scattered apologies.
//
// THE ARGUMENT, in the page's honesty idiom (concede first, claim second —
// same structure CategoryBand uses):
//   1. What your chat app does better — genuinely. An assistant that helps
//      you think inside its own window is not the enemy and the band says
//      so; pretending ChatGPT is useless is the least credible thing a
//      landing page can claim.
//   2. What it will never do — work FOR you on your customers' channels,
//      under your rules, while you sleep. The difference is jurisdiction,
//      not intelligence.
//   3. Running your own agent already (Hermes, OpenClaw and friends)? The
//      concession is that the idea is the same — and that VegaDūta is it
//      productised: the platform parts (guardrails, approvals, budgets,
//      audit, multi-tenant channels) built in instead of self-hosted.
//   4. The payload: "what you actually control" — the eight levers a chat
//      window does not hand you.
//
// NO-FICTION LEDGER — every control chip maps to a shipped mechanism (the
// same sources HeroOutcome's ledger uses):
//   approvals   -> approval nodes that hold a run for a named person
//   guardrails  -> PII / jailbreak / policy checks (AudienceTracks copy)
//   budgets     -> per-user/day model spend caps that STOP, not warn
//   models      -> BYOK any provider · on-device edge models · hosted pool
//   channels    -> core ChannelType enum (9 values)
//   audit       -> admin/audit + full action tracing on runs
//   workspace   -> schema-per-tenant isolation; keys encrypted at rest
//   source      -> "repo going public" — the OpenSourceBand phrasing, NOT
//                  "open source" unqualified: the code is being organised
//                  for a public repo and the copy must not claim otherwise.
//
// Beacons: control_why_click / control_compare_click (and sec_control reach)
// are NEW names — they need core's LandingEventController.ALLOWED_EVENTS
// (_ORDERED) before they count (core-3 scope; flagged in AGENT-COORDINATION).
// Until then they 204-drop by design — same convention as oss_try_click.
// ---------------------------------------------------------------------------

const CONTROL_KEYS = [
  { key: "wn_c1", icon: UserCheck },
  { key: "wn_c2", icon: ShieldCheck },
  { key: "wn_c3", icon: Wallet },
  { key: "wn_c4", icon: KeyRound },
  { key: "wn_c5", icon: MessagesSquare },
  { key: "wn_c6", icon: ScrollText },
  { key: "wn_c7", icon: Database },
  { key: "wn_c8", icon: GitFork },
] satisfies { key: string; icon: LucideIcon }[];

const KEEP_KEYS = ["wn_keep1", "wn_keep2", "wn_keep3"] as const;

export default function ControlBand() {
  const t = useTranslations("landing");
  return (
    <section id="why-vegaduta" className="mkt-band mkt-band--ruled">
      <div className="mkt-band__inner">
        <div className="mkt-head mkt-head--center mkt-reveal">
          <p className="mkt-eyebrow"><BrandText text={t("wn_eyebrow")} /></p>
          <h2 className="mkt-h2">
            {t("wn_h2")} <span className="mkt-gradient-text">{t("wn_h2Em")}</span>
          </h2>
          <p className="mkt-section-sub mkt-ink-2 text-pretty">{t("wn_sub")}</p>
        </div>

        <div className="mkt-reveal-group mt-12 grid gap-4 md:grid-cols-2">
          {/* The concession card — honest about what assistants do better,
              then the one boundary they do not cross. Not a link: this card
              argues, it doesn't route. */}
          <div className="mkt-card flex h-full flex-col gap-4 p-6">
            <div className="flex items-center gap-2.5">
              <span className="grid size-9 shrink-0 place-items-center rounded-lg border border-[var(--mkt-line)] bg-[var(--mkt-surface)]">
                <MessageSquareText className="size-4 text-[var(--mkt-positive)]" aria-hidden="true" />
              </span>
              <span className="mkt-card-title">{t("wn_keepTitle")}</span>
            </div>
            <ul className="space-y-2.5">
              {KEEP_KEYS.map((k) => (
                <li key={k} className="flex items-start gap-2">
                  <Check className="mt-0.5 size-3.5 shrink-0 text-[var(--mkt-positive)]" aria-hidden="true" />
                  <span className="mkt-small mkt-ink-2">{t(k)}</span>
                </li>
              ))}
            </ul>
            <p className="mkt-small mkt-ink-2 mt-auto border-t border-[var(--mkt-line)] pt-4">
              {t("wn_keepFoot")}
            </p>
          </div>

          {/* The DIY-agent card — same idea, self-hosted. Concedes the
              approach is real, names what "being your own platform" costs. */}
          <div className="mkt-card flex h-full flex-col gap-4 p-6">
            <div className="flex items-center gap-2.5">
              <span className="grid size-9 shrink-0 place-items-center rounded-lg border border-[var(--mkt-line)] bg-[var(--mkt-surface)]">
                <SlidersHorizontal className="size-4 text-[var(--mkt-accent)]" aria-hidden="true" />
              </span>
              <span className="mkt-card-title">{t("wn_diyTitle")}</span>
            </div>
            <p className="mkt-small mkt-ink-2"><BrandText text={t("wn_diyBody")} /></p>
          </div>
        </div>

        {/* The payload: the eight levers this platform hands you that a
            chat window cannot. Chips, not prose — this row is scanned, not
            read. */}
        <div className="mkt-reveal mt-10">
          <p className="mkt-ui-label mkt-ink-3 mb-3 text-center">{t("wn_controlTitle")}</p>
          <ul className="grid gap-2 sm:grid-cols-2 lg:grid-cols-4">
            {CONTROL_KEYS.map(({ key, icon: Icon }) => (
              <li
                key={key}
                className="flex items-start gap-2.5 rounded-xl border border-[var(--mkt-line)] bg-[var(--mkt-surface)] px-3.5 py-3"
              >
                <Icon className="mt-0.5 size-4 shrink-0 text-[var(--mkt-accent)]" aria-hidden="true" />
                <span className="mkt-small mkt-ink-2">{t(key)}</span>
              </li>
            ))}
          </ul>
        </div>

        <div className="mkt-reveal mt-8 flex flex-wrap items-center justify-center gap-3">
          <Link
            href="/why-vegaduta"
            onClick={() => sendLandingEvent("control_why_click")}
            className="mkt-btn mkt-btn-secondary no-underline"
          >
            {t("wn_linkWhy")}
            <ArrowRight className="size-4" />
          </Link>
          <Link
            href="/compare"
            onClick={() => sendLandingEvent("control_compare_click")}
            className="mkt-link font-medium no-underline"
          >
            {t("wn_linkCompare")}
          </Link>
        </div>
      </div>
    </section>
  );
}
