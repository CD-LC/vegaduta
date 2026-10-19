"use client";

import AppShell from "../../components/AppShell";
import ApprovalsPanel from "../../components/ApprovalsPanel";
import { useRequireScope } from "../../components/useRequireScope";
import { useTranslations } from "../../lib/i18n";

// Approvals home (granular RBAC, 2026-09-23): restored as a REAL page after
// the #554 redirect-to-maintenance era. A delegated MANAGE_APPROVALS holder
// can't reach /admin/maintenance at all (it's still role-gated to
// tenant-admin), so without this page their scope would have no surface -
// the panel itself is the same component maintenance embeds, fed the
// caller's permission rather than the blanket role flag.
export default function AdminApprovalsPage() {
  const allowed = useRequireScope("MANAGE_APPROVALS");
  const t = useTranslations("approvalsPanel");
  const tg = useTranslations("f2GapsAdmin");

  if (!allowed) return null;

  return (
    <AppShell title={t("title")} eyebrow={tg("eyebrow")}>
      <ApprovalsPanel isAdmin={allowed} />
    </AppShell>
  );
}
