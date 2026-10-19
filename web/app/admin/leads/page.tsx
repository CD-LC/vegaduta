"use client";

import { useState } from "react";
import { useRequireScope } from "../../components/useRequireScope";
import AppShell from "../../components/AppShell";
import { usePageReady } from "../../components/RouteTransition";
import LeadsPanel from "../../components/leads/LeadsPanel";
import PageHeader from "../../components/ax/PageHeader";
import { useTranslations } from "../../lib/i18n";

// Leads CRM (backend V337) - pipeline board + table, lead drawer with
// activity timeline, CSV import/export. Same page-shell split as
// /admin/contacts: the page owns the scope guard and readiness signal, the
// panel owns all interaction state.
export default function AdminLeadsPage() {
  const t = useTranslations("leadsPage");
  const allowed = useRequireScope("MANAGE_LEADS");
  const [loaded, setLoaded] = useState(false);

  usePageReady(loaded || !allowed);

  if (!allowed) return null;

  return (
    <AppShell eyebrow={t("pageEyebrow")}>
      <PageHeader eyebrow={t("headerEyebrow")} title={t("pageTitle")} subtitle={t("pageSubtitle")} />
      <LeadsPanel onLoaded={() => setLoaded(true)} />
    </AppShell>
  );
}
