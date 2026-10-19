"use client";

import { useEffect, useState } from "react";
import nextDynamic from "next/dynamic";
import { useParams, useRouter, useSearchParams } from "next/navigation";
import { api } from "../../lib/api";
import AppShell from "../../components/AppShell";
import { useTranslations } from "../../lib/i18n";

// @xyflow/react + the 3k-line canvas stay out of the route's initial bundle.
const WorkflowCanvas = nextDynamic(() => import("../../components/workflow/WorkflowCanvas"), { ssr: false });

/**
 * Dedicated, full-height canvas page for a single workflow. Previously the
 * canvas was embedded inline on the same page as the workflow list (and
 * five other console panels above it) - it only ever got whatever leftover
 * vertical space remained, which is the root of the "feels congested,
 * nodes too large for the space" complaint. Giving it a route of its own
 * means the canvas can size itself to the viewport instead of a leftover
 * scrap of one (see .ax-wf-shell/.ax-wf-main's flex-fill sizing in
 * globals.css).
 *
 * Needs its own agents/collections fetch since this page no longer shares
 * React state with the workflows list page - same "each route fetches what
 * it needs" pattern already used across /agents, /knowledge, etc.
 */
export default function WorkflowCanvasPage() {
  const t = useTranslations("workflowIdPage");
  const params = useParams();
  const router = useRouter();
  const searchParams = useSearchParams();
  const workflowId = params.id;
  // Focus mode (direct feedback: "give a full screen link so the workflow
  // designer will be opened in large window, focused mode") - a real query
  // param, not just a client-only toggle, so the "Focus mode" link in
  // WorkflowCanvas.js's action bar (target="_blank" href={`/workflows/
  // ${id}?focus=1`}) opens a genuinely shareable, bookmarkable URL that
  // always lands in this chrome-free view, including in a brand-new tab/
  // window with no prior client state.
  const isFocusMode = searchParams.get("focus") === "1";

  const [agents, setAgents] = useState([]);
  const [collections, setCollections] = useState([]);
  const [devices, setDevices] = useState([]);

  useEffect(() => {
    api
      .listAgents()
      .then(setAgents)
      .catch(() => setAgents([]));
    api
      .listCollections()
      .then(setCollections)
      .catch(() => setCollections([]));
    // Mobile SDK (#157) - Device Control node's device dropdown. Failing
    // silently to an empty list (same as agents/collections above) means a
    // tenant with no paired devices yet still gets a usable canvas, just
    // with an empty picker and the Devices page's own empty-state pointing
    // them there.
    api
      .listDevices()
      .then(setDevices)
      .catch(() => setDevices([]));
  }, []);

  const canvas = (
    <WorkflowCanvas
      workflowId={workflowId}
      agents={agents}
      collections={collections}
      devices={devices}
      onBack={() => router.push("/workflows")}
      focusMode={isFocusMode}
    />
  );

  // Focus mode: deliberately skips AppShell entirely (no sidebar/topbar
  // chrome) rather than just hiding it with CSS, so the canvas gets the
  // full viewport rather than fighting AppShell's own layout/scroll
  // container for space. Global theme (data-mode on :root, body background)
  // still applies since that's set app-wide, not by AppShell itself.
  //
  // Direct feedback: "on focus mode, canvas is not visible - it should fit
  // the entire screen on top of it all the options and panels, transparent -
  // on focus,hover - becomes opaque." The old version wrapped the canvas in
  // a plain padded div with a separate "Exit focus mode" link floating above
  // it - but WorkflowCanvas's own layout math (.ax-wf-shell's min-height)
  // assumes it's sitting inside AppShell's fixed topbar + padding chain,
  // which doesn't exist here, so the canvas never actually got the full
  // viewport it needed. WorkflowCanvas now owns its own immersive layout
  // when focusMode is true (see .ax-wf-shell-immersive in globals.css: a
  // fixed, edge-to-edge canvas with every control panel - topbar, palette,
  // inspector, describe bar - floating on top as a translucent overlay that
  // opaques on hover/keyboard focus) - nothing extra needed at this level,
  // including the exit control, which now lives inside that same topbar
  // (replacing the "Focus" button) instead of a separate element here.
  if (isFocusMode) {
    return canvas;
  }

  // "Run history" used to live here as a header action, duplicating the
  // real-runs History tab now built into WorkflowCanvas.js's right-side run
  // inspector - removed rather than kept as a second path to the same data.
  return (
    <AppShell title={t("title")} eyebrow={t("eyebrow")} bleed fillViewport collapsibleRail>
      {canvas}
    </AppShell>
  );
}
