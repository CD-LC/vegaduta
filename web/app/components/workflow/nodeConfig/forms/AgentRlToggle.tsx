"use client";

import { useEffect, useState } from "react";
import { api } from "../../../../lib/api";
import { Badge } from "../../../ui/badge";
import { Label } from "../../../ui/label";
import { useTranslations } from "../../../../lib/i18n";
import { useAuth } from "../../../AuthProvider";
import { toast } from "../../../../lib/toastBus";
import { errorMessage } from "../../../../lib/errors";
import { type RlPolicy } from "../types";

/**
 * "Wire the existing RL bandit into workflow AGENT nodes" - the MODEL_ROUTE
 * bandit is already live for every workflow-run agent turn (WorkflowNodeExecutor
 * passes this node's own id as turnRef, which is exactly what
 * AgentChatService's selectArm gate requires) whenever an admin has enabled
 * that agent's MODEL_ROUTE policy. The only thing actually missing was a way
 * to flip that switch without leaving the canvas for the standalone Agent
 * Learning page - this is that switch, in context, for the exact agent this
 * node is about to call. Admin-only (mirrors RlController's server-side
 * ROLE_tenant-admin gate on the PUT) - non-admins see a read-only status line
 * instead of a toggle they'd get a 403 from anyway.
 */
export function AgentRlToggle({ agentId }: { agentId: string }) {
  const t = useTranslations("nodeConfigDrawer");
  const { hasPermission } = useAuth();
  const canManageWorkflows = hasPermission("MANAGE_WORKFLOWS_ADMIN");
  const [policy, setPolicy] = useState<RlPolicy | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    api
      .getRlPolicies(agentId)
      .then((list: RlPolicy[] | null) => {
        if (cancelled) return;
        const modelRoute = (list || []).find((p) => p.dimension === "MODEL_ROUTE") || null;
        setPolicy(modelRoute);
      })
      .catch(() => {
        if (!cancelled) setPolicy(null);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [agentId]);

  if (loading || !policy) {
    return null;
  }

  async function toggle() {
    if (!canManageWorkflows || saving) return;
    setSaving(true);
    try {
      const updated = await api.updateRlPolicy(agentId, "MODEL_ROUTE", {
        enabled: !policy.enabled,
        explorationRate: policy.explorationRate,
        algorithm: policy.algorithm || "THOMPSON",
      });
      setPolicy(updated);
    } catch (err) {
      // Not optimistic: `policy` only ever flips on a successful response,
      // so the toggle is already back at its last-known state here - all
      // that was missing was telling the user the click didn't take.
      toast.error(errorMessage(err, t("adaptiveRoutingUpdateError")));
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="field">
      <div className="flex items-center justify-between gap-3">
        <Label className="mb-0">{t("adaptiveRoutingLabel")}</Label>
        {canManageWorkflows ? (
          <button
            type="button"
            role="switch"
            aria-checked={policy.enabled}
            aria-label={t("adaptiveRoutingLabel")}
            disabled={saving}
            className={`ax-toggle${policy.enabled ? " ax-toggle-on" : ""}`}
            onClick={toggle}
          />
        ) : (
          <Badge variant="outline">{policy.enabled ? t("adaptiveRoutingOnBadge") : t("adaptiveRoutingOffBadge")}</Badge>
        )}
      </div>
      <p className="ax-wf-empty">{policy.enabled ? t("adaptiveRoutingOnHint") : t("adaptiveRoutingOffHint")}</p>
    </div>
  );
}
