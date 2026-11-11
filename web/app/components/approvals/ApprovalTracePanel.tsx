"use client";

import { useEffect, useState } from "react";
import { approvalTraceApi, type ApprovalTrace } from "../../lib/api/approvalTrace";
import { useTranslations } from "../../lib/i18n";
import type { ApprovalRequestItem } from "./approvalsModel";

const fmt = (iso: string) => new Date(iso).toLocaleTimeString();

/**
 * The tool calls the agent made in the request's conversation after the decision, read from core's
 * trace endpoint. Works for old requests too: the server derives it from records that already exist
 * and says so when nothing was recorded.
 */
export default function ApprovalTracePanel({
  item,
  load = (id: string) => approvalTraceApi.getApprovalTrace(id),
}: {
  item: ApprovalRequestItem;
  load?: (id: string) => Promise<ApprovalTrace | null | undefined>;
}) {
  const q = useTranslations("approvalsPanel");
  const [trace, setTrace] = useState<ApprovalTrace | null | undefined>(undefined);

  useEffect(() => {
    let stopped = false;
    setTrace(undefined);
    load(item.id)
      .then((t) => !stopped && setTrace(t ?? null))
      .catch(() => !stopped && setTrace(null));
    return () => {
      stopped = true;
    };
  }, [item.id, load]);

  if (trace === undefined) return <p className="text-xs text-muted-foreground">{q("traceLoading")}</p>;
  if (trace === null) return <p className="text-xs text-muted-foreground">{q("traceUnavailable")}</p>;
  return (
    <div className="grid gap-2 text-sm" data-testid="approval-trace">
      <p className="text-xs text-muted-foreground">{trace.traceNote}</p>
      {trace.calls.length > 0 && (
        <ol className="grid gap-1.5">
          {trace.calls.map((c) => (
            <li key={c.id} className="flex gap-3">
              <time className="w-24 shrink-0 text-xs text-muted-foreground">{fmt(c.startedAt)}</time>
              <span className="min-w-0 break-words">
                <code className="font-mono text-xs">{c.toolName}</code>{" "}
                <span className={c.outcome === "FAILED" ? "text-destructive" : "text-muted-foreground"}>
                  {c.outcome === "FAILED" ? q("traceFailed") : q("traceOk")}
                </span>
                {c.inputSummary ? <span className="block text-xs text-muted-foreground">{c.inputSummary}</span> : null}
                {c.errorMessage ? <span className="block text-xs text-destructive">{c.errorMessage}</span> : null}
              </span>
            </li>
          ))}
        </ol>
      )}
      {trace.callsTruncated && <p className="text-xs text-muted-foreground">{q("traceTruncated")}</p>}
    </div>
  );
}
