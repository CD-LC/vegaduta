"use client";

import { useContext, useEffect, useRef } from "react";
import Link from "next/link";
import { ArrowUpRight, CheckCircle2, Info, Lock, PlayCircle, Save, ScrollText, Waypoints } from "lucide-react";
import { settingsApi, type SettingsClient } from "../../../lib/api/settings";
import { useTranslations } from "../../../lib/i18n";
import { AuthContext } from "../../AuthProvider";
import { SettingsPanel } from "../../settings";
import { useEffectiveSettings, useSettingsCatalog } from "../../settings/useSettings";
import { Alert, AlertDescription } from "../../ui/alert";
import { Button } from "../../ui/button";
import { goToGuideAnchor } from "../guide/guideDom";
import { GUIDE_ANCHORS } from "../guide/agentChecklist";
import {
  BEHAVIOR_ANCHORS,
  TENANT_RUNTIME_SETTINGS_HREF,
  roleForAgentAccess,
} from "./behaviorModel";
import { agentRelatedLinks, reachableAgentLinks, type AgentRelatedLink } from "./agentLinks";
import DiscardChangesDialog from "./DiscardChangesDialog";
import EffectiveBehaviorCard from "./EffectiveBehaviorCard";
import { useDirtyWatch, useLinkGuard } from "./useUnsavedGuard";

/** The slice of the agent this tab reads. */
export interface BehaviorAgent {
  id: string;
  name?: string;
  providerName?: string | null;
  model?: string | null;
  accessLevel?: string | null;
  autonomyTier?: string | null;
}

const LINK_ICON: Record<AgentRelatedLink["id"], typeof ScrollText> = {
  prompts: ScrollText,
  approvals: CheckCircle2,
  turns: Waypoints,
};

/**
 * The "Behavior & limits" tab of the agent workbench: everything about how far this agent may go on a turn, in one
 * place and with inheritance made visible. Top to bottom: what is in force right now (summary card with the layer
 * each value comes from and what it means in plain words), the editable settings for this agent (the catalog-driven
 * settings panel in agent scope: bounds, units, risk confirmation, reset to inherited, history), and links to the
 * workspace-wide defaults, to this agent's prompts, approvals and turn timeline (each opened already scoped to the
 * agent), to the playground to try a change, and to the older cards that hold controls the
 * catalog does not cover (sampling, spending cap, tool rules, autonomy).
 *
 * Unsaved edits are protected three ways: the tab stays mounted while another tab is shown (the workbench keeps it
 * hidden, not unmounted), leaving through an in-app link asks first, and closing the page triggers the browser's
 * prompt (settings panel). Switching agent is guarded by the workbench itself.
 */
