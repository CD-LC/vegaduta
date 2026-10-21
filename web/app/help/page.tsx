"use client";

import Link from "next/link";
import { Brain, Wrench, UserCheck, GraduationCap, Rocket, Workflow, ArrowRight, BookOpen, Compass } from "lucide-react";

import AppShell from "../components/AppShell";
import PageHeader from "../components/ax/PageHeader";
import { Badge } from "../components/ui/badge";
import { Button } from "../components/ui/button";
import { useTranslations } from "../lib/i18n";
import { useTour } from "../components/TourProvider";
import { useAuth } from "../components/AuthProvider";
import InlineStrong from "../components/InlineStrong";
import BrandText from "./BrandText";

// Help / docs reference — rebuilt on Tailwind + the shadcn token layer (theme-
// following, since this renders inside AppShell with no .dark wrapper, so it
// tracks the app's light/dark theme via app/shadcn-theme.css's :root mapping).
// Retires the .ax-help-* CSS block. The jump-pills are shadcn Badges. Still a
// pure reading surface: type hierarchy over chrome, one visible h1 (PageHeader),
// a sticky "On this page" rail on wide screens / pill row on narrow ones.
//
// Copy lives in en.json under "helpPage"; paragraphs with inline <strong> keep
// the tags in the string (so translators see whole sentences) and are rendered
// by InlineStrong, which turns ONLY <strong>…</strong> into an element and
// everything else into text. Catalogs are community-editable, so they are never
// rendered as HTML (security review FE-003).
// Plain-language primer on agentic AI (copy in the "helpPrimer" i18n namespace). Rendered as a distinct
// first article region above the SECTION_META docs sections, in the same visual
// language (foreground headings, muted 68ch body, token-styled cards).
const PRIMER_ID = "agentic-basics";

const PRIMER_CARDS = [
  { icon: Brain, key: "plan" },
  { icon: Wrench, key: "tools" },
  { icon: UserCheck, key: "ask" },
] as const;

const SECTION_META = [
  { id: "getting-started", titleKey: "gettingStartedTitle", paraKeys: ["gettingStartedPara1", "gettingStartedPara2"] },
  { id: "ask-ai", titleKey: "askAiTitle", paraKeys: ["askAiPara1"] },
  { id: "agents", titleKey: "agentsTitle", paraKeys: ["agentsPara1", "agentsPara2"] },
  { id: "knowledge", titleKey: "knowledgeTitle", paraKeys: ["knowledgePara1", "knowledgePara2"] },
  { id: "workflows", titleKey: "workflowsTitle", paraKeys: ["workflowsPara1", "workflowsPara2"] },
  { id: "tools", titleKey: "toolsTitle", paraKeys: ["toolsPara1"] },
  { id: "research", titleKey: "researchTitle", paraKeys: ["researchPara1", "researchPara2"] },
  { id: "channels", titleKey: "channelsTitle", paraKeys: ["channelsPara1", "channelsPara2"] },
  { id: "devices", titleKey: "devicesTitle", paraKeys: ["devicesPara1"] },
  { id: "admin", titleKey: "adminTitle", paraKeys: ["adminPara1"] },
  { id: "faq", titleKey: "faqTitle", paraKeys: ["faqPara1", "faqPara2", "faqPara3", "faqPara4"] },
];

