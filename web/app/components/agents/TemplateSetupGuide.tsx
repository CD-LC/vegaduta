"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { createPortal } from "react-dom";
import {
  AlertTriangle,
  BookOpen,
  KeyRound,
  ListChecks,
  MessageSquare,
  Plug,
  Rocket,
  ShieldAlert,
  Terminal,
  X,
} from "lucide-react";
import { api } from "../../lib/api";
import { useTranslations } from "../../lib/i18n";
import { useAuth } from "../AuthProvider";
import { Button } from "../ui/button";
import { Badge } from "../ui/badge";
import { useDialogFocus } from "../../lib/hooks/useDialogFocus";
import {
  mcpConnectorCapFor,
  dependsOnDeepResearch,
  prereqsForConsoleTemplate,
  requiredConnectorCount,
  type McpServerKey,
  type TemplatePrereqs,
} from "./templatePrereqs";
import BrandText from "../BrandText";
import Step, { type StepProps } from "./setupGuide/GuideStep";
import McpSuggestionList from "./setupGuide/McpSuggestionList";
import {
  getSnapshot,
  groupSuggestions,
  serverState,
  type CatalogEntry,
  type Tri,
  type WorkspaceSnapshot,
} from "./setupGuide/workspaceSnapshot";

/**
 * "A total guide on click" - everything that has to be true before the agent
 * this template creates does real work, in the order it has to be done, with
 * the parts this workspace has already satisfied ticked off.
 *
 * The honesty rules this component is built around:
 *
 *   1. Picking a template pre-fills a system prompt. That is ALL it does. It
 *      does not enable an MCP server, connect a channel, or upload a document,
 *      and there is no per-template or per-agent MCP association anywhere in
 *      the product to do it with. So this guide tells the person what to go and
 *      switch on rather than pretending the template did it.
 *   2. Progress ticks come from real endpoints only - tenant settings, the MCP
 *      registry, knowledge collections, channel status, the Google connection
 *      and the E2B credential. Anything with no endpoint to check (a repo being
 *      indexed for code-intel) renders as "not checked", never as satisfied.
 *      A failed fetch renders as "not checked" too: an untickable checklist is
 *      correct, a falsely ticked one is not.
 *   3. Only the 17 curated catalog servers are ever suggested. A FREE tenant is
 *      restricted to catalog entries and two connectors total, and a
 *      non-catalog STDIO registration needs platform-staff review before it can
 *      connect - so anything outside the catalog would be advice the API
 *      refuses to take.
 *   4. Every mutating step here (registering a connector, connecting a channel
 *      or Google, storing a provider key) is gated server-side to
 *      ROLE_tenant-admin. A non-admin is told to hand the list over rather than
 *      sent to a page that will 403.
 *
 * There is no deep link that preselects a card on /tools - that page only reads
 * an ?atlassian= OAuth-callback param and owns its search box as local state -
 * so each server names the exact card to look for instead of linking to a
 * parameter nothing reads.
 */

export interface TemplateSetupGuideProps {
  /** components/agentTemplates.ts key. Unknown keys resolve to the honest default (no MCP suggestion). */
  templateKey: string;
  /** Already-translated display name - this component does not reach into the agentTemplates namespace itself. */
  templateName: string;
  suggestKnowledgeBase?: boolean;
  /** agentTemplates category string - industry categories imply a channel step and a hard knowledge requirement. */
  category?: string;
  /** Called when the person accepts the guide's final step. Omit (or pass useDisabled) when the gallery is plan-locked. */
  onUseTemplate?: () => void;
  useDisabled?: boolean;
}

