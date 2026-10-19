"use client";

import { edgeEnabled, setEdgeFallbackPolicy, type EdgeFallbackPolicy } from "../../../lib/edge/capabilities";
import { Select } from "../../ui/select";


interface EdgeFallbackPolicyRowProps {
  fallbackPolicy: EdgeFallbackPolicy;
  edgeOptInActive: boolean;
  setFallbackPolicyState: (policy: EdgeFallbackPolicy) => void;
}

export default function EdgeFallbackPolicyRow({ fallbackPolicy, edgeOptInActive, setFallbackPolicyState }: EdgeFallbackPolicyRowProps) {
  return (
    <>
      {/* Client-side preference for what happens at the AUTOMATIC fallback
          points in trySendTurnLocally (STT not cached, STT declined,
          routeTurn declined) - never the server-policy EDGE_ONLY refusal,
          which always outranks this and is unaffected by it. Per-browser
          (localStorage), see capabilities.ts's getEdgeFallbackPolicy. */}
      {edgeEnabled() && (
        <div className="flex items-center gap-2 text-[11px] text-muted-foreground">
          <label htmlFor="edge-fallback-policy">If on-device can&apos;t handle this:</label>
          <Select
            id="edge-fallback-policy"
            className="w-auto min-w-56 [&_button]:h-7 [&_button]:text-[11px]"
            value={fallbackPolicy}
            disabled={!edgeOptInActive}
            onValueChange={(v) => {
              const value = v as EdgeFallbackPolicy;
              setFallbackPolicyState(value);
              setEdgeFallbackPolicy(value);
            }}
            options={[
              { value: "auto", label: "Fall back automatically (default)" },
              { value: "ask", label: "Ask me first" },
              { value: "never", label: "Never fall back (show an error instead)" },
            ]}
          />
          {/* Fix #5 (P1): trySendTurnLocally's very first gate is `if
              (!edgeOnly && !getEdgeOptIn()) return { outcome: "server-audio"
              }` - every fallback point this selector governs is unreachable
              whenever on-device replies are off for this browser, so say so
              instead of leaving the control looking live but inert. */}
          {!edgeOptInActive && <span>(on-device replies are currently off for this browser)</span>}
        </div>
      )}
    </>
  );
}
