import { useEffect, useState } from "react";
// Edge seam: the edge flag decides whether the on-device toggle is
// offered even before the local VLM probe answers.
import { edgeEnabled } from "../../../lib/edge/capabilities";
import { edgeVisionAvailable } from "../../../lib/edge/vision";

/**
 * Edge ("analyze on this device") offer: the toggle is offered when the
 * platform edge flag is on OR the local VLM probe answers yes.
 */
export function useEdgeVisionOffered(): boolean {
  const [edgeOffered, setEdgeOffered] = useState(false);
  useEffect(() => {
    let cancelled = false;
    if (edgeEnabled()) {
      setEdgeOffered(true);
      return undefined;
    }
    edgeVisionAvailable()
      .then((ok) => {
        if (!cancelled && ok) setEdgeOffered(true);
      })
      .catch(() => {
        // Probe failure just means the toggle isn't offered.
      });
    return () => {
      cancelled = true;
    };
  }, []);
  return edgeOffered;
}
