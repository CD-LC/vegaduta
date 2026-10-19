import { publicApiBase } from "../../lib/env";
// Fire-and-forget funnel beacons for the public landing (2026-07-28 audit:
// "you literally cannot answer how well conversion happens with data today").
// Counterpart of core's LandingEventController: POST /api/public/landing-event
// increments a Redis counter per event per UTC day — no PII, no cookies, no
// third-party script, nothing stored about the visitor beyond +1.
//
// Same philosophy as try/demoClient.ts's postDemoEvent: never throws, never
// reports failure — a lost analytics event is nobody's problem, least of all
// the visitor's. keepalive:true so a click-through beacon survives the
// navigation it measures.

import type { OnboardingEvent } from "../../start/events";

const API_BASE = publicApiBase();

export type LandingEvent =
  | "landing_view"
  | "hero_try_click"
  | "hero_watch_click"
  | "signup_click"
  | "magic_run_click"
  | "closing_try_click"
  | "closing_signup_click"
  // 2026-09-12 CategoryBand ("where this sits", vs n8n/Dify/Botpress).
  // CROSS-SCOPE FOLLOW-UP, NOT YET DONE: these two, plus "sec_category" from
  // LANDING_SECTIONS below, still need adding to core's
  // LandingEventController.ALLOWED_EVENTS *and* ALLOWED_EVENTS_ORDERED.
  // core/ is core-3's scope, so this session did not edit it. Until that
  // lands the endpoint 204-drops all three and they read as a flat 0 —
  // which is indistinguishable from a band nobody reached. Do not interpret
  // a zero on these until the server side is in.
  | "category_compare_click"
  | "category_compare_all_click"
  // 2026-08-03 workforce-shift + automate-anything bands. Server-side
  // whitelist (core's LandingEventController.ALLOWED_EVENTS) carries these
  // four as of the same date — any NEW event added here must be added there
  // too, or it's silently 204-dropped by design.
  | "workforce_dev_academy_click"
  | "workforce_exec_hire_click"
  | "automate_try_click"
  | "automate_templates_click"
  // 2026-08-12: "contact our team" support line, same band.
  | "automate_contact_click"
  // 2026-08-03 entrepreneur band + /earn playbook page.
  | "entrepreneur_earn_click"
  | "earn_academy_click"
  | "earn_signup_click"
  // 2026-09-09 funnel audit: the five click sites that had NO beacon — nav
  // "Sign in", the pricing band's Free CTA, the senses band's /try button,
  // the IDE-plugins band's /plugins link and the /hire chip. Without these
  // the funnel read-out could not distinguish "nobody scrolled to pricing"
  // from "pricing converted everyone". Whitelisted server-side the same day.
  | "nav_signin_click"
  | "pricing_free_click"
  | "senses_try_click"
  | "plugins_click"
  | "hire_banner_click"
  // 2026-09-10 conversion redesign ("The Path"): the hero's journey-aware
  // buttons and the new Try -> Understand -> Implement band (PathBand.tsx).
  // hero_keep_agent_click fires ONLY for a browser that already holds a
  // /try session - so (hero_keep_agent_click / hero_try_click) is the
  // returning-trialist conversion the redesign exists to move. Whitelisted
  // server-side the same day.
  | "hero_keep_agent_click"
  | "hero_signup_click"
  | "path_try_click"
  | "path_academy_click"
  | "path_templates_click"
  | "path_signup_click"
  // 2026-09-10 conversion band: BusinessCaseBand (the free "bring us your
  // process" case-study offer) + ProviderWall (bring your own key / use ours
  // / run it on your device). casestudy_contact_click is the one that
  // matters most — it is the only human-contact conversion on this page that
  // is NOT the automate band's support line, so keeping it a separate name
  // from automate_contact_click is what makes the two attributable.
  // Whitelisted server-side the same day (LandingEventController).
  | "casestudy_industry_click"
  | "casestudy_contact_click"
  | "casestudy_signup_click"
  | "casestudy_academy_click"
  | "providerwall_signup_click"
  | "providerwall_learn_click"
  // 2026-09-12 conversion rebuild. PlatformLedger is the new breadth band
  // ("what the platform actually is", high up); ContactBand is the first
  // real ask-a-human banner this page has ever carried.
  // contact_band_click is deliberately a NEW name rather than a revival of
  // the dead automate_contact_click: that counter has been frozen since its
  // component was unmounted on 2026-08-14, and reusing it would splice a
  // new band's numbers onto weeks of flat line, making the before/after
  // unreadable. It fires from BOTH the button and the plain-email link in
  // the same band - one band, one intent - so it is a count of "wanted to
  // write to us", not of clicks on one control. Whitelisted server-side the
  // same day (LandingEventController).
  //
  // magic_signup_click fires from MagicStudio's publish-success card -
  // the one place a visitor has already built, run AND published a
  // working agent, so (magic_signup_click / magic_run_click) is the
  // cleanest intent-to-account ratio this page can measure.
  | "magic_signup_click"
  // hero_console_signup_click sits INSIDE the hero simulation card, after
  // the stations finish. Separate from hero_signup_click on purpose: a
  // reader reaches this one by watching the card to its end rather than
  // by reading the headline, so merging them would hide exactly the
  // difference this placement exists to measure.
  | "hero_console_signup_click"
  | "ledger_signup_click"
  | "ledger_academy_click"
  | "contact_band_click"
  | "contact_signup_click"
  // 2026-09-17 conversion rebuild (HeroOutcome + OutcomesBand + signup
  // instrumentation). hero_email_start = the hero's email starter submitted
  // (a typed address, not a click - the strongest intent the hero can
  // measure). hero_persona_click = a persona chip tapped. outcomes_* = the
  // CTAs of the new band under the hero. signup_view / signup_submit /
  // signup_done close the gap the funnel always had: every landing counter
  // stopped at the CLICK to /signup, so "14 hero clicks" could never be
  // read against "how many accounts were actually created". Fired from
  // web/app/signup/page.tsx. hero_watch_click is REUSED for opening the
  // walkthrough dialog: the intent ("show me the demo") is the same, and
  // the counter now finally measures a demo that exists. All need the
  // matching names in core's LandingEventController.ALLOWED_EVENTS(_ORDERED)
  // or they 204-drop silently - see AGENT-COORDINATION 2026-09-17.
  | "hero_email_start"
  | "hero_persona_click"
  | "outcomes_signup_click"
  | "outcomes_try_click"
  | "signup_view"
  | "signup_submit"
  | "signup_done"
  // atlas_tile_click: any tile opened in CapabilityAtlas (band 3). One name
  // for all 29 tiles on purpose - the question is "do people explore the
  // atlas at all", not which tile; per-tile would need 29 whitelist entries.
  | "atlas_tile_click"
  // 2026-09-22 open-source band: OpenSourceBand took PricingBand's slot.
  // oss_try_click is its one live CTA (/try); the Star button is deliberately
  // disabled until the repo is public, so there is no oss_star_click yet.
  // CROSS-SCOPE FOLLOW-UP, NOT YET DONE: oss_try_click still needs adding to
  // core's LandingEventController.ALLOWED_EVENTS *and* ALLOWED_EVENTS_ORDERED.
  // core/ is core-3's scope, so this session did not edit it. Until that
  // lands the endpoint 204-drops it and it reads as a flat 0. Noted in
  // docs/AGENT-COORDINATION.md.
  | "oss_try_click"
  // 2026-09-28: ShippedBand ("Just shipped", under OutcomesBand). Same
  // CROSS-SCOPE caveat as oss_try_click: both names (and sec_shipped) need
  // core's LandingEventController whitelist or they 204-drop as a flat 0.
  | "shipped_signup_click"
  | "shipped_try_click"
  // 2026-09-24 use-cases band (UseCasesBand.tsx): usecases_try_click fires
  // when a card's "run this spec" hands its brief to the live studio via
  // queueMagicPrompt; usecases_recipe_click fires on the per-card link into
  // the matching workflow-recipes guide page. CROSS-SCOPE FOLLOW-UP, NOT
  // YET DONE: both, plus "sec_usecases" from LANDING_SECTIONS below, still
  // need adding to core's LandingEventController.ALLOWED_EVENTS *and*
  // ALLOWED_EVENTS_ORDERED (core-3 scope) — until then they 204-drop and
  // read as flat 0, same caveat as oss_try_click above.
  | "usecases_try_click"
  | "usecases_recipe_click"
  // 2026-09-25 control band (ControlBand.tsx — "why not the AI you already
  // have", mounted right after `category`): control_why_click fires on the
  // /why-vegaduta link, control_compare_click on the /compare link.
  // CROSS-SCOPE FOLLOW-UP, NOT YET DONE: both, plus "sec_control" from
  // LANDING_SECTIONS below, still need adding to core's
  // LandingEventController.ALLOWED_EVENTS *and* ALLOWED_EVENTS_ORDERED
  // (core-3 scope) — until then they 204-drop and read as flat 0, same
  // caveat as oss_try_click above.
  | "control_why_click"
  | "control_compare_click"
  // 2026-09-11 section-reach funnel: one `sec_<band>` per landing band, so
  // the read-out can finally separate "nobody scrolled to pricing" from
  // "pricing converted nobody". See LANDING_SECTIONS below. Whitelisted
  // server-side the same day.
  | SectionReachEvent
  // 2026-10-04 /start onboarding flow (web/app/start): a closed vocabulary of
  // 18 names, defined in app/start/events.ts and whitelisted in core's
  // LandingEventController.ALLOWED_EVENTS(_ORDERED). Same 2026-10-04 change
  // whitelists sec_shipped, shipped_signup_click, shipped_try_click,
  // sec_category, sec_control and sec_usecases, which the notes above still
  // describe as pending. category_compare_click, category_compare_all_click,
  // usecases_try_click, usecases_recipe_click, control_why_click and
  // control_compare_click remain NOT whitelisted and are still 204-dropped.
  | OnboardingEvent;

