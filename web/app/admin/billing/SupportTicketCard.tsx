"use client";

import { Select } from "../../components/ui/select";
import { useCallback, useState } from "react";
import Link from "next/link";
import {
  LifeBuoy as LifebuoyIcon,
  Clipboard as ClipboardDocumentIcon,
  ExternalLink as ArrowTopRightOnSquareIcon,
} from "lucide-react";
import { api } from "../../lib/api";
import { Button } from "../../components/ui/button";
import { Card } from "../../components/ui/card";
import { Input } from "../../components/ui/input";
import { toast } from "../../lib/toastBus";
import { useTranslations } from "../../lib/i18n";
import { errorMessage } from "../../lib/errors";
import { isRealCap, TICKET_CATEGORIES, type TenantBillingSettings } from "./billingShared";

const LABEL = "mb-1 block text-[11.5px] text-muted-foreground";
const FIELD =
  "flex w-full rounded-md border border-border bg-secondary/40 px-3 py-2 text-sm text-foreground shadow-sm outline-none transition-[color,box-shadow] placeholder:text-muted-foreground focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/40";

interface Props {
  settings: TenantBillingSettings | null;
  planLabel: string;
  planPrice: string;
  usersCount: number | null;
  agentsCount: number | null;
  workflowsCount: number | null;
}

/**
 * #974 self-service support ticket + copyable diagnostics bundle. Ticket
 * submission reuses the Issues portal table (source TENANT_REPORTED). Submit is
 * deliberately a SECONDARY button: the page's one primary action is Upgrade.
 */
export default function SupportTicketCard({
  settings,
  planLabel,
  planPrice,
  usersCount,
  agentsCount,
  workflowsCount,
}: Props) {
  const t = useTranslations("adminBillingPage");
  const x = useTranslations("adminBillingExtra");
  const [category, setCategory] = useState(TICKET_CATEGORIES[0].value);
  const [title, setTitle] = useState("");
  const [message, setMessage] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [lastTicketId, setLastTicketId] = useState<string | null>(null);

  const submit = useCallback(async () => {
    if (!title.trim()) {
      toast.error(t("toastSubjectRequired"));
      return;
    }
    setSubmitting(true);
    try {
      const created = await api.createIssueTicket(category, title, message);
      setLastTicketId(created.id);
      setTitle("");
      setMessage("");
      toast.success(t("toastTicketSubmitted"));
    } catch (err) {
      toast.error(errorMessage(err, t("toastTicketError")));
    }
    setSubmitting(false);
  }, [category, title, message, t]);

  // A plain-text diagnostic bundle built entirely from data already loaded on
  // the page - no new backend call, no fabricated fields.
  const copyDiagnostics = useCallback(async () => {
    const capOrUnlimited = (cap: unknown) => (isRealCap(cap) ? cap : t("diagUnlimited"));
    const lines = [
      t("diagWorkspaceLabel", { value: settings?.organizationName || t("diagWorkspaceUnnamed") }),
      t("diagPlanLabel", { label: planLabel, price: planPrice }),
      t("diagTeamSeatsLabel", { used: usersCount ?? t("diagUnknown"), cap: capOrUnlimited(settings?.maxUsers) }),
      t("diagAgentsLabel", { used: agentsCount ?? t("diagUnknown"), cap: capOrUnlimited(settings?.maxAgents) }),
      t("diagWorkflowsLabel", {
        used: workflowsCount ?? t("diagUnknown"),
        cap: capOrUnlimited(settings?.maxWorkflows),
      }),
      t("diagTimestampLabel", { value: new Date().toISOString() }),
    ];
    const text = lines.join("\n");
    try {
      await navigator.clipboard.writeText(text);
      toast.success(t("toastDiagnosticsCopied"));
    } catch {
      toast.error(t("toastClipboardError") + "\n" + text);
    }
  }, [settings, planLabel, planPrice, usersCount, agentsCount, workflowsCount, t]);

  return (
    <Card className="mb-0 gap-0 p-6">
      <div className="mb-1 flex flex-wrap items-center justify-between gap-2">
        <h2 className="m-0 flex items-center gap-2 text-[0.95rem] font-semibold">
          <LifebuoyIcon width={15} height={15} aria-hidden="true" /> {t("getHelpTitle")}
        </h2>
        <Link href="/admin/issues" className="inline-flex items-center gap-1 text-xs text-muted-foreground">
          {t("viewTicketsLink")} <ArrowTopRightOnSquareIcon width={12} height={12} aria-hidden="true" />
        </Link>
      </div>
      <p className="mb-4 mt-1 text-[0.8rem] text-muted-foreground">{t("getHelpHint")}</p>

      <div className="ax-bill-ticket-grid mb-3 grid gap-3">
        <div>
          <label className={LABEL} htmlFor="ticket-category">
            {t("categoryLabel")}
          </label>
          <Select
            id="ticket-category"
            value={category}
            onValueChange={setCategory}
            options={TICKET_CATEGORIES.map((c) => ({ value: c.value, label: t(c.labelKey) }))}
          />
          <p id="ticket-category-help" className="mt-1 text-xs text-muted-foreground">
            {x("categoryHelp")}
          </p>
        </div>
        <div>
          <label className={LABEL} htmlFor="ticket-title">
            {t("subjectLabel")}
          </label>
          <Input
            id="ticket-title"
            type="text"
            placeholder={t("subjectPlaceholder")}
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            maxLength={255}
          />
        </div>
      </div>

      <label className={LABEL} htmlFor="ticket-message">
        {t("detailsLabel")}
      </label>
      <textarea
        id="ticket-message"
        className={`${FIELD} mb-3 resize-y`}
        rows={3}
        placeholder={t("detailsPlaceholder")}
        value={message}
        onChange={(e) => setMessage(e.target.value)}
      />

      <div className="flex flex-wrap items-center gap-3">
        <Button type="button" variant="outline" className="mb-0" onClick={submit} disabled={submitting}>
          {submitting ? t("submittingButton") : t("submitTicketButton")}
        </Button>
        <Button type="button" variant="outline" size="sm" className="mb-0 rounded-full" onClick={copyDiagnostics}>
          <ClipboardDocumentIcon width={13} height={13} className="mr-1" aria-hidden="true" />
          {t("copyDiagnosticsButton")}
        </Button>
        {lastTicketId && (
          <span className="font-mono text-[0.72rem] text-muted-foreground">
            {t("lastTicketIdLabel", { id: lastTicketId })}
          </span>
        )}
      </div>
    </Card>
  );
}
