"use client";

import type { ReactNode } from "react";
import { Loader2 } from "lucide-react";
import { Button } from "../ui/button";
import { Alert, AlertDescription } from "../ui/alert";
import { Label } from "../ui/label";
import { Input } from "../ui/input";
import { Textarea } from "../ui/textarea";
import { Card, CardContent, CardHeader, CardTitle } from "../ui/card";
import { useTranslations } from "../../lib/i18n";
import ApprovalStatusBadge from "./ApprovalStatusBadge";
import ApprovalContinuationPanel from "./ApprovalContinuationPanel";
import ExpiryCountdown from "./ExpiryCountdown";
import ToolCallPreview, { argsTooLongToReview } from "./ToolCallPreview";
import { isExpiredRequest, isToolCallRequest, requestToolName, requesterIds } from "./approvalsQueueModel";
import {
  actionLabel,
  fmtTime,
  inr,
  paymentContext,
  prettyPayload,
  proposalContext,
  requestedFields,
  type ApprovalRequestItem,
  type Translate,
} from "./approvalsModel";

function Section({ label, children }: { label: string; children: ReactNode }) {
  return (
    <section className="grid gap-1.5">
      <h3 className="text-xs font-medium uppercase tracking-wide text-muted-foreground">{label}</h3>
      {children}
    </section>
  );
}

function Code({ children }: { children: ReactNode }) {
  return (
    <pre className="max-h-64 overflow-auto whitespace-pre-wrap break-words rounded-md border border-border bg-muted/40 p-3 font-mono text-xs leading-relaxed">
      {children}
    </pre>
  );
}

export interface ApprovalDetailProps {
  item: ApprovalRequestItem;
  note: string;
  onNoteChange: (v: string) => void;
  formValues: Record<string, string>;
  onFormValueChange: (field: string, v: string) => void;
  busy: boolean;
  /** Seconds a pending request may wait before core closes it (0 = never, null = not known). */
  ttlSeconds?: number | null;
  onApprove: (e: React.MouseEvent<HTMLButtonElement>) => void;
  onReject: (e: React.MouseEvent<HTMLButtonElement>) => void;
  t: Translate;
}

