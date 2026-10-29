"use client";

import { useState } from "react";
import Link from "next/link";
import { motion } from "motion/react";
import {
  ArrowRight,
  BookOpen,
  Check,
  GraduationCap,
  Mail,
  Mic,
  Quote,
  ScrollText,
  ShieldCheck,
  Sparkles,
  Stethoscope,
  Store,
  Building2,
  Wrench,
  MessageSquare,
  Workflow,
} from "lucide-react";
import Reveal from "../marketing/Reveal";
import { useTranslations } from "../../lib/i18n";
import { academyHref } from "../../lib/academyUrl";
import { useJourney } from "../../lib/journey";
import { useReducedMotionSafe } from "../../lib/useReducedMotionSafe";
import { sendLandingEvent } from "./landingBeacon";
import { ENTITY_FACTS } from "../../lib/platform-facts";

// BusinessCaseBand — "bring us one process, we draft the agent for it, free"
// (direct instruction, 2026-09-10: "ensure consumers are shown a banner
// contact us on your bussiness case study of agentic AI free, use dashboard
// free and tell them signup and start learning building agents, take your
// bussiness to next level using AI").
//
// WHY THIS SHAPE, AND NOT ANOTHER CARD ROW. The page already has a hero with
// a live console, PathBand's three-stop rail, the senses cards, ProofBand,
// WhatsUnderneath and PricingBand — six card grids. A seventh would read as
// more of the same and convert nothing. So this band is a CASE FILE: the
// visitor's own words on the left, the agent we would draft from them on the
// right, and a junction between the two. The industry chips swap the worked
// example client-side (no backend, no fetch) so the transformation is
// something the visitor watches happen rather than a paragraph describing it.
//
// ---------------------------------------------------------------------------
// NO-FICTION LEDGER (house rule: every claim shown here maps to shipped code)
// ---------------------------------------------------------------------------
// The worked examples are labelled a sketch (bc_disclaimer) and are assembled
// ONLY from mechanisms that exist and are on the Free plan today:
//   * Telegram + Email channels, free   -> control_plane.plan_limits FREE row,
//     V101__plan_limits.sql:45 (whatsapp_allowed=FALSE, so WhatsApp is
//     deliberately NOT named anywhere in this band); shipped copy
//     en.json landing.pricing_plan1_f3.
//   * "in the customer's own language"  -> FREE_PLAN_FACTS.indianLanguages
//     (22, FreePlanIncludes.tsx:41) — the same figure the senses band uses.
//   * Your knowledge base               -> 1 collection on FREE
//     (V101__plan_limits.sql:45 max_kb_collections=1; PricingBand f4).
//   * Human approval gate + audit log   -> FREE_INCLUDE_KEYS "approvals"
//     (FreePlanIncludes.tsx), HumanApprovalGateService — the same claim
//     DemoLanding's SecuritySection and PathBand already make.
//   * Workflow                          -> 3 on FREE (planLimits.ts
//     WORKFLOW_LIMIT / V101 max_workflows=3).
//   * Voice note -> text                -> sidecar faster-whisper
//     (sidecar/app/transcription.py:42-44) via SidecarWhisperProvider, the
//     free-plan default that needs NO tenant credential.
// No outcome metric, ROI figure, time-saved number or customer count appears
// anywhere in this band — none of those exist in the codebase, so none of
// them ship. The examples show what the agent DOES, never what it earns.
//
// The reassurance strip reuses the four ALREADY-SHIPPED Free-row strings
// verbatim (landing.pricing_plan1_f1..f4 + pricing_plan1_period) rather than
// re-deriving the numbers, so this band cannot drift from PricingBand.
//
// The contact CTA is mailto:<ENTITY_FACTS.contactEmail> — the platform's real,
// already-in-production destination (DemoLanding.tsx:975 footer,
// AutomateAnythingSection.tsx:170, EarnLanding.tsx:338, privacy/terms).
// There is no /contact route and no lead-capture API in core, so inventing a
// form here would have been a dead end. Subject+body are prefilled the way
// EarnLanding.tsx:338 already prefills a subject.
//
// Colors: theme tokens + alpha tints only, no `dark:` classes (see the NOTE
// in HeroUniversalAgent.tsx — this app themes via a .dark class on nested
// divs, so `dark:` follows the OS query and desyncs).

