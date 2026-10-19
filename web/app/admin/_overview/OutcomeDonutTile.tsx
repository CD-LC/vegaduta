"use client";

import { ClipboardCheck } from "lucide-react";
import { Card } from "../../components/ui/card";
import { useTranslations } from "../../lib/i18n";
import { Eyebrow } from "./ui";

// Approval outcomes are states, not warnings: pending = the live accent,
// approved = the ok tint, rejected = neutral slate. No amber, no red.
const OUTCOME_COLOR = {
  pending: "var(--accent-500)",
  approved: "var(--state-success)",
  rejected: "var(--tint-neutral-fg)",
};

/** Approval outcome donut: accent / ok / neutral segments with an ink legend. */
export default function OutcomeDonutTile({
  pendingN,
  approvedN,
  rejectedN,
}: {
  pendingN: number;
  approvedN: number;
  rejectedN: number;
}) {
  const t = useTranslations("adminOverview");
  const outcomeTotal = pendingN + approvedN + rejectedN;
  const pendingPct = outcomeTotal > 0 ? Math.round((pendingN / outcomeTotal) * 100) : 0;
  const approvedPct = outcomeTotal > 0 ? Math.round((approvedN / outcomeTotal) * 100) : 0;
  const rejectedPct = outcomeTotal > 0 ? Math.max(0, 100 - pendingPct - approvedPct) : 0;
  const outcomeOffset2 = 25 - pendingPct;
  const outcomeOffset3 = outcomeOffset2 - approvedPct;
  return (
    <Card className="gap-3 p-5">
      <Eyebrow icon={<ClipboardCheck aria-hidden="true" />}>{t("approvalOutcomeLabel")}</Eyebrow>
      <div className="flex items-center gap-4">
        <svg viewBox="0 0 36 36" className="size-14 shrink-0 -rotate-90" aria-hidden="true">
          <circle cx="18" cy="18" r="15.915" fill="none" stroke="var(--surface-sunken)" strokeWidth="4" />
          {outcomeTotal > 0 && (
            <>
              <circle
                cx="18"
                cy="18"
                r="15.915"
                fill="none"
                stroke={OUTCOME_COLOR.pending}
                strokeWidth="4"
                strokeDasharray={`${pendingPct} ${100 - pendingPct}`}
                strokeDashoffset="25"
              />
              <circle
                cx="18"
                cy="18"
                r="15.915"
                fill="none"
                stroke={OUTCOME_COLOR.approved}
                strokeWidth="4"
                strokeDasharray={`${approvedPct} ${100 - approvedPct}`}
                strokeDashoffset={outcomeOffset2}
              />
              <circle
                cx="18"
                cy="18"
                r="15.915"
                fill="none"
                stroke={OUTCOME_COLOR.rejected}
                strokeWidth="4"
                strokeDasharray={`${rejectedPct} ${100 - rejectedPct}`}
                strokeDashoffset={outcomeOffset3}
              />
            </>
          )}
        </svg>
        <ul className="m-0 flex min-w-0 list-none flex-col gap-1 p-0 text-sm text-foreground">
          {(
            [
              ["pending", t("pendingPctLabel", { pct: pendingPct })],
              ["approved", t("approvedPctLabel", { pct: approvedPct })],
              ["rejected", t("rejectedPctLabel", { pct: rejectedPct })],
            ] as const
          ).map(([key, label]) => (
            <li key={key} className="flex items-center gap-2">
              <span
                aria-hidden="true"
                className="size-2 shrink-0 rounded-full"
                style={{ background: OUTCOME_COLOR[key] }}
              />
              <span className="truncate tabular-nums">{label}</span>
            </li>
          ))}
        </ul>
      </div>
    </Card>
  );
}
