// Per-page "how this page works" guides: what the page is for, the MINIMUM a
// person has to do on it, and the one obvious next move. Rendered by
// PageGuideButton in the AppShell topbar, so it reaches every signed-in page -
// including the full-viewport workflow canvas, where an inline card has no
// room to live.
//
// The product tour (TourProvider) walks the rail once. This answers a
// different question: "I am on THIS page now - what do I actually do?"
//
// WHY PLAIN STRINGS AND NOT t(): same reasoning as tour/consumerGuide.ts. A key
// missing from en.json renders its raw dotted path, so these are the English
// fallback of a key-first lookup (`pageGuide.<id>.*` wins the day it exists).
//
// NO FICTION. Each step names a control that exists on that page:
//   - /agents: validate() in AgentWorkbench requires only name + instructions,
//     and instructions ship with a default, so a NAME is the true minimum. The
//     create button's label is agentWorkbench.saveDraft = "Save draft".
//   - /workflows: WorkflowDesignerPanel#createWorkflow requires only a name;
//     the graph defaults to starterGraph() (one Trigger node).
//   - canvas: "Build with AI", "Save Draft", "Validate", "Run now", "Activate"
//     are workflowCanvas.* labels; draftAlertText backs the Activate sentence.
//   - channels on the Free plan: migration V225 (WhatsApp, Telegram, Email).
// Steps deliberately describe actions, not counts or limits, so a plan change
// cannot make them false.
//
// Pure: no React, no DOM - unit-tested in pageGuides.test.ts.

import { ADMIN_GUIDE_ENTRIES } from "./guidesAdmin";
import { TOOLS_GUIDE_ENTRIES } from "./guidesTools";
import { WORKSPACE_GUIDE_ENTRIES } from "./guidesWorkspace";
import type { PageGuide, PageGuideEntry } from "./pageGuideTypes";

export type { PageGuide, PageGuideLink } from "./pageGuideTypes";

// Order matters: first match wins, so specific routes sit above their parents.
const ENTRIES: PageGuideEntry[] = [...WORKSPACE_GUIDE_ENTRIES, ...TOOLS_GUIDE_ENTRIES, ...ADMIN_GUIDE_ENTRIES];

/** The guide for a browser path, or null when the page has none. */
export function guideForPath(
  pathname: string | null | undefined,
  opts: { isAdmin?: boolean; edgeAvailable?: boolean } = {}
): PageGuide | null {
  if (typeof pathname !== "string" || pathname === "") return null;
  const path = pathname.length > 1 ? pathname.replace(/\/+$/, "") : pathname;
  const hit = ENTRIES.find((e) => e.match.test(path));
  if (!hit) return null;
  if (hit.guide.adminOnly && opts.isAdmin !== true) return null;
  if (hit.guide.edgeOnly && opts.edgeAvailable !== true) return null;
  return hit.guide;
}

/** Every guide, for tests and for a future written index. */
export const ALL_PAGE_GUIDES: PageGuide[] = ENTRIES.map((e) => e.guide);

// --- "seen" persistence -----------------------------------------------------
// One localStorage key holding the ids already auto-opened. Pure helpers so the
// parse/serialise rules are testable; the component owns the try/catch.

export const SEEN_KEY = "vd_page_guides_seen";

export function parseSeen(raw: string | null | undefined): string[] {
  if (!raw) return [];
  try {
    const v: unknown = JSON.parse(raw);
    return Array.isArray(v) ? v.filter((x): x is string => typeof x === "string") : [];
  } catch {
    return [];
  }
}

export function addSeen(seen: string[], id: string): string[] {
  return seen.includes(id) ? seen : [...seen, id];
}