/** Review panel for one approval: context, proposed action, decision controls, audit trail. */
export default function ApprovalDetail({
  item,
  note,
  onNoteChange,
  formValues,
  onFormValueChange,
  busy,
  ttlSeconds = null,
  onApprove,
  onReject,
  t,
}: ApprovalDetailProps) {
  const q = useTranslations("approvalsQueue");
  const toolCall = isToolCallRequest(item);
  const tool = toolCall ? requestToolName(item) : "";
  const ids = requesterIds(item.requestedBy);
  const expired = isExpiredRequest(item);
  const tooLong = toolCall && item.status === "PENDING" && argsTooLongToReview(item);
  const payload = prettyPayload(item.payload);
  const pay = paymentContext(item.payload);
  const proposal = item.actionType === "agent.proposal" ? proposalContext(item.payload) : null;
  const fields = item.status === "PENDING" ? requestedFields(item.payload) : [];
  const retries = item.retryCount ?? 0;

  return (
    <Card className="gap-4 py-5" data-testid="approval-detail">
      <CardHeader className="gap-1">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <CardTitle className="min-w-0 break-words text-base">{actionLabel(item.actionType, t)}</CardTitle>
          <ApprovalStatusBadge status={item.status} decidedBy={item.decidedBy} />
        </div>
        <p className="break-words text-sm text-muted-foreground">
          {t("requestedByRow", { name: item.requestedBy || t("unknownActor") })} &middot; {fmtTime(item.createdAt)}
        </p>
        <ExpiryCountdown item={item} ttlSeconds={ttlSeconds} showWhenNever />
        {ids && (
          <p
            className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground"
            data-testid="approval-links"
          >
            <span>
              {q("sessionLabel")} <code className="break-all font-mono">{ids.sessionId}</code>
            </span>
            <a
              className="font-medium text-foreground underline underline-offset-2"
              href={`/playground?agent=${encodeURIComponent(ids.agentId)}`}
              data-testid="approval-link-agent"
            >
              {q("linkOpenAgent")}
            </a>
            <a
              className="font-medium text-foreground underline underline-offset-2"
              href={`/agents/turns?agentId=${encodeURIComponent(ids.agentId)}`}
              data-testid="approval-link-turns"
            >
              {q("linkInspectTurns")}
            </a>
          </p>
        )}
      </CardHeader>
      <CardContent className="grid gap-5">
        <Section label={t("policyTriggeredLabel")}>
          {toolCall ? (
            <p className="text-sm">{q("whyGated", { tool })}</p>
          ) : (
            <p className="text-sm">
              {t("policyTriggeredPrefix")} <code className="break-all font-mono text-xs">{item.actionType}</code>{" "}
              {t("policyTriggeredSuffix")}
            </p>
          )}
        </Section>

        <Section label={t("whyAgentAskedLabel")}>
          <p className="break-words text-sm">{item.summary || t("noSummaryRecorded")}</p>
        </Section>

        {pay && (
          <Section label={t("paymentContextLabel")}>
            <div className="grid gap-1 break-words text-sm">
              <div>
                <strong>{inr(pay.amountPaise)}</strong> {pay.currency && pay.currency !== "INR" ? pay.currency : ""} ·{" "}
                {t("paymentTypeRow", { type: pay.type || "?" })}
                {pay.payeeVpa ? <> · {t("paymentPayeeRow", { vpa: pay.payeeVpa })}</> : null}
              </div>
              {pay.description && <div>{pay.description}</div>}
              <div className="text-muted-foreground">
                {t("paymentMandateRow", {
                  scope: pay.mandate.scope || "?",
                  perTxn: inr(pay.mandate.maxPerTxnPaise),
                  daily: inr(pay.mandate.maxDailyPaise),
                  monthly: inr(pay.mandate.maxMonthlyPaise),
                })}
              </div>
            </div>
          </Section>
        )}

        {proposal && (
          <Section label={t("proposalGoalLabel")}>
            <p className="break-words text-sm">{proposal.goal}</p>
          </Section>
        )}

        {toolCall ? (
          <Section label={t("proposedActionLabel")}>
            <ToolCallPreview item={item} />
          </Section>
        ) : (
          payload && (
            <Section label={t("proposedActionLabel")}>
              <Code>{payload}</Code>
            </Section>
          )
        )}

        {item.rejectionReason && (
          <Alert variant="error">
            <AlertDescription className="break-words">
              {expired ? q("expiredReasonPrefix") : t("reasonPrefix")} {item.rejectionReason}
            </AlertDescription>
          </Alert>
        )}
        {item.executionError && (
          <Alert variant="error">
            <AlertDescription className="break-words">
              {t("executionErrorPrefix")} {item.executionError}
            </AlertDescription>
          </Alert>
        )}

        {item.executionResult && (
          <Section label={t("executionResultLabel")}>
            <Code>{item.executionResult}</Code>
          </Section>
        )}

        {fields.length > 0 && (
          <Section label={t("workflowNeedsLabel")}>
            <div className="grid gap-3">
              {fields.map((field) => (
                <div key={field} className="grid gap-1.5">
                  <Label htmlFor={`appr-field-${field}`}>{field}</Label>
                  <Input
                    id={`appr-field-${field}`}
                    value={formValues[field] ?? ""}
                    maxLength={2000}
                    onChange={(e) => onFormValueChange(field, e.target.value)}
                  />
                </div>
              ))}
            </div>
            <p className="text-xs text-muted-foreground">{t("workflowNeedsHint")}</p>
          </Section>
        )}

        {item.status === "PENDING" && (
          <Section label={toolCall ? q("denyReasonLabel") : t("decisionNoteLabel")}>
            <Textarea
              value={note}
              onChange={(e) => onNoteChange(e.target.value)}
              placeholder={toolCall ? q("denyReasonPlaceholder") : t("decisionNotePlaceholder")}
              aria-label={toolCall ? q("denyReasonLabel") : t("decisionNoteLabel")}
              aria-describedby={toolCall ? "approval-deny-reason-help" : undefined}
              maxLength={2000}
              className="min-h-16"
            />
            {toolCall && (
              <p id="approval-deny-reason-help" className="text-xs text-muted-foreground">
                {q("denyReasonHelp")}
              </p>
            )}
            {tooLong && (
              <p className="text-xs font-medium text-destructive" role="alert" data-testid="approve-blocked-too-long">
                {q("approveBlockedTooLong")}
              </p>
            )}
            <div className="flex flex-wrap justify-end gap-2" aria-busy={busy}>
              <Button type="button" variant="destructive" size="sm" disabled={busy} onClick={onReject}>
                {busy && <Loader2 size={14} className="animate-spin" aria-hidden="true" />}
                {toolCall ? q("denyButton") : t("reject")}
              </Button>
              <Button type="button" size="sm" disabled={busy || tooLong} onClick={onApprove}>
                {busy && <Loader2 size={14} className="animate-spin" aria-hidden="true" />}
                {t("approve")}
              </Button>
            </div>
          </Section>
        )}

        {toolCall && item.status !== "PENDING" && (
          <Section label={q("continuationLabel")}>
            <ApprovalContinuationPanel item={item} />
          </Section>
        )}

        <Section label={t("auditTrailLabel")}>
          <ol className="grid gap-1.5 text-sm">
            <li className="flex gap-3">
              <time className="w-40 shrink-0 text-xs text-muted-foreground">{fmtTime(item.createdAt)}</time>
              <span className="min-w-0 break-words">
                {t("requestedByRow", { name: item.requestedBy || t("unknownActor") })}
              </span>
            </li>
            {item.decidedAt && (
              <li className="flex gap-3">
                <time className="w-40 shrink-0 text-xs text-muted-foreground">{fmtTime(item.decidedAt)}</time>
                <span className="min-w-0 break-words">
                  {expired ? q("auditExpired") : item.status.toLowerCase()}{" "}
                  {t("byActorSuffix", { name: item.decidedBy || t("unknownActor") })}
                </span>
              </li>
            )}
            {retries > 0 && (
              <li className="flex gap-3">
                <span className="w-40 shrink-0 text-xs text-muted-foreground">&mdash;</span>
                <span className="min-w-0 break-words">
                  {t("retriedLabel", { count: retries, plural: retries === 1 ? "" : "s" })}
                  {item.nextRetryAt ? t("nextAttemptSuffix", { time: fmtTime(item.nextRetryAt) }) : ""}
                </span>
              </li>
            )}
          </ol>
        </Section>
      </CardContent>
    </Card>
  );
}
