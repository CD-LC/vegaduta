// Small formatting helpers shared by the agent trust console panels.

import { Badge } from "../../components/ui/badge";
import { useTranslations } from "../../lib/i18n";

export function fmtTime(iso: string | null | undefined): string {
  return iso ? new Date(iso).toLocaleString() : "—";
}

export function statusBadge(status: string) {
  if (status === "ACTIVE" || status === "PUBLISHED") return <Badge variant="default">{status}</Badge>;
  if (status === "REVOKED") return <Badge variant="destructive">{status}</Badge>;
  return <Badge variant="secondary">{status}</Badge>;
}

export function csvList(raw: string): string[] | null {
  const items = raw.split(",").map((s) => s.trim()).filter(Boolean);
  return items.length ? items : null;
}

export function SpendBar({ spent, cap }: { spent: number; cap: number | null | undefined }) {
  const t = useTranslations("adminAgentTrustPage");
  if (cap == null) {
    return <span className="text-xs text-muted-foreground">{t("spendUncapped", { spent })}</span>;
  }
  const pct = cap > 0 ? Math.min(100, Math.round((spent / cap) * 100)) : 0;
  const tone = pct >= 100 ? "bg-red-500" : pct >= 80 ? "bg-amber-500" : "bg-emerald-500";
  return (
    <div className="min-w-44">
      <div className="flex items-baseline justify-between gap-2 text-xs text-muted-foreground">
        <span>{t("spendLabel")}</span>
        <span className="font-mono">{spent} / {cap}</span>
      </div>
      <div
        className="mt-1 h-1.5 w-full overflow-hidden rounded-full bg-secondary"
        role="progressbar"
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={pct}
      >
        <div className={`h-full ${tone}`} style={{ width: `${pct}%` }} />
      </div>
    </div>
  );
}
