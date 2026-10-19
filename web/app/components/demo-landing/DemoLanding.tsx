"use client";

import { useEffect } from "react";
import { sendLandingViewOnce } from "./landingBeacon";
import {
  ProcessTheater,
  MagicStudio,
  OutcomesBand,
  ShippedBand,
  PathBand,
  ChannelWall,
  UseCasesBand,
  BusinessCaseBand,
  LandingFaq,
  CapabilityAtlas,
  ContactBand,
  CategoryBand,
  ControlBand,
} from "./landingSections";
import LandingNav from "./LandingNav";
import ClosingBand from "./ClosingBand";
import SecuritySection from "./SecuritySection";
// HeroOutcome replaced HeroUniversalAgent as the mounted hero on 2026-09-17
// (see HeroOutcome.tsx's header). Re-mounting the old hero is one import + one line.
import HeroOutcome from "./HeroOutcome";
import SectionReach from "./SectionReach";
import SiteFooter from "./SiteFooter";
import { useGeo, type LandingVariant } from "../../lib/geo";
import { useJourney } from "../../lib/journey";
// Reveal (../marketing/Reveal) and `motion` are deliberately NOT imported here
// any more (load-perf pass, 2026-09-12). This file used framer-motion for
// exactly three things — the closing band's fade/rise and the two section
// heads in SecuritySection / HowHiringWorksSection — every one of them a plain
// on-scroll fade+rise with `once: true`, no state machine, no AnimatePresence.
// `.mkt-reveal` (marketing-system.css §8) is the same animation as a CSS
// view-timeline: zero JS, no IntersectionObserver, no motion component to
// hydrate. It is already gated on `prefers-reduced-motion: no-preference` AND
// `@supports (animation-timeline: view())`, so with motion off — or in a
// browser without scroll-driven animations — the element is simply visible
// from the start. Nothing rests at opacity 0, which is more than the JS
// version could promise.
//
// Honest scope: this does NOT remove framer-motion from the landing's first
// load today, because HeroUniversalAgent (a static import, in the first
// viewport) still imports it. What it does is take DemoLanding itself off that
// dependency, so the page's own tree hydrates three fewer motion components —
// and the day the hero drops `motion`, this file is no longer what holds it.
// The remaining ~20 demo-landing files that import `motion` are all behind
// nextDynamic boundaries or unmounted, so they cost the initial load nothing.
//
// The `../ui/card` import that used to sit here was removed in the same pass:
// Card/CardDescription/CardHeader/CardTitle were imported and never once
// referenced in this file, and DemoLanding was the ONLY importer of
// `ui/card.tsx` anywhere in the landing's static closure — so the module (and
// its own deps) was being compiled into the first-load chunk for nothing.