export default function BehaviorLimitsTab({
  agent,
  locked = false,
  client = settingsApi,
  focusKey,
  tenantSettingsHref = TENANT_RUNTIME_SETTINGS_HREF,
  onDirtyChange,
  onOpenPlayground,
  onGoToSection,
}: {
  agent: BehaviorAgent;
  /** The agent is edit-locked (plan lock): every field is read-only. */
  locked?: boolean;
  /** Defaults to the console's authenticated client; inject a fake in tests. */
  client?: SettingsClient;
  /** Catalog key (or key prefix such as `agent.limits`) from ?focus=: scrolled to and focused once the settings have loaded. */
  focusKey?: string | null;
  tenantSettingsHref?: string;
  onDirtyChange?: (dirty: boolean) => void;
  onOpenPlayground?: () => void;
  onGoToSection?: (anchor: string) => void;
}) {
  const t = useTranslations("agentBehavior");
  const rootRef = useRef<HTMLDivElement>(null);
  const catalog = useSettingsCatalog(client);
  const effective = useEffectiveSettings(client, "agent", agent.id);
  const dirty = useDirtyWatch(rootRef, onDirtyChange);
  const guard = useLinkGuard(dirty);
  const auth = useContext(AuthContext) as { isAdmin: boolean; hasPermission: (name: string) => boolean } | null;
  const agentLinks = reachableAgentLinks(agentRelatedLinks(agent.id), auth);
  const agentLabel = agent.name || agent.id;

  const role = roleForAgentAccess(agent.accessLevel);
  const readOnly = locked || role === "VIEWER";

  const status =
    catalog.status === "error" || effective.status === "error"
      ? "error"
      : catalog.status === "loading" || effective.status === "loading"
        ? "loading"
        : "ready";
  const reloadEffective = effective.reload;
  const retry = () => {
    catalog.reload();
    void reloadEffective();
  };

  // ?focus=<key>: wait for the field to render (the panel loads its own data), then bring it into view once.
  useEffect(() => {
    const root = rootRef.current;
    if (!focusKey || !root) return undefined;
    let done = false;
    const tryFocus = () => {
      if (done) return;
      // A setting key names one field; a group such as "agent.limits" (the drawer's "Change limits" link) names the
      // first field of the group.
      const field =
        root.querySelector<HTMLElement>(`[data-testid="setting-${CSS.escape(focusKey)}"]`) ??
        root.querySelector<HTMLElement>(`[data-testid^="setting-${CSS.escape(focusKey)}."]`);
      if (!field) return;
      done = true;
      field.scrollIntoView?.({ block: "center" });
      field
        .querySelector<HTMLElement>('input, textarea, [role="switch"], [role="combobox"]')
        ?.focus({ preventScroll: true });
      observer.disconnect();
    };
    const observer = new MutationObserver(tryFocus);
    observer.observe(root, { subtree: true, childList: true });
    tryFocus();
    const stop = setTimeout(() => observer.disconnect(), 15000);
    return () => {
      done = true;
      clearTimeout(stop);
      observer.disconnect();
    };
  }, [focusKey, agent.id]);

  const modelText = [agent.providerName, agent.model].filter(Boolean).join(" / ");
  const goTo = onGoToSection ?? ((anchor: string) => void goToGuideAnchor(anchor));
  const openPlayground = onOpenPlayground ?? (() => void goToGuideAnchor(GUIDE_ANCHORS.playground));

  return (
    <div ref={rootRef} className="flex min-w-0 flex-col gap-4" data-testid="behavior-limits-tab">
      <div>
        <h2 className="text-base font-semibold">{t("title")}</h2>
        <p className="mt-1 text-sm text-muted-foreground">{t("intro")}</p>
        <nav aria-label={t("links.label")} className="mt-3 flex flex-wrap items-center gap-2">
          <Button asChild size="sm" variant="outline">
            <Link href={tenantSettingsHref} data-testid="tenant-settings-link">
              {t("links.tenant")}
              <ArrowUpRight aria-hidden="true" />
            </Link>
          </Button>
          <Button type="button" size="sm" variant="outline" onClick={openPlayground} data-testid="try-in-playground">
            <PlayCircle aria-hidden="true" />
            {t("links.playground")}
          </Button>
          {agentLinks.map((l) => {
            const Icon = LINK_ICON[l.id];
            return (
              <Button key={l.id} asChild size="sm" variant="outline">
                <Link href={l.href} aria-label={t(l.ariaKey, { name: agentLabel })} data-testid={`agent-link-${l.id}`}>
                  <Icon aria-hidden="true" />
                  {t(l.labelKey)}
                </Link>
              </Button>
            );
          })}
        </nav>
      </div>

      {readOnly ? (
        <Alert variant="info" data-testid="behavior-readonly">
          <Lock aria-hidden="true" />
          <AlertDescription>{locked ? t("readOnly.locked") : t("readOnly.access")}</AlertDescription>
        </Alert>
      ) : null}

      <div role="status" aria-live="polite" data-testid="behavior-unsaved">
        {dirty ? (
          <Alert variant="warning">
            <Save aria-hidden="true" />
            <AlertDescription>{t("unsaved")}</AlertDescription>
          </Alert>
        ) : null}
      </div>

      <EffectiveBehaviorCard
        status={status}
        error={catalog.error ?? effective.error}
        entries={catalog.entries}
        values={effective.values}
        model={modelText}
        autonomyTier={agent.autonomyTier}
        onRetry={retry}
      />

      <Alert variant="info">
        <Info aria-hidden="true" />
        <AlertDescription>{t("inheritNote")}</AlertDescription>
      </Alert>

      <SettingsPanel
        scope="agent"
        agentId={agent.id}
        client={client}
        role={role}
        readOnly={readOnly}
        heading={null}
        onSaved={() => void reloadEffective({ quiet: true })}
      />

      <section aria-labelledby="agent-behavior-others" className="rounded-xl border border-border bg-card p-4" data-testid="behavior-crosslinks">
        <h3 id="agent-behavior-others" className="text-sm font-semibold">
          {t("others.title")}
        </h3>
        <p className="mt-1 text-xs text-muted-foreground">{t("others.intro")}</p>
        <ul className="mt-3 space-y-3">
          <li>
            <p className="text-sm">{t("others.generation")}</p>
            <Button type="button" size="sm" variant="outline" className="mt-1" onClick={() => goTo(BEHAVIOR_ANCHORS.generation)} data-testid="goto-generation">
              {t("others.generationLink")}
            </Button>
          </li>
          <li>
            <p className="text-sm">{t("others.autonomy")}</p>
            <Button type="button" size="sm" variant="outline" className="mt-1" onClick={() => goTo(BEHAVIOR_ANCHORS.autonomy)} data-testid="goto-autonomy">
              {t("others.autonomyLink")}
            </Button>
          </li>
        </ul>
      </section>

      <DiscardChangesDialog open={guard.pendingHref != null} onStay={guard.stay} onDiscard={guard.leave} />
    </div>
  );
}
