"use client";

import { AlertTriangle, CheckCircle2, Info, Sparkles, XCircle } from "lucide-react";
import type { TemplateAiReview, TemplateIssue } from "../../lib/api";
import { useTranslations } from "../../lib/i18n";

// Every validation result in one place: blockers (must fix), advisories (should fix) and the AI review.
// Each issue carries the principle it protects and exactly what to do about it - no guessing.
export default function TemplateReviewPanel({
  blockers,
  advisories,
  ai,
}: {
  blockers: TemplateIssue[];
  advisories: TemplateIssue[];
  ai?: TemplateAiReview | null;
}) {
  const tHC = useTranslations("hcComponentsTemplatesTemplateReviewPanel");
  const allBlockers = [...blockers, ...(ai?.blockers ?? [])];
  const allAdvisories = [...advisories, ...(ai?.advisories ?? [])];
  const clean = allBlockers.length === 0;

  return (
    <div className="space-y-3" role="region" aria-label={tHC("s3")} data-testid="template-review">
      <div
        className="flex items-start gap-2.5 rounded-lg border p-3 text-sm"
        style={{
          color: clean ? "var(--state-success, currentColor)" : "var(--state-error)",
          borderColor: "color-mix(in srgb, currentColor 30%, transparent)",
          backgroundColor: "color-mix(in srgb, currentColor 8%, transparent)",
        }}
      >
        {clean ? (
          <CheckCircle2 size={16} strokeWidth={2} className="mt-0.5 shrink-0" aria-hidden />
        ) : (
          <XCircle size={16} strokeWidth={2} className="mt-0.5 shrink-0" aria-hidden />
        )}
        <span>
          {clean
            ? allAdvisories.length > 0
              ? `Ready to submit. ${allAdvisories.length} suggestion${allAdvisories.length === 1 ? "" : "s"} below would make it better.`
              : "Ready to submit. It meets the standard."
            : `${allBlockers.length} thing${allBlockers.length === 1 ? "" : "s"} to fix before you can submit.`}
        </span>
      </div>

      {allBlockers.length > 0 && <IssueList title={tHC("s2")} tone="error" issues={allBlockers} />}
      {allAdvisories.length > 0 && <IssueList title="Suggestions" tone="warn" issues={allAdvisories} />}

      {ai && (
        <p className="flex items-start gap-2 text-xs text-muted-foreground">
          <Sparkles size={13} strokeWidth={2} className="mt-0.5 shrink-0" aria-hidden />
          <span>
            {ai.status === "UNAVAILABLE"
              ? `AI review: ${ai.summary}`
              : `AI review: ${ai.summary} (an aid for the platform team - they make the final decision).`}
          </span>
        </p>
      )}
    </div>
  );
}

function IssueList({ title, tone, issues }: { title: string; tone: "error" | "warn"; issues: TemplateIssue[] }) {
  const tHC = useTranslations("hcComponentsTemplatesTemplateReviewPanel");
  const color = tone === "error" ? "var(--state-error)" : "var(--state-queued)";
  const Icon = tone === "error" ? AlertTriangle : Info;
  return (
    <div className="space-y-2">
      <p className="text-[0.6875rem] font-semibold uppercase tracking-[0.12em] text-muted-foreground">{title}</p>
      <ul className="space-y-2">
        {issues.map((i, n) => (
          <li key={`${i.code}-${n}`} className="rounded-lg border border-border/70 px-3 py-2.5 text-sm">
            <p className="flex items-start gap-2 font-medium text-foreground">
              <Icon size={14} strokeWidth={2} className="mt-0.5 shrink-0" style={{ color }} aria-hidden />
              <span>{i.message}</span>
            </p>
            <p className="mt-1 ps-[22px] text-muted-foreground">
              <span className="font-medium text-foreground">{tHC("s1")} </span>
              {i.fix}
            </p>
            <p className="mt-1 ps-[22px] text-[0.6875rem] uppercase tracking-wide text-muted-foreground">
              {i.principle}
            </p>
          </li>
        ))}
      </ul>
    </div>
  );
}
