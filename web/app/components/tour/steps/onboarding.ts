import type { TourStep } from "../types";
import { tenantOnly } from "./tenantOnly";

// Opening steps: start, build, home, and the two platform-admin steps.
export const ONBOARDING_STEPS: TourStep[] = [
  // tenantOnly(): stem "start" + the "Platform" variant token is
  // `startPlatformTitle`, which is the platform-workspace step's own copy. See
  // the fence's comment above before touching `audiences` here.
  tenantOnly({
    id: "start",
    titleKey: "start",
    // The tour's spine. Three facts live ONLY here:
    //
    // 1. Price and prerequisites. Verified against shipped copy, not memory:
    //    signup.subtitle ("a hosted model included — no API key needed"),
    //    adminOverview.setupStepAgentHint and dashboard.firstRunBody (same
    //    sentence), agentWorkbench.platformLlmFreeTierNote (Free-tier agents
    //    run on the platform-hosted LLM), signup.completeSuccessBox and
    //    landingAxon.closeSubtitle (no card required). All still true 2026-09.
    // 2. The build recipe, up front. It used to appear only in completedBody,
    //    i.e. on the modal most people dismiss, so the one statement of
    //    sequence in the whole tour was also the least-read line in it.
    // 3. An honest expectation. It said "Eight short stops" until v3 and that
    //    became false the moment the walk stopped being one fixed list: a
    //    member in SIMPLE mode gets five stops after this one, a member in
    //    ADVANCED seven, a tenant-admin eight. A promise the visitor can
    //    count has to be a promise that survives selection, so it no longer
    //    names a number. (An earlier version claimed "one per screen", which
    //    was false for a different reason — this step is not a screen,
    //    monitor is a section header, help is a link.)
    //
    // Not shown to a platform-admin: they are not provisioning a workspace,
    // and the plan/price facts describe a tenant they may not have.
    title: "Start with the agent - then fly higher",
    body: "Customize how your agent thinks and acts, connect workflows, then use that intelligence to power apps and customer experiences.",
    points: ["Hosted AI included; no API key or card to start", "One focused launch path, no ceiling on the idea"],
    target: null,
    placement: "center",
    illustration: "recipe",
    audiences: ["member", "tenant-admin"],
    simple: true,
  }),
  // --- Build Studio (2026-09-26, Build Studio is the home) ---------------
  // "nav-build" is a marker added to AppShell's NAV_TOUR_TARGETS.
  {
    id: "build",
    titleKey: "build",
    title: "Build the intelligence first",
    body: "Describe the agent's job in plain language. Review its instructions, model, knowledge, tools and guardrails before creating it.",
    points: ["Start from a sentence or a proven recipe", "Nothing is created until you approve the blueprint"],
    target: "nav-build",
    placement: "right",
    illustration: "recipe",
    href: "/build",
    audiences: ["member", "tenant-admin"],
    simple: true,
  },
  // --- Home / the cognition panel -------------------------------------
  // Member-only by construction rather than by tag alone: "/home" is
  // `adminHidden` in AppShell's NAV_SECTIONS (admins are bounced off it to
  // /admin), so the nav-home marker simply never exists in an admin's DOM
  // and rule 4 drops the step. This is the surface the founder calls
  // "cognition" - DashboardHome's CognitionCore, "each dot is one of your
  // agents" (cognitionCoreSub). It is NOT in the topbar; the step points at
  // the rail row that opens it. Kept `simple`: the live picture of the
  // system breathing is exactly what a brand-new member should see first.
  {
    id: "home",
    titleKey: "home",
    title: "Home is your live dashboard",
    body: "This screen watches your workspace as it runs - every dot on the panel is one of your agents.",
    points: ["Calls and conversations stream in live", "Open it anytime for the big picture"],
    target: "nav-home",
    placement: "right",
    illustration: "monitor",
    href: "/home",
    audiences: ["member"],
    simple: true,
  },
  // --- platform-admin (2 steps + help) --------------------------------
  //
  // Why this is two honest sentences and not a walkthrough: web/ has NO
  // platform-admin surface to walk. Verified, not assumed — `isPlatformAdmin`
  // is read in exactly one file in this app (AuthProvider.tsx:614, and only
  // to decide the tenant-less onboarding gate at :622); nothing else branches
  // on it. The cross-tenant console moved out to the standalone web-admin/
  // app and api.ts:3370 records the removal in as many words: "nothing in
  // web/ calls PlatformController's endpoints anymore, so the wrappers were
  // removed here rather than kept as dead code". Inventing a platform
  // walkthrough here would have meant describing screens that live in a
  // different application.
  {
    id: "platform-console",
    // Stem is "platform", not the id. The id names the STEP; the stem names
    // the en.json key set, and the shipped set is platformTitle / platformBody
    // / platformPoint1 / platformPoint2. A "platformConsole" stem was specified
    // during the round but never written, so this card rendered the literal
    // string "tour.platformConsoleTitle" on screen, in every locale. Do not
    // rename the stem back without writing the keys in the same edit -
    // en.json is the side that renders; these four strings are only a mirror.
    titleKey: "platform",
    title: "Platform tools are a separate console",
    body: "Tenants, plans, the hosted model pool, spend and the platform audit log live in the admin console, not here.",
    points: ["This app is the tenant workspace", "You may have no tenant of your own"],
    target: null,
    placement: "center",
    // Drawn FOR this step: tenant tiles on the left, the admin console
    // cropped off the right edge, because the crop is the message. It
    // previously carried "monitor", which depicts the Playground, files and
    // the audit log - a picture of a different feature, on the card whose
    // whole point is that the platform console is somewhere else.
    illustration: "platform",
    audiences: ["platform-admin"],
    simple: true,
  },
  {
    id: "platform-workspace",
    // Stem is "startPlatform" - the platform-admin variant of step 0's key
    // set, which is where this copy was actually written and shipped. It
    // reads as step 0 FOR a platform-admin ("you are in the tenant console"),
    // which is exactly what this step is. The start step itself stays
    // member/tenant-admin: its copy is about provisioning a workspace, and
    // its price claims describe a tenant a platform-admin may not have. A
    // "platformWorkspace" stem was specified during the round but never
    // written to en.json, so this card rendered a raw dotted path too.
    titleKey: "startPlatform",
    // Anchored on nav-rail rather than any individual row: the point is the
    // rail as a whole, and it is the one target guaranteed to exist — the
    // provider's own arming effect waits for it before the tour can start,
    // so the reachability rule can never drop this step out from under its
    // own audience.
    title: "You are in the tenant console",
    body: "This is the workspace your tenants use to build and run agents. Platform administration is somewhere else.",
    points: ["Everything here is scoped to one tenant", "A short walk; skip or replay anytime"],
    target: "nav-rail",
    placement: "right",
    illustration: "recipe",
    audiences: ["platform-admin"],
    simple: true,
  },
];
