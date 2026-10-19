// Small formatting helpers shared by the agentic-commerce console panels.

import { Badge } from "../../components/ui/badge";

export function fmtTime(iso: string | null | undefined): string {
  return iso ? new Date(iso).toLocaleString() : "—";
}

export function statusBadge(status: string) {
  if (["ACTIVE", "AUTHORIZED", "CAPTURED", "SETTLED"].includes(status)) {
    return <Badge variant="default">{status}</Badge>;
  }
  if (["REVOKED", "DISABLED", "REJECTED", "REFUNDED"].includes(status)) {
    return <Badge variant="destructive">{status}</Badge>;
  }
  return <Badge variant="secondary">{status}</Badge>;
}

export function csvList(raw: string): string[] | null {
  const items = raw
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean);
  return items.length ? items : null;
}
