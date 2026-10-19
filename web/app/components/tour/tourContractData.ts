// The tour's contract, transcribed as plain data (see ./tourTestSupport for the
// why). No runtime imports: the suites compare the real catalogue, en.json and
// AppShell's markers against these tables.
import type { TourStep } from "../TourProvider";
import type { TourAudience } from "./audience";

const SUFFIXES = ["Title", "Body", "Point1", "Point2"] as const;

/** The full CATALOGUE - the blueprint, not any one visitor's walk. Order is
 * tour order: selectSteps returns a stable subsequence, so every audience's
 * sequence is this sequence with entries removed. That is why the two
 * platform-admin steps sit at index 1-2 and not at the end; a platform-admin's
 * three-step walk has to OPEN with what this console is, not close with it. */
const EXPECTED: Array<{
  id: string;
  target: string | null;
  placement: TourStep["placement"];
  audiences: TourAudience[];
  simple: boolean;
}> = [
  // Step 0 is "start", NOT "welcome". The rebuild shipped it as a second copy
  // of the welcome hero - same illustration, same two strings the visitor had
  // just dismissed - so the first thing the step counter showed was a screen
  // they had already answered. It now carries the price/recipe copy (see the
  // step-0 facts test below) and its own drawing.
  {
    id: "start",
    target: null,
    placement: "center",
    audiences: ["member", "tenant-admin"],
    simple: true,
  },
  // Build Studio is the signed-in home (2026-09-26): the launch story opens
  // on it - promise -> agent -> workflow -> app/channel.
  {
    id: "build",
    target: "nav-build",
    placement: "right",
    audiences: ["member", "tenant-admin"],
    simple: true,
  },
  // The live dashboard ("cognition"): member-only because the /home row is
  // adminHidden, so admins never have the marker to reach anyway.
  {
    id: "home",
    target: "nav-home",
    placement: "right",
    audiences: ["member"],
    simple: true,
  },
  {
    id: "platform-console",
    target: null,
    placement: "center",
    audiences: ["platform-admin"],
    simple: true,
  },
  // Anchored on the rail as a whole rather than a row: it is the one target the
  // provider's own arming effect proves exists before a tour can start, so the
  // reachability rule can never drop this step out from under its own audience.
  {
    id: "platform-workspace",
    target: "nav-rail",
    placement: "right",
    audiences: ["platform-admin"],
    simple: true,
  },
  {
    id: "agents",
    target: "nav-agents",
    placement: "right",
    audiences: ["member", "tenant-admin"],
    simple: true,
  },
  {
    id: "workflows",
    target: "nav-workflows",
    placement: "right",
    audiences: ["member", "tenant-admin"],
    simple: true,
  },
  {
    id: "knowledge",
    target: "nav-knowledge",
    placement: "right",
    audiences: ["member", "tenant-admin"],
    simple: true,
  },
  // Member-only, and the one step that exists BECAUSE of the audience split: a
  // member invited into a workspace typically owns nothing on their first
  // login, and the admin walk never mentioned where their work actually is.
  {
    id: "shared-with-me",
    target: "nav-shared",
    placement: "right",
    audiences: ["member"],
    simple: true,
  },
  // The "Inbox & people" group header - the rows under it are perm-gated, so
  // the step anchors on the header that only renders when one survives.
  {
    id: "connect-group",
    target: "nav-group-connect",
    placement: "right",
    audiences: ["member", "tenant-admin"],
    simple: true,
  },
  // Admin-only as of v3. /admin/channels redirects a non-admin to "/" while its
  // nav row stays ungated, so every member who pressed this step's own
  // "Show me" was thrown out of the tour's destination.
  {
    id: "channels",
    target: "nav-channels",
    placement: "right",
    audiences: ["tenant-admin"],
    simple: true,
  },
  {
    id: "tools",
    target: "nav-tools",
    placement: "right",
    audiences: ["member", "tenant-admin"],
    simple: false,
  },
  // 2026-09-19: where on-device AI lives. `simple`, because a new B2C signup is
  // SIMPLE by default and is who this step is for; gated on the edge build flag.
  {
    id: "edge",
    target: "nav-edge",
    placement: "right",
    audiences: ["member", "tenant-admin"],
    simple: true,
  },
  {
    id: "playground",
    target: "nav-playground",
    placement: "right",
    audiences: ["member", "tenant-admin"],
    simple: true,
  },
  // Admin-only: /admin/issues, /admin/audit-tail and /admin/audit are adminOnly
  // rows and each page redirects a non-admin to "/".
  {
    id: "monitor",
    target: "nav-monitor",
    placement: "right",
    audiences: ["tenant-admin"],
    simple: false,
  },
  // The "Plans & billing" group header - features/usage are ungated, so the
  // header renders for every tenant user.
  {
    id: "plans-group",
    target: "nav-group-plans",
    placement: "right",
    audiences: ["member", "tenant-admin"],
    simple: true,
  },
  // Moved near the end and ADVANCED-only: the tenant-admin welcome already
  // explains the role, so this step is a pointer to the control room.
  {
    id: "admin",
    target: "nav-admin",
    placement: "right",
    audiences: ["tenant-admin"],
    simple: false,
  },
  // The header-controls pass: the top strip (page help, usage, alerts, theme,
  // language, the ? menu), the on-device chip, the Jump-to palette row and
  // the account footer. Universal chrome, so all three audiences and SIMPLE.
  {
    id: "topbar",
    target: "topbar",
    placement: "bottom",
    audiences: ["member", "tenant-admin", "platform-admin"],
    simple: true,
  },
  {
    id: "edge-chip",
    target: "edge-chip",
    placement: "bottom",
    audiences: ["member", "tenant-admin"],
    simple: true,
  },
  {
    id: "search",
    target: "nav-jump",
    placement: "right",
    audiences: ["member", "tenant-admin", "platform-admin"],
    simple: true,
  },
  {
    id: "security",
    target: "security",
    placement: "bottom",
    audiences: ["member", "tenant-admin"],
    simple: true,
  },
  {
    id: "account",
    target: "account",
    placement: "right",
    audiences: ["member", "tenant-admin", "platform-admin"],
    simple: true,
  },
  {
    id: "help",
    target: "help-link",
    placement: "right",
    audiences: ["member", "tenant-admin", "platform-admin"],
    simple: true,
  },
];

