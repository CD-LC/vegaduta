import type { TourStep } from "../types";
import { ONBOARDING_STEPS } from "./onboarding";
import { WALK_STEPS } from "./walk";
import { CONNECT_STEPS } from "./connect";
import { CHROME_STEPS } from "./chrome";


// Product tour — first-run guided walkthrough for the console.
//
// Scope note (v1, superseded): the tour shipped with 3 steps that were each
// reliably targetable at the time (the real create-agent control, the nav
// rail, and the Help nav link), on the reasoning that the mechanism below
// (steps, spotlight targeting via data-tour attributes, cross-route
// persistence, progress tracking, completion, replay) is fully generic and
// extending it later is just adding entries to TOUR_STEPS plus a matching
// data-tour="<id>" attribute on the new target element.
//
// Scope note (v2, 2026-09): that extension is what this is. Three text-only
// steps told a new visitor almost nothing about what the console IS — they
// spotlit a button, a menu and a link. The tour became 9 illustrated steps
// that walk the actual nav rail top to bottom (agents -> workflows ->
// knowledge -> channels -> tools -> playground -> monitor -> help) behind a
// centered opener, each with two concrete bullets. The v1 prediction held:
// the only mechanism changes needed there were making `target` nullable (the
// start step is a centered hero with nothing to spotlight), adding the
// "center" placement, and carrying a per-step illustration and optional deep
// link.
//
// Scope note (v3, 2026-09-12 — AUDIENCE): those 9 steps were identical for
// every visitor. A member who cannot open /admin/channels was walked through
// Channels (and its "Show me" bounced them off the page — /admin/channels
// redirects a non-admin to "/"); a platform-admin, who may not even HAVE a
// tenant, was walked through building an agent in a workspace that is not
// theirs. The catalogue below is now TAGGED rather than ordered-and-shipped:
// every step declares which audiences it is for, whether it survives SIMPLE
// experience mode, and (where a real entitlement gates it) a `requires`
// predicate. TourProvider resolves the visitor's audience and entitlements
// once, when the tour starts, and runs the catalogue through selectSteps()
// in ./tour/audience.ts. TOUR_STEPS stays exported as the full catalogue —
// it is the blueprint, not the thing the visitor sees. What the visitor sees
// is useTour().steps / .totalSteps / .currentStep, all of which operate on
// the SELECTED list.
//
// Scope note (v4, 2026-09-19 — FIRST LOGIN AS A CONSUMER): founder feedback
// was that the tour "is not explaining to consumers completely on landing,
// first login". Audited end to end as that person - who is NOT the "member"
// audience: self-signup grants tenant-admin, provisions the tenant B2C/SIMPLE,
// and attaches a complimentary grant, so they are a tenant-admin in SIMPLE mode
// whose plan READS AS PAID, landing on /admin (app/page.tsx redirects an admin
// off "/"). The mechanism held up: it arms 900ms after the nav rail mounts when
// vd_tour_status is unset, on any console route; every anchor is a nav-rail
// row, present on every route; AppShell opens the drawer below `lg` and the
// card docks as a bottom sheet; a slow or missing anchor is polled for ~6s and
// then the card centres over a scrim. Four things were wrong, all fixed here
// and in ./tour/*:
//   1. The "hosted model, no API key" fact was keyed off the plan name, so the
//      grant-holding new signup got the PAID wording, which never says it. Now
//      asked of the server (hostedModelAvailable) - see the effect below.
//   2. Nothing said where on-device AI lives -> the "edge" step (the first
//      `inline` step and the first with a real `requires`).
//   3. "Take the tour" / replay that selected nothing was a silent no-op ->
//      resolveRequestedSteps().
//   4. Nobody who skipped was told how to get back, and the "?" button offered
//      only the walk they had just left -> reopenHintVisible, and
//      TourReopenButton's "What should I do next?" panel.
// The consumer's walk was nine stops or fewer until the header-controls pass
// (founder request, 2026-10): the rail groups nobody had named (Inbox &
// people, Plans & billing), the /home cognition dashboard, and the chrome
// every visitor sees but nothing explained - the topbar strip, the on-device
// chip, the Jump-to palette and the account footer. tour.test.tsx now holds
// the consumer walk at fifteen or fewer.
//
// Start-step correction (2026-09, completeness review): v2 shipped its first
// step as a SECOND copy of the welcome modal — same illustration, same two
// strings the visitor had just dismissed — so the first thing a step counter
// showed was a screen they had already answered. It is now the "start" step,
// which does three jobs nothing else in the tour did: it states that starting
// is free and a hosted model is included so no API key is needed (v1 said
// this in its opening line and the v2 rebuild dropped the fact entirely), it
// states the build recipe UP FRONT instead of only on the completion modal
// most people dismiss, and it sets an honest expectation for the walk. The
// welcome HERO still renders tour.welcomeTitle / tour.welcomeBody; the start
// step renders the separate startTitle/startBody/startPoint1/startPoint2 keys.
//
// Where data-tour="create-agent" lives (funnel audit, 2026-09): it used to
// sit on the /agents nav-rail Link, so the agents step spotlit a menu item and
// told the person to "click through" — while the welcome modal's own primary
// already navigates to /agents. It now marks the real create control:
// AgentWorkbench's toolbar (the Save-draft button while a draft is open,
// otherwise the agent switcher whose first row is "New agent") and the
// first-run "Create your first agent" CTA on /admin and /. TourOverlay uses
// the FIRST match on the current route, and those never share a route.
// No step targets it any more (the v2 walk is nav-rail-shaped), but the
// attribute stays where it is — the welcome's primary still sends people to
// /agents, and a future "create" step should re-use that marker rather than
// re-introduce the menu-item spotlight that audit removed.

// The catalogue. The copy rules that produced this wording, so a later edit
// does not quietly undo them: plain second person, one sentence per body
// (~22 words max), bullet fragments (~10 words max), and NEVER a capability
// that is not real — every claim below was checked against the page it
// describes or against en.json's own shipped product copy, not written from
// memory. In particular WhatsApp, Telegram and email are FREE on every plan
// as of 2026-09 (landing.ledger_channels_body,
// channelsPanel.whatsappGatePitch); do not re-import the old "connect a paid
// channel" framing that the legacy briefing still carries.
//
// ORDER IS THE TOUR ORDER. selectSteps returns a stable subsequence of this
// array, so the sequence any audience gets is this sequence with entries
// removed. That is why the two platform-admin steps sit at index 1-2 rather
// than at the end: a platform-admin's three-step walk has to open with what
// this console is, not close with it.
export const TOUR_STEPS: TourStep[] = [...ONBOARDING_STEPS, ...WALK_STEPS, ...CONNECT_STEPS, ...CHROME_STEPS];
