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

// Safety & Moderation - layered, human-reviewable content moderation, backed
// by ModerationController (/api/platform/moderation/**). Every control writes
// the same platform_setting keys the live guardrail engine reads, so changes
// take effect on the next inbound message.
export default function SafetyModerationPage() {
  return (
    <AdminShell title="Safety & Moderation">
      <PageHint>
        Layered, human-reviewable content moderation - never a single model deciding &ldquo;illegal.&rdquo; The pattern
        and semantic guardrail layers run inside core on every request; the controls below tune them and let a
        reviewer decide what they flag.
      </PageHint>

      <InfoNote>
        CSAM detection carries legal reporting obligations and must be designed with counsel, so that pack is held for
        legal review and cannot be enabled from here - the server refuses it. Matches and reports go to a restricted
        audit trail, never general logs.
      </InfoNote>

      <PolicyPacksPanel delay={0} />
      <ThresholdsPanel delay={0.03} />
      <ReviewQueuePanel delay={0.06} />
      <BlocklistsPanel delay={0.09} />
      <OutboundPolicyPanel delay={0.12} />
    </AdminShell>
  );
}