/** Every drawing TourIllustrations knows about. A name the component does not
 * know silently falls back to "welcome", so every step would look like the
 * hero. */
const ILLUSTRATION_NAMES = [
  "recipe",
  "welcome",
  "agents",
  "workflows",
  "knowledge",
  "channels",
  "tools",
  "playground",
  "monitor",
  "shared-with-me",
  "help",
  "edge",
  "platform",
  "complete",
];

/** The two drawings round 3 added, and the step each was drawn FOR. Asserted
 * separately from the structural table because a placeholder here is not a
 * crash and not a missing key - it is a step illustrated with a picture of a
 * different feature, which only a human or this line will ever notice. */
const AUDIENCE_ILLUSTRATIONS: Record<string, string> = {
  "shared-with-me": "shared-with-me",
  "platform-console": "platform",
  // Round 4: a laptop with the model arriving into it. Borrowing `tools` or
  // `playground` would have been the same mistake a third time.
  edge: "edge",
};

// Chrome keys the overlay and its two modals need beyond the per-step ones.
const CHROME_KEYS = [
  "welcomeTitle",
  "welcomeBody",
  // The hero's pitch ("you build an AI agent, connect it to the channels your
  // customers already use") is false for a platform-admin, who is not building
  // one; this is the variant the overlay swaps in for them.
  "welcomeBodyPlatform",
  "welcomePrimary",
  "welcomeSkip",
  "skip",
  "back",
  "next",
  "finish",
  "progress",
  // The completion copy is per-AUDIENCE (not per-plan): what you just saw
  // depends on which walk you were given. `completedTitle`/`completedBody` were
  // retired with the split and are asserted absent below.
  "completedAdminTitle",
  "completedAdminBody",
  "completedMemberTitle",
  "completedMemberBody",
  "completedPlatformTitle",
  "completedPlatformBody",
  "completedCta",
  "completedSecondary",
  "stepCounter",
  "duration",
  // A three-step platform walk promised "About 2 minutes" until this key
  // existed - the same class of countable lie as step 0's old "Eight stops".
  "durationShort",
  "showMe",
  "closeLabel",
  "dialogLabel",
  "jumpToStep",
  "replayHint",
  // The topbar "?" button. It used to render onboardingTour.replayTitle
  // ("Replay the welcome briefing"), naming a component that no longer
  // exists; these two are its replacement and live in this namespace now.
  "reopenTitle",
  "reopenLabel",
];