export default function TemplateSetupGuide({
  templateKey,
  templateName,
  suggestKnowledgeBase,
  category,
  onUseTemplate,
  useDisabled,
}: TemplateSetupGuideProps) {
  const t = useTranslations("templateSetupGuide");
  const { hasPermission } = useAuth();
  const canManageIntegrations = hasPermission("MANAGE_INTEGRATIONS");
  const [open, setOpen] = useState(false);
  const [mounted, setMounted] = useState(false);
  const [snapshot, setSnapshot] = useState<WorkspaceSnapshot | null>(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => setMounted(true), []);

  // Only for a template whose connector need depends on it, and only once the
  // guide is opened: does this deployment offer built-in deep research? The
  // status call is quiet; unknown or failed = not offered, so the search
  // connector stays required.
  const [deepResearchAvailable, setDeepResearchAvailable] = useState(false);
  useEffect(() => {
    if (!open || !dependsOnDeepResearch(templateKey)) return undefined;
    let cancelled = false;
    Promise.resolve()
      .then(() => api.getResearchStatus())
      .then((status) => {
        if (!cancelled) setDeepResearchAvailable(Boolean(status?.enabled && status?.searchConfigured));
      })
      .catch(() => {
        if (!cancelled) setDeepResearchAvailable(false);
      });
    return () => {
      cancelled = true;
    };
  }, [open, templateKey]);

  const prereqs: TemplatePrereqs = useMemo(
    () => prereqsForConsoleTemplate(templateKey, { suggestKnowledgeBase, category, deepResearchAvailable }),
    [templateKey, suggestKnowledgeBase, category, deepResearchAvailable]
  );

  // Only fetch once the guide is actually opened - the gallery renders 40+
  // cards and none of them should cost a request until someone asks.
  useEffect(() => {
    if (!open) return undefined;
    let cancelled = false;
    setLoading(true);
    getSnapshot()
      .then((value) => {
        if (!cancelled) setSnapshot(value);
      })
      .catch(() => {
        if (!cancelled) setSnapshot(null);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [open]);

  useEffect(() => {
    if (!open) return undefined;
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") setOpen(false);
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open]);

  const close = useCallback(() => setOpen(false), []);

  // Focus in, Tab trap and focus back to the trigger on close (FE-041). Escape stays with the effect above.
  const dialogRef = useRef<HTMLDivElement>(null);
  useDialogFocus(dialogRef, null, null, { enabled: mounted && open });

  const plan = snapshot?.plan ?? null;
  const isFree = plan === "FREE";
  const connectorCap = mcpConnectorCapFor(plan);
  const connectorsUsed = snapshot?.registrations?.length ?? null;
  const capReached = connectorCap !== null && connectorsUsed !== null && connectorsUsed >= connectorCap;
  const mcpBlocks = useMemo(() => groupSuggestions(prereqs.mcp), [prereqs.mcp]);
  const requiredConnectors = requiredConnectorCount(prereqs);

  const catalogEntry = useCallback(
    (server: McpServerKey): CatalogEntry | null =>
      snapshot?.catalog?.find((e) => e?.key === server || e?.name === server) ?? null,
    [snapshot]
  );

  function label(status: Tri) {
    if (status === "done") return t("statusDone");
    if (status === "todo") return t("statusTodo");
    return t("statusUnknown");
  }

  // ------------------------------------------------------------------ steps
  const steps: StepProps[] = [];
  const push = (step: Omit<StepProps, "index" | "last">) =>
    steps.push({ ...step, index: steps.length + 1, last: false });

  if (prereqs.knowledge !== "none") {
    const count = snapshot?.collectionCount;
    const status: Tri = count === null || count === undefined ? "unknown" : count > 0 ? "done" : "todo";
    push({
      icon: BookOpen,
      title: t("stepKnowledgeTitle"),
      status,
      statusLabel: label(status),
      body: prereqs.knowledge === "required" ? t("stepKnowledgeBodyRequired") : t("stepKnowledgeBodyRecommended"),
      notes: [
        status === "done" && count ? t("stepKnowledgeCount", { count: String(count) }) : null,
        isFree ? t("stepKnowledgeFreeNote") : null,
        t("stepKnowledgeContentNote"),
      ],
      href: "/knowledge",
      linkLabel: t("openKnowledge"),
    });
  }

  if (prereqs.channel) {
    const connected = snapshot?.connectedChannels;
    const status: Tri =
      connected === null || connected === undefined ? "unknown" : connected.length > 0 ? "done" : "todo";
    push({
      icon: MessageSquare,
      title: t("stepChannelTitle"),
      status,
      statusLabel: label(status),
      body: t("stepChannelBody"),
      notes: [
        status === "done" && connected && connected.length > 0
          ? t("stepChannelConnected", { channels: connected.join(", ") })
          : null,
        t("stepChannelByoNote"),
      ],
      href: "/admin/channels",
      linkLabel: t("openChannels"),
    });
  }

  if (prereqs.google) {
    const googleConnected = snapshot?.googleConnected;
    const status: Tri =
      googleConnected === null || googleConnected === undefined ? "unknown" : googleConnected ? "done" : "todo";
    push({
      icon: KeyRound,
      title: t("stepGoogleTitle"),
      status,
      statusLabel: label(status),
      body: t("stepGoogleBody"),
      notes: [t("stepGoogleNotMcpNote")],
      href: "/admin/integrations",
      linkLabel: t("openIntegrations"),
    });
  }

  if (prereqs.e2b) {
    const e2bActive = snapshot?.e2bActive;
    const status: Tri = e2bActive === null || e2bActive === undefined ? "unknown" : e2bActive ? "done" : "todo";
    push({
      icon: Terminal,
      title: t("stepE2bTitle"),
      status,
      statusLabel: label(status),
      body: t("stepE2bBody"),
      notes: [t("stepE2bBothNote")],
      href: "/providers",
      linkLabel: t("openProviders"),
    });
  }

  if (prereqs.codeIntel) {
    // No endpoint tells us whether a repository is indexed for this tenant, so
    // this one is rendered statically and always reads "not checked". Ticking
    // it on a guess would be the exact failure this guide exists to prevent.
    push({
      icon: Terminal,
      title: t("stepCodeIntelTitle"),
      status: "unknown",
      statusLabel: label("unknown"),
      body: t("stepCodeIntelBody"),
      notes: [t("stepCodeIntelUncheckableNote")],
      href: "/code-intel",
      linkLabel: t("openCodeIntel"),
    });
  }

  // MCP step always renders. "This one needs no MCP server" is an answer the
  // person came here for, not an omission.
  const mcpRequiredBlocks = mcpBlocks.filter((b) => b.items.some((i) => i.necessity === "required"));
  const mcpStatus: Tri = (() => {
    if (prereqs.mcp.length === 0) return "done";
    if (!snapshot?.registrations) return "unknown";
    if (mcpRequiredBlocks.length === 0) return "done";
    return mcpRequiredBlocks.every((b) => b.items.some((i) => serverState(snapshot, i.server) === "on"))
      ? "done"
      : "todo";
  })();

  // A step headed "Done" directly above three servers each marked "Not added
  // yet" reads as a contradiction, even though nothing here is outstanding:
  // every server this template suggests is optional. Say that instead - the
  // tick stays (nothing blocks the agent) but the words match the list.
  const mcpStatusLabel =
    prereqs.mcp.length === 0
      ? t("statusNothingNeeded")
      : mcpStatus === "done" &&
          mcpRequiredBlocks.length === 0 &&
          !prereqs.mcp.some((s) => serverState(snapshot, s.server) === "on")
        ? t("statusOptionalOnly")
        : label(mcpStatus);

  push({
    icon: Plug,
    title: t("stepMcpTitle"),
    status: mcpStatus,
    statusLabel: mcpStatusLabel,
    body: prereqs.mcp.length === 0 ? t("stepMcpBodyNone") : t("stepMcpBody"),
    notes: [
      prereqs.mcp.length === 0 ? null : t("stepMcpCatalogOnlyNote"),
      prereqs.mcp.length === 0 || !isFree ? null : t("stepMcpFreeCapNote"),
      prereqs.mcp.length === 0 || connectorsUsed === null
        ? null
        : connectorCap === null
          ? t("stepMcpUsageUncapped", { used: String(connectorsUsed) })
          : t("stepMcpUsage", { used: String(connectorsUsed), cap: String(connectorCap) }),
      prereqs.mcp.length > 0 && capReached ? t("stepMcpCapReached") : null,
      prereqs.mcp.length > 0 && isFree && requiredConnectors > (mcpConnectorCapFor("FREE") ?? 0)
        ? t("stepMcpExceedsFree")
        : null,
    ],
    href: prereqs.mcp.length === 0 ? undefined : "/tools",
    linkLabel: prereqs.mcp.length === 0 ? undefined : t("openTools"),
    children:
      prereqs.mcp.length === 0 ? null : (
        <McpSuggestionList mcpBlocks={mcpBlocks} snapshot={snapshot} catalogEntry={catalogEntry} />
      ),
  });

  if (prereqs.mcp.length > 0) {
    push({
      icon: ListChecks,
      title: t("stepAllowListTitle"),
      // Nothing to check: this is a caveat about a restriction the person has
      // not applied yet, not a task with a satisfied state.
      status: "unknown",
      statusLabel: label("unknown"),
      body: t("stepAllowListBody"),
      notes: [t("stepAllowListNamesNote")],
    });
  }

  push({
    icon: Rocket,
    title: t("stepCreateTitle"),
    status: "todo",
    statusLabel: label("todo"),
    body: t("stepCreateBody"),
  });

  if (steps.length > 0) steps[steps.length - 1].last = true;

  return (
    <>
      <Button type="button" variant="outline" size="sm" onClick={() => setOpen(true)}>
        <ListChecks size={14} aria-hidden="true" />
        {t("triggerLabel")}
      </Button>

      {mounted &&
        open &&
        createPortal(
          <div
            ref={dialogRef}
            className="fixed inset-0 z-[120] flex items-center justify-center bg-black/60 p-4 backdrop-blur-sm"
            role="presentation"
            onClick={(e) => {
              if (e.target === e.currentTarget) close();
            }}
          >
            <div
              role="dialog"
              aria-modal="true"
              aria-labelledby="template-setup-guide-title"
              className="relative flex max-h-[86vh] w-full max-w-2xl flex-col overflow-hidden rounded-2xl border border-border bg-card shadow-2xl"
            >
              <button
                type="button"
                onClick={close}
                aria-label={t("close")}
                className="absolute right-3 top-3 flex size-8 items-center justify-center rounded-md border-0 bg-transparent p-0 text-muted-foreground shadow-none transition-colors hover:bg-accent hover:text-foreground"
              >
                <X size={16} aria-hidden="true" />
              </button>

              <div className="border-b border-border px-6 py-5">
                <div className="flex items-center gap-2 text-primary">
                  <ListChecks size={16} aria-hidden="true" />
                  <span className="text-xs font-semibold uppercase tracking-wider">{t("eyebrow")}</span>
                </div>
                <h2
                  id="template-setup-guide-title"
                  className="mt-1.5 text-xl font-semibold tracking-tight text-foreground"
                >
                  {templateName}
                </h2>
                <p className="mt-1 text-sm text-muted-foreground">
                  <BrandText text={t("subtitle")} />
                </p>
                <div className="mt-3 flex flex-wrap items-center gap-2">
                  {plan && <Badge variant="secondary">{t("planBadge", { plan })}</Badge>}
                  {loading && <span className="text-xs text-muted-foreground">{t("checking")}</span>}
                </div>
                {!loading && !snapshot && (
                  <p className="mt-2 flex items-start gap-1.5 text-xs leading-relaxed text-muted-foreground">
                    <AlertTriangle size={13} className="mt-0.5 shrink-0" aria-hidden="true" />
                    {t("checkFailed")}
                  </p>
                )}
                {!canManageIntegrations && (
                  <p className="mt-2 flex items-start gap-1.5 text-xs leading-relaxed text-muted-foreground">
                    <ShieldAlert size={13} className="mt-0.5 shrink-0" aria-hidden="true" />
                    {t("adminOnly")}
                  </p>
                )}
              </div>

              <ol className="flex-1 overflow-y-auto px-6 py-5">
                {steps.map((step) => (
                  <Step key={step.title} {...step} />
                ))}
              </ol>

              <div className="flex items-center justify-between gap-3 border-t border-border px-6 py-4">
                <span className="text-xs text-muted-foreground">{t("footerHint")}</span>
                <div className="flex items-center gap-2">
                  <Button type="button" variant="ghost" size="sm" onClick={close}>
                    {t("close")}
                  </Button>
                  {onUseTemplate && !useDisabled && (
                    <Button
                      type="button"
                      size="sm"
                      onClick={() => {
                        close();
                        onUseTemplate();
                      }}
                    >
                      {t("useTemplate")}
                    </Button>
                  )}
                </div>
              </div>
            </div>
          </div>,
          document.body
        )}
    </>
  );
}