/**
 * Every band on the landing page, in render order (DemoLanding.tsx). Order
 * matters: the read-out draws these as a waterfall and a band out of order
 * would show as a nonsensical increase in reach partway down the page.
 *
 * Adding a band here is HALF the change - the matching `sec_<name>` string
 * must also go into core's LandingEventController.ALLOWED_EVENTS, or the
 * endpoint silently 204-drops it and the new band reads as 0% reach, which
 * looks exactly like a band nobody scrolled to. Same trap as every other
 * event in this file, but easier to fall into because these names are
 * generated rather than written out.
 */
export const LANDING_SECTIONS = [
  "hero",
  // 2026-09-17: four outcomes with illustrations, directly under the hero.
  "outcomes",
  // 2026-09-28: ShippedBand, six dated "just shipped" cards under outcomes.
  // "sec_shipped" needs the core whitelist too.
  "shipped",
  "path",
  // 2026-09-12: the breadth band, placed third on purpose. "What is this
  // thing, in full" is the question the page previously answered in band 9
  // (WhatsUnderneath) and band 13 (ProviderWall), i.e. after most readers
  // had already left.
  "ledger",
  // 2026-09-12: the category band, immediately after the ledger. The ledger
  // says WHAT the platform is; this says what it is LIKE, against the tools a
  // visitor already knows (n8n/Zapier/Make). That order is deliberate —
  // anchoring to a competitor before stating your own scope invites the
  // visitor to assume you are a thinner copy of it.
  "category",
  // 2026-09-25: ControlBand — "why not the AI you already have" (ChatGPT /
  // Claude Desktop / Hermes / OpenClaw) plus the what-you-control grid.
  // Directly after `category` so the two comparison bands read as one
  // positioning move. "sec_control" needs the core whitelist too — see the
  // comment above the LandingEvent type.
  "control",
  "channels",
  // 2026-09-24: UseCasesBand, mounted after the channel wall and before the
  // process theater / live studio. "sec_usecases" needs the core whitelist
  // too — see the comment above this type.
  "usecases",
  "senses",
  "proof",
  "audience",
  "studio",
  "channeldemos",
  "underneath",
  "ide",
  "security",
  "providers",
  "pricing",
  "casestudy",
  "faq",
  // 2026-09-12: after the FAQ, because the FAQ is what a visitor reads
  // instead of asking - whoever is still holding a question at that point is
  // exactly who this band is for.
  "contact",
  "closing",
] as const;

