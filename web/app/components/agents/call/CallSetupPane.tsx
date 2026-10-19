"use client";

// Presentational pane for call setup and settings: the preflight (every
// missing piece gets one plain line and at most one action, renders nothing
// when nothing is missing), the mic test, on-device turn notices and the
// fallback policy selector. No state of its own.

import type { ComponentProps } from "react";
import CallPreflight from "./CallPreflight";
import MicTestPanel from "../CallPanel/MicTestPanel";
import EdgeTurnNotices from "../CallPanel/EdgeTurnNotices";
import FallbackAskPrompt from "../CallPanel/FallbackAskPrompt";
import EdgeFallbackPolicyRow from "../CallPanel/EdgeFallbackPolicyRow";

interface CallSetupPaneProps {
  preflight: ComponentProps<typeof CallPreflight>;
  /** One quiet line after a Download finished; already gated on idle by the caller. */
  sttReadyNotice: string | null;
  micTest: ComponentProps<typeof MicTestPanel>;
  edgeNotices: ComponentProps<typeof EdgeTurnNotices>;
  fallbackAsk: ComponentProps<typeof FallbackAskPrompt>;
  policyRow: ComponentProps<typeof EdgeFallbackPolicyRow>;
}

export default function CallSetupPane({
  preflight,
  sttReadyNotice,
  micTest,
  edgeNotices,
  fallbackAsk,
  policyRow,
}: CallSetupPaneProps) {
  return (
    <>
      <CallPreflight {...preflight} />
      {sttReadyNotice && (
        <p className="text-xs text-muted-foreground" role="status">
          {sttReadyNotice}
        </p>
      )}
      <MicTestPanel {...micTest} />
      <EdgeTurnNotices {...edgeNotices} />
      <FallbackAskPrompt {...fallbackAsk} />
      <EdgeFallbackPolicyRow {...policyRow} />
    </>
  );
}
