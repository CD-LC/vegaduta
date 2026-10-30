"use client";

// "While you were away" - the ambient-agent surface's one aggregate card
// (GET /api/ambient/digest, AmbientDigestController in core/app). Mounted at
// the top of /jobs because that is the console's runs-and-schedules surface:
// sentinel outcomes and parked proposals are job-adjacent artifacts.
//
// Renders nothing (null) when the call 404s (older core), errors, or comes
// back entirely empty - a digest that has nothing to report should not take
// up the screen. Sections are best-effort on the server too, so each block is
// rendered only when it has content.
//
// Plain-English strings, not i18n - same call NotificationBell made (its own
// header comment): the digest's CONTENT (titles, summaries, verdicts) comes
// from the backend untranslated, so localizing only the chrome around it
// would be a partial, inconsistent translation anyway.

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { Check, Eye, Loader2, MoonStar, X } from "lucide-react";
import { api, type AmbientDigest } from "../../lib/api";
import { Card } from "../ui/card";
import { Badge } from "../ui/badge";
import { Button } from "../ui/button";
import { formatWhen } from "./jobStatus";
import { useTranslations } from "../../lib/i18n";

const OUTCOME_LABEL: Record<string, string> = {
  QUIET: "quiet",
  NOTIFIED: "noticed",
  PROPOSED: "proposal",
  NO_VERDICT: "no verdict",
  JOB_FAILED: "watch failed",
  JOB_GONE: "run lost",
};

