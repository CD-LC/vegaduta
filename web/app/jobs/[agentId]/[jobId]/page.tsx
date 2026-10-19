"use client";

import { useParams } from "next/navigation";
import AppShell from "../../../components/AppShell";
import PageHeader from "../../../components/ax/PageHeader";
import JobDetail from "../../../components/jobs/JobDetail";
import { useTranslations } from "../../../lib/i18n";

/**
 * One background job, live: status row, result, and the trace streamed over
 * SSE while the job runs. The route is /jobs/<agentId>/<jobId> because every
 * job endpoint is agent-scoped server-side — a jobId alone cannot be fetched.
 *
 * Client page + useParams() rather than the server `params` prop: every
 * console screen is a client component under AppShell, and the ids are only
 * ever used in client-side fetches.
 */
export default function JobDetailPage() {
  const t = useTranslations("jobsPage");
  const params = useParams<{ agentId: string; jobId: string }>();
  return (
    <AppShell eyebrow={t("eyebrow")}>
      <PageHeader title={t("detailTitle")} subtitle={t("detailSubtitle")} />
      <JobDetail agentId={params.agentId} jobId={params.jobId} />
    </AppShell>
  );
}
