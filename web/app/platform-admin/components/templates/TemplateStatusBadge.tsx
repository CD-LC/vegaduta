"use client";

import { Badge } from "../ui/badge";
import type { TemplateStatus } from "../../templates/types";

// Lifecycle badge (DRAFT/PENDING/APPROVED/REJECTED/WITHDRAWN) - same
// outline-Badge + semantic Tailwind-token color pairing already used
// throughout web-admin (see AdminShell's PLATFORM HEALTHY/OFFLINE pill and
// ui/alert.tsx's success/warning variants) rather than raw hex values.
const STATUS_STYLE: Record<string, string> = {
  DRAFT: "border-border bg-secondary/50 text-muted-foreground",
  // text-*-600 measured ~2.95-3.43:1 on this badge's own light bg tint at
  // badge text size - under the 4.5:1 AA minimum. -700 clears ~4.6-5:1 on
  // the same tint while dark mode (already AA) is untouched.
  PENDING: "border-[var(--warning-border)] bg-[var(--warning-bg)] text-[var(--warning-text)]",
  APPROVED: "border-[var(--success-border)] bg-[var(--success-bg)] text-[var(--success-text)]",
  REJECTED: "border-destructive/30 bg-destructive/10 text-destructive",
  WITHDRAWN: "border-border bg-secondary text-muted-foreground",
};

export default function TemplateStatusBadge({ status }: { status?: TemplateStatus | string }) {
  const key = status || "";
  const cls = STATUS_STYLE[key] || "border-border text-muted-foreground";
  return (
    <Badge variant="outline" className={cls}>
      {key || "UNKNOWN"}
    </Badge>
  );
}
