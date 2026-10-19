"use client";

import { Suspense } from "react";
import { useRequireScope } from "../../components/useRequireScope";
import AppShell from "../../components/AppShell";
import GoogleIntegrationPanel from "../../components/GoogleIntegrationPanel";
import { useTranslations } from "../../lib/i18n";

function AdminIntegrationsInner() {
  // MANAGE_INTEGRATIONS scope (granular RBAC, 2026-09-23) - was blanket
  // isAdmin; Google Workspace connection is an integrations surface.
  const allowed = useRequireScope("MANAGE_INTEGRATIONS");
  const t = useTranslations("adminIntegrationsPage");

  if (!allowed) return null;

  return (
    <AppShell eyebrow={t("pageEyebrow")}>
      {/* GoogleIntegrationPanel now renders its own ShadCN PageHeader (the
        * visible "Google Workspace" h1 the e2e suite asserts on) plus the
        * Card/Badge/Button/Alert redesign, so this shell stays a thin
        * wrapper - no more `.ax-intg-screen` scoping div; that class and its
        * scoped overrides in globals.css were removed since nothing else
        * used them (the base `.ax-int-*` classes stay, still consumed by the
        * unrelated workflow-runs pages). */}
      <GoogleIntegrationPanel />
    </AppShell>
  );
}

// GoogleIntegrationPanel reads the ?google=connected|error query param
// GoogleOAuthController#callback's redirect lands with via useSearchParams,
// which Next.js requires a Suspense boundary for - same pattern /playground
// already established.
export default function AdminIntegrationsPage() {
  return (
    <Suspense fallback={null}>
      <AdminIntegrationsInner />
    </Suspense>
  );
}
