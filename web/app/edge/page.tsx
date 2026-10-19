"use client";

import AppShell from "../components/AppShell";
import PageHeader from "../components/ax/PageHeader";
import EdgeConsole from "../components/edge/EdgeConsole";
import EdgeRelaySection from "../components/edge/EdgeRelaySection";
import { SetupHint } from "../components/setup/SetupLinks";
import { useTranslations } from "../lib/i18n";
import { EDGE_BRANDING } from "../lib/edge/branding";

/**
 * The "VegaDūta Edge" console route: device verdict + mode master
 * toggle, the on-device model marketplace (real downloads, visible
 * progress), installed-model management, and the privacy band. All logic
 * lives in components/edge/EdgeConsole.tsx; this file is only the route
 * chrome (AppShell + PageHeader), matching the clips/files page shape.
 * The nav-rail entry is owned by the AppShell agent, not this file.
 */
export default function EdgePage() {
  const t = useTranslations("edge");
  const te = useTranslations("edgePage");
  return (
    <AppShell eyebrow={te("eyebrow")}>
      <PageHeader title={EDGE_BRANDING.name} subtitle={t("subtitle")} />
      <SetupHint
        className="mb-3"
        lead={te("setupHint")}
        targets={["providers", "agents", "workflows"]}
      />
      <EdgeConsole />
      <div className="mt-4">
        <EdgeRelaySection />
      </div>
    </AppShell>
  );
}
