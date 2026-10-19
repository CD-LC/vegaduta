"use client";

import AppShell from "../../components/AppShell";
import InputRequestsPanel from "../../components/InputRequestsPanel";
import { useRequireScope } from "../../components/useRequireScope";
import { useTranslations } from "../../lib/i18n";

// Workflow input requests (Tier-3): the inbox Human Input nodes write to.
// Same human-in-the-loop scope as the approvals inbox - a workflow pausing
// for structured input is the same "a person must act" surface as a
// pending approval, just with a form instead of approve/reject.
export default function AdminInputRequestsPage() {
  const allowed = useRequireScope("MANAGE_APPROVALS");
  const t = useTranslations("inputRequestsPanel");
  const tg = useTranslations("f2GapsAdmin");

  if (!allowed) return null;

  return (
    <AppShell title={t("queueTitle")} eyebrow={tg("eyebrow")}>
      <InputRequestsPanel />
    </AppShell>
  );
}
