"use client";

import { Suspense } from "react";
import { useSearchParams } from "next/navigation";
import AppShell from "../../components/AppShell";
import ApprovalsPanel from "../../components/ApprovalsPanel";
import { useRequireScope } from "../../components/useRequireScope";
import { useTranslations } from "../../lib/i18n";
import { AGE_FILTERS, type AgeFilter } from "../../components/approvals/approvalsQueueModel";

// Approvals home (granular RBAC, 2026-09-23): restored as a REAL page after
// the #554 redirect-to-maintenance era. A delegated MANAGE_APPROVALS holder
// can't reach /admin/maintenance at all (it's still role-gated to
// tenant-admin), so without this page their scope would have no surface -
// the panel itself is the same component maintenance embeds, fed the
// caller's permission rather than the blanket role flag.
//
// Deep links (notifications, agent pages): ?id=<requestId> opens one request,
// ?status=PENDING|APPROVED|REJECTED|EXPIRED|...|all, ?agent=<agentId>, ?tool=<name>,
// ?age=newer1h|older1h|older24h|older7d, ?rules=1 opens the tool-rule form.
function ApprovalsInner() {
  const allowed = useRequireScope("MANAGE_APPROVALS");
  const t = useTranslations("approvalsPanel");
  const tg = useTranslations("f2GapsAdmin");
  const params = useSearchParams();

  if (!allowed) return null;

  const status = params.get("status");
  const age = params.get("age");
  return (
    <AppShell title={t("title")} eyebrow={tg("eyebrow")}>
      <ApprovalsPanel
        isAdmin={allowed}
        initialRequestId={params.get("id")}
        initialStatus={status == null ? null : status === "all" ? "" : status.toUpperCase()}
        initialFilters={{
          agent: params.get("agent") ?? "",
          tool: params.get("tool") ?? "",
          age: (AGE_FILTERS as readonly string[]).includes(age ?? "") ? (age as AgeFilter) : "",
        }}
        initialShowRules={params.get("rules") === "1"}
      />
    </AppShell>
  );
}

export default function AdminApprovalsPage() {
  return (
    <Suspense fallback={null}>
      <ApprovalsInner />
    </Suspense>
  );
}
