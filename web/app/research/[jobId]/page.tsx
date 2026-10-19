"use client";

import { useParams } from "next/navigation";
import { Search } from "lucide-react";
import AppShell from "../../components/AppShell";
import PageHeader from "../../components/ax/PageHeader";
import ResearchRunView from "../../components/research/ResearchRunView";
import { useTranslations } from "../../lib/i18n";

/**
 * One research run: live progress while it works, then the cited report.
 * The route carries only the run id - the server resolves the caller's own
 * research agent, so a run id from another user answers 404.
 *
 * Client page + useParams(), like /jobs/[agentId]/[jobId]: every console
 * screen is a client component under AppShell.
 */
export default function ResearchRunPage() {
  const t = useTranslations("researchPage");
  const params = useParams<{ jobId: string }>();
  return (
    <AppShell eyebrow={t("eyebrow")}>
      <PageHeader title={t("runTitle")} subtitle={t("runSubtitle")} icon={Search} />
      {/* key: moving between runs remounts, so nothing from the last run is shown under the new id. */}
      <ResearchRunView key={params.jobId} jobId={params.jobId} />
    </AppShell>
  );
}
