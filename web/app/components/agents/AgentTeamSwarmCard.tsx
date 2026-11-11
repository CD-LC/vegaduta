"use client";

import { useEffect, useMemo, useState } from "react";
import { Loader2 } from "lucide-react";
import { api } from "../../lib/api";
import { useTranslations } from "../../lib/i18n";
import {
  SWARM_LIMITS,
  TEAM_PRESETS,
  applyPreset,
  planPreset,
  swarmLimitsHint,
  type ApplyResult,
  type TeamPreset,
} from "../../lib/teamSwarm";
import { Badge } from "../ui/badge";
import { Button } from "../ui/button";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "../ui/alert-dialog";

// Team / Swarm card. A swarm is an agent with 2+ collaborators; this card makes
// that discoverable and offers preset teams. It reuses the existing endpoints
// (createAgent, setAgentCollaborators) and leaves the Collaborators card in
// "Caching, sharing & knowledge" untouched. Saved agents only (the parent
// renders a SavedOnlyStub for drafts).

const PRESET_KEYS: Record<TeamPreset["id"], { title: string; blurb: string }> = {
  research: { title: "teamSwarmPresetResearch", blurb: "teamSwarmPresetResearchBlurb" },
  review: { title: "teamSwarmPresetReview", blurb: "teamSwarmPresetReviewBlurb" },
  triage: { title: "teamSwarmPresetTriage", blurb: "teamSwarmPresetTriageBlurb" },
  modernization: { title: "teamSwarmPresetModernization", blurb: "teamSwarmPresetModernizationBlurb" },
};

interface LeaderAgent {
  id: string;
  name: string;
  providerName?: string;
  model?: string | null;
  experienceMode?: string | null;
  collaboratorAgentIds?: string[] | null;
}

