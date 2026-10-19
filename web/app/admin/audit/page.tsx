"use client";

import AppShell from "../../components/AppShell";
import AuditLogPanel from "../../components/AuditLogPanel";
import { useRequireScope } from "../../components/useRequireScope";
import { useTranslations } from "../../lib/i18n";

// #909 - pixel-match rebuild of design_handoff_axon's "Admin Audit.dc.html"
// lives inside AuditLogPanel itself (title/subtitle/filters/table); this
// wrapper is unchanged - AppShell already gives the panel a full-width
// `.app-content` area to render into, and `title`/`eyebrow` here only ever
// fed the topbar breadcrumb, not a second in-content heading.
//
// Scope-gated (granular RBAC, 2026-09-23): VIEW_AUDIT_LOG, the same
// permission SecurityConfig requires on /api/audit-log/** - a delegated
// auditor gets this screen without any other admin surface.
export default function AdminAuditPage() {
  const t = useTranslations("adminAuditPage");
  const allowed = useRequireScope("VIEW_AUDIT_LOG");

  if (!allowed) return null;

  return (
    <AppShell title={t("pageTitle")} eyebrow={t("pageEyebrow")}>
      <AuditLogPanel />
    </AppShell>
  );
}
