"use client";

import AdminShell from "../components/AdminShell";
import { PageHint, InfoNote } from "../components/sections/common";
import { PlatformSettingsSection } from "../components/sections/platform-settings";
import { useTranslations } from "../../lib/i18n";

// Platform Settings - the single "every knob is configurable" surface. Every
// admin-editable platform_setting row core exposes is listed, searchable, and
// editable here; writes apply on the very next read with no redeploy. The
// rate-limit subset also stays visible on Abuse & Rate Control, and the
// detector's thresholds (migration V120) are edited here while Fraud &
// Anomaly shows them read-only.
export default function PlatformSettingsPage() {
  const tHC = useTranslations("hcPlatformadminSettingsPage");
  return (
    <AdminShell title={tHC("s3")}>
      <PageHint>{tHC("s2")}</PageHint>

      <InfoNote>{tHC("s1")}</InfoNote>

      <PlatformSettingsSection />
    </AdminShell>
  );
}
