"use client";

import { Sparkles } from "lucide-react";
import type { TemplateIssue, TemplateRequirement, TemplateReview } from "../../templates/types";
import { useTranslations } from "../../../lib/i18n";

// What the platform already checked before this reached the queue: the automatic rules, the AI reviewer's
// verdict, and what an importer would have to connect. The AI review is an aid - the decision stays here.
export default function TemplateReviewSummary({
  review,
  requirements,
}: {
  review?: TemplateReview | null;
  requirements?: TemplateRequirement[];
}) {
  const tHC = useTranslations("hcPlatformadminComponentsTemplatesTemplateReviewSummary");
  if (!review) {
    return (
      <p className="rounded-md border border-border bg-secondary/20 px-3 py-2 text-xs text-muted-foreground">
        {tHC("s4")}
      </p>
    );
  }
  const blockers = [...review.rules.blockers, ...review.ai.blockers];
  const advisories = [...review.rules.advisories, ...review.ai.advisories];
  const reqs = requirements ?? review.rules.requirements;
  return (
    <div
      className="space-y-2 rounded-md border border-border bg-secondary/20 p-3 text-xs"
      data-testid="template-review-summary"
    >
      <p className="flex items-center gap-1.5 font-medium text-foreground">
        <Sparkles size={12} aria-hidden /> {tHC("s3")}
        {review.standardVersion})
      </p>
      <p className="text-muted-foreground">
        Rules: {review.rules.ready ? "passed" : "failed"} · {advisories.length} suggestion
        {advisories.length === 1 ? "" : "s"} {tHC("s2")}{" "}
        {review.ai.status === "UNAVAILABLE" ? "did not run" : review.ai.status.toLowerCase()}
        {review.ai.summary ? ` - ${review.ai.summary}` : ""}
      </p>
      {blockers.length > 0 && <IssueLines label="Blockers" issues={blockers} />}
      {advisories.length > 0 && <IssueLines label="Suggestions" issues={advisories} />}
      {reqs.length > 0 && (
        <p className="text-muted-foreground">
          <span className="font-medium text-foreground">{tHC("s1")} </span>
          {reqs.map((r) => r.label).join(" · ")}
        </p>
      )}
    </div>
  );
}

function IssueLines({ label, issues }: { label: string; issues: TemplateIssue[] }) {
  return (
    <div>
      <p className="font-medium text-foreground">{label}</p>
      <ul className="list-disc space-y-0.5 ps-4 text-muted-foreground">
        {issues.map((i, n) => (
          <li key={`${i.code}-${n}`}>
            {i.message} <span className="uppercase tracking-wide">({i.principle})</span>
          </li>
        ))}
      </ul>
    </div>
  );
}
