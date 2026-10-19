import nextDynamic from "next/dynamic";

// Below-the-fold sections of this page (performance-cwv audit, 2026-07-30):
// this page is the logged-out root and by far the highest-traffic route on
// the domain, but every section — including the 728-line interactive
// MagicStudio widget — used to be a static import, so all of it landed in
// the initial JS payload regardless of whether the visitor ever scrolled
// past the hero. next/dynamic code-splits each into its own chunk, fetched
// only once React actually needs to render it, cutting first-load JS/TBT.
// HeroAgentDemo stays a static import on purpose — it's the hero's own
// content, in the first viewport, and the whole point of the 2026-07-29
// redesign was proving the product IS there on first paint; deferring it
// would just move the layout shift from "download" to "runtime swap".
const SKELETON_CLS = "min-h-[420px]";
// Process Theater: big, long-running processes (finance, incidents, code
// migrations, onboarding, claims) animated from REAL workflow node types. Same
// code-split treatment as MagicStudio below.
export const ProcessTheater = nextDynamic(() => import("../capabilities/ProcessTheater"), {
  loading: () => <div className={SKELETON_CLS} aria-hidden="true" />,
});
export const MagicStudio = nextDynamic(() => import("../try/MagicStudio"), {
  loading: () => <div className={SKELETON_CLS} aria-hidden="true" />,
});
// ---------------------------------------------------------------------------
// UNMOUNTED BANDS — declarations removed (load-perf pass, 2026-09-12).
//
// The 2026-09-12 length cut unmounted seven bands (senses, audience,
// channeldemos, underneath, ide, providers, proof) but left their
// nextDynamic() declarations behind. A dead nextDynamic() costs nothing at
// RUNTIME — the chunk is never fetched, so first-load JS was already correct —
// but it is not free at BUILD time: each one still anchors its whole subgraph
// into the compile and emits an async chunk nobody ever requests. Measured
// across the seven: ~365 KB of TSX (ChannelDemoTabs alone pulls 16 files /
// 91 KB via WhatsAppDemo + VoiceCallDemo + UpiWorkflowDemo; ProofBand 9 files
// / 93 KB; ProviderWall 7 files / 71 KB) compiled into seven orphan chunks.
//
// The files themselves are untouched and stay in the repo — every one of the
// mount-site comments below still says "re-mount is one line", and that stays
// true: the line is the mount, and the declaration it needs is this one form,
// verbatim, with the component's own name:
//
//   const X = nextDynamic(() => import("./X"), {
//     loading: () => <div className={SKELETON_CLS} aria-hidden="true" />,
//   });
//
// Removed here: ChannelDemoTabs, AudienceShowcase, SensesShowcase,
// ProviderWall, ProofBand, WhatsUnderneath, IdePluginsSection. Their i18n
// keys, `sec_*` beacon names and data-testids are all untouched.
// ---------------------------------------------------------------------------
// "The Path" (conversion redesign, 2026-09-10) — Try → Understand →
// Implement as three stops on one rail, journey-aware (see PathBand.tsx and
// lib/journey.ts). Mounted directly under the hero: it is the answer to
// "where do I start, and what comes after the trial?" that the funnel data
// showed visitors never got.
// OutcomesBand (2026-09-17) sits directly under the hero: four shipped
// capabilities as pictures, before PathBand asks "where do I start?". The
// hero->next-band drop was the funnel's largest; this is the band that
// has to earn the scroll. See OutcomesBand.tsx.
export const OutcomesBand = nextDynamic(() => import("./OutcomesBand"), {
  loading: () => <div className={SKELETON_CLS} aria-hidden="true" />,
});
// ShippedBand (2026-09-28): six dated "just shipped" cards — App Studio,
// sales automation, versioning, specialist handoff, "Powered by", SSO/SCIM.
// Directly after outcomes so momentum is on the second screen. See the file.
export const ShippedBand = nextDynamic(() => import("./ShippedBand"), {
  loading: () => <div className={SKELETON_CLS} aria-hidden="true" />,
});
export const PathBand = nextDynamic(() => import("./PathBand"), {
  loading: () => <div className={SKELETON_CLS} aria-hidden="true" />,
});
// Channel wall (2026-09-11) — the nine ChannelType values as artwork, with the
// free / paid / bring-your-own-account split stated on the same screen. See
// the mount site below for the placement argument.
export const ChannelWall = nextDynamic(() => import("./ChannelWall"), {
  loading: () => <div className={SKELETON_CLS} aria-hidden="true" />,
});
// Use cases (2026-09-24, direct instruction: "needs proper use cases").
// Six real briefs -> the node chain they compile to -> "run this spec"
// hands the sentence to the live studio below via magicPrefill. Dynamic
// like every other below-the-fold band.
export const UseCasesBand = nextDynamic(() => import("./UseCasesBand"), {
  loading: () => <div className={SKELETON_CLS} aria-hidden="true" />,
});
// Conversion band (2026-09-10, direct instruction: free business-case-study
// offer + "all providers that can be configured" + the true version of
// "using WebLLM make code changes"). ProviderWall was the other half of this
// pair; it is unmounted (content lives at /integrations) and its declaration
// went with the rest — see the UNMOUNTED BANDS block above.
export const BusinessCaseBand = nextDynamic(() => import("./BusinessCaseBand"), {
  loading: () => <div className={SKELETON_CLS} aria-hidden="true" />,
});
// Open-source band (2026-09-22, direct instruction): the pricing slot
// answered "why pricing?" with "we're going open source". Unmounted
// 2026-10-04 (direct instruction) — OpenSourceBand.tsx and PricingBand.tsx
// both stay in the repo unmounted (kept-for-rollback convention, same as
// every other cut band); the "pricing" SectionReach slot is gone with it.
export const LandingFaq = nextDynamic(() => import("./LandingFaq"), {
  loading: () => <div className="min-h-[320px]" aria-hidden="true" />,
});
// Conversion rebuild (2026-09-12). PlatformLedger answers "what has this
// platform actually got" in one band near the top; ContactBand is the first
// ask-a-human banner this page has carried. Both are dynamic like every other
// below-the-fold band — PlatformLedger sits third, so it is the first dynamic
// chunk a scroller pulls, which is deliberate.
// 2026-09-17: CapabilityAtlas took PlatformLedger's slot (band 3, same
// SectionReach name "ledger" so the breadth band's reach stays one series).
// 29 searchable tiles instead of 9 static ones - plugins, RL learning,
// quality judges, guardrails, on-device AI, share links, templates, Academy,
// budgets, mobile all had no home on the page before. PlatformLedger.tsx is
// kept, unmounted; re-mount is the usual one declaration + one line.
export const CapabilityAtlas = nextDynamic(() => import("./CapabilityAtlas"), {
  loading: () => <div className={SKELETON_CLS} aria-hidden="true" />,
});
export const ContactBand = nextDynamic(() => import("./ContactBand"), {
  loading: () => <div className="min-h-[320px]" aria-hidden="true" />,
});
// CategoryBand (2026-09-12) sits fourth, right behind PlatformLedger. Dynamic
// like its neighbours — and worth it here specifically, because this band
// imports `app/compare/compare-data.ts`, which carries all three full
// competitor tables plus their citations. Statically imported it would drag
// that entire module into the landing's first load for a band that renders
// two strings per competitor.
export const CategoryBand = nextDynamic(() => import("./CategoryBand"), {
  loading: () => <div className={SKELETON_CLS} aria-hidden="true" />,
});
// ControlBand (2026-09-25, direct instruction: answer "why not ChatGPT /
// Claude Desktop / Hermes / OpenClaw — why VegaDūta?" and show what the
// visitor actually controls). Mounted directly after `category` — the two
// comparison bands are one positioning move: category locates the product
// among BUILDER tools, control locates it against the assistant/agent the
// visitor already runs. See ControlBand.tsx's header for the argument.
export const ControlBand = nextDynamic(() => import("./ControlBand"), {
  loading: () => <div className={SKELETON_CLS} aria-hidden="true" />,
});
// Content-reduction pass (2026-08-14, judged multi-agent workflow — plan
// published as an artifact, then implemented): 23 mounted sections down to
// 9 real content sections. ProofBand + WhatsUnderneath are new, consolidating
// what used to be WorkforceShiftSection, the inline "no invented numbers"
// stat strip, DayInTheLife, AutomateAnythingSection, PlatformIntelligenceSection,
// FeatureGrid, WhySwitchSection, TemplatesShowcase, IdePluginsSection,
// EntrepreneurSection and AcademyCTA — each new file's own header comment
// documents the source facts each merged card/line traces back to. None
// were deleted: DayInTheLife.tsx stays in the repo unmounted (same "kept
// for reference/rollback" convention as HowHiringWorksSection below); the
// rest still serve their own real destination pages (/templates, /plugins,
// /earn) or now render as a closing-CTA link chip instead of a full band.
// ProofBand, WhatsUnderneath and IdePluginsSection were themselves unmounted
// on 2026-09-12; their declarations went with the rest — see the UNMOUNTED
// BANDS block above.
// India regional-language modal (genesis/FirstVisitLanguagePrompt.tsx) —
// UNMOUNTED 2026-10-05 (direct instruction: "disable language detection
// logic"). The component file stays in the repo, unchanged; re-mounting it
// is one import + one JSX line. proxy.js's own language detection
// (geo/Accept-Language) was disabled in the same pass.