export default function AmbientDigestCard() {
    const tHC = useTranslations("hcComponentsJobsAmbientDigestCard");
  const [digest, setDigest] = useState<AmbientDigest | null>(null);
  // id -> which decision is in flight. `decided` hides a row instantly even if
  // the refetched digest still carries it for a beat; the refetch keeps the
  // section totals honest.
  const [deciding, setDeciding] = useState<Record<string, "approve" | "reject">>({});
  const [decided, setDecided] = useState<ReadonlySet<string>>(new Set());
  const [decisionError, setDecisionError] = useState<string | null>(null);

  const load = useCallback(() => {
    api
      .getAmbientDigest(24)
      .then(setDigest)
      .catch(() => {});
  }, []);

  useEffect(() => {
    let cancelled = false;
    api
      .getAmbientDigest(24)
      .then((d) => {
        if (!cancelled) setDigest(d);
      })
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, []);

  // One-tap decision - only offered on agent.proposal items, which carry a
  // fully-specified payload (no form fields to fill). Other pending items
  // route to /admin/approvals where their inputs can be answered.
  const decide = async (id: string, action: "approve" | "reject") => {
    setDeciding((m) => ({ ...m, [id]: action }));
    setDecisionError(null);
    try {
      if (action === "approve") await api.approveRequest(id, null);
      else await api.rejectRequest(id, null, "dismissed from ambient digest");
      setDecided((s) => new Set(s).add(id));
      load();
    } catch (e) {
      setDecisionError(e instanceof Error ? e.message : "decision failed");
    } finally {
      setDeciding((m) => {
        const next = { ...m };
        delete next[id];
        return next;
      });
    }
  };

  if (!digest) return null;

  const proposals = digest.pendingApprovals.items.filter((i) => i.actionType === "agent.proposal");
  const otherPending = digest.pendingApprovals.total - proposals.length;
  const escalations = digest.sentinelOutcomes.filter((o) => o.outcome !== "QUIET");
  const quietCount = digest.sentinelOutcomes.length - escalations.length;
  const failedJobs = digest.jobs.byStatus["FAILED"] ?? 0;
  const runningJobs = digest.jobs.byStatus["RUNNING"] ?? 0;

  if (
    digest.pendingApprovals.total === 0 &&
    escalations.length === 0 &&
    digest.agentNotifications.length === 0 &&
    digest.jobs.observed === 0
  ) {
    return null;
  }

  return (
    <Card className="gap-3 p-4">
      <div className="flex items-center gap-2">
        <MoonStar className="size-4 text-[var(--accent-500)]" aria-hidden="true" />
        <h3 className="text-sm font-medium text-foreground">{tHC("s10")}</h3>
        <span className="text-xs text-muted-foreground">{tHC("s9")}</span>
        {digest.sentinelScheduleCount > 0 && (
          <Badge variant="outline" className="ms-auto">
            <Eye className="size-3" aria-hidden="true" /> {digest.sentinelScheduleCount} sentinel
            {digest.sentinelScheduleCount === 1 ? "" : "s"} watching
          </Badge>
        )}
      </div>

      {digest.jobs.observed > 0 && (
        <p className="text-xs text-muted-foreground">
          {digest.jobs.observed}
          {digest.jobs.truncated ? "+" : ""} job{digest.jobs.observed === 1 ? "" : "s"} ran
          {runningJobs > 0 && <> · {runningJobs} {tHC("s8")}</>}
          {failedJobs > 0 && (
            <>
              {" "}
              · <span className="text-[var(--state-error)]">{failedJobs} failed</span>
            </>
          )}
        </p>
      )}

      {digest.pendingApprovals.total > 0 && (
        <div className="space-y-1.5">
          <p className="text-xs font-medium text-foreground">
            {digest.pendingApprovals.total} action{digest.pendingApprovals.total === 1 ? "" : "s"} {tHC("s7")}
          </p>
          <ul className="space-y-1">
            {digest.pendingApprovals.items
              .filter((a) => !decided.has(a.id))
              .slice(0, 5)
              .map((a) => (
                <li key={a.id} className="flex flex-wrap items-center gap-2 text-xs">
                  <Badge variant="outline">{a.actionType === "agent.proposal" ? "proposal" : a.actionType}</Badge>
                  <span className="min-w-0 flex-1 basis-40 truncate text-muted-foreground" title={a.summary}>
                    {a.summary}
                  </span>
                  <span className="shrink-0 text-muted-foreground">{formatWhen(a.createdAt)}</span>
                  {a.actionType === "agent.proposal" && (
                    <span className="flex shrink-0 gap-0.5">
                      <Button
                        variant="ghost"
                        size="icon"
                        className="size-6 text-[var(--state-success)]"
                        disabled={a.id in deciding}
                        aria-label={tHC("s6")}
                        onClick={() => decide(a.id, "approve")}
                      >
                        {deciding[a.id] === "approve" ? (
                          <Loader2 className="size-3 animate-spin" aria-hidden="true" />
                        ) : (
                          <Check className="size-3.5" aria-hidden="true" />
                        )}
                      </Button>
                      <Button
                        variant="ghost"
                        size="icon"
                        className="size-6 text-[var(--state-error)]"
                        disabled={a.id in deciding}
                        aria-label={tHC("s5")}
                        onClick={() => decide(a.id, "reject")}
                      >
                        {deciding[a.id] === "reject" ? (
                          <Loader2 className="size-3 animate-spin" aria-hidden="true" />
                        ) : (
                          <X className="size-3.5" aria-hidden="true" />
                        )}
                      </Button>
                    </span>
                  )}
                </li>
              ))}
          </ul>
          {decisionError && <p className="text-xs text-[var(--state-error)]">{decisionError}</p>}
          <Link href="/admin/approvals" className="inline-block text-xs text-primary underline">
            {tHC("s4")}{otherPending > 0 ? ` (${digest.pendingApprovals.total} total)` : ""}
          </Link>
        </div>
      )}

      {escalations.length > 0 && (
        <div className="space-y-1.5">
          <p className="text-xs font-medium text-foreground">{tHC("s3")}</p>
          <ul className="space-y-1">
            {escalations.slice(0, 5).map((o) => (
              <li key={o.runId} className="flex items-center gap-2 text-xs">
                <Badge variant="outline">{OUTCOME_LABEL[o.outcome] ?? o.outcome.toLowerCase()}</Badge>
                <span className="min-w-0 flex-1 truncate text-muted-foreground">
                  {o.agentName ?? "agent"}
                  {o.outcomeNote ? ` — ${o.outcomeNote}` : ""}
                </span>
                <span className="shrink-0 text-muted-foreground">{formatWhen(o.startedAt)}</span>
                {o.agentId && (
                  <Link href={`/agents/${o.agentId}`} className="shrink-0 text-primary underline">
                    open
                  </Link>
                )}
              </li>
            ))}
          </ul>
          {quietCount > 0 && (
            <p className="text-[11px] text-muted-foreground">
              {quietCount} {tHC("s2")}{quietCount === 1 ? "" : "s"} {tHC("s1")}
            </p>
          )}
        </div>
      )}
    </Card>
  );
}
