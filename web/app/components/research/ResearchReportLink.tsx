"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { api } from "../../lib/api";
import { useTranslations } from "../../lib/i18n";
import { isResearchResult } from "./researchRun";

/**
 * "Open as research report" for the generic job screen.
 *
 * A research-shaped result is not enough to show it: /research/{jobId} only
 * resolves runs on the viewer's own research agent, so a job on any other
 * agent (a template agent with a result schema, someone else's shared job)
 * would land on "run not found". The link therefore appears only after the
 * same lookup the report page makes has answered for this job id. That lookup
 * is quiet on 404, so "not a research run" raises nothing; any failure means
 * no link.
 */
export default function ResearchReportLink({ jobId, resultJson }: { jobId: string; resultJson: unknown }) {
  const t = useTranslations("researchPage");
  const shaped = isResearchResult(resultJson);
  const [resolvedId, setResolvedId] = useState<string | null>(null);

  useEffect(() => {
    if (!shaped || !jobId) return undefined;
    let cancelled = false;
    Promise.resolve()
      .then(() => api.getResearchRun(jobId))
      .then((run: any) => {
        if (!cancelled && run && run.id === jobId) setResolvedId(jobId);
      })
      .catch(() => {
        // Not one of the viewer's research runs (or research is off): no link.
      });
    return () => {
      cancelled = true;
    };
  }, [shaped, jobId]);

  if (!shaped || resolvedId !== jobId) return null;

  return (
    <Link
      className="text-sm font-medium text-primary underline underline-offset-2"
      href={`/research/${jobId}`}
      data-testid="research-report-link"
    >
      {t("openAsReport")}
    </Link>
  );
}
