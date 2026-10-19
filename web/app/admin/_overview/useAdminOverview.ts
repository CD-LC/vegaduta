"use client";

import { useCallback, useEffect, useState } from "react";
import { api } from "../../lib/api";
import { countActiveLlmKeys } from "../../lib/llmProviders";
import { errorMessage } from "../../lib/errors";
import {
  PLATFORM_CHECKS,
  type AdminStats,
  type AdminSystemStatus,
  type PendingApproval,
  type PlatformKey,
} from "./model";

/**
 * All the data the admin overview shows. Every fetch is independent and
 * best-effort: one failing endpoint never blanks the rest (a guessed 0 on a
 * failed agents fetch is kept out of the first-run routing via agentsKnown).
 */
export function useAdminOverview(isAdmin: boolean) {
  const [stats, setStats] = useState<AdminStats>({
    users: null,
    approvals: null,
    providers: null,
    agents: null,
    workflows: null,
    workflowsActive: null,
    channelConnected: false,
    plan: null,
    onPlatformLlm: null,
  });
  const [loadError, setLoadError] = useState<string | null>(null);
  const [coreReachable, setCoreReachable] = useState(true);
  const [loaded, setLoaded] = useState(false);
  // True only when listAgents() really answered: the stats fall back to 0 on a failed fetch, and a guessed 0 must
  // never send an established workspace to the first-run Build Center.
  const [agentsKnown, setAgentsKnown] = useState(false);
  // "Where are the system metrics you added in dashboard, which showed full
  // ecosystem and other details" - admins never actually see the Dashboard
  // (page.js redirects isAdmin straight here), so the same real telemetry
  // that page's hero surfaces never reached them. Same two components, same
  // api.js calls, just rendered on the page an admin actually lands on.
  const [systemStatus, setSystemStatus] = useState<AdminSystemStatus | null>(null);
  const [systemStatusError, setSystemStatusError] = useState<string | null>(null);
  const [platforms, setPlatforms] = useState<{ key: PlatformKey; label: string; connected: boolean | null }[]>(
    PLATFORM_CHECKS.map((p): { key: PlatformKey; label: string; connected: boolean | null } => ({
      key: p.key,
      label: p.label,
      connected: null,
    }))
  );
  // Real pending-approval items (not just the count) so the overview's
  // "Pending approvals" panel can list actual actions, same posture as
  // ApprovalsPanel.js - id/actionType/summary/requestedBy/createdAt, no
  // placeholder text.
  const [pendingApprovals, setPendingApprovals] = useState<PendingApproval[]>([]);
  // Genuinely computed approval SLA (design's own "4m 12s avg" widget,
  // reimplemented honestly): average (decidedAt - createdAt) across every
  // resolved (APPROVED or REJECTED) request, in seconds. null until at
  // least one request has been resolved - rendered as "no data yet" rather
  // than a fabricated number.
  const [avgApprovalSeconds, setAvgApprovalSeconds] = useState<number | null>(null);
  // Real APPROVED/REJECTED counts (same two listApprovals() calls the SLA
  // computation below already makes) - feeds the approval-outcome donut,
  // real, no fabricated split.
  const [approvalOutcomes, setApprovalOutcomes] = useState<{ approved: number | null; rejected: number | null }>({
    approved: null,
    rejected: null,
  });
  const refresh = useCallback(async () => {
    try {
      const users = await api.listAdminUsers();
      setStats((s) => ({ ...s, users: users.length }));
      setCoreReachable(true);
      setLoadError(null);
    } catch (err) {
      setCoreReachable(false);
      setLoadError(errorMessage(err));
    }
    try {
      const pending = await api.listApprovals("PENDING");
      // The list is capped server-side (newest N), so its length is not the
      // count; the real total comes from X-Total-Count (falls back to length).
      const pendingTotal = await api.countApprovals("PENDING").catch(() => pending.length);
      setStats((s) => ({ ...s, approvals: pendingTotal }));
      setPendingApprovals(pending);
    } catch {
      setStats((s) => ({ ...s, approvals: 0 }));
      setPendingApprovals([]);
    }
    // Approval SLA (real, see state comment above) - fetch both resolved
    // statuses and average the real createdAt/decidedAt gap. Best-effort:
    // an admin with zero resolved requests yet just sees "no data yet".
    try {
      const [approved, rejected, approvedTotal, rejectedTotal] = await Promise.all([
        api.listApprovals("APPROVED"),
        api.listApprovals("REJECTED"),
        api.countApprovals("APPROVED"),
        api.countApprovals("REJECTED"),
      ]);
      // Donut uses the real totals; the SLA average below is over the newest
      // rows each list returns (a recent sample once a tenant exceeds the cap).
      setApprovalOutcomes({ approved: approvedTotal, rejected: rejectedTotal });
      const resolved = [...approved, ...rejected].filter((r) => r.createdAt && r.decidedAt);
      if (resolved.length > 0) {
        const totalSeconds = resolved.reduce((sum, r) => {
          const ms = new Date(r.decidedAt).getTime() - new Date(r.createdAt).getTime();
          return sum + Math.max(0, ms) / 1000;
        }, 0);
        setAvgApprovalSeconds(totalSeconds / resolved.length);
      } else {
        setAvgApprovalSeconds(null);
      }
    } catch {
      setAvgApprovalSeconds(null);
      setApprovalOutcomes({ approved: null, rejected: null });
    }
    // Each of these feeds the setup checklist below and is independently
    // best-effort, same posture as the dashboard's own stat fetches - a
    // profile with the workflow/knowledge module disabled shouldn't blank
    // out unrelated checklist items.
    try {
      const providers: { providerName?: string; status: string }[] = await api.listProviders();
      setStats((s) => ({ ...s, providers: countActiveLlmKeys(providers) }));
    } catch {
      setStats((s) => ({ ...s, providers: 0 }));
    }
    try {
      const agents = await api.listAgents();
      setStats((s) => ({ ...s, agents: agents.length }));
      setAgentsKnown(true);
    } catch {
      setStats((s) => ({ ...s, agents: 0 }));
      setAgentsKnown(false);
    }
    try {
      const workflows: { status?: string }[] = await api.listWorkflows();
      setStats((s) => ({
        ...s,
        workflows: workflows.length,
        workflowsActive: workflows.filter((w) => w.status === "ACTIVE").length,
      }));
    } catch {
      setStats((s) => ({ ...s, workflows: 0, workflowsActive: 0 }));
    }
    // Plan / platform-LLM signal for buildSetupSteps and the first-run card
    // - same GET /api/tenant/settings AgentWorkbench.tsx reads. On failure
    // both stay null ("unknown"): the checklist then omits the provider
    // step and the first-run card omits the "included free" list rather
    // than asserting either for a tenant we can't classify.
    try {
      const settings: { plan?: string | null; usePlatformLlm?: boolean } = await api.getTenantSettings();
      const plan = settings?.plan ?? null;
      setStats((s) => ({ ...s, plan, onPlatformLlm: plan === "FREE" || !!settings?.usePlatformLlm }));
    } catch {
      setStats((s) => ({ ...s, plan: null, onPlatformLlm: null }));
    }
    try {
      const [twilio, meta] = await Promise.all([
        api.getTwilioCredential().catch((): null => null),
        api.getMetaWhatsAppCredential().catch((): null => null),
      ]);
      setStats((s) => ({ ...s, channelConnected: Boolean(twilio || meta) }));
    } catch {
      setStats((s) => ({ ...s, channelConnected: false }));
    }
    try {
      setSystemStatus(await api.getSystemStatus());
      setSystemStatusError(null);
    } catch (err) {
      setSystemStatus(null);
      setSystemStatusError(errorMessage(err));
    }
    // Independent, best-effort per platform - identical posture to the
    // Dashboard's own refresh() - one channel erroring never blanks the rest.
    const platformResults = await Promise.all(
      PLATFORM_CHECKS.map(async (p) => {
        try {
          const value = await p.fetch();
          return { key: p.key, label: p.label, connected: Boolean(value) };
        } catch {
          return { key: p.key, label: p.label, connected: false };
        }
      })
    );
    setPlatforms(platformResults);
    setLoaded(true);
  }, []);

  useEffect(() => {
    // `void`: refresh() settles every failure into its own state.
    if (isAdmin) void refresh();
  }, [isAdmin, refresh]);

  return {
    stats,
    loadError,
    coreReachable,
    loaded,
    agentsKnown,
    systemStatus,
    systemStatusError,
    platforms,
    pendingApprovals,
    avgApprovalSeconds,
    approvalOutcomes,
  };
}
