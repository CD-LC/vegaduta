"use client";

import AdminShell from "../components/AdminShell";
import { PageHint, InfoNote } from "../components/sections/common";
import { PlatformSettingsSection } from "../components/sections/platform-settings";

// Platform Settings - the single "every knob is configurable" surface. Every
// admin-editable platform_setting row core exposes is listed, searchable, and
// editable here; writes apply on the very next read with no redeploy. The
// rate-limit subset also stays visible on Abuse & Rate Control, and the
// detector's thresholds (migration V120) are edited here while Fraud &
// Anomaly shows them read-only.
export default function PlatformSettingsPage() {
  return (
    <AdminShell title="Platform Settings">
      <PageHint>
        Every runtime knob the platform exposes - rate limits, detector thresholds, quotas, feature switches - editable
        in one place. Changes take effect on the very next use.
      </PageHint>

      <InfoNote>
        Prefer the typed editors when one exists - Budgets &amp; Limits, LLM &amp; Providers, Demo Sandbox, Tenants &amp;
        Users, Safety &amp; Moderation - since they validate ranges server-side. This page is the escape hatch for every
        key that has no dedicated screen.
      </InfoNote>

      <PlatformSettingsSection />
    </AdminShell>
  );
}
