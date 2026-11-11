"use client";

import type { CSSProperties } from "react";
import Link from "next/link";
import {
  ArrowRight,
  Check,
  FlaskConical,
  Eye,
  Rocket,
  User,
  Mail,
  KeyRound,
  Building2,
  GraduationCap,
  LayoutTemplate,
  ShieldCheck,
  Sparkles,
  Clock,
} from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { academyHref } from "../../lib/academyUrl";
import { useJourney } from "../../lib/journey";
import { sendLandingEvent } from "./landingBeacon";

// "The Path" (conversion redesign, 2026-09-10, direct instruction: "users
// are getting confused where to start - they are giving try but not
// registering... clear guidance on give a try and signup to use the
// dashboard... minimal details... give a try - understand, learn,
// implement... something very creative and out of the box").
//
// The diagnosis behind this band: the page had a good trial and a good
// signup, but never drew the line between them. A visitor finished /try
// with no idea that (a) an account is the next step, (b) it asks for
// almost nothing, and (c) the agent they just built comes with them. So
// this band is literally a road with three stops, in order, each with a
// time cost and exactly what happens there:
//
//   01 TRY        - 2 min, nothing asked (the real /try sandbox)
//   02 UNDERSTAND - see the steps every reply took; Academy for the why
//   03 IMPLEMENT  - name / email / password / workspace name -> the trial
//                   agent is copied in (DemoConversionService) and the
//                   dashboard unlocks agents + workflows
//
// It is journey-aware (useJourney): a browser holding a /try session sees
// stop 01 ticked, the "you are here" marker moves to 03, and 03's button
// becomes "Keep my agent" -> /signup?demo=<token>. That is the out-of-the-
// box part - the landing page remembers how far you got and asks for the
// next thing, not the first thing again.
//
// Every claim maps to shipped code: the sandbox is a real isolated tenant
// (TrySandbox.tsx), the activity trace is AgentActivityTrace.tsx (AG-UI
// events), Academy is academyHref()'s separate deployable (135 lessons as of
// 2026-09-09), signup's fields are exactly app/signup/page.tsx's `fields`
// array, and the free-plan facts are PricingBand's/FreePlanIncludes' own.
//
// Colors: marketing-system tokens only (--mkt-*), no `dark:` classes (see the
// NOTE in HeroUniversalAgent.tsx - the theme is a class, not the OS query).
// Emerald literals were replaced by --mkt-positive, which carries its own
// light/dark value, and the band's surfaces/rhythm/typography now come from
// the .mkt-* roles rather than per-band Tailwind stacks.

// The station marker's three states, in one place each, so a state's tint
// cannot drift between the marker, its badge and the card edge. All three read
// marketing-system tokens only - `bg-primary` / `border-border` resolve from
// the CONSOLE's shadcn scale, which is a different value in dark mode than
// --mkt-accent, so a funnel band mixing the two shifts hue on theme switch.
const POSITIVE_TINT: CSSProperties = {
  color: "var(--mkt-positive)",
  borderColor: "color-mix(in oklab, var(--mkt-positive) 55%, transparent)",
  backgroundColor: "color-mix(in oklab, var(--mkt-positive) 14%, transparent)",
};

const HERE_TINT: CSSProperties = {
  color: "var(--accent-fg, #fff)", // the label .mkt-btn-primary uses on the accent fill (white light / ink dark)
  borderColor: "var(--mkt-accent)",
  backgroundColor: "var(--mkt-accent)",
  boxShadow: "var(--mkt-shadow-glow)",
};

const NEXT_TINT: CSSProperties = {
  color: "var(--mkt-ink-3)",
  borderColor: "var(--mkt-line)",
  backgroundColor: "var(--mkt-surface-sunken)",
};

// Accent-tinted inline icons (bullet ticks, the signup-field chips).
const ACCENT_ICON: CSSProperties = { color: "var(--mkt-accent)" };

