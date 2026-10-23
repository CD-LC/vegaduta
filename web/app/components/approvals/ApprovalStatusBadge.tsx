"use client";

import { Badge } from "../ui/badge";
import { useTranslations } from "../../lib/i18n";
import { statusState } from "./approvalsModel";
import { displayStatus } from "./approvalsQueueModel";

/**
 * Status pill on the shared `state` badge variant; the label is the status text so colour is never the only cue.
 * Pass `decidedBy` to tell an expired request (rejected by the expiry job) from one a person rejected.
 */
export default function ApprovalStatusBadge({ status, decidedBy }: { status: string; decidedBy?: string | null }) {
  const q = useTranslations("approvalsQueue");
  const shown = displayStatus({ status, decidedBy });
  return (
    <Badge variant="state" state={statusState(shown)} data-status={shown}>
      {q(`status_${shown}`)}
    </Badge>
  );
}
