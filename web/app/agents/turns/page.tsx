"use client";

import { Suspense } from "react";
import { useSearchParams } from "next/navigation";
import AppShell from "../../components/AppShell";
import PageHeader from "../../components/ax/PageHeader";
import TurnInspector from "../../components/turns/TurnInspector";
import { useTranslations } from "../../lib/i18n";

/**
 * Turn inspector (architecture bet 10, first slice; ADR-0021). Read-only view of one recorded
 * agent turn from the turn journal (ADR-0020). Deep link: /agents/turns?agentId=...&turnId=...
 */
function TurnsInner() {
  const t = useTranslations("turnInspector");
  const params = useSearchParams();
  const agentId = params.get("agentId") ?? "";
  const turnId = params.get("turnId") ?? "";
  return (
    <AppShell eyebrow={t("eyebrow")}>
      <PageHeader title={t("title")} subtitle={t("subtitle")} />
      <TurnInspector initialAgentId={agentId} initialTurnId={turnId} />
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
