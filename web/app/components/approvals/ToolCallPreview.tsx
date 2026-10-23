"use client";

import { useTranslations } from "../../lib/i18n";
import RiskBadge from "./RiskBadge";
import { redactedArguments, replayGoal, requestRisk, requestToolName, isToolCallRequest } from "./approvalsQueueModel";
import type { ApprovalRequestItem } from "./approvalsModel";

/** The queue shows the whole call: nothing is cut for a normal request (the chat card uses a smaller cap). */
export const QUEUE_ARGS_MAX_CHARS = 200_000;

/** True when the arguments are too long to show in full - the caller then refuses a one-click approve. */
export function argsTooLongToReview(item: ApprovalRequestItem): boolean {
  return redactedArguments(item.payload, QUEUE_ARGS_MAX_CHARS).truncated;
}

/**
 * Everything a person needs to decide a gated tool call: which tool, the task it was for, the exact arguments
 * (credential-looking values hidden; model-written text shown as plain text, never HTML), an estimated risk,
 * and in plain words what approving and denying each do. Rendered only for `mcp_tool.*` requests.
 */
export default function ToolCallPreview({ item }: { item: ApprovalRequestItem }) {
  const t = useTranslations("approvalsQueue");
  if (!isToolCallRequest(item)) return null;
  const tool = requestToolName(item);
  const args = redactedArguments(item.payload, QUEUE_ARGS_MAX_CHARS);
  const goal = replayGoal(item.payload);
  const pending = item.status === "PENDING";

  return (
    <div className="grid gap-3" data-testid="tool-call-preview">
      <div className="flex flex-wrap items-center gap-2">
        <code className="rounded-md bg-muted px-2 py-0.5 font-mono text-sm" data-testid="tool-call-name">
          {tool}
        </code>
        <RiskBadge risk={requestRisk(item)} />
      </div>
      {goal && (
        <p className="text-sm" data-testid="tool-call-goal">
          <span className="text-muted-foreground">{t("previewGoal")} </span>
          {goal}
        </p>
      )}
      <div className="grid gap-1.5">
        <h4 className="text-xs font-medium uppercase tracking-wide text-muted-foreground">{t("previewArguments")}</h4>
        {args.text ? (
          // A scrollable region must be reachable by keyboard so long arguments can be read.
          // eslint-disable-next-line jsx-a11y/no-noninteractive-tabindex
          <pre
            tabIndex={0}
            aria-label={t("previewArguments")}
            data-testid="tool-call-args"
            className="max-h-64 overflow-auto whitespace-pre-wrap break-words rounded-md border border-border bg-muted/40 p-3 font-mono text-xs leading-relaxed outline-none focus-visible:ring-2 focus-visible:ring-ring/50"
          >
            {args.text}
          </pre>
        ) : (
          <p className="text-sm text-muted-foreground">{t("previewNoArguments")}</p>
        )}
        {args.redactedCount > 0 && (
          <p className="text-xs text-muted-foreground" data-testid="tool-call-redacted">
            {t("previewRedacted", { count: args.redactedCount })}
          </p>
        )}
        {args.truncated && (
          <p className="text-xs font-medium text-destructive" role="alert" data-testid="tool-call-truncated">
            {t("previewTruncated")}
          </p>
        )}
      </div>
      {pending && (
        <ul className="grid gap-1 text-sm" data-testid="tool-call-consequences">
          <li>
            <strong>{t("ifApproveLabel")}</strong> {t("ifApprove", { tool })}
          </li>
          <li>
            <strong>{t("ifDenyLabel")}</strong> {t("ifDeny", { tool })}
          </li>
        </ul>
      )}
      <p className="text-xs text-muted-foreground">
        {t("riskEstimateNote")}{" "}
        <a href="#approval-tiers" className="underline underline-offset-2">
          {t("riskWhoDecides")}
        </a>
      </p>
    </div>
  );
}
