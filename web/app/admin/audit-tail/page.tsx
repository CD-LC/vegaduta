"use client";

import { useState } from "react";
import Link from "next/link";
import { RefreshCw } from "lucide-react";
import { useRequireScope } from "../../components/useRequireScope";
import AppShell from "../../components/AppShell";
import AuditTailFeed from "./AuditTailFeed";
import PageHeader from "../../components/ax/PageHeader";
import { Card } from "../../components/ui/card";
import { Button } from "../../components/ui/button";
import { useTranslations } from "../../lib/i18n";

// Direct feedback: "i want a separate page to view LIVE AUDIT TAIL
// completely full" - the Admin Overview widget of the same name
// (admin/page.js) is deliberately small (one grid cell, capped at the last
// 12 rows, `overflow: hidden`) so it fits alongside the rest of that
// screen's telemetry. This is the same real component/data source
// (AdminEventTail -> api.listAuditLog(), the append-only audit trail),
// just given the whole page: a much higher row cap and a scrollable,
// non-clipped layout (see globals.css's .ax-feed-full). No new backend
// endpoint - AuditLogController already returns the full trail,
// AdminEventTail already polls it every 6s.

export default function AuditTailPage() {
  const t = useTranslations("adminAuditTailPage");
  const tn = useTranslations("auditNotifications");
  const allowed = useRequireScope("VIEW_AUDIT_LOG");
  const [tick, setTick] = useState(0);

  if (!allowed) return null;

  return (
    <AppShell eyebrow={t("pageEyebrow")}>
      <PageHeader
        title={t("pageTitle")}
        subtitle={
          <>
            {t("hintPrefix")}{" "}
            <Link href="/admin/audit" className="text-primary underline-offset-4 hover:underline">
              {t("auditLogLinkText")}
            </Link>
            .
          </>
        }
        actions={
          <Button
            type="button"
            variant="outline"
            size="sm"
            className="rounded-full"
            onClick={() => setTick((tk) => tk + 1)}
            title={t("refreshTooltip")}
          >
            <RefreshCw size={14} aria-hidden="true" /> {t("refreshNow")}
          </Button>
        }
      />

      <Card className="gap-3 p-4 sm:p-5">
        <div className="flex flex-wrap items-center gap-1.5">
          <span className="font-mono text-[11px] uppercase tracking-[0.1em] text-muted-foreground">
            &#9612; {tn("tailLabel")}
          </span>
        </div>
        <AuditTailFeed refreshKey={tick} />
      </Card>
    </AppShell>
  );
}
