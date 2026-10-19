import type { TourStep } from "../types";
import { edgeEnabled } from "../../../lib/edge/capabilities";

// Plans group, admin, console chrome (topbar, chips, search, security, account), help.
export const CHROME_STEPS: TourStep[] = [
  // --- Plans & billing group header -----------------------------------
  // Same group-header anchor trick as connect-group: features and usage are
  // ungated so the group renders for every tenant user (billing adds itself
  // for MANAGE_BILLING holders), and pointing at the header means the step
  // cannot outlive the group it names.
  {
    id: "plans-group",
    titleKey: "plans",
    title: "Know what your plan gives you",
    body: "Plans & billing holds what your tier includes and how much of it you have used.",
    points: ["Features compares what each tier unlocks", "Usage tracks what is left"],
    target: "nav-group-plans",
    placement: "right",
    illustration: "welcome",
    href: "/features",
    audiences: ["member", "tenant-admin"],
    simple: true,
  },
  {
    id: "admin",
    titleKey: "admin",
    // Moved near the end in the launch-story rebuild: the tenant-admin
    // welcome already explains the role, so this step is a pointer to the
    // control room, not an orientation. "nav-admin" is a marker added to
    // AppShell's NAV_TOUR_TARGETS.
    title: "Your control room stays one step away",
    body: "Administration holds channels, keys, guardrails, usage and team access. Configure it when your first launch needs it.",
    // No teammate nudge: on a personal install there is one person, and elsewhere the welcome
    // modal already offers the invite.
    points: ["Model keys, guardrails and usage live there", "Nothing goes live unless you decide it does"],
    inline: true,
    target: "nav-admin",
    placement: "right",
    illustration: "monitor",
    href: "/admin/access",
    audiences: ["tenant-admin"],
    // ADVANCED-only: a SIMPLE admin's welcome already covered "you are the
    // admin", so in SIMPLE this pointer earns its place less than the chrome
    // steps below do.
  },
  // --- console chrome (2026-10: the header-controls pass) --------------
  // The four steps below are why this block exists: the walk used to cover
  // ONLY rail rows, so nothing ever named the top strip (page help, usage,
  // alerts, theme, language, the ? menu), the command palette, or the
  // account block at the rail's foot. All three are universal - the same
  // chrome on every route and for every audience - so they are tagged for
  // all three audiences and kept in SIMPLE. Order matters: topbar first
  // because it names the whole strip, edge-chip second because it is the
  // one topbar control that deserves its own card, then the rail's own
  // chrome bottom-up (jump row, account footer), then help as ever last.
  {
    id: "topbar",
    titleKey: "topbar",
    // Verified against AppShell's <header>, not memory: PageGuideButton
    // ("How this page works"), UsageMeterPill, NotificationBell, the theme
    // picker, EdgeSetupChip, LanguageSwitcher, TourReopenButton (the "?"
    // button), TrustStrip. Every claim below names a control that renders
    // there on every console route.
    title: "The top strip is your control panel",
    body: "Page help, usage, alerts, theme and language live along the top of every screen.",
    points: ["'How this page works' explains the screen you are on", "The ? button replays this tour anytime"],
    target: "topbar",
    placement: "bottom",
    illustration: "tools",
    audiences: ["member", "tenant-admin", "platform-admin"],
    simple: true,
  },
  {
    id: "edge-chip",
    titleKey: "edgeChip",
    // The topbar's on-device settings control - the header half of the
    // "edge settings" the walk was asked to explain (the rail row keeps its
    // own step above). Claims verified against EdgeSetupChip: the chip shows
    // what is running locally, the popover picks a model or switches back to
    // hosted, and it draws a progress bar while a model downloads.
    title: "On-device AI is one click away",
    body: "This chip shows what is running on this device and opens its settings.",
    points: ["Pick a model, or switch back to hosted", "It shows progress while a model downloads"],
    target: "edge-chip",
    placement: "bottom",
    illustration: "edge",
    href: "/edge",
    audiences: ["member", "tenant-admin"],
    // Same gate as the "edge" step: with NEXT_PUBLIC_EDGE_AGENTS off the
    // chip renders null, so this doubles rule 4 rather than carrying a new
    // condition to keep in sync.
    requires: () => edgeEnabled(),
    simple: true,
  },
  {
    id: "search",
    titleKey: "search",
    // Anchored on "nav-jump", the rail's "Jump to…" row (NAV_TOUR_TARGETS
    // does not cover it - the marker sits on the button itself, in both the
    // rail and the mobile-topbar variant). Ctrl+K/Cmd+K verified against
    // AppShell's own keydown handler and shortcutLabel.
    title: "Jump straight to any screen",
    body: "The 'Jump to…' row opens a palette listing every screen in the console.",
    points: ["Ctrl+K or Cmd+K opens it from anywhere", "Type a name to filter the list"],
    target: "nav-jump",
    placement: "right",
    illustration: "knowledge",
    audiences: ["member", "tenant-admin", "platform-admin"],
    simple: true,
  },
  {
    // Added 2026-10-09 (direct instruction: tell consumers to turn on 2FA from
    // the top header). Anchored on TwoFactorButton's data-tour="security".
    id: "security",
    titleKey: "security",
    title: "Turn on two-factor authentication",
    body: "The shield in the top bar opens two-factor setup: after your password you also enter a 6-digit code from an authenticator app.",
    points: [
      "Takes about a minute with Google or Microsoft Authenticator",
      "Stops anyone who learns your password from signing in",
    ],
    target: "security",
    placement: "bottom",
    illustration: "welcome",
    // Customers only: the platform-admin walk is capped at 6 short steps about
    // operating the platform (staff 2FA is enforced in Keycloak's master realm).
    audiences: ["member", "tenant-admin"],
    simple: true,
  },
  {
    id: "account",
    titleKey: "account",
    // The rail footer: avatar (opens the upload/crop modal), name (Keycloak
    // manage-profile, opens in a new tab), power button (sign out). All
    // verified against AppShell's footer block, which carries the marker.
    title: "Your account sits at the rail's foot",
    body: "Your picture, profile settings and sign-out live at the bottom of the rail.",
    points: ["Click your picture to update it", "The power button signs you out"],
    target: "account",
    placement: "right",
    illustration: "agents",
    audiences: ["member", "tenant-admin", "platform-admin"],
    simple: true,
  },
  {
    id: "help",
    titleKey: "help",
    title: "The rest is yours to explore",
    body: "Open the Guide for worked examples, or use the ? menu to walk the court and revisit any part of the platform.",
    points: ["Written walkthroughs for every section", "Replay this launch path anytime"],
    target: "help-link",
    placement: "right",
    illustration: "help",
    // Every audience, and selectSteps' floor pins it in place: /help is
    // ungated (NAV_SECTIONS: adminOnly false) and "replay the tour" only has
    // a home if the visitor was shown where it lives.
    audiences: ["member", "tenant-admin", "platform-admin"],
    simple: true,
  },
];
