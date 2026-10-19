"use client";

import { useEffect, useState } from "react";
import AppShell from "../../components/AppShell";
import PageHeader from "../../components/ax/PageHeader";
import ScimTokensTable from "../../components/sso/ScimTokensTable";
import SsoConnectionForm from "../../components/sso/SsoConnectionForm";
import { useRequireScope } from "../../components/useRequireScope";
import { usePageReady } from "../../components/RouteTransition";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "../../components/ui/tabs";
import { useTranslations } from "../../lib/i18n";

// Per-tenant BYO-IdP SSO and SCIM provisioning tokens (TenantSsoController). Every endpoint
// there is gated by MANAGE_TENANT_SETTINGS.
export default function AdminSsoPage() {
  const allowed = useRequireScope("MANAGE_TENANT_SETTINGS");
  const t = useTranslations("privacySso");
  const [loaded, setLoaded] = useState(false);
  const [tab, setTab] = useState("connection");
  useEffect(() => setLoaded(true), []);
  usePageReady(loaded || !allowed);

  if (!allowed) return null;

  return (
    <AppShell eyebrow={t("eyebrow")}>
      <PageHeader title={t("ssoTitle")} subtitle={t("ssoSubtitle")} />
      <Tabs value={tab} onValueChange={setTab}>
        <TabsList>
          <TabsTrigger value="connection">{t("tab_connection")}</TabsTrigger>
          <TabsTrigger value="scim">{t("tab_scim")}</TabsTrigger>
        </TabsList>
        <TabsContent value="connection">
          <SsoConnectionForm />
        </TabsContent>
        <TabsContent value="scim">
          <ScimTokensTable />
        </TabsContent>
      </Tabs>
    </AppShell>
  );
}