const CONTACT_EMAIL = ENTITY_FACTS.contactEmail;

// One tag per step. Each key is a real platform mechanism — see the ledger
// above for what backs each one.
const TAGS = {
  channels: { icon: MessageSquare, key: "bc_tag_channels" },
  knowledge: { icon: BookOpen, key: "bc_tag_knowledge" },
  approval: { icon: ShieldCheck, key: "bc_tag_approval" },
  audit: { icon: ScrollText, key: "bc_tag_audit" },
  workflow: { icon: Workflow, key: "bc_tag_workflow" },
  voice: { icon: Mic, key: "bc_tag_voice" },
} as const;

type TagKey = keyof typeof TAGS;

const CASES: Array<{
  key: string;
  icon: typeof Store;
  labelKey: string;
  briefKey: string;
  steps: Array<{ bodyKey: string; tag: TagKey }>;
}> = [
  {
    key: "retail",
    icon: Store,
    labelKey: "bc_ind_retail",
    briefKey: "bc_retail_brief",
    steps: [
      { bodyKey: "bc_retail_s1", tag: "channels" },
      { bodyKey: "bc_retail_s2", tag: "knowledge" },
      { bodyKey: "bc_retail_s3", tag: "approval" },
      { bodyKey: "bc_retail_s4", tag: "audit" },
    ],
  },
  {
    key: "clinic",
    icon: Stethoscope,
    labelKey: "bc_ind_clinic",
    briefKey: "bc_clinic_brief",
    steps: [
      { bodyKey: "bc_clinic_s1", tag: "channels" },
      { bodyKey: "bc_clinic_s2", tag: "knowledge" },
      { bodyKey: "bc_clinic_s3", tag: "voice" },
      { bodyKey: "bc_clinic_s4", tag: "approval" },
    ],
  },
  {
    key: "agency",
    icon: Building2,
    labelKey: "bc_ind_agency",
    briefKey: "bc_agency_brief",
    steps: [
      { bodyKey: "bc_agency_s1", tag: "channels" },
      { bodyKey: "bc_agency_s2", tag: "knowledge" },
      { bodyKey: "bc_agency_s3", tag: "workflow" },
      { bodyKey: "bc_agency_s4", tag: "approval" },
    ],
  },
  {
    key: "field",
    icon: Wrench,
    labelKey: "bc_ind_field",
    briefKey: "bc_field_brief",
    steps: [
      { bodyKey: "bc_field_s1", tag: "voice" },
      { bodyKey: "bc_field_s2", tag: "knowledge" },
      { bodyKey: "bc_field_s3", tag: "workflow" },
      { bodyKey: "bc_field_s4", tag: "approval" },
    ],
  },
];

const FREE_ROW_KEYS = ["pricing_plan1_f1", "pricing_plan1_f2", "pricing_plan1_f3", "pricing_plan1_f4"];

