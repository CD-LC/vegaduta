"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { Loader2 } from "lucide-react";
import { api } from "../../lib/api";
import { useAuth } from "../AuthProvider";
import { UpgradeLock } from "../ui/upgrade-lock";
import { useTranslations } from "../../lib/i18n";

// Per-agent content guardrails — the picker for GET/PATCH
// /api/agents/{id}/guardrails.
//
// That endpoint pair has always returned exactly what a checkbox list with
// upgrade locks needs (enabled / available / locked / descriptions), and until
// now nothing rendered it: the only way a customer could turn a guardrail on
// was curl, so in practice every agent ran with the default empty set and no
// content filtering at all.
//
// Self-contained on purpose (own fetch, own busy/error state) rather than
// threaded through AgentWorkbench's already very large state surface. It takes
// `locked` from the parent because the edit-lock rule is a per-control
// obligation in that file, not something a child can infer.
//
// Changing the set needs MANAGE_GUARDRAILS (the PATCH is gated on it, like the
// agent's procedures and tool policies): without it the card is read-only and
// says who can change it, rather than surfacing a bare 403.

const ORDER = ["profanity", "jailbreak", "pii", "policy_violation", "toxicity", "custom"] as const;

/**
 * Display copy per guardrail. `where` is deliberately specific: these do NOT
 * all apply in both directions, and a checkbox that silently does nothing on
 * the path a customer cares about is the exact failure this whole change is
 * fixing. Each line below is what AgentChatService's INPUT_PATH_GUARDRAILS /
 * OUTPUT_PATH_GUARDRAILS actually contain, not a hopeful summary.
 */
const COPY: Record<string, { title: string; blurb: string; where: string }> = {
  profanity: {
    title: "Profanity",
    blurb: "Offensive language, matched against a built-in keyword list.",
    where: "Messages and replies",
  },
  jailbreak: {
    title: "Jailbreak attempts",
    blurb: "Common ways people try to talk an agent out of its instructions.",
    where: "Messages only — an agent discussing these phrases legitimately shouldn't trip it",
  },
  pii: {
    title: "Personal data",
    blurb: "Emails, phone numbers, card and ID numbers on their way out.",
    where: "Replies only — people type their own details into support chats all day",
  },
  policy_violation: {
    title: "Policy violations",
    blurb: "Violence, illegal activity and hate speech.",
    where: "Messages and replies",
  },
  toxicity: {
    title: "Toxic tone",
    blurb: "Hostility, abuse and threats, judged by a model rather than a word list.",
    where:
      "Replies only, and only on Careful, Rigorous or Planned — it needs a model call that Fast can't make mid-stream",
  },
  custom: {
    title: "Your workspace rules",
    blurb: "The patterns written on the Guardrails screen, applied to this agent's conversations too.",
    where: "Blocks on both; redacts replies only",
  },
};

interface GuardrailsResponse {
  enabledGuardrails: string[];
  availableGuardrails: string[];
  lockedGuardrails: string[];
  guardrailDescriptions: Record<string, string>;
}

export default function AgentGuardrailsCard({
  agentId,
  locked,
  editLockedTooltip,
}: {
  agentId: string;
  locked: boolean;
  editLockedTooltip?: string;
}) {
  const tHC = useTranslations("hcComponentsAgentsAgentGuardrailsCard");
  const [data, setData] = useState<GuardrailsResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const { hasPermission } = useAuth();
  const canManage = hasPermission("MANAGE_GUARDRAILS");

  const load = useCallback(async (): Promise<void> => {
    const response: GuardrailsResponse = await api.getAgentGuardrails(agentId);
    setData(response);
  }, [agentId]);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    api
      .getAgentGuardrails(agentId)
      .then((response: GuardrailsResponse) => {
        if (!cancelled) setData(response);
      })
      .catch((e: unknown) => {
        if (!cancelled) setError(e instanceof Error ? e.message : "Could not load this agent's guardrails.");
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [agentId]);

  const enabled = new Set(data?.enabledGuardrails || []);
  const lockedByPlan = new Set(data?.lockedGuardrails || []);

  async function toggle(id: string) {
    if (!data || busyId || locked || !canManage || lockedByPlan.has(id)) return;
    const next = new Set(enabled);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    setBusyId(id);
    setError(null);
    try {
      // The PATCH replaces the whole set and returns the fresh state, so the
      // response is applied directly rather than re-fetching.
      const updated: GuardrailsResponse = await api.setAgentGuardrails(agentId, Array.from(next));
      setData(updated);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "Could not change that guardrail.");
      // Re-read rather than trusting the optimistic set: a 403 means the server
      // kept its own version of the truth and ours is now wrong.
      await load().catch((): void => undefined);
    } finally {
      setBusyId(null);
    }
  }

  return (
    <div className="mb-4">
      <div className="mb-1 text-xs text-muted-foreground">{tHC("s5")}</div>

      {loading && (
        <div className="flex items-center gap-2 text-xs text-muted-foreground">
          <Loader2 className="size-3.5 animate-spin" aria-hidden="true" />
          {tHC("s4")}
        </div>
      )}

      {!loading && error && <div className="ax-kb-error mb-2">{error}</div>}

      {!loading && data && (
        <>
          {!canManage && (
            <p className="mb-2 text-xs text-muted-foreground" data-testid="agent-guardrails-read-only">
              {tHC("s3")}
            </p>
          )}
          <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
            {ORDER.filter((id) => COPY[id]).map((id) => {
              const isLockedByPlan = lockedByPlan.has(id);
              const isOn = enabled.has(id);
              const disabled = locked || !canManage || isLockedByPlan || busyId !== null;
              const row = (
                <label
                  key={id}
                  data-testid={`agent-guardrail-${id}`}
                  title={locked ? editLockedTooltip : undefined}
                  className={[
                    "flex items-start gap-2 rounded-lg border px-3 py-2 transition-colors",
                    isOn ? "border-primary bg-primary/10" : "border-border bg-card",
                    disabled ? "opacity-60" : "cursor-pointer hover:bg-accent",
                  ].join(" ")}
                >
                  <input
                    type="checkbox"
                    className="mt-1"
                    checked={isOn}
                    disabled={disabled}
                    onChange={() => void toggle(id)}
                  />
                  <span className="min-w-0">
                    <span className="block text-sm font-medium">{COPY[id].title}</span>
                    <span className="mt-0.5 block text-xs text-muted-foreground">{COPY[id].blurb}</span>
                    <span className="mt-0.5 block text-[11px] text-muted-foreground">{COPY[id].where}</span>
                  </span>
                </label>
              );
              return isLockedByPlan ? (
                <UpgradeLock
                  key={id}
                  className="w-full [&>*]:w-full"
                  reason={`${COPY[id].title} is included on a higher plan.`}
                >
                  {row}
                </UpgradeLock>
              ) : (
                row
              );
            })}
          </div>

          <p className="mt-2 text-xs text-muted-foreground">
            {tHC("s2")}{" "}
            <Link href="/admin/guardrails" className="underline underline-offset-2">
              Guardrails
            </Link>{" "}
            {tHC("s1")}
          </p>
        </>
      )}
    </div>
  );
}
