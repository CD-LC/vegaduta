import type { TourStep } from "../types";
import { edgeEnabled } from "../../../lib/edge/capabilities";

// Connect and run: inbox group, channels, tools, edge, playground, monitor.
export const CONNECT_STEPS: TourStep[] = [
  // --- Inbox & people group header ------------------------------------
  // Anchored on the GROUP HEADER (nav-group-connect, "Inbox & people"), not
  // a row: the rows under it are perm-gated (contacts/inbox need
  // MANAGE_CHANNELS, leads needs MANAGE_LEADS), so which ones a given
  // visitor sees varies. The header only renders when at least one item
  // survives pruning (AppShell drops empty sections), so rule 4 hands the
  // visibility question to the same check that hides the group - no
  // `requires` to keep in sync. The copy names what the group CAN hold and
  // says so, which stays true for any subset of its rows. No `href`: there
  // is no single destination that exists for every holder of either scope.
  {
    id: "connect-group",
    titleKey: "connect",
    title: "See who is talking to your agents",
    body: "Contacts, the conversations inbox and the leads board appear here as your role allows.",
    points: ["New senders wait for your approval first", "Follow live conversations from the inbox"],
    target: "nav-group-connect",
    placement: "right",
    illustration: "channels",
    audiences: ["member", "tenant-admin"],
    simple: true,
  },
  {
    id: "channels",
    titleKey: "channels",
    title: "Put the agent where people already are",
    body: "Connect WhatsApp, Telegram, Instagram, Slack, email and more, then choose which custom agent answers on each channel.",
    // Point2 IS a general property of Channels - do not re-scope it to the
    // phone-number channels again. This was narrowed once (2026-09-12) to
    // "New WhatsApp or Telegram senders need your approval" on the strength of
    // helpPage.channelsPara2, which happens to describe the WhatsApp case; that
    // made it false by implication, telling a Slack or email admin that unknown
    // senders reach their agent unfiltered. The gate is ChannelSession
    // .isPaired() and every inbound controller checks it before dispatch:
    // slack/SlackWebhookController:196, email/EmailInboundPollingJob:199,
    // telegram/TelegramWebhookController:157, meta/MetaWhatsAppWebhookController
    // :240, meta/MetaSocialWebhookController:203, discord/DiscordWebhookController
    // :148. ChannelsPanel.tsx:986 agrees - it renders `awaitingApproval` off a
    // generic entry.mapped check, with no per-channel branch. Help-page prose
    // about one channel is not evidence about the gate; the controllers are.
    points: ["WhatsApp, Telegram and email on the free plan", "A new sender waits for your approval first"],
    target: "nav-channels",
    placement: "right",
    illustration: "channels",
    // /admin/channels is the real route — there is no top-level /channels.
    // This step is tenant-admin only as of v3, which is what finally closes
    // the "Show me" dead end noted here before: the page redirects a
    // non-admin to "/" even though the nav row is deliberately NOT
    // admin-gated, so every member who pressed the button was thrown out of
    // the tour's own destination. The nav inconsistency is still there and
    // is not ours to fix; the tour simply no longer walks anyone into it.
    href: "/admin/channels",
    audiences: ["tenant-admin"],
    simple: true,
  },
  {
    id: "tools",
    titleKey: "tools",
    title: "Integrations let the AI take action",
    body: "Give an agent controlled access to web search, databases, MCP servers and your own scripts while it works.",
    points: ["Enable here, then allow it per agent", "Add your own MCP server or code tool"],
    target: "nav-tools",
    placement: "right",
    illustration: "tools",
    audiences: ["member", "tenant-admin"],
    // Not `simple` — MCP servers and custom code tools are the definition of
    // the mode's "not this person's job" line.
  },
  {
    id: "edge",
    titleKey: "edge",
    // Added 2026-09-19 (first-login audit). The tour never said where
    // on-device AI lives, and it is the one capability a consumer cannot guess
    // from the nav: "Edge AI" means nothing until somebody says "it runs on
    // YOUR device". Sits here because the walk follows the rail top to bottom
    // and Edge AI is the CONNECT group's row after Integrations.
    //
    // Every claim mirrors shipped copy rather than memory: edge.subtitle ("Run
    // AI on your own device — free, private, and instant. Download models,
    // manage what's installed, and choose where your replies run.") and the
    // AppShell note on the /edge row ("the page itself explains when this
    // device can't run models locally"). It promises no speed, no model count
    // and no device list - the page owns those, per device.
    //
    // `inline`: these four strings have no en.json keys yet. See TourStep.inline.
    title: "Browser models run on your device",
    body: "Download a model on the Browser models page and replies can run on this device instead of the hosted model.",
    points: ["Free, and private to this device", "The page tells you if this device cannot run one"],
    inline: true,
    // No AppShell marker yet, so this resolves through targets.ts's fallback
    // (the rail's own <a href="/edge">). Wanted: `"/edge": "nav-edge"` in
    // AppShell's NAV_TOUR_TARGETS, after which the fallback can go.
    target: "nav-edge",
    placement: "right",
    illustration: "edge",
    href: "/edge",
    audiences: ["member", "tenant-admin"],
    // A REAL gate, the first `requires` in the catalogue: the whole edge
    // surface is behind the build-time NEXT_PUBLIC_EDGE_AGENTS flag, and with
    // it off /edge renders "VegaDūta Edge isn't enabled in this build yet".
    // Walking someone to that sentence is the tour pitching a feature the
    // deployment does not carry - rule 2's exact reason to exist.
    requires: () => edgeEnabled(),
    // `simple`: on-device is a consumer feature, not an ADVANCED one - a new
    // B2C signup is SIMPLE by default and is who this step is for.
    simple: true,
  },
  {
    id: "playground",
    titleKey: "playground",
    title: "Rehearse before you go live",
    body: "Chat with your agent in a private window, inspect its answers, then tune and try again before customers see it.",
    points: ["Pick an agent and test real scenarios", "Adjust the instructions, then try again"],
    target: "nav-playground",
    placement: "right",
    illustration: "playground",
    href: "/playground",
    audiences: ["member", "tenant-admin"],
    simple: true,
  },
  {
    id: "monitor",
    titleKey: "monitor",
    // Admin-only in v3, and the copy is now honest as a result: "for admins,
    // live activity and the audit log" was written for a mixed audience
    // because the step had one. /admin/issues, /admin/audit-tail and
    // /admin/audit are adminOnly rows (AppShell's NAV_SECTIONS) and each
    // page redirects a non-admin to "/", so the two thirds of this step that
    // are about observability never applied to a member at all.
    title: "Watch the system, not just the demo",
    body: "Test and Monitor keeps the Playground beside the files your agents produced and, for admins, live activity and the audit log.",
    points: ["Files gathers documents, images and audio", "Admins get live activity and an audit log"],
    target: "nav-monitor",
    placement: "right",
    illustration: "monitor",
    audiences: ["tenant-admin"],
    // Not `simple` — the audit log is the far end of the ADVANCED surface.
  },
];
