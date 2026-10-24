"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { api } from "../../lib/api";
import { useTranslations } from "../../lib/i18n";
import AutonomyTierExplainer, { isAutonomyTier, type AutonomyTierName } from "../approvals/AutonomyTierExplainer";

const TIER_NOTE_KEY: Record<AutonomyTierName, string> = {
  SUPERVISED: "agentModeSupervised",
  SEMI_AUTONOMOUS: "agentModeSemi",
  FULLY_AUTONOMOUS: "agentModeFull",
};

/**
 * "When does this agent's tool use wait for a person?" for one agent: its autonomy level, what that means in
 * plain words, whether the workspace's per-tool rules apply to it, and where to change them. Mounted with the
 * other per-agent safety controls.
 *
 * Self-hiding when the agent cannot be read (an older core, an agent that is not visible): an absent explainer
 * must not look like an error. Nothing here is editable on purpose - the level is set in the autonomy selector
 * above, and tool rules are workspace-wide (the server has no per-agent tool approval mode and no "ask once per
 * session" mode; see the report that came with this card).
 */
export default function AgentApprovalModeCard({ agentId }: { agentId: string | null | undefined }) {
  const t = useTranslations("approvalsQueue");
  const [state, setState] = useState<{ agentId: string; tier: string | null } | null>(null);
  const [open, setOpen] = useState(false);

  useEffect(() => {
    if (!agentId) {
      setState(null);
      return undefined;
    }
    let live = true;
    Promise.resolve()
      .then(() => api.listAgents())
      .then((list: Array<{ id: string; autonomyTier?: string | null }>) => {
        if (!live) return;
        const found = Array.isArray(list) ? list.find((a) => a.id === agentId) : undefined;
        setState(found ? { agentId, tier: found.autonomyTier ?? "SEMI_AUTONOMOUS" } : null);
      })
      .catch(() => {
        if (live) setState(null);
      });
    return () => {
      live = false;
    };
  }, [agentId]);

  if (!agentId || !state || state.agentId !== agentId) return null;
  const tier = isAutonomyTier(state.tier) ? state.tier : null;
  if (!tier) return null;

  return (
    <div className="mb-4" data-testid="agent-approval-mode">
      <div className="mb-1 text-xs text-muted-foreground">{t("agentModeTitle")}</div>
      <p className="text-xs text-foreground" data-testid="agent-approval-mode-note">
        {t(TIER_NOTE_KEY[tier])}
      </p>
      <div className="mt-2 flex flex-wrap items-center gap-x-4 gap-y-1 text-xs">
        <Link
          href={`/admin/approvals?agent=${encodeURIComponent(agentId)}`}
          className="font-medium text-foreground underline underline-offset-2"
          data-testid="agent-approval-queue-link"
        >
          {t("agentModeQueueLink")}
        </Link>
        {tier === "SEMI_AUTONOMOUS" && (
          <Link
            href="/admin/approvals?rules=1"
            className="font-medium text-foreground underline underline-offset-2"
            data-testid="agent-approval-rules-link"
          >
            {t("agentModeRulesLink")}
          </Link>
        )}
        <button
          type="button"
          aria-expanded={open}
          aria-controls="agent-approval-tiers"
          onClick={() => setOpen((v) => !v)}
          className="text-muted-foreground underline underline-offset-2 hover:text-foreground"
          data-testid="agent-approval-tiers-toggle"
        >
          {open ? t("agentModeHideTiers") : t("agentModeShowTiers")}
        </button>
      </div>
      {open && (
        <div id="agent-approval-tiers" className="mt-2">
          <AutonomyTierExplainer current={tier} />
        </div>
      )}
      <p className="mt-2 text-xs text-muted-foreground">{t("agentModeScopeNote")}</p>
    </div>
  );
}