export default function BusinessCaseBand() {
  const t = useTranslations("landing");
  const journey = useJourney();
  const reduced = useReducedMotionSafe();
  const [activeIndex, setActiveIndex] = useState(0);
  const active = CASES[activeIndex];

  // Prefilled subject + body — the ask stops being "write us an email from a
  // blank page" and becomes "fill in three lines". Same mailto destination
  // the footer and AutomateAnythingSection already use.
  const mailHref =
    `mailto:${CONTACT_EMAIL}` +
    `?subject=${encodeURIComponent(t("bc_mailSubject"))}` +
    `&body=${encodeURIComponent(t("bc_mailBody"))}`;

  return (
    <section id="business-case" className="mkt-band mkt-band--lg mkt-band--ruled mkt-spot">
      {/* Blueprint ruling — the "drafting table" register that separates this
          band from every card grid above it. Decorative only; the hue is now
          the marketing accent token rather than a raw palette variable.
          The band's old 26rem `blur-[130px]` glow div is gone: `.mkt-spot` on
          the section paints the same pool with a radial-gradient and no
          full-viewport blur filter. */}
      <div
        aria-hidden="true"
        className="pointer-events-none absolute inset-0 opacity-[0.5]"
        style={{
          backgroundImage:
            "repeating-linear-gradient(135deg, color-mix(in srgb, var(--mkt-accent) 7%, transparent) 0 1px, transparent 1px 22px)",
        }}
      />

      <div className="mkt-band__inner relative">
        <Reveal className="mkt-head mkt-head--center">
          <p className="mkt-pill mkt-pill--accent mkt-badge">
            <Sparkles className="size-3.5" />
            {t("bc_eyebrow")}
          </p>
          <h2 className="mkt-h2">{t("bc_title")}</h2>
          <p className="mkt-section-sub mkt-ink-2 text-pretty">{t("bc_sub")}</p>
        </Reveal>

        {/* ---------------------------------------------------------------
            The case file. Header docket + brief -> junction -> run sheet. */}
        <Reveal delay={0.1} className="mt-12">
          <div className="mkt-card mkt-card--raised overflow-hidden">
            {/* Docket bar. The live marker is `.mkt-dot` — one element with a
                single pulsing ring — instead of the stacked `animate-ping`
                clones it replaces. */}
            <div className="flex flex-col gap-3 border-b border-[color:var(--mkt-line)] bg-background/60 px-5 py-4 sm:flex-row sm:items-center sm:justify-between sm:px-7">
              <p className="mkt-caption mkt-ink-2">{t("bc_pickLabel")}</p>
            </div>

            {/* Industry chips — the interactive bit. Beaconed so we can see
                whether visitors actually engage with the transformation or
                scroll past it. */}
            <div className="flex flex-wrap gap-2 border-b border-[color:var(--mkt-line)] px-5 py-4 sm:px-7">
              {CASES.map((c, i) => {
                const Icon = c.icon;
                const on = i === activeIndex;
                return (
                  <button
                    key={c.key}
                    type="button"
                    aria-pressed={on}
                    onClick={() => {
                      setActiveIndex(i);
                      sendLandingEvent("casestudy_industry_click");
                    }}
                    // `.mkt-pill` is unlayered, so a Tailwind hover:*-colour
                    // utility on it would never win — the selected state is
                    // carried by `.mkt-pill--accent` and the hover affordance
                    // by opacity, which nothing in the layer sets.
                    className={
                      "mkt-pill cursor-pointer transition-opacity hover:opacity-80 " + (on ? "mkt-pill--accent" : "")
                    }
                  >
                    <Icon className="size-3.5" aria-hidden="true" />
                    {t(c.labelKey)}
                  </button>
                );
              })}
            </div>

            <div className="grid gap-0 p-5 sm:p-7 lg:grid-cols-[minmax(0,1fr)_auto_minmax(0,1.3fr)] lg:gap-2">
              {/* ---- LEFT: the visitor's own words. Pinned-note register:
                      dashed rule, a hairline tilt, quote glyph, italic. */}
              <div className="flex flex-col">
                <p className="mkt-eyebrow mb-3 inline-flex items-center gap-2">
                  <Quote className="size-3.5" style={{ color: "var(--mkt-warm)" }} />
                  {t("bc_briefLabel")}
                </p>
                {/* Deliberately NOT a `.mkt-card`: the pinned-note register
                    (dashed rule, hairline tilt, warm tint) is what makes the
                    visitor's own words read differently from the machine sheet
                    opposite. It now takes its hue from --mkt-warm, which is
                    theme-aware, instead of a fixed amber palette step. */}
                <motion.blockquote
                  key={active.key}
                  initial={reduced ? false : { opacity: 0, y: 10 }}
                  animate={{ opacity: 1, y: 0 }}
                  transition={{ duration: 0.35, ease: [0.16, 1, 0.3, 1] }}
                  className="mkt-body flex-1 rounded-2xl border border-dashed p-5 italic text-foreground/85 sm:p-6"
                  style={{
                    rotate: "-0.4deg",
                    borderColor: "color-mix(in oklab, var(--mkt-warm) 45%, transparent)",
                    background: "color-mix(in oklab, var(--mkt-warm) 7%, transparent)",
                  }}
                >
                  {t(active.briefKey)}
                </motion.blockquote>
                <p className="mkt-caption mkt-ink-2 mt-3">{t("bc_briefHint")}</p>
              </div>

              {/* ---- JUNCTION: the flow motif either side of the arrow.
                      `.mkt-flowline` is the dashed connector with a travelling
                      packet; it replaces the hand-rolled dashed border. It sets
                      its own `position`, so the orientation swap is done with
                      two spans (row on small screens, column from lg) rather
                      than absolute positioning. */}
              <div className="relative flex items-center justify-center gap-3 py-6 lg:flex-col lg:gap-2 lg:px-6 lg:py-0">
                <span aria-hidden="true" className="mkt-flowline lg:hidden" />
                <span aria-hidden="true" className="mkt-flowline mkt-flowline--v hidden lg:block" />
                <span className="relative flex flex-col items-center gap-2">
                  <span className="flex size-11 items-center justify-center rounded-full border border-[color:var(--mkt-line-strong)] bg-background text-primary shadow-lg shadow-primary/20">
                    <ArrowRight className="size-5 rotate-90 lg:rotate-0" />
                  </span>
                  <span className="mkt-badge whitespace-nowrap rounded-full bg-background px-2 py-0.5 text-primary">
                    {t("bc_transform")}
                  </span>
                </span>
                <span aria-hidden="true" className="mkt-flowline lg:hidden" />
                <span aria-hidden="true" className="mkt-flowline mkt-flowline--v hidden lg:block" />
              </div>

              {/* ---- RIGHT: the run sheet. Machine register: numbered
                      steps, a spine, a mechanism tag on each. */}
              <div className="flex flex-col">
                {/* `.mkt-badge`, not `.mkt-eyebrow`: the eyebrow role pins its
                    own (muted) colour in unlayered CSS, and this kicker is
                    deliberately the accent. */}
                <p className="mkt-badge mb-3 inline-flex items-center gap-2 text-primary">
                  <Sparkles className="size-3.5" />
                  {t("bc_agentLabel")}
                </p>
                <motion.ol
                  key={active.key}
                  initial={reduced ? false : { opacity: 0, y: 10 }}
                  animate={{ opacity: 1, y: 0 }}
                  transition={{ duration: 0.35, delay: 0.06, ease: [0.16, 1, 0.3, 1] }}
                  className="mkt-card mkt-card--sunken flex-1 p-5 sm:p-6"
                >
                  {active.steps.map((s, i) => {
                    const TagIcon = TAGS[s.tag].icon;
                    const last = i === active.steps.length - 1;
                    return (
                      <li key={s.bodyKey} className={"relative flex gap-4 " + (last ? "" : "pb-5")}>
                        {!last && (
                          <span
                            aria-hidden="true"
                            className="absolute bottom-1 start-[13px] top-8 w-px bg-gradient-to-b from-primary/40 to-primary/10"
                          />
                        )}
                        <span className="mkt-num mkt-caption relative z-10 mt-0.5 flex size-7 shrink-0 items-center justify-center rounded-full border border-primary/40 bg-primary/10 font-bold text-primary">
                          {i + 1}
                        </span>
                        <div className="min-w-0">
                          <p className="mkt-small text-foreground/85">{t(s.bodyKey)}</p>
                          <span className="mkt-pill mt-2">
                            <TagIcon className="size-3 text-primary" />
                            {t(TAGS[s.tag].key)}
                          </span>
                        </div>
                      </li>
                    );
                  })}
                </motion.ol>
                <p className="mkt-caption mkt-ink-2 mt-3">{t("bc_disclaimer")}</p>
              </div>
            </div>

            {/* ---- How the free case study actually works: three plain
                    steps, so "free" does not read as a sales trap. */}
            <div className="border-t border-[color:var(--mkt-line)] bg-background/50 px-5 py-6 sm:px-7">
              <p className="mkt-eyebrow mb-4">{t("bc_howLabel")}</p>
              {/* `.mkt-reveal-group` staggers the three steps off their own
                  view timelines — pure CSS, and with no scroll-timeline support
                  they are simply already visible. */}
              <div className="mkt-reveal-group grid gap-4 sm:grid-cols-3">
                {["bc_how1", "bc_how2", "bc_how3"].map((k, i) => (
                  <div key={k} className="flex gap-3">
                    <span className="mkt-num mkt-caption mt-0.5 flex size-6 shrink-0 items-center justify-center rounded-md bg-primary/12 font-bold text-primary">
                      {i + 1}
                    </span>
                    <p className="mkt-small text-foreground/80">{t(k)}</p>
                  </div>
                ))}
              </div>
            </div>
          </div>
        </Reveal>

        {/* ---------------------------------------------------------------
            Reassurance: the dashboard itself is free. Every string below is
            the ALREADY-SHIPPED PricingBand Free row, reused verbatim. */}
        <Reveal delay={0.15} className="mt-10">
          {/* The positive tint is now the theme-aware --mkt-positive token
              rather than fixed emerald-500 palette steps, which only looked
              right in one theme. Same `.mkt-card` hairline + sheen as every
              other surface on the page, tinted the way the brief note above
              tints itself with --mkt-warm. NOT `--interactive`: nothing in
              this strip is clickable. */}
          <div
            className="mkt-card p-6 sm:p-7"
            style={{
              borderColor: "color-mix(in oklab, var(--mkt-positive) 35%, transparent)",
              background: "color-mix(in oklab, var(--mkt-positive) 7%, transparent)",
            }}
          >
            <div className="flex flex-col gap-5 lg:flex-row lg:items-center lg:justify-between">
              <div>
                <p
                  className="mkt-small inline-flex items-center gap-2 font-bold"
                  style={{ color: "var(--mkt-positive)" }}
                >
                  <ShieldCheck className="size-4" />
                  {t("bc_freeLabel")}
                </p>
                <p className="mkt-small mt-2 max-w-xl text-foreground/80">{t("bc_freeBody")}</p>
              </div>
              <ul className="grid gap-2 sm:grid-cols-2 lg:shrink-0">
                {FREE_ROW_KEYS.map((k) => (
                  <li key={k} className="mkt-small flex items-start gap-2 text-foreground/85">
                    <Check className="mt-0.5 size-4 shrink-0" style={{ color: "var(--mkt-positive)" }} />
                    <span>{t(k)}</span>
                  </li>
                ))}
              </ul>
            </div>
          </div>
        </Reveal>

        {/* ---------------------------------------------------------------
            The two doors: talk to us, or just start. Both are real. */}
        <Reveal delay={0.2} className="mt-10 flex flex-col items-center gap-4">
          {/* The funnel's one button set. These were console <Button asChild>
              wrappers, i.e. a second primary treatment on a marketing page;
              `.mkt-btn-*` on the anchor itself drops the wrapper entirely and
              keeps the beacon handlers (casestudy_contact_click /
              casestudy_signup_click / casestudy_academy_click) exactly where
              they were. */}
          <div className="flex w-full flex-col items-stretch justify-center gap-3 sm:w-auto sm:flex-row sm:items-center">
            <a
              href={mailHref}
              className="mkt-btn mkt-btn-primary mkt-btn-lg"
              onClick={() => sendLandingEvent("casestudy_contact_click")}
            >
              <Mail className="size-4" />
              {t("bc_ctaContact")}
            </a>
            <Link
              href={journey.signupHref}
              className="mkt-btn mkt-btn-secondary mkt-btn-lg"
              onClick={() => sendLandingEvent("casestudy_signup_click")}
            >
              {t("bc_ctaSignup")}
              <ArrowRight className="size-4" />
            </Link>
            <a
              href={academyHref()}
              target="_blank"
              rel="noopener noreferrer"
              className="mkt-btn mkt-btn-ghost mkt-btn-lg"
              onClick={() => sendLandingEvent("casestudy_academy_click")}
            >
              <GraduationCap className="size-4" />
              {t("bc_ctaAcademy")}
            </a>
          </div>
          <p className="mkt-small mkt-ink-2 max-w-2xl text-center">{t("bc_micro")}</p>
        </Reveal>
      </div>
    </section>
  );
}
