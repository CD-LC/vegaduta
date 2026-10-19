// The landing page's band inventory and its click attribution — the single
// place that knows which beacon events belong to which section.
//
// Kept as data, next to the screen that renders it, because the mapping is a
// judgement call that needs explaining, not a lookup that can be derived. The
// event names were coined over six weeks of incremental funnel work and some
// of them no longer say what they mean (see RENAMED / RETIRED below); a naive
// `event.startsWith(section)` join would quietly mis-attribute those and the
// waterfall would lie in the most convincing way possible.
//
// SECTIONS must stay in DemoLanding.tsx render order — the waterfall reads as
// a drop-off curve and an out-of-order row would render as reach INCREASING
// down the page, which is impossible and would discredit the whole screen.

export interface LandingSectionSpec {
  /** `sec_<key>` is the reach event; see web's landingBeacon.ts LANDING_SECTIONS. */
  key: string;
  label: string;
  /** What the band is trying to get the visitor to do. */
  job: string;
  /** Click events attributable to this band, for the engage rate. */
  clicks: string[];
  /** Rendered under the row when the attribution needs a caveat. */
  caveat?: string;
}

export const SECTIONS: LandingSectionSpec[] = [
  {
    key: "hero",
    label: "Hero",
    job: "State the offer and take the first click",
    clicks: ["hero_try_click", "hero_watch_click", "hero_keep_agent_click", "hero_signup_click"],
    caveat:
      "Reach here should track landing_view almost exactly. If it doesn't, the instrumentation is wrong before any conclusion below is worth reading — treat this row as the calibration check, not as a finding.",
  },
  {
    key: "path",
    label: "The Path",
    job: "Answer “where do I start?” — Try → Understand → Implement",
    clicks: ["path_try_click", "path_academy_click", "path_templates_click", "path_signup_click"],
  },
  {
    key: "channels",
    label: "Channel wall",
    job: "Back the hero's “9 channels” claim",
    clicks: [],
  },
  {
    key: "senses",
    label: "Senses showcase",
    job: "Show what the agent can perceive and do",
    clicks: ["senses_try_click"],
  },
  {
    key: "proof",
    label: "Proof band",
    job: "Verifiable stats + the on-record quote",
    clicks: ["automate_try_click", "automate_templates_click"],
    caveat:
      "Both click events are named automate_* because they were coined for AutomateAnythingSection, which was removed in the 2026-08-14 content-reduction pass. ProofBand.tsx inherited the buttons and kept the old names, so the counters are LIVE but their names point at a band that no longer exists. Renaming them would reset the counters, so the mapping is corrected here instead.",
  },
  {
    key: "audience",
    label: "Audience showcase",
    job: "“Someone like you uses this”",
    clicks: [],
  },
  {
    key: "studio",
    label: "Magic Studio (live demo)",
    job: "The one genuinely live demo on the page",
    clicks: ["magic_run_click"],
  },
  {
    key: "channeldemos",
    label: "Channel demo tabs",
    job: "Simulated WhatsApp / voice / UPI walkthroughs",
    clicks: [],
  },
  {
    key: "underneath",
    label: "What's underneath",
    job: "Platform internals for the technical reader",
    clicks: [],
  },
  {
    key: "ide",
    label: "IDE plugins",
    job: "Developer surface — VS Code / JetBrains / Chrome",
    clicks: ["plugins_click"],
    caveat:
      "plugins_click also fires from the closing CTA's link chip in DemoLanding.tsx, so this band's engage rate is inflated by clicks that happened further down the page. Read it as an upper bound.",
  },
  {
    key: "security",
    label: "Security & trust",
    job: "Answer the credibility objection before price",
    clicks: [],
  },
  {
    key: "providers",
    label: "Provider wall",
    job: "Bring your own key / use ours / run on device",
    clicks: ["providerwall_signup_click", "providerwall_learn_click"],
  },
  {
    key: "pricing",
    label: "Pricing",
    job: "Answer “what does it cost?” inline",
    clicks: ["pricing_free_click"],
  },
  {
    key: "casestudy",
    label: "Free business case study",
    job: "“Send us one process and we'll draft the agent”",
    clicks: [
      "casestudy_industry_click",
      "casestudy_contact_click",
      "casestudy_signup_click",
      "casestudy_academy_click",
    ],
  },
  { key: "faq", label: "FAQ", job: "Last-objection handler", clicks: [] },
  {
    key: "closing",
    label: "Closing CTA",
    job: "The final ask",
    clicks: ["closing_try_click", "closing_signup_click"],
  },
];

/**
 * Clicks that belong to the page but not to any one band — the persistent nav
 * and the floating chips. Excluded from the waterfall on purpose: they can be
 * clicked from any scroll position, so dividing them by any single band's
 * reach would produce a meaningless rate.
 */
export const UNATTRIBUTED_CLICKS = [
  { event: "nav_signin_click", label: "Nav — Sign in" },
  { event: "signup_click", label: "Nav — Sign up" },
  { event: "hire_banner_click", label: "/hire chip" },
];

/**
 * Events still in core's whitelist whose only call sites are components that
 * DemoLanding no longer mounts (removed in the 2026-08-14 content-reduction
 * pass). They can never increment again.
 *
 * They are listed rather than deleted for two reasons: their historical counts
 * are still real and still in Redis until the 90-day TTL rolls them off, and a
 * whitelist entry costs nothing. What matters is that they must never appear
 * as a 0 next to live bands — a permanent 0 reads as "this converts nobody",
 * which is a much stronger and entirely false claim than "nothing fires this".
 */
export const RETIRED_EVENTS = [
  { event: "workforce_dev_academy_click", from: "WorkforceShiftSection" },
  { event: "workforce_exec_hire_click", from: "WorkforceShiftSection" },
  { event: "entrepreneur_earn_click", from: "EntrepreneurSection" },
  { event: "automate_contact_click", from: "AutomateAnythingSection" },
];

/** Events belonging to other pages entirely — not part of this funnel. */
export const OTHER_PAGE_EVENTS = [
  "hire_view",
  "hire_interview_answer",
  "hire_stamp_click",
  "hire_calc_interact",
  "hire_sign_draw",
  "hire_cta_click",
  "earn_academy_click",
  "earn_signup_click",
];