export type LandingSection = (typeof LANDING_SECTIONS)[number];
export type SectionReachEvent = `sec_${LandingSection}`;

/**
 * A band counts as REACHED once per browser session. Same sessionStorage
 * guard and same reasoning as sendLandingViewOnce: client-side nav can
 * remount the landing, and a band that re-counted on remount would report
 * more reach than views - a reach rate over 100%, which is not a number
 * anyone can act on.
 *
 * Storage unavailable (private browsing) counts the reach anyway, matching
 * sendLandingViewOnce: over-counting a private-mode visitor is a smaller lie
 * than dropping them out of the denominator entirely.
 */
export function sendSectionReachOnce(section: LandingSection): void {
  try {
    const key = `vd_sec_${section}_sent`;
    if (window.sessionStorage.getItem(key) === "1") return;
    window.sessionStorage.setItem(key, "1");
  } catch {
    /* storage unavailable - count the reach anyway, see doc comment */
  }
  sendLandingEvent(`sec_${section}`);
}

export function sendLandingEvent(event: LandingEvent): void {
  try {
    void fetch(`${API_BASE}/api/public/landing-event`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ event }),
      keepalive: true,
    }).catch(() => {});
  } catch {
    /* nothing to report — see header comment */
  }
}

/**
 * landing_view, at most once per browser session (sessionStorage guard) — the
 * landing renders on both "/" and /explore and client-side nav can remount it,
 * and a view metric that double-counts remounts would overstate the funnel's
 * top and quietly flatter every conversion rate computed against it.
 */
export function sendLandingViewOnce(): void {
  try {
    const KEY = "vd_landing_view_sent";
    if (window.sessionStorage.getItem(KEY) === "1") return;
    window.sessionStorage.setItem(KEY, "1");
  } catch {
    /* storage unavailable (private browsing) — count the view anyway */
  }
  sendLandingEvent("landing_view");
}
