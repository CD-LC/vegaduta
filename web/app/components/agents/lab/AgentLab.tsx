"use client";

import { Suspense, lazy, useEffect, useId, useRef, useState, useSyncExternalStore } from "react";
import { createPortal } from "react-dom";
import { X } from "lucide-react";
import { useTranslations } from "../../../lib/i18n";
import { Button } from "../../ui/button";
import { Tabs, TabsList, TabsTrigger } from "../../ui/tabs";
import { tabbableWithin } from "./focusables";
import { labApi, type LabCapabilities } from "./labApi";
import type { AgentLabProps, LabTab } from "./types";
import BrandText from "../../BrandText";

// The three instruments are separate chunks: the run graph pulls in the
// React Flow canvas library, and AgentPlayground (a static import of the workbench)
// imports this file, so nothing below may be a static import or the canvas
// library lands in the /agents bundle before anyone opens the lab.
const RunGraphPanel = lazy(() => import("./RunGraphPanel"));
const KnockoutPanel = lazy(() => import("./KnockoutPanel"));
const ConsistencyPanel = lazy(() => import("./ConsistencyPanel"));

/**
 * Agent Lab - one full-screen overlay over the workbench playground with
 * three instruments: the run graph of a playground turn, Prompt Knockout and
 * the Consistency check. The playground pane itself is 360-460px wide, too
 * narrow for a graph, hence an overlay rather than another side card.
 *
 * This outer function holds NO hooks and returns null while closed: `turns`
 * changes on every streamed token, so nothing in here may run (or subscribe,
 * or fetch) until the lab is actually open. Everything lives in
 * AgentLabDialog, which only mounts while `open`.
 */
export default function AgentLab(props: AgentLabProps) {
  if (!props.open) return null;
  return <AgentLabDialog {...props} />;
}

const subscribeNever = () => () => {};

