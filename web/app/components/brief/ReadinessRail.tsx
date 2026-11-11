"use client";

import Link from "next/link";
import { BookOpen, Gauge, KeyRound, MessageSquare, Plug, ShieldCheck, UserCheck, type LucideIcon } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { cn } from "../../lib/utils";
import { Button } from "../ui/button";
import { READINESS_STATE_TOKEN, sortReadiness, summariseReadiness } from "./logic";
import type { ReadinessKind, ReadinessRow } from "./types";

const KIND_ICON: Record<ReadinessKind, LucideIcon> = {
  SECRET: KeyRound,
  CONNECTOR: Plug,
  KNOWLEDGE: BookOpen,
  CHANNEL: MessageSquare,
  PLAN_LIMIT: Gauge,
  PERMISSION: UserCheck,
  GUARDRAIL: ShieldCheck,
};

const KIND_CAT: Record<ReadinessKind, string> = {
  SECRET: "var(--cat-creative)",
  CONNECTOR: "var(--cat-analysis)",
  KNOWLEDGE: "var(--cat-data)",
  CHANNEL: "var(--cat-comms)",
  PLAN_LIMIT: "var(--cat-automation)",
  PERMISSION: "var(--cat-assistant)",
  GUARDRAIL: "var(--cat-assistant)",
};

/** State as dot + word (never colour alone). Also used on graph secrets and envoy cards. */
export function ReadinessPill({ state }: { state: ReadinessRow["state"] }) {
  const t = useTranslations("brief");
  return (
    <span className="state-pill" style={{ ["--state" as string]: READINESS_STATE_TOKEN[state] }}>
      <span className="state-dot" aria-hidden="true" />
      {t(`state.${state}`)}
    </span>
  );
}

/**
 * Everything the envoy needs and whether it has it. Rows are never hidden:
 * a gated row says why and which plan unlocks it, a waiting-on-admin row says
 * an admin has to act. Reason text comes from core and is shown as written.
 */
export default function ReadinessRail({
  rows,
  onAskAdmin,
  onSelect,
  className,
}: {
  rows: readonly ReadinessRow[];
  /** Stub until admin requests exist; the button is disabled while this is undefined. */
  onAskAdmin?: (row: ReadinessRow) => void;
  onSelect?: (ref: string) => void;
  className?: string;
}) {
  const t = useTranslations("brief");
  const summary = summariseReadiness(rows);
  return (
    <section aria-label={t("rail.title")} className={cn("space-y-3", className)}>
      <div className="flex items-baseline justify-between gap-2">
        <h3 className="text-sm font-semibold text-foreground">{t("rail.title")}</h3>
        <span className="text-xs text-muted-foreground tabular-nums">
          {t("rail.summary", { ready: summary.ready, total: summary.total })}
        </span>
      </div>
      {rows.length === 0 ? (
        <p className="text-sm text-muted-foreground">{t("rail.empty")}</p>
      ) : (
        <ul className="space-y-2">
          {sortReadiness(rows).map((row) => {
            const Icon = KIND_ICON[row.kind] ?? KeyRound;
            return (
              <li
                key={`${row.kind}:${row.ref}`}
                className="rounded-lg border border-border bg-card/60 p-3"
                data-state={row.state}
              >
                <div className="flex items-start gap-3">
                  <span
                    className="cat-chip cat-chip-sm shrink-0"
                    style={{ ["--cat" as string]: KIND_CAT[row.kind] }}
                    aria-hidden="true"
                  >
                    <Icon className="size-3.5" aria-hidden="true" />
                  </span>
                  <div className="min-w-0 flex-1 space-y-1.5">
                    <div className="flex flex-wrap items-center gap-2">
                      <button
                        type="button"
                        onClick={() => onSelect?.(row.ref)}
                        className="truncate border-0 bg-transparent p-0 text-start text-sm font-medium text-foreground shadow-none hover:underline"
                        disabled={!onSelect}
                      >
                        {row.label}
                      </button>
                      <ReadinessPill state={row.state} />
                      {row.state === "GATED" && row.unlocksAt && (
                        <span className="text-xs text-muted-foreground">
                          {t("rail.unlocksAt", { plan: row.unlocksAt })}
                        </span>
                      )}
                    </div>
                    <p className="text-xs leading-relaxed text-muted-foreground">{row.reason}</p>
                    {row.state === "WAITING_ON_ADMIN" && (
                      <div className="space-y-1">
                        <Button size="sm" variant="outline" disabled={!onAskAdmin} onClick={() => onAskAdmin?.(row)}>
                          {t("rail.askAdmin")}
                        </Button>
                        {/* A disabled Button has pointer-events:none, so a `title`
                            tooltip here could never actually show — say it as
                            visible text instead (same pattern as TuneBox's
                            disabledReason). */}
                        {!onAskAdmin && <p className="text-xs text-muted-foreground">{t("rail.askAdminSoon")}</p>}
                      </div>
                    )}
                    {row.state === "MISSING" && row.actionHref && (
                      <Link
                        href={row.actionHref}
                        className="inline-block text-xs font-medium text-primary hover:underline"
                      >
                        {t("rail.fixThis")}
                      </Link>
                    )}
                  </div>
                </div>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}
