"use client";

import { useEffect, useState } from "react";
import AppShell from "../../components/AppShell";
import PageHeader from "../../components/ax/PageHeader";
import DataPrivacySection from "../../components/DataPrivacySection";
import { useRequireScope } from "../../components/useRequireScope";
import { usePageReady } from "../../components/RouteTransition";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "../../components/ui/tabs";
import { useTranslations } from "../../lib/i18n";
import AuditExportTab from "./AuditExportTab";
import ConsentPolicyTab from "./ConsentPolicyTab";
import ContactsTab from "./ContactsTab";
import PurposesTab from "./PurposesTab";
import RetentionTab from "./RetentionTab";

// Scoped data-governance home (granular RBAC, 2026-09-23). Every tab calls an endpoint gated by
// MANAGE_DATA_GOVERNANCE (PrivacyController, data-export, data-deletion), so one guard covers the page.
// Wording: "DPDP-oriented controls" - never "compliant" (docs/compliance/dpdp-obligations.md).
const TABS = ["consent", "retention", "purposes", "contacts", "audit", "workspace"] as const;
type TabId = (typeof TABS)[number];

export default function AdminDataPrivacyPage() {
  const allowed = useRequireScope("MANAGE_DATA_GOVERNANCE");
  const t = useTranslations("privacySso");
  const [loaded, setLoaded] = useState(false);
  const [tab, setTab] = useState<TabId>("consent");
  useEffect(() => setLoaded(true), []);
  usePageReady(loaded || !allowed);

  if (!allowed) return null;

  return (
    <AppShell eyebrow={t("eyebrow")}>
      <PageHeader title={t("privacyTitle")} subtitle={t("privacySubtitle")} />
      <Tabs value={tab} onValueChange={(v) => setTab(v as TabId)}>
        <TabsList className="max-w-full flex-wrap h-auto">
          {TABS.map((id) => (
            <TabsTrigger key={id} value={id}>
              {t(`tab_${id}`)}
            </TabsTrigger>
          ))}
        </TabsList>
        <TabsContent value="consent">{tab === "consent" && <ConsentPolicyTab />}</TabsContent>
        <TabsContent value="retention">{tab === "retention" && <RetentionTab />}</TabsContent>
        <TabsContent value="purposes">{tab === "purposes" && <PurposesTab />}</TabsContent>
        <TabsContent value="contacts">{tab === "contacts" && <ContactsTab />}</TabsContent>
        <TabsContent value="audit">{tab === "audit" && <AuditExportTab />}</TabsContent>
        <TabsContent value="workspace">{tab === "workspace" && <DataPrivacySection />}</TabsContent>
      </Tabs>
    </AppShell>
  );
}
