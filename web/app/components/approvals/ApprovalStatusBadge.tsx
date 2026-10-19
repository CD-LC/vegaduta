"use client";

import { Badge } from "../ui/badge";
import { statusState } from "./approvalsModel";

/** Status pill on the shared `state` badge variant; the label is the status text so colour is never the only cue. */
export default function ApprovalStatusBadge({ status }: { status: string }) {
  return (
    <Badge variant="state" state={statusState(status)} data-status={status}>
      {status.replace(/_/g, " ")}
    </Badge>
  );
}
