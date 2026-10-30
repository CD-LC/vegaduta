"use client";

import AdminShell from "../components/AdminShell";
import { PageHint, InfoNote } from "../components/sections/common";
import {
  PolicyPacksPanel,
  ThresholdsPanel,
  ReviewQueuePanel,
  BlocklistsPanel,
} from "../components/sections/moderation";
import { OutboundPolicyPanel } from "../components/sections/outbound-policy";
import { useTranslations } from "../../lib/i18n";

// Safety & Moderation - layered, human-reviewable content moderation, backed
// by ModerationController (/api/platform/moderation/**). Every control writes
// the same platform_setting keys the live guardrail engine reads, so changes
// take effect on the next inbound message.
export default function SafetyModerationPage() {
  const tHC = useTranslations("hcPlatformadminSafetyPage");
  return (
    <AdminShell title={tHC("s3")}>
      <PageHint>{tHC("s2")}</PageHint>

      <InfoNote>{tHC("s1")}</InfoNote>

      <PolicyPacksPanel delay={0} />
      <ThresholdsPanel delay={0.03} />
      <ReviewQueuePanel delay={0.06} />
      <BlocklistsPanel delay={0.09} />
      <OutboundPolicyPanel delay={0.12} />
    </AdminShell>
  );
}