export default function PathBand() {
  const t = useTranslations("landing");
  const journey = useJourney();
  const tried = journey.stage === "keep";

  const stations: Array<{
    n: string;
    icon: typeof FlaskConical;
    labelKey: string;
    timeKey: string;
    titleKey: string;
    bodyKey: string;
    bullets: string[];
    state: "done" | "here" | "next";
  }> = [
    {
      n: "01",
      icon: FlaskConical,
      labelKey: "path_s1_label",
      timeKey: "path_s1_time",
      titleKey: "path_s1_title",
      bodyKey: "path_s1_body",
      bullets: ["path_s1_b1", "path_s1_b2", "path_s1_b3"],
      state: tried ? "done" : "here",
    },
    {
      n: "02",
      icon: Eye,
      labelKey: "path_s2_label",
      timeKey: "path_s2_time",
      titleKey: "path_s2_title",
      bodyKey: "path_s2_body",
      bullets: ["path_s2_b1", "path_s2_b2", "path_s2_b3"],
      state: "next",
    },
    {
      n: "03",
      icon: Rocket,
      labelKey: "path_s3_label",
      timeKey: "path_s3_time",
      titleKey: "path_s3_title",
      bodyKey: "path_s3_body",
      bullets: [],
      state: tried ? "here" : "next",
    },
  ];

  return (
    <section id="path" className="mkt-band mkt-band--lg mkt-band--ruled">
      {/* .mkt-spot is the band's focal glow - the system's local aurora pool
          (a radial gradient) replacing the old 28rem blur-[120px] circle,
          which cost a full-viewport gaussian on every paint. */}
      <div className="mkt-band__inner mkt-spot">
        <div className="mkt-head mkt-head--center mkt-reveal">
          <p className="mkt-eyebrow inline-flex items-center gap-2">
            <Sparkles className="size-3.5" />
            {t("path_eyebrow")}
          </p>
          <h2 className="mkt-h2">{t("path_title")}</h2>
          <p className="mkt-section-sub mkt-ink-2 text-pretty">{t("path_sub")}</p>
        </div>

        {/* The road: three stations on one rail, drawn with the system's flow
            motif (.mkt-flowline - dashed connector + travelling packet). On lg
            one horizontal line runs through the three station markers, so the
            dashes show in the gaps between cards; below lg the same connector
            turns vertical (.mkt-flowline--v) and sits in the gap above each
            card after the first. */}
        <div className="relative mt-14">
          <div aria-hidden="true" className="pointer-events-none absolute start-[8%] end-[8%] top-14 hidden lg:flex">
            <span className="mkt-flowline" />
          </div>

          <ol className="mkt-reveal-group grid gap-6 lg:grid-cols-3 lg:gap-8">
            {stations.map((s, i) => {
              const Icon = s.icon;
              const here = s.state === "here";
              const done = s.state === "done";
              return (
                <li key={s.n} className="relative flex">
                  {i > 0 && (
                    <div
                      aria-hidden="true"
                      className="pointer-events-none absolute inset-x-0 -top-6 flex h-6 flex-col items-center lg:hidden"
                    >
                      <span className="mkt-flowline mkt-flowline--v" />
                    </div>
                  )}
                  <article
                    className={"mkt-card flex w-full flex-col p-6 sm:p-7 " + (here ? "mkt-card--raised" : "")}
                    style={
                      here
                        ? {
                            borderColor: "var(--mkt-line-strong)",
                            boxShadow: "var(--mkt-shadow), var(--mkt-shadow-glow)",
                          }
                        : done
                          ? { borderColor: "color-mix(in oklab, var(--mkt-positive) 45%, var(--mkt-line))" }
                          : undefined
                    }
                  >
                    {/* Station marker - sits ON the rail on lg. */}
                    <div className="mb-5 flex items-center justify-between gap-3">
                      {/* .mkt-h3 carries the marker's size/weight (a flat 20/600
                          - it must not resize with the card) and .mkt-num, which
                          is declared later in marketing-system.css, wins the font
                          family back to mono with tabular figures so "01/02/03"
                          sit at identical widths. */}
                      <div
                        className="mkt-num mkt-h3 flex size-14 items-center justify-center rounded-2xl border"
                        style={here ? HERE_TINT : done ? POSITIVE_TINT : NEXT_TINT}
                      >
                        {done ? <Check className="size-6" /> : s.n}
                      </div>
                      <div className="flex flex-col items-end gap-1.5">
                        {here && (
                          <span className="mkt-pill mkt-pill--accent mkt-badge">
                            <span className="mkt-dot" />
                            {t("path_youAreHere")}
                          </span>
                        )}
                        {done && (
                          <span className="mkt-pill mkt-badge" style={POSITIVE_TINT}>
                            {t("path_stepDone")}
                          </span>
                        )}
                        <span className="mkt-caption mkt-num mkt-ink-2 inline-flex items-center gap-1">
                          <Clock className="size-3" />
                          {t(s.timeKey)}
                        </span>
                      </div>
                    </div>

                    <p className="mkt-eyebrow flex items-center gap-2">
                      <Icon className="size-3.5" />
                      {t(s.labelKey)}
                    </p>
                    <h3 className="mkt-card-title mt-2">{t(s.titleKey)}</h3>
                    <p className="mkt-small mkt-ink-2 mt-2">{t(s.bodyKey)}</p>

                    {s.bullets.length > 0 && (
                      <ul className="mkt-small mt-4 space-y-1.5">
                        {s.bullets.map((b) => (
                          <li key={b} className="flex items-start gap-2">
                            <Check className="mt-0.5 size-4 shrink-0" style={ACCENT_ICON} />
                            <span>{t(b)}</span>
                          </li>
                        ))}
                      </ul>
                    )}

                    {/* Stop 03 shows the WHOLE ask - the four fields signup
                        actually has - so "minimal details" is a picture, not
                        a promise. */}
                    {s.n === "03" && (
                      <div className="mkt-card mkt-card--sunken mt-4 p-4">
                        <p className="mkt-eyebrow mb-2.5">{t("path_s3_askLabel")}</p>
                        <div className="flex flex-wrap gap-2">
                          {[
                            { icon: User, key: "path_s3_field1" },
                            { icon: Mail, key: "path_s3_field2" },
                            { icon: KeyRound, key: "path_s3_field3" },
                            { icon: Building2, key: "path_s3_field4" },
                          ].map(({ icon: F, key }) => (
                            <span key={key} className="mkt-pill">
                              <F className="size-3.5" style={ACCENT_ICON} />
                              {t(key)}
                            </span>
                          ))}
                        </div>
                        <p className="mkt-caption mkt-ink-2 mt-3 flex items-start gap-1.5">
                          <ShieldCheck className="mt-0.5 size-3.5 shrink-0" style={{ color: "var(--mkt-positive)" }} />
                          {t("path_s3_thatsIt")}
                        </p>
                        <p
                          className="mkt-caption mkt-ink-2 mt-3 border-t pt-3"
                          style={{ borderColor: "var(--mkt-line)" }}
                        >
                          <span className="font-semibold" style={{ color: "var(--mkt-ink)" }}>
                            {t("path_s3_unlockLabel")}
                          </span>{" "}
                          {t("path_s3_unlock")}
                        </p>
                      </div>
                    )}

                    {/* Actions - pinned to the bottom so the three cards'
                        buttons line up regardless of body length. */}
                    <div className="mt-auto pt-6">
                      {s.n === "01" &&
                        (tried ? (
                          <Link
                            href={journey.tryHref}
                            className="mkt-btn mkt-btn-secondary w-full"
                            onClick={() => sendLandingEvent("path_try_click")}
                          >
                            <Check className="size-4" style={{ color: "var(--mkt-positive)" }} />
                            {t("path_s1_ctaDone")}
                          </Link>
                        ) : (
                          <Link
                            href={journey.tryHref}
                            className="mkt-btn mkt-btn-primary mkt-btn-lg w-full"
                            onClick={() => sendLandingEvent("path_try_click")}
                          >
                            {t("path_s1_cta")}
                            <ArrowRight className="size-4" />
                          </Link>
                        ))}
                      {s.n === "02" && (
                        <div className="flex flex-col gap-2 sm:flex-row">
                          <a
                            href={academyHref()}
                            target="_blank"
                            rel="noopener noreferrer"
                            className="mkt-btn mkt-btn-secondary w-full"
                            onClick={() => sendLandingEvent("path_academy_click")}
                          >
                            <GraduationCap className="size-4" />
                            {t("path_s2_cta")}
                          </a>
                          <Link
                            href="/templates"
                            className="mkt-btn mkt-btn-ghost w-full"
                            onClick={() => sendLandingEvent("path_templates_click")}
                          >
                            <LayoutTemplate className="size-4" />
                            {t("path_s2_cta2")}
                          </Link>
                        </div>
                      )}
                      {s.n === "03" && (
                        <Link
                          href={journey.signupHref}
                          className={"mkt-btn mkt-btn-lg w-full " + (tried ? "mkt-btn-primary" : "mkt-btn-secondary")}
                          onClick={() => sendLandingEvent("path_signup_click")}
                        >
                          {tried ? t("path_s3_ctaKeep") : t("path_s3_cta")}
                          <ArrowRight className="size-4" />
                        </Link>
                      )}
                    </div>
                  </article>
                </li>
              );
            })}
          </ol>
        </div>

        <p className="mkt-small mkt-ink-2 mkt-reveal mt-10 text-center">
          {tried ? t("path_footerTried") : t("path_footer")}
        </p>
      </div>
    </section>
  );
}
