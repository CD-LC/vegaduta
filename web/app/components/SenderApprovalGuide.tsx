"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { ShieldCheck } from "lucide-react";
import { api } from "../lib/api";
import { useTranslations } from "../lib/i18n";
import { Badge } from "./ui/badge";
import { Button } from "./ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "./ui/card";

/**
 * Channel-agnostic explainer for the sender-approval gate. Every inbound
 * channel (WhatsApp, Telegram, Slack, Discord, Teams, Meta social, X, email)
 * holds a NEW sender until an admin approves them, unless the channel is set
 * to auto-approve. Nothing pushes the owner a notification, so this card is
 * where the rule is stated and the live waiting count is surfaced.
 */
export default function SenderApprovalGuide() {
  const t = useTranslations("channelsPanel");
  const [pending, setPending] = useState<number | null>(null);

  useEffect(() => {
    let alive = true;
    api
      .listPendingPairings()
      .then((rows: unknown) => alive && setPending(Array.isArray(rows) ? rows.length : 0))
      .catch(() => alive && setPending(null));
    return () => {
      alive = false;
    };
  }, []);

  const steps = [t("senderGuideStep1"), t("senderGuideStep2"), t("senderGuideStep3")];

  return (
    <Card data-testid="sender-approval-guide">
      <CardHeader className="flex-row items-center justify-between gap-2 pb-0">
        <CardTitle className="flex items-center gap-2 font-mono text-[11px] font-semibold uppercase tracking-[0.14em] text-muted-foreground">
          <ShieldCheck size={14} aria-hidden="true" />
          {t("senderGuideTitle")}
        </CardTitle>
        {pending !== null && pending > 0 && (
          <Badge
            variant="outline"
            className="border-[var(--warning-border)] bg-[var(--warning-bg)] font-mono text-[10px] text-[var(--warning-text)]"
          >
            {t("senderGuideWaiting", { count: pending })}
          </Badge>
        )}
      </CardHeader>
      <CardContent className="space-y-3 pt-3">
        <p className="max-w-prose text-sm text-muted-foreground">{t("senderGuideIntro")}</p>
        <ol className="list-decimal space-y-1 ps-5 text-sm text-muted-foreground">
          {steps.map((s) => (
            <li key={s}>{s}</li>
          ))}
        </ol>
        <p className="max-w-prose text-xs text-muted-foreground">{t("senderGuideModes")}</p>
        <div className="flex flex-wrap gap-2">
          <Button asChild variant="outline" size="sm">
            <Link href="/admin/contacts">{t("senderGuideReview")}</Link>
          </Button>
          <Button asChild variant="ghost" size="sm">
            <Link href="/admin/approvals">{t("senderGuideApprovalsLink")}</Link>
          </Button>
        </div>
      </CardContent>
    </Card>
  );
}
