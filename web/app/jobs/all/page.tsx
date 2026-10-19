"use client";

import { Suspense } from "react";
import { useSearchParams } from "next/navigation";
import Link from "next/link";
import { ListChecks } from "lucide-react";
import AppShell from "../../components/AppShell";
import PageHeader from "../../components/ax/PageHeader";
import { Button } from "../../components/ui/button";
import { Skeleton } from "../../components/ui/skeleton";
import JobsList from "../../components/jobs/JobsList";
import { useTranslations } from "../../lib/i18n";

/**
 * Every background job of the workspace, with filters and pages (JobsList). The launcher that used
 * to sit above it is the Assistant's "In background" mode now (/assistant?mode=background); this
 * page is the ledger the Work rail's "All jobs" link opens. ?agentId= filters, as before.
 */
function AllJobsInner() {
  const t = useTranslations("jobsPage");
  const agentId = useSearchParams().get("agentId");
  return (
    <AppShell eyebrow={t("eyebrow")}>
      <PageHeader
        icon={ListChecks}
        title={t("title")}
        subtitle={t("subtitle")}
        actions={
          <Button asChild size="sm">
            <Link href={agentId ? `/assistant?mode=background&agent=${encodeURIComponent(agentId)}` : "/assistant?mode=background"}>
              {t("startFromAssistant")}
            </Link>
          </Button>
        }
      />
      <JobsList agentId={agentId} />
    </AppShell>
  );
}

export default function AllJobsPage() {
  return (
    <Suspense
      fallback={
        <div className="space-y-4 p-6" role="status" aria-busy="true">
          <Skeleton className="h-8 w-48" />
          <Skeleton className="h-40 w-full" />
        </div>
      }
    >
      <AllJobsInner />
    </Suspense>
  );
}
