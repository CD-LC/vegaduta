"use client";

import { Suspense, useCallback, useState } from "react";
import { useSearchParams } from "next/navigation";
import AppShell from "../../components/AppShell";
import PageHeader from "../../components/ax/PageHeader";
import TurnInspector from "../../components/turns/TurnInspector";
import SessionTurns from "../../components/turns/SessionTurns";
import { useTranslations } from "../../lib/i18n";

/**
 * Turn timeline (architecture bet 10; ADR-0021). Read-only view of recorded agent turns from the turn journal
 * (ADR-0020). Deep links:
 *   /agents/turns?agentId=...&turnId=...                one turn
 *   /agents/turns?agentId=...&sessionId=...             the session's turns; the newest opens
 *   /agents/turns?agentId=...&sessionId=...&turnId=...  that turn, with the session's list beside it
 */
function TurnsInner() {
  const t = useTranslations("turnInspector");
  const params = useSearchParams();
  const agentId = params.get("agentId") ?? "";
  const sessionId = params.get("sessionId") ?? "";
  const [selected, setSelected] = useState<{ agentId: string; turnId: string }>({
    agentId,
    turnId: params.get("turnId") ?? "",
  });
  const select = useCallback((a: string, turnId: string) => setSelected({ agentId: a, turnId }), []);
  return (
    <AppShell eyebrow={t("eyebrow")}>
      <PageHeader title={t("title")} subtitle={t("subtitle")} />
      <div className="space-y-6">
        <SessionTurns initialAgentId={agentId} initialSessionId={sessionId} selectedTurnId={selected.turnId} onSelect={select} />
        {/* Remounted per turn: the inspector loads its deep link once, on mount. */}
        <TurnInspector key={`${selected.agentId}:${selected.turnId}`} initialAgentId={selected.agentId} initialTurnId={selected.turnId} />
      </div>
    </AppShell>
  );
}

export default function TurnsPage() {
  return (
    <Suspense fallback={null}>
      <TurnsInner />
    </Suspense>
  );
}