export default function AgentTeamSwarmCard({
  agent,
  locked,
  editLockedTooltip,
  onSaved,
}: {
  agent: LeaderAgent;
  locked: boolean;
  editLockedTooltip?: string;
  onSaved?: (updated: unknown) => void;
}) {
  const t = useTranslations("agentWorkbench");
  const [agents, setAgents] = useState<{ id: string; name: string }[] | null>(null);
  const [loadFailed, setLoadFailed] = useState(false);
  const [reloadTick, setReloadTick] = useState(0);
  const [pending, setPending] = useState<TeamPreset | null>(null);
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<ApplyResult | null>(null);

  useEffect(() => {
    let cancelled = false;
    api
      .listAgents()
      .then((list: unknown) => {
        if (cancelled) return;
        setLoadFailed(false);
        setAgents(
          Array.isArray(list) ? list.map((a: { id: string; name: string }) => ({ id: a.id, name: a.name })) : []
        );
      })
      .catch(() => {
        if (!cancelled) setLoadFailed(true);
      });
    return () => {
      cancelled = true;
    };
  }, [agent.id, reloadTick]);

  const memberIds = useMemo(() => agent.collaboratorAgentIds ?? [], [agent.collaboratorAgentIds]);
  const members = useMemo(
    () => memberIds.map((id) => ({ id, name: agents?.find((a) => a.id === id)?.name ?? null })),
    [memberIds, agents]
  );
  const hint = swarmLimitsHint(memberIds.length);
  const plan = useMemo(
    () => (pending && agents ? planPreset(pending, agent, agents, memberIds) : null),
    [pending, agents, agent, memberIds]
  );

  async function confirmApply() {
    if (!plan || busy || locked) return;
    setBusy(true);
    setResult(null);
    let lastUpdated: unknown = null;
    const r = await applyPreset(
      plan,
      agent.id,
      {
        providerName: agent.providerName,
        model: agent.model || undefined,
        experienceMode: agent.experienceMode || undefined,
      },
      {
        createAgent: (payload) => api.createAgent(payload),
        setCollaborators: async (id, ids) => {
          lastUpdated = await api.setAgentCollaborators(id, ids);
        },
      }
    );
    setResult(r);
    setBusy(false);
    setPending(null);
    setReloadTick((n) => n + 1);
    // Created agents change the roster even when the link step failed.
    if (r.ok) onSaved?.(lastUpdated);
    else if (r.created.length > 0) onSaved?.(null);
  }

  function failureText(r: ApplyResult): string {
    const created = r.created.map((c) => c.name).join(", ");
    if (r.linkError) return t("teamSwarmErrLink", { error: r.linkError, created });
    const f = r.failed[0];
    return t(r.created.length > 0 ? "teamSwarmErrCreatePartial" : "teamSwarmErrCreate", {
      name: f?.name ?? "",
      error: f?.error ?? "",
      created,
    });
  }

  return (
    <section
      aria-label={t("teamSwarmTitle")}
      data-testid="agent-team-swarm-card"
      title={locked ? editLockedTooltip : undefined}
    >
      <p className="text-[12.5px] text-muted-foreground">{t("teamSwarmIntro1")}</p>
      <p className="mt-1 text-[12.5px] text-muted-foreground">{t("teamSwarmIntro2")}</p>

      <div className="mt-3 rounded-lg border border-border bg-background px-3 py-2">
        <div className="flex flex-wrap items-center gap-2">
          <Badge variant={hint.active ? "default" : "secondary"} data-testid="team-swarm-state">
            {hint.active ? t("teamSwarmActive", { count: hint.members }) : t("teamSwarmNotTeam")}
          </Badge>
          {!hint.active && <span className="text-[12.5px] text-muted-foreground">{t("teamSwarmUnlock")}</span>}
        </div>
        {members.length > 0 && (
          <ul className="mt-2 flex flex-wrap gap-1.5" aria-label={t("teamSwarmMembers")}>
            {members.map((m) => (
              <li key={m.id}>
                <Badge variant="outline" className="max-w-full truncate">
                  {m.name ?? t("teamSwarmUnknownMember")}
                </Badge>
              </li>
            ))}
          </ul>
        )}
        <p className="mt-2 text-[12.5px] text-muted-foreground">{t("teamSwarmEditHint")}</p>
      </div>

      <div className="mt-3 rounded-lg border border-border bg-background px-3 py-2" data-testid="team-swarm-limits">
        <div className="text-sm font-medium text-foreground">{t("teamSwarmLimitsTitle")}</div>
        <ul className="mt-1 list-disc ps-5 text-[12.5px] text-muted-foreground">
          <li>{t("teamSwarmLimitBranches", { max: SWARM_LIMITS.maxParallelBranches })}</li>
          <li>{t("teamSwarmLimitTimeout", { seconds: SWARM_LIMITS.branchTimeoutSeconds })}</li>
          <li>{t("teamSwarmLimitBudget", { usd: SWARM_LIMITS.defaultRunBudgetUsd })}</li>
          <li>{t("teamSwarmLimitTokens", { tokens: SWARM_LIMITS.estTokensPerBranch.toLocaleString("en-US") })}</li>
          {hint.active && (
            <li>
              {t("teamSwarmLimitThisTeam", {
                branches: hint.parallelBranches,
                tokens: hint.estTokensPerCall.toLocaleString("en-US"),
              })}
            </li>
          )}
          {hint.overCap > 0 && <li>{t("teamSwarmLimitOverCap", { max: SWARM_LIMITS.maxParallelBranches })}</li>}
          <li>{t("teamSwarmDepth")}</li>
        </ul>
      </div>

      <div className="mt-3">
        <div className="text-sm font-medium text-foreground">{t("teamSwarmPresetsTitle")}</div>
        {agents === null && !loadFailed && (
          <div className="mt-2 flex items-center gap-2 text-[12.5px] text-muted-foreground">
            <Loader2 className="size-3.5 animate-spin" aria-hidden="true" />
            {t("teamSwarmLoading")}
          </div>
        )}
        {loadFailed && (
          <div role="alert" className="ax-kb-error mt-2">
            {t("teamSwarmLoadError")}
          </div>
        )}
        {agents && (
          <div className="mt-2 grid grid-cols-1 gap-2 sm:grid-cols-3">
            {TEAM_PRESETS.map((p) => (
              <div
                key={p.id}
                className="flex flex-col justify-between gap-2 rounded-md border border-border bg-card px-3 py-2"
              >
                <div>
                  <div className="text-sm font-medium text-foreground">{t(PRESET_KEYS[p.id].title)}</div>
                  <p className="mt-0.5 text-[12.5px] text-muted-foreground">{t(PRESET_KEYS[p.id].blurb)}</p>
                  <p className="mt-1 text-[12px] text-muted-foreground">{p.members.map((m) => m.role).join(", ")}</p>
                </div>
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  disabled={locked || busy}
                  onClick={() => {
                    setResult(null);
                    setPending(p);
                  }}
                >
                  {t("teamSwarmApply")}
                </Button>
              </div>
            ))}
          </div>
        )}
        {busy && (
          <div className="mt-2 flex items-center gap-2 text-[12.5px] text-muted-foreground" role="status">
            <Loader2 className="size-3.5 animate-spin" aria-hidden="true" />
            {t("teamSwarmApplying")}
          </div>
        )}
        {result?.ok && (
          <div role="status" className="mt-2 text-[12.5px] text-foreground" data-testid="team-swarm-success">
            {t("teamSwarmDone", { created: result.created.length, total: result.collaboratorIds?.length ?? 0 })}
          </div>
        )}
        {result && !result.ok && (
          <div role="alert" className="ax-kb-error mt-2" data-testid="team-swarm-failure">
            {failureText(result)}
          </div>
        )}
      </div>

      <AlertDialog
        open={pending !== null}
        onOpenChange={(open) => {
          if (!open && !busy) setPending(null);
        }}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>
              {pending ? t("teamSwarmConfirmTitle", { preset: t(PRESET_KEYS[pending.id].title) }) : ""}
            </AlertDialogTitle>
            <AlertDialogDescription>{t("teamSwarmConfirmBody", { name: agent.name })}</AlertDialogDescription>
          </AlertDialogHeader>
          {plan && (
            <div className="text-[12.5px] text-foreground" data-testid="team-swarm-plan">
              <div className="font-medium">{t("teamSwarmWillCreate", { count: plan.toCreate.length })}</div>
              {plan.toCreate.length > 0 && (
                <ul className="list-disc ps-5">
                  {plan.toCreate.map((m) => (
                    <li key={m.name}>{m.name}</li>
                  ))}
                </ul>
              )}
              {plan.toReuse.length > 0 && (
                <>
                  <div className="mt-2 font-medium">{t("teamSwarmWillReuse", { count: plan.toReuse.length })}</div>
                  <ul className="list-disc ps-5">
                    {plan.toReuse.map((m) => (
                      <li key={m.id}>{m.name}</li>
                    ))}
                  </ul>
                </>
              )}
              <p className="mt-2 text-muted-foreground">
                {t("teamSwarmKeepExisting", { count: memberIds.length, total: plan.collaboratorIds.length })}
              </p>
            </div>
          )}
          <AlertDialogFooter>
            <AlertDialogCancel disabled={busy}>{t("teamSwarmCancel")}</AlertDialogCancel>
            <AlertDialogAction
              disabled={busy}
              onClick={(e) => {
                e.preventDefault();
                void confirmApply();
              }}
            >
              {t("teamSwarmConfirm")}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </section>
  );
}