/** Keys a rebuild retired. Asserted ABSENT, not merely unused: a stale key
 * left in en.json is 30 locales' worth of translator time spent on copy
 * nothing renders, and the next person to grep for "welcomePoint" finds a
 * string that looks live. The welcome HERO renders welcomeTitle/welcomeBody
 * and no bullets at all.
 *
 * Round 3 added six more. The un-varied `start*` four had to go rather than
 * stay as a fallback: startPoint2 read "Eight short stops", which is false for
 * every audience except a tenant-admin in ADVANCED mode, so leaving it as the
 * safety net would have made the safety net the lie. `completedTitle`/`Body`
 * went the same way. Both chains now end on an explicit DEFAULT VARIANT
 * instead, which the variant-chain test below proves always resolves. */
const REMOVED_KEYS = [
  "welcomePoint1",
  "welcomePoint2",
  "startTitle",
  "startBody",
  "startPoint1",
  "startPoint2",
  "completedTitle",
  "completedBody",
];

/** Every data-tour marker AppShell paints, so the provider's reachability
 * check has something real to find. Before round 3 these tests ran against a
 * tree with no markers at all, which is now indistinguishable from "this
 * visitor can reach nothing" - rule 4 would drop every anchored step and
 * selectSteps would correctly return [] for a tour of one. */
const SHELL_TARGETS = [
  "nav-rail",
  "nav-build",
  "nav-home",
  "nav-admin",
  "nav-agents",
  "nav-workflows",
  "nav-knowledge",
  "nav-shared",
  "nav-group-connect",
  "nav-channels",
  "nav-tools",
  // Painted by NAV_TOUR_TARGETS now; the targets.ts fallback still covers a
  // shell that predates the marker (see the round-4 suite).
  "nav-edge",
  "nav-playground",
  "nav-monitor",
  "nav-group-plans",
  // Console chrome, added in the header-controls pass: the topbar strip, the
  // on-device chip, the rail's "Jump to…" row and the account footer.
  "topbar",
  "edge-chip",
  "nav-jump",
  "security",
  "account",
  "help-link",
];

const DEFAULT_SETTINGS: Record<string, unknown> = {
  plan: "UNLIMITED",
  accountType: "B2B",
  experienceMode: "ADVANCED",
  whatsappAllowed: true,
  byokAllowed: true,
  liveVisionAllowed: true,
};

/** The settings a brand-new self-signup actually has: tenant-admin (granted at
 * provisioning), B2C/SIMPLE (TenantProvisioningService's default), and a plan
 * that reads as PAID because of the complimentary grant. */
const NEW_SIGNUP: Record<string, unknown> = {
  plan: "UNLIMITED",
  accountType: "B2C",
  experienceMode: "SIMPLE",
  whatsappAllowed: true,
  byokAllowed: true,
  liveVisionAllowed: false,
};

export {
  AUDIENCE_ILLUSTRATIONS,
  CHROME_KEYS,
  DEFAULT_SETTINGS,
  EXPECTED,
  ILLUSTRATION_NAMES,
  NEW_SIGNUP,
  REMOVED_KEYS,
  SHELL_TARGETS,
  SUFFIXES,
};