// DemoLanding — the logged-out root ("/") AND /explore experience (merged
// 2026-07-28; category redesign 2026-07-29 per the homepage audit + direct
// instruction "implement and try to make it 10/10").
//
// The 2026-07-29 redesign, concretely (supersedes the 2026-07-28 "one big
// centred agentic-AI statement / for everyone" hero — that framing described
// the category, not the company, and showed no product):
//   * Identity-first hero: देश का अपना AI एजेंट as the headline (India
//     variant) with the "AI staff — hired, not installed" category line, and
//     the kirana WhatsApp demo PLAYING in the hero (HeroAgentDemo) — the
//     product proves the claim in the first viewport.
//   * Employment frame throughout: brief → put it on the counter → you
//     approve (HowHiringWorksSection, replacing the "what makes it agentic"
//     explainer). Every claim maps to shipped capability; agent-collected
//     payments appear ONLY inside the clearly-labelled simulated demos
//     (the UPI build-out is still pending in core — no-fiction rule).
//   * KeyFacts (Gartner/NASSCOM band) removed: borrowed analyst authority,
//     and its count-up rendered literal zeros pre-animation.
//   * Order: hero(+demo) → verifiable-numbers strip → how hiring works →
//     Magic Studio → channel demos → AudienceShowcase → FeatureGrid →
//     Academy → Security → closing CTA. Proof before segmentation.
//   * Deeper routes unchanged (/features, /compare, /why-vegaduta, /docs);
//     app/explore/page.tsx still renders THIS component.
//
// Geo-targeting (2026-07): `variant` ("india" | "global") is resolved
// server-side in layout.tsx from Cloudflare's CF-IPCountry header and reaches
// here via useGeo() (see GenesisExperience.tsx, lib/geo.tsx).
export default function DemoLanding({ variant: variantProp }: { variant?: LandingVariant } = {}) {
  const { variant: contextVariant } = useGeo();
  const variant = variantProp ?? contextVariant;
  const isIndia = variant === "india";
  // Journey-aware CTAs (2026-09-10): a browser that already holds a /try
  // session gets "keep my agent" (signup carrying the demo token) as the
  // primary in the nav and the closing band, matching the hero and PathBand.
  const journey = useJourney();
  const tried = journey.stage === "keep";

  // Funnel beacon: one landing_view per browser session (see landingBeacon.ts).
  useEffect(() => {
    sendLandingViewOnce();
  }, []);

  return (
    // marketing-root: the public-funnel scope. It opts every h1/h2 on this
    // page into the marketing display face (Sora — globals.css) and is the
    // root every `.mkt-*` rule in marketing-system.css hangs off.
    <main className="marketing-root relative min-h-screen w-full overflow-x-hidden bg-background text-foreground antialiased">
      <span id="top" className="absolute -top-24" aria-hidden="true" />

      {/* Ambient backdrop. Now two empty elements: marketing-system.css owns
          the aurora pools, the engineering grid and the grain sheet.

          Two real bugs died with the markup this replaced.
          (1) The grid colour was keyed off `prefers-color-scheme` in an inline
              <style>, but this app switches theme on `:root[data-mode="dark"]`.
              A visitor whose OS is light and who picked dark in the nav got the
              LIGHT grid (rgba(15,23,42,.08)) painted on a near-black canvas —
              a visible dirty haze behind the hero. `--mkt-grid` is defined in
              the same `[data-mode]` scope as every other token, so it cannot
              disagree with the theme again.
          (2) Those two circles were `blur-[140px]` on 42rem/32rem boxes — a
              full-viewport gaussian recomputed on every paint. It is why
              screenshots of this page time out, and it is a measurable frame
              cost on the mid-range Android that carries most of this traffic.
              Radial-gradient stops render the identical glow for free. */}
      <div aria-hidden="true" className="mkt-canvas-bg" />
      <div aria-hidden="true" className="mkt-grain" />
      {/* Brand-emblem watermark (2026-09-24, direct instruction): the winged
          mark at viewport scale and low alpha, fixed behind every band,
          slowly drifting. One empty element — marketing-system.css §2 owns
          the artwork, the alpha stops and the reduced-motion gate. It only
          renders because .marketing-root is `isolation: isolate` — without a
          stacking context on main, every negative-z layer below (aurora,
          grain, this mark) sits behind main's opaque bg-background. */}
      <div aria-hidden="true" className="mkt-emblem" />

      {/* The /hire announcement strip that used to sit ABOVE the nav was
          removed here (funnel audit, 2026-09-09): it was the very first
          thing every visitor saw — a second product pitch before the page's
          own — and /hire is already reachable from the hamburger "Products"
          group, the footer, and now a chip in the closing link row (which
          carries the same data-testid + a beacon). hire_banner* keys stay
          in the locale files for that chip's tooltip. */}

      {/* Lean sticky nav. `.mkt-nav` owns sticky/backdrop/transition;
          `.is-stuck` is the condensed state (see the sentinel effect above).
          At the very top of the page the header is nearly transparent so the
          hero's aurora reads as one continuous surface behind it — the header
          only materialises once there is content scrolling under it. */}
      <LandingNav journey={journey} tried={tried} />

      {/* ------------------------------------------------------------- Hero.
          2026-07-29 category redesign (homepage audit + direct instruction
          "implement and try to make it 10/10"): the previous hero was one big
          centred agentic-AI statement with no product in sight — the category
          described, not the company. This one makes the ONE differentiated
          argument and proves it in the same viewport:
            * identity first — देश का अपना AI एजेंट is the headline (India
              variant), not a subtitle under a generic English line;
            * category line — "AI staff", hired not installed: the employment
              frame that no chatbot/wrapper competitor occupies;
            * the product runs beside the words — HeroAgentDemo plays the
              kirana order end to end, clearly labelled simulated. The demo
              IS the badge row; the six capability chips it replaced were
              claims, this is the thing itself.
          "FOR EVERYONE" is gone on purpose: the audit's core finding was
          that a page for everyone positions no one. Students/devs/enterprise
          still get their sections below — the hero commits. */}
      <section id="how" className="relative mx-auto max-w-6xl scroll-mt-16 px-5 pb-16 pt-6 sm:px-8 sm:pt-8">
        {/* HeroOutcome is the whole hero (2026-09-17 conversion rebuild;
            HeroUniversalAgent held this slot from 2026-08-03 and is kept,
            unmounted). One owner for the hero text, as before. */}
        <SectionReach section="hero">
          <HeroOutcome />
        </SectionReach>
      </section>

      {/* What it does - four outcomes with illustrations (2026-09-17). */}
      <SectionReach section="outcomes">
        <OutcomesBand />
      </SectionReach>

      {/* Just shipped - six live-in-prod capabilities added since the
          2026-09-17 rebuild, each dated (2026-09-28). */}
      <SectionReach section="shipped">
        <ShippedBand />
      </SectionReach>

      {/* The Path — Try → Understand → Implement, expanded from the hero's
          compact stepper (same three stops, same order). */}
      <SectionReach section="path">
        <PathBand />
      </SectionReach>

      {/* Breadth, stated once and early (2026-09-12). Before this band the
          page never said what the platform IS in one place — RAG lived in
          band 9, models in band 13, evals nowhere at all — so a visitor who
          left before the halfway mark had seen a WhatsApp agent demo and
          concluded that was the product. */}
      <SectionReach section="ledger">
        <CapabilityAtlas signupHref={journey.signupHref} />
      </SectionReach>

      {/* Category, stated once, immediately after the ledger (2026-09-12,
          direct instruction: "inform consumers this is something like n8n and
          other platform, it should be clearly communicated").

          Order is the argument: the ledger says WHAT this is, then this says
          what it is LIKE. Anchoring to a competitor BEFORE stating your own
          scope invites the visitor to file you as a thinner copy of them.

          The band authors no competitor claims — it renders audited fields
          straight out of `app/compare/compare-data.ts`, which has shipped
          cited n8n / Dify / Botpress pages for weeks with nothing on the
          landing pointing at them except a hamburger entry and a footer
          link. See CategoryBand.tsx for why it leads with what the
          competitor does better. */}
      <SectionReach section="category">
        <CategoryBand />
      </SectionReach>

      {/* Why not the AI you already have (2026-09-25). `category` answers
          "what is this LIKE among builder tools"; this answers the other
          half — "why not just ChatGPT / Claude Desktop / a self-hosted
          agent" — then hands the visitor the eight control levers a chat
          window can't. Same SectionReach convention; "control" is a new
          name needing the core whitelist (see landingBeacon.ts). */}
      <SectionReach section="control">
        <ControlBand />
      </SectionReach>

      {/* Channels — mounted HERE, third, and the placement is the argument:
          the h1 now makes this page's single most specific numeric claim
          ("9 channels, free on Telegram and Email"), and a visitor who reads
          a number wants to know which nine and what the "free on" qualifier
          excludes. So the proof has to sit within one scroll-stop of the
          claim, not five sections down.

          Why not ABOVE PathBand, directly under the hero? PathBand's own
          mount comment records that it was put directly under the hero on
          funnel evidence — it is the "where do I start, and what comes after
          the trial?" answer visitors were never reaching. Displacing it to
          make room for a feature band would trade a measured conversion fix
          for a presentation preference. One stop later still reads as the
          hero's payoff.

          Why not next to ProviderWall (the builder's suggestion)? Both are
          mark-grid "wall" bands. Adjacent, they read as one long undifferen-
          tiated logo dump and each blunts the other. Split high/low they do
          different jobs: reach up here, model choice down by Security and
          Pricing where the lock-in objection lives.

          It is a complete <section id="channels"> with its own border-t and
          padding, so it needs no wrapper — and the id gives the nav a
          /#channels anchor. */}
      <SectionReach section="channels">
        <ChannelWall />
      </SectionReach>

      {/* Use cases (2026-09-24) — the standard "what would I build with it"
          band this page never had, mounted between the channel wall (the
          reach claim) and ProcessTheater (the proof in motion): six real
          briefs, each with the node chain it compiles to, a "run this spec"
          handoff into the live studio below, and a link to the matching
          recipe in the 89-industry guide. */}
      <SectionReach section="usecases">
        <UseCasesBand />
      </SectionReach>

      {/* How It Works — the senses, one card each; eyes/ears/voice/hands/wings
          live today, touch (IoT/BLE bridge) honestly marked "in design" as a
          one-line footnote instead of a full card. */}
      {/* LENGTH CUT, 2026-09-12 ("ensure page is not lengthy and overloaded
          with information, it should be at standard legth, if required move
          other sections to other pages").

          The page measured 31,416px — 46 screens of scroll against a 8-12
          screen norm for a converting landing page. Six bands were unmounted
          here; each one's content already has a real destination page that is
          public, in the sitemap and better suited to it. Nothing was deleted:
          every component file, every i18n key and every `sec_*` beacon name
          survives untouched, so any band can be re-mounted with one line.

          Unmounted here, and where each now lives:
            senses      -> /platform   (capability detail)
            audience    -> /platform   (persona detail)
            channeldemos-> redundant: `channels` + the live studio both already
                           demonstrate this, one scroll-stop apart
            underneath  -> /platform   (architecture detail)
            ide         -> /integrations
            providers   -> /integrations  (4,211px on its own — six screens,
                           the single largest band on the page)

          (2026-10-05: the bare `sensesWrapperRef` sentinel that used to live
          here is gone — it existed only to arm the India language prompt
          once the visitor was past the hero, and that prompt is unmounted
          now. See the import block above.) */}

      {/* Proof & Who It's For — merged section (2026-08-14 content-reduction
          pass, judged multi-agent workflow). ProofBand (verifiable stats +
          the on-record Salesforce quote + 3 example agents) flows straight
          into AudienceShowcase's own picker (id="audience-showcase",
          unchanged mechanism, just tightened to show 6 of 16 by default) —
          together they replace five sections that used to separately try to
          prove "this works for someone like you". Full merge ledger in
          ProofBand.tsx's header comment. */}
      {/* LENGTH CUT 2026-09-12 — `proof` unmounted (2,161px); its mechanism
          examples live at /platform. Component, i18n keys and the `sec_proof`
          beacon name all survive; re-mount is one line. NOTE: ProofBand is
          where `automate_try_click` / `automate_templates_click` fired, so
          those two counters go quiet from here — that is an unmount, not a
          drop-off. See the senses cut above for the full rationale. */}
      {/* LENGTH CUT 2026-09-12 — `audience` unmounted; content lives at /platform.
          Component, i18n keys and the `sec_audience` beacon name all survive; re-mount
          is one line. See the senses cut above for the full rationale. */}

      {/* See It Work — Magic Studio, the one LIVE demo on this page (real
          backend: plans, MCP-registry discovery, model pool, dynamic agent,
          public /w link — see MagicStudio.tsx), with the three simulated
          channel demos re-tabbed directly underneath instead of their own
          full section (ChannelDemoTabs.tsx's own comment: proof stays
          together, just visually joined rather than a second scroll-stop). */}
      <div id="processes" className="scroll-mt-16 border-t border-border/60">
        <div className="mx-auto w-full max-w-6xl px-4 py-12 sm:px-6">
          <ProcessTheater ctaHref="/try" />
        </div>
      </div>
      <SectionReach section="studio">
        <div id="live-studio" className="scroll-mt-16 border-t border-border/60">
          <MagicStudio />
        </div>
      </SectionReach>
      {/* LENGTH CUT 2026-09-12 — `channeldemos` unmounted; content lives at (redundant with `channels` + the live studio).
          Component, i18n keys and the `sec_channeldemos` beacon name all survive; re-mount
          is one line. See the senses cut above for the full rationale. */}

      {/* What's Underneath — merged (2026-08-14): PlatformIntelligenceSection,
          FeatureGrid and WhySwitchSection used to carry 16 overlapping cards
          between them; see WhatsUnderneath.tsx's header for the full dedup
          ledger down to 7 cards + one comparison callout. */}
      {/* LENGTH CUT 2026-09-12 — `underneath` unmounted; content lives at /platform.
          Component, i18n keys and the `sec_underneath` beacon name all survive; re-mount
          is one line. See the senses cut above for the full rationale. */}

      {/* "Control your agents/workflows from your IDE — and Chrome". Sat
          directly under the hero from 2026-08-14 (direct instruction at the
          time); moved down here after WhatsUnderneath (funnel audit,
          2026-09-09): for the SMB visitor the landing is written for, a
          developer-tools pitch as the SECOND thing on the page was a detour
          between the hero's CTA and the proof (senses / examples / live
          demo) that earns it. Developers still find it — it follows the
          platform-internals band, which is where they are reading anyway —
          and the closing CTA's link chip points here too. */}
      {/* LENGTH CUT 2026-09-12 — `ide` unmounted; content lives at /integrations.
          Component, i18n keys and the `sec_ide` beacon name all survive; re-mount
          is one line. See the senses cut above for the full rationale. */}

      {/* Trust — the platform's actual security claims, ahead of pricing:
          research says this is exactly the objection a credibility-gap
          product needs to keep visible, not defer past the fold. */}
      <SectionReach section="security">
        <SecuritySection />
      </SectionReach>

      {/* Models & providers — mounted BETWEEN Security and Pricing on
          purpose. It is the same objection Security is already answering,
          one layer up ("where does my data actually go, and am I locked to
          one vendor?"), so it inherits that band's attention instead of
          competing for fresh attention further down. And it has to come
          BEFORE PricingBand: the Free row's "Hosted model included — no API
          key" only lands as generous once the visitor has seen that there
          ARE 13 keys they could otherwise be paying for, plus the on-device
          option. Reversed, pricing reads as a limitation. */}
      {/* LENGTH CUT 2026-09-12 — `providers` unmounted; content lives at /integrations.
          Component, i18n keys and the `sec_providers` beacon name all survive; re-mount
          is one line. See the senses cut above for the full rationale. */}

      {/* UNMOUNTED 2026-10-04 — the "pricing" slot's open-source band
          (OpenSourceBand, which took PricingBand's place 2026-09-22) is cut
          per direct instruction; both files stay in the repo, remount is one
          declaration + one line. /billing still exists for existing accounts;
          the landing just stops leading with it. */}

      {/* Free business case study — mounted DIRECTLY AFTER pricing, and
          before the FAQ, because that is where the page's one unanswered
          question lands. Everything above proves the platform works and
          costs nothing; the visitor's remaining doubt is not "is it real"
          or "can I afford it", it is "would it work for MY business, and
          can I actually build it?" This band answers both without asking
          for a rupee: send us one process and we draft the agent, or open
          the free dashboard and build it yourself with Academy. Putting it
          after the FAQ would bury it under the closing CTA; putting it
          before pricing would ask for a conversation before the visitor
          knew the product is free. */}
      <SectionReach section="casestudy">
        <BusinessCaseBand />
      </SectionReach>

      {/* Last-objection handler before the closing CTA + the homepage's
          first page-level JSON-LD (FAQPage). Trimmed 6 questions to the 4
          that rebut a real objection. */}
      <SectionReach section="faq">
        <LandingFaq />
      </SectionReach>

      {/* The contact banner (2026-09-12), after the FAQ on purpose: the FAQ is
          what a visitor reads INSTEAD of asking, so whoever still has a
          question at this point is exactly who this band is for. See
          ContactBand.tsx for why this is a mailto and not a form. */}
      <SectionReach section="contact">
        <ContactBand signupHref={journey.signupHref} />
      </SectionReach>

      <ClosingBand journey={journey} tried={tried} isIndia={isIndia} />
      <SiteFooter />
    </main>
  );
}
