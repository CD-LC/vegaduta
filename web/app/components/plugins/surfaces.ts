// Every place VegaDūta runs outside the web console - plugins, apps and
// wearables - with its REAL availability. The downloadable gallery above this
// on /plugins comes from core's plugin catalog and only ever held three
// entries; the Eclipse plugin, the Android app, the Wear OS companion and the
// desktop agent existed in the repo and were mentioned nowhere.
//
// NO FICTION. Each status below is what the repo records on 2026-09-19:
//   - vscode / jetbrains / chrome: served by the catalog; NONE is on its public
//     store (clients/PUBLISHING.md status table). PlatformLedger's do-not-claim
//     list forbids presenting the Chrome extension "as on the Web Store".
//   - Android Studio is NOT named: PUBLISHING.md section 7 says not to claim it
//     until `verifyPlugin` has been run against it, and it has not been.
//   - edge: the same package as Chrome, never submitted to Edge Add-ons.
//   - eclipse: built, not in the catalog, never launched in a released
//     Eclipse (clients/README.md) and it has no coding agent.
//   - android: a debug build downloaded from Console -> Channels -> Mobile
//     (channels.mobileSdkDownloadHint); not on Google Play.
//   - wear: mobile-app/android/wear, approvals on the watch; no Play listing.
//   - ios: never compiled (mobile-app/ios/README.md). Listed so nobody has to
//     ask, and labelled as not available.
//   - desktop: CLI daemon, pairing + run loop implemented, distribution open
//     (desktop-agent/README.md "Status").
//   - mcp: core/mcpserver, five tools (PLATFORM_FACTS.mcpServerTools).
// "available" means: a person can get it today from the place named in `where`.
//
// Pure data - unit-tested in surfaces.test.ts.

import { PLATFORM_FACTS } from "../../lib/platform-facts";

export type SurfaceStatus = "available" | "beta" | "preview" | "in-development";

export type SurfaceGroup = "editors" | "browsers" | "phones-wearables" | "computers-tools";

export type Surface = {
  id: string;
  group: SurfaceGroup;
  name: string;
  /** What it does, one or two plain sentences. */
  does: string;
  status: SurfaceStatus;
  /** How to get it today - or the honest reason you cannot yet. */
  where: string;
  /** In-site link for `where`, when there is one. */
  href?: string;
};

export const STATUS_LABEL: Record<SurfaceStatus, string> = {
  available: "Available",
  beta: "Beta",
  preview: "Preview",
  "in-development": "In development",
};

export const GROUP_LABEL: Record<SurfaceGroup, string> = {
  editors: "Code editors",
  browsers: "Browsers",
  "phones-wearables": "Phones and wearables",
  "computers-tools": "Your computer and other AI tools",
};

export const GROUP_ORDER: SurfaceGroup[] = ["editors", "browsers", "phones-wearables", "computers-tools"];

export const SURFACES: Surface[] = [
  {
    id: "vscode",
    group: "editors",
    name: "VS Code",
    does: "Chat with your agents, run workflows, explain, fix and refactor selected code, and a coding agent that asks before every edit and command.",
    status: "available",
    where: "Download on this page and install it from the file. It is not on the VS Code Marketplace yet.",
  },
  {
    id: "jetbrains",
    group: "editors",
    name: "JetBrains IDEs",
    does: "The same chat, workflow runs and code actions in IntelliJ IDEA, PyCharm, WebStorm, GoLand and the other IntelliJ-based IDEs, with inline completions.",
    status: "available",
    where: "Download on this page and install it from disk. It is not on the JetBrains Marketplace yet.",
  },
  {
    id: "eclipse",
    group: "editors",
    name: "Eclipse",
    does: "Chat plus explain, fix, refactor, tests, docs and review commands on your code. It does not have the coding agent.",
    status: "preview",
    where: "Built, but not offered for download yet.",
  },
  {
    id: "chrome",
    group: "browsers",
    name: "Chrome",
    does: "A side panel that follows you across the web: ask about a page or a selection, run a workflow, and a browser agent that asks before it clicks or types.",
    status: "available",
    where: "Download on this page. It is not on the Chrome Web Store yet.",
  },
  {
    id: "edge",
    group: "browsers",
    name: "Microsoft Edge",
    does: "The Chrome extension, packaged for Edge.",
    status: "preview",
    where: "Not submitted to Edge Add-ons yet.",
  },
  {
    id: "android",
    group: "phones-wearables",
    name: "Android app",
    does: "Chat, quick tasks, workflows and approvals on your phone, with on-device AI on capable devices.",
    status: "beta",
    where: "A beta build you download directly: Console, Channels, Mobile. It is not on Google Play yet.",
    href: "/admin/channels?channel=mobile-sdk",
  },
  {
    id: "wear-os",
    group: "phones-wearables",
    name: "Wear OS watch",
    does: "A companion to the Android app: approve or decline what an agent wants to do, from your wrist.",
    status: "preview",
    where: "Built alongside the Android app. It is not on Google Play yet.",
  },
  {
    id: "ios",
    group: "phones-wearables",
    name: "iPhone app",
    does: "The same mobile experience for iPhone.",
    status: "in-development",
    where: "No build is available yet.",
  },
  {
    id: "desktop-agent",
    group: "computers-tools",
    name: "Desktop agent",
    does: "A small command-line program that pairs your own computer with your workspace and runs a local model of your choice for your workflows.",
    status: "preview",
    where: "It works from source today. A packaged download is not ready yet.",
  },
  {
    id: "mcp-server",
    group: "computers-tools",
    name: "MCP server",
    does: `Use your agents, workflows and knowledge base from any MCP client, through ${PLATFORM_FACTS.mcpServerTools} tools and a scoped API key.`,
    status: "available",
    where: "Part of every workspace. Read how it works.",
    href: "/ai/mcp",
  },
];

export function surfacesByGroup(
  surfaces: Surface[] = SURFACES,
): Array<{ group: SurfaceGroup; label: string; items: Surface[] }> {
  return GROUP_ORDER.map((group) => ({
    group,
    label: GROUP_LABEL[group],
    items: surfaces.filter((s) => s.group === group),
  })).filter((g) => g.items.length > 0);
}

/**
 * One source of truth for what can be downloaded (SE-46). vscode, jetbrains and
 * chrome are "available" in SURFACES only on the premise that the plugin catalog
 * holds a build; the gallery above this list reads that same catalog. When the
 * catalog was fetched and an entry has no published build, the gallery says
 * "Coming soon", so this list must not say "Available" for it. A null catalog
 * (backend unreachable) leaves the static statuses untouched.
 */
export function applyCatalog(
  surfaces: Surface[],
  catalog: { plugins: Array<{ id: string; artifact: unknown | null }> } | null,
): Surface[] {
  if (!catalog) return surfaces;
  const unpublished = new Set(catalog.plugins.filter((p) => !p.artifact).map((p) => p.id));
  return surfaces.map((s) =>
    s.status === "available" && unpublished.has(s.id)
      ? { ...s, status: "preview" as const, where: "Built, but no build is published to the download catalog yet." }
      : s,
  );
}
