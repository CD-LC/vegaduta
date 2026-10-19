"use client";

import { useEffect, useState } from "react";
import { api } from "../../lib/api";
import { useRequireScope } from "../../components/useRequireScope";
import AppShell from "../../components/AppShell";
import { usePageReady } from "../../components/RouteTransition";
import ContactsPanel from "../../components/ContactsPanel";
import PageHeader from "../../components/ax/PageHeader";
import { useTranslations } from "../../lib/i18n";

// Contacts & request mapping (V238) - the dedicated inbound-mapping surface
// the maintainer asked for: one page that shows every channel's senders as contacts,
// groups them, imports numbers from a sheet, and carries the per-channel
// "does a new sender need approval" switch. Same page-shell split as
// /admin/channels: the page fetches the agent list, the panel owns all
// interaction state.
export default function AdminContactsPage() {
  const t = useTranslations("contactsPage");
  // MANAGE_CHANNELS scope (granular RBAC, 2026-09-23) - contacts/routing is
  // the inbound half of channel administration, same backend permission.
  const allowed = useRequireScope("MANAGE_CHANNELS");
  const [agents, setAgents] = useState([]);
  const [loaded, setLoaded] = useState(false);

  useEffect(() => {
    if (!allowed) return;
    api
      .listAgents()
      .then(setAgents)
      .catch(() => setAgents([]))
      .finally(() => setLoaded(true));
  }, [allowed]);

  usePageReady(loaded || !allowed);

  if (!allowed) return null;

  return (
    <AppShell eyebrow={t("pageEyebrow")}>
      <PageHeader eyebrow={t("headerEyebrow")} title={t("pageTitle")} subtitle={t("pageSubtitle")} />
      <ContactsPanel agents={agents} />
    </AppShell>
  );
}
