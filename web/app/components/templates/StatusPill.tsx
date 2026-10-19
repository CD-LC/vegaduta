"use client";

import { Badge } from "../ui/badge";
import { statusMeta } from "./categories";
import type { CommunityTemplateStatus } from "../../lib/api";

// A submission's lifecycle status as a .state-pill (via Badge variant="state").
// State is carried by shape + hue, never hue alone; a static dot precedes the
// label so it reads in greyscale too. Pending shows a live (animated) dot -
// it's the one status that is actively "in progress".
export default function StatusPill({ status }: { status: CommunityTemplateStatus }) {
  const meta = statusMeta(status);
  return (
    <Badge variant="state" state={meta.state}>
      <span className={`state-dot${status === "PENDING" ? " state-dot-live" : ""}`} aria-hidden="true" />
      {meta.label}
    </Badge>
  );
}