function AgentLabDialog({
  onClose,
  agent,
  sessionId,
  turns,
  busy,
  onForkRerun,
  onCancelTurn,
  initialTab,
}: AgentLabProps) {
  const t = useTranslations("agentLab");
  const titleId = useId();
  const panelRef = useRef<HTMLDivElement | null>(null);
  const closeButtonRef = useRef<HTMLButtonElement | null>(null);
  const [tab, setTab] = useState<LabTab>(initialTab ?? "graph");
  // document.body only exists in the browser. False on the server and for
  // the hydration pass (the console-preview page renders the lab open from
  // its very first render), true for every ordinary client render - so an
  // overlay opened by a click portals immediately, with no extra pass.
  const canPortal = useSyncExternalStore(
    subscribeNever,
    () => true,
    () => false
  );

  // What this workspace may run (plan gate + server caps). Keyed by agent id
  // so "still loading" and "loaded, and the answer is null" (a core without
  // the lab routes) stay distinguishable, and an agent switch can never show
  // the previous agent's answer.
  const agentId = agent?.id ?? null;
  const [loaded, setLoaded] = useState<{ agentId: string; capabilities: LabCapabilities | null } | null>(null);
  useEffect(() => {
    if (!agentId) return undefined;
    let stale = false;
    void labApi.getLabCapabilities(agentId).then((capabilities) => {
      if (!stale) setLoaded({ agentId, capabilities });
    });
    return () => {
      stale = true;
    };
  }, [agentId]);
  const capabilitiesLoading = agentId != null && loaded?.agentId !== agentId;
  const capabilities = capabilitiesLoading ? null : (loaded?.capabilities ?? null);

  // Hand focus back to whatever opened the lab (the launcher button) when it
  // closes. Declared before the focus-on-open effect so it still sees the
  // opener as document.activeElement.
  useEffect(() => {
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    return () => opener?.focus();
  }, []);

  // Move focus into the dialog on open, instead of leaving it on whatever the
  // browser defaults to (or nowhere, for a screen reader user).
  useEffect(() => {
    if (canPortal) closeButtonRef.current?.focus();
  }, [canPortal]);

  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        // A control inside a panel (a confirm step, an open menu) claims
        // Escape for itself by calling preventDefault on the keydown.
        if (e.defaultPrevented) return;
        onClose();
        return;
      }
      // Tab focus trap: wrap Tab from the last control back to the first,
      // and Shift+Tab from the first back to the last, so keyboard focus
      // can't leak out to the page behind the overlay (where the playground
      // composer would still take a message). "First" and "last" are the
      // controls Tab can really reach: two of the three panels sit in
      // `hidden` wrappers, and their inputs must not be counted.
      if (e.key === "Tab" && panelRef.current) {
        // A dialog inside a panel (the fork confirm) runs its own trap in
        // the capture phase and has already dealt with this key.
        if (e.defaultPrevented) return;
        const panel = panelRef.current;
        const focusable = tabbableWithin(panel);
        if (focusable.length === 0) {
          e.preventDefault();
          return;
        }
        const first = focusable[0];
        const last = focusable[focusable.length - 1];
        const active = document.activeElement;
        if (!panel.contains(active)) {
          // Focus is somewhere behind the overlay (or nowhere): bring it in.
          e.preventDefault();
          (e.shiftKey ? last : first).focus();
        } else if (e.shiftKey && active === first) {
          e.preventDefault();
          last.focus();
        } else if (!e.shiftKey && active === last) {
          e.preventDefault();
          first.focus();
        }
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [onClose]);

  if (!canPortal) return null;

  const panelFallback = <p className="text-sm text-muted-foreground">{t("panelLoading")}</p>;

  return createPortal(
    // role="dialog" sits on the panel, not on the backdrop: tokens.css
    // forces every [role="dialog"] to a solid raised surface ("floating menus
    // are always solid"), which on the backdrop would paint the dimmed scrim
    // opaque.
    <div className="fixed inset-0 z-50 flex items-stretch justify-center bg-black/60 p-2 sm:p-6">
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        className="flex w-full max-w-[1400px] flex-col overflow-hidden rounded-xl border border-border bg-background shadow-2xl"
      >
        <div className="flex shrink-0 flex-wrap items-center gap-x-4 gap-y-2 border-b border-border px-4 py-3 sm:px-5">
          <div className="flex min-w-0 items-baseline gap-2">
            <h2 id={titleId} className="text-sm font-semibold tracking-tight text-foreground">
              <BrandText text={t("title")} />
            </h2>
            {agent && (
              <span className="truncate text-xs text-muted-foreground">
                <BrandText text={t("subtitle", { name: agent.name })} />
              </span>
            )}
          </div>
          <Tabs
            value={tab}
            onValueChange={(value) => {
              if (value === "graph" || value === "knockout" || value === "consistency") setTab(value);
            }}
            className="order-last w-full gap-0 sm:order-none sm:w-auto"
          >
            <TabsList className="sm:w-auto">
              <TabsTrigger value="graph">{t("tabGraph")}</TabsTrigger>
              <TabsTrigger value="knockout">{t("tabKnockout")}</TabsTrigger>
              <TabsTrigger value="consistency">{t("tabConsistency")}</TabsTrigger>
            </TabsList>
          </Tabs>
          <div className="ms-auto flex items-center gap-2">
            {/* l22-4 teaches all three instruments (run graph, knockout,
                consistency) — one link for the lab itself; the knockout
                panel's dataset empty state links l16-3 separately. */}
            <Button
              type="button"
              ref={closeButtonRef}
              variant="ghost"
              size="icon"
              onClick={onClose}
              aria-label={t("close")}
              title={t("close")}
            >
              <X size={16} />
            </Button>
          </div>
        </div>
        <div className="min-h-0 flex-1 overflow-y-auto">
          {!agent ? (
            <p className="p-4 text-sm text-muted-foreground sm:p-5">{t("noAgent")}</p>
          ) : (
            <>
              {/* The graph reads the live transcript, so it only exists while
                  its tab is showing. It owns its own padding and height. */}
              {tab === "graph" && (
                <Suspense fallback={<div className="p-4 sm:p-5">{panelFallback}</div>}>
                  <RunGraphPanel
                    key={agent.id}
                    agent={agent}
                    sessionId={sessionId}
                    turns={turns}
                    busy={busy}
                    onForkRerun={onForkRerun}
                    onCancelTurn={onCancelTurn}
                  />
                </Suspense>
              )}
              {capabilitiesLoading ? (
                tab !== "graph" && (
                  <p role="status" className="p-4 text-sm text-muted-foreground sm:p-5">
                    {t("capabilitiesLoading")}
                  </p>
                )
              ) : (
                <>
                  {/* `hidden` wrappers rather than TabsContent: the two forms
                      (and a run in flight) must survive a tab switch. */}
                  <div hidden={tab !== "knockout"} className="space-y-4 p-4 sm:p-5">
                    <p className="text-xs leading-relaxed text-muted-foreground">{t("scopeNote")}</p>
                    <Suspense fallback={panelFallback}>
                      <KnockoutPanel key={agent.id} agent={agent} capabilities={capabilities} />
                    </Suspense>
                  </div>
                  <div hidden={tab !== "consistency"} className="space-y-4 p-4 sm:p-5">
                    <p className="text-xs leading-relaxed text-muted-foreground">{t("scopeNote")}</p>
                    <Suspense fallback={panelFallback}>
                      <ConsistencyPanel key={agent.id} agent={agent} turns={turns} capabilities={capabilities} />
                    </Suspense>
                  </div>
                </>
              )}
            </>
          )}
        </div>
      </div>
    </div>,
    document.body
  );
}
