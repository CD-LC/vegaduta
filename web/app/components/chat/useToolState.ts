import { useEffect, useState } from "react";
import { detectCapabilities, edgeEnabled } from "../../lib/edge/capabilities";
import { listLocalTools } from "../../lib/edge/localTools";
import { fetchEdgeTools, serverToolBlockReason } from "../../lib/edge/toolBridge";
import { getTier1RunModel } from "../../lib/edge/tier1";
import type { ToolState } from "./toolStatus";

/**
 * Tool state for THIS agent, resolved once per agent. null until both halves
 * have settled, so the line appears already correct instead of flickering
 * through a momentarily-wrong "unavailable".
 */
export function useToolState(agentIdProp: string | null | undefined): ToolState | null {
  const [toolState, setToolState] = useState<ToolState | null>(null);
  // Resolve both tool surfaces for the selected agent. Deliberately not part
  // of a turn: the count is a property of the agent and the device, not of
  // whatever is being said, and fetchEdgeTools is already cached per agent per
  // browser session so re-mounting this panel costs no extra request.
  useEffect(() => {
    let cancelled = false;
    const agentId = agentIdProp ?? null;
    // No edge flag means no on-device model runs here at all, so no in-page
    // tool can run either — "0" would be a claim about the registry rather
    // than about this browser, so the panel says nothing instead.
    // Two separate questions, and conflating them is how a count becomes a
    // lie: how many local tools EXIST (listLocalTools), and whether anything
    // could run one (a tier-1 model whose weights are already cached).
    // getTier1RunModel is ready-only — it returns null unless the weights are
    // on the device already, and never starts a download.
    const localPromise: Promise<{ count: number; modelReady: boolean } | null> = edgeEnabled()
      ? detectCapabilities()
          .then(async (caps) => ({
            count: listLocalTools(caps).length,
            modelReady: (await getTier1RunModel(caps)) !== null,
          }))
          .catch((): { count: number; modelReady: boolean } | null => null)
      : Promise.resolve(null);
    // Entitlement first (synchronous, never prompts): asking for tools this
    // session was never allowed to have would turn an ordinary signed-out
    // state into a fetch failure, which is the exact confusion being fixed.
    const block = serverToolBlockReason(agentId);
    const entitled = block === null;
    const serverPromise = entitled ? fetchEdgeTools(agentId) : Promise.resolve(null);
    void Promise.all([localPromise, serverPromise]).then(([local, tools]) => {
      if (cancelled) return;
      if (local == null) {
        setToolState(null);
        return;
      }
      if (!local.modelReady) {
        setToolState({ kind: "needs-model", local: local.count });
        return;
      }
      if (!entitled) {
        // Which refusal it is decides which sentence is true. "Sign in" is
        // wrong advice for someone already signed in with no agent selected.
        setToolState(
          block?.code === "no-agent"
            ? { kind: "no-agent", local: local.count }
            : { kind: "local-only", local: local.count }
        );
      } else if (tools) setToolState({ kind: "both", local: local.count, server: tools.length });
      // Entitled but nothing came back: the surface was asked and did not
      // answer. That is the one case worth naming as unavailable.
      else setToolState({ kind: "server-unavailable", local: local.count });
    });
    return () => {
      cancelled = true;
    };
  }, [agentIdProp]);
  return toolState;
}