export default function HelpPage() {
  const t = useTranslations("helpPage");
  const tp = useTranslations("helpPrimer");
  const PRIMER_TITLE = tp("title");
  const tour = useTour();
  // /help is an ANONYMOUS_RENDER_ROUTE (see AuthProvider.tsx), so useAuth()
  // resolves for a logged-out visitor too (username: null there). Route the
  // "Open the docs" CTA to the page that actually works for them: /help/docs
  // requires a session (it's wrapped in AppShell, the authenticated twin),
  // while bare /docs is the real public route with the same shared content.
  const { username } = useAuth();
  const docsHref = username ? "/help/docs" : "/docs";

  const sections = SECTION_META.map((s) => ({
    id: s.id,
    title: t(s.titleKey),
    paras: s.paraKeys.map((k) => t(k)),
  }));

  return (
    <AppShell eyebrow={t("pageEyebrow")}>
      <div className="mb-8">
        <PageHeader
          title={t("pageTitle")}
          subtitle={t("introHint")}
        />
      </div>

      {/* Revisit affordance — one of the two documented places the guided
          product tour can be replayed from (the other is the "?" button in
          the topbar, TourReopenButton). It no longer navigates to "/" first:
          since the 9-step rebuild, step 1 is a centred hero with no target
          and steps 2-9 point at the nav rail and this page's own Help row,
          all of which render on /help itself — so pushing the reader to the
          dashboard would only yank them off the page they clicked from. */}
      <div className="mb-4 flex flex-col gap-4 rounded-xl border border-border bg-card p-5 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex items-start gap-3">
          <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary">
            <Compass className="h-5 w-5" aria-hidden="true" />
          </span>
          <div className="min-w-0">
            <h2 className="text-base font-semibold text-foreground">{tp("tourTitle")}</h2>
            <p className="mt-0.5 max-w-[68ch] text-sm leading-relaxed text-muted-foreground">
              {tp("tourBody")}
            </p>
          </div>
        </div>
        <Button
          className="shrink-0"
          onClick={() => {
            // Pure client-side state change, no navigation and no reload —
            // TourProvider is mounted above the router, and the tour runs
            // fine right here on /help.
            tour.replayTour();
          }}
        >
          {tp("tourCta")}
          <ArrowRight className="h-4 w-4" aria-hidden="true" />
        </Button>
      </div>

      {/* Discoverability CTA — links to the full platform Help & Docs
          reference (shared with the public /docs page). */}
      <div className="mb-4 flex flex-col gap-4 rounded-xl border border-border bg-card p-5 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex items-start gap-3">
          <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary">
            <BookOpen className="h-5 w-5" aria-hidden="true" />
          </span>
          <div className="min-w-0">
            <h2 className="text-base font-semibold text-foreground">{tp("docsTitle")}</h2>
            <p className="mt-0.5 max-w-[68ch] text-sm leading-relaxed text-muted-foreground">
              {tp("docsBody")}
            </p>
          </div>
        </div>
        <Button asChild className="shrink-0">
          <Link href={docsHref}>
            {tp("docsCta")}
            <ArrowRight className="h-4 w-4" aria-hidden="true" />
          </Link>
        </Button>
      </div>

      {/* Discoverability CTA — links to the Industry workflow recipes guide.
          Added as a prominent card near the top; does not alter existing docs. */}
      <div className="mb-10 flex flex-col gap-4 rounded-xl border border-border bg-card p-5 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex items-start gap-3">
          <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary">
            <Workflow className="h-5 w-5" aria-hidden="true" />
          </span>
          <div className="min-w-0">
            <h2 className="text-base font-semibold text-foreground">{tp("recipesTitle")}</h2>
            <p className="mt-0.5 max-w-[68ch] text-sm leading-relaxed text-muted-foreground">
              <BrandText text={tp("recipesBody")} />
            </p>
          </div>
        </div>
        <Button asChild className="shrink-0">
          <Link href="/help/workflow-recipes">
            {tp("recipesCta")}
            <ArrowRight className="h-4 w-4" aria-hidden="true" />
          </Link>
        </Button>
      </div>

      <div className="grid gap-10 lg:grid-cols-[210px_minmax(0,1fr)]">
        {/* Sticky rail (wide screens) */}
        <nav aria-label={t("tocLabel")} className="hidden lg:block">
          <div className="sticky top-6 space-y-1">
            <span className="mb-2 block font-mono text-[11px] font-semibold uppercase tracking-[0.14em] text-muted-foreground">
              {t("tocLabel")}
            </span>
            <a
              href={`#${PRIMER_ID}`}
              className="block rounded-md px-3 py-1.5 text-sm text-muted-foreground transition-colors hover:bg-[var(--surface-hover)] hover:text-foreground"
            >
              {PRIMER_TITLE}
            </a>
            {sections.map((s) => (
              <a
                key={s.id}
                href={`#${s.id}`}
                className="block rounded-md px-3 py-1.5 text-sm text-muted-foreground transition-colors hover:bg-[var(--surface-hover)] hover:text-foreground"
              >
                {s.title}
              </a>
            ))}
          </div>
        </nav>

        <div className="min-w-0">
          {/* Jump pills (narrow screens) */}
          <nav aria-label={t("tocLabel")} className="mb-8 flex flex-wrap gap-2 lg:hidden">
            <Badge asChild variant="outline">
              <a href={`#${PRIMER_ID}`}>{PRIMER_TITLE}</a>
            </Badge>
            {sections.map((s) => (
              <Badge key={s.id} asChild variant="outline">
                <a href={`#${s.id}`}>{s.title}</a>
              </Badge>
            ))}
          </nav>

          {/* Plain-language primer — distinct first article region */}
          <section id={PRIMER_ID} className="mb-12 scroll-mt-6 border-b border-border pb-12">
            <h2 className="mb-3 text-xl font-semibold tracking-tight text-foreground">{PRIMER_TITLE}</h2>
            <div className="space-y-3">
              <p className="max-w-[68ch] leading-relaxed text-muted-foreground [&_strong]:font-semibold [&_strong]:text-foreground">
                <InlineStrong text={tp("intro")} />
              </p>
            </div>

            <div className="mt-6 grid gap-4 sm:grid-cols-3">
              {PRIMER_CARDS.map((c) => {
                const Icon = c.icon;
                return (
                  <div key={c.key} className="rounded-xl border border-border bg-card p-4">
                    <Icon className="mb-3 h-5 w-5 text-primary" aria-hidden="true" />
                    <h3 className="mb-1.5 text-sm font-semibold text-foreground">{tp(`card_${c.key}_title`)}</h3>
                    <p className="text-sm leading-relaxed text-muted-foreground">{tp(`card_${c.key}_body`)}</p>
                  </div>
                );
              })}
            </div>

            <div className="mt-8 space-y-3">
              <h3 className="text-base font-semibold text-foreground">{tp("nowTitle")}</h3>
              <p className="max-w-[68ch] leading-relaxed text-muted-foreground [&_strong]:font-semibold [&_strong]:text-foreground">
                <InlineStrong text={tp("nowBody")} />
              </p>
            </div>

            <div className="mt-8 space-y-3">
              <div className="flex items-center gap-2">
                <GraduationCap className="h-5 w-5 text-primary" aria-hidden="true" />
                <h3 className="text-base font-semibold text-foreground">{tp("worthTitle")}</h3>
              </div>
              <p className="max-w-[68ch] leading-relaxed text-muted-foreground">
                {tp("worthBody")}
              </p>
            </div>

            <div className="mt-8 space-y-3">
              <div className="flex items-center gap-2">
                <Rocket className="h-5 w-5 text-primary" aria-hidden="true" />
                <h3 className="text-base font-semibold text-foreground"><BrandText text={tp("learnTitle")} /></h3>
              </div>
              <p className="max-w-[68ch] leading-relaxed text-muted-foreground">
                <BrandText text={tp("learnBody")} />
              </p>
            </div>
          </section>

          <article className="space-y-10">
            {sections.map((s) => (
              <section key={s.id} id={s.id} className="scroll-mt-6">
                <h2 className="mb-3 text-xl font-semibold tracking-tight text-foreground">{s.title}</h2>
                <div className="space-y-3">
                  {s.paras.map((p, i) => (
                    <p
                      key={i}
                      className="max-w-[68ch] leading-relaxed text-muted-foreground [&_strong]:font-semibold [&_strong]:text-foreground"
                    >
                      <InlineStrong text={p} />
                    </p>
                  ))}
                </div>
              </section>
            ))}
          </article>
        </div>
      </div>
    </AppShell>
  );
}
