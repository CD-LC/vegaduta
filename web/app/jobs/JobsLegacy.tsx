"use client";

import { Suspense, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { ListChecks } from "lucide-react";
import { api } from "../lib/api";
import AppShell from "../components/AppShell";
import PageHeader from "../components/ax/PageHeader";
import { Skeleton } from "../components/ui/skeleton";
import JobLauncher from "../components/jobs/JobLauncher";
import JobsList from "../components/jobs/JobsList";
import SchedulePanel from "../components/jobs/SchedulePanel";
import AmbientDigestCard from "../components/jobs/AmbientDigestCard";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "../components/ui/tabs";
import { usePageReady } from "../components/RouteTransition";
import { useTranslations } from "../lib/i18n";

/**
 * Jobs — the console surface for background agent runs (core/agents/.../job).
 * The backend shipped as HTTP-only; this page is what makes "start a job and
 * close the tab" reachable without an API client: a launcher on top, the
 * tenant-wide run list under it, and recurring schedules on the second tab.
 *
 * Deep link: /jobs?agentId=<id> pre-selects that agent in the launcher and
 * filters the list — the same ?agentId= convention /agents already uses, so
 * an agent's own screens can link straight to "this agent's jobs".
 */
function JobsPageInner() {
  const t = useTranslations("jobsPage");
  const searchParams = useSearchParams();
  const agentIdParam = searchParams.get("agentId");
  const [agents, setAgents] = useState<any[]>([]);
  const [loaded, setLoaded] = useState(false);
  // This app's Tabs primitive is controlled-only (no defaultValue).
  const [tab, setTab] = useState("runs");

  useEffect(() => {
    api
      .listAgents()
      .then((data: any) => setAgents(Array.isArray(data) ? data : []))
      .catch(() => {
        // JobLauncher renders nothing without agents; the list still works.
      })
      .finally(() => setLoaded(true));
  }, []);
  usePageReady(loaded);

  return (
    <AppShell eyebrow={t("eyebrow")}>
      <PageHeader
        icon={ListChecks}
        title={t("title")}
        subtitle={t("subtitle")}
      />
      <div className="mb-4">
        <AmbientDigestCard />
      </div>
      <Tabs value={tab} onValueChange={setTab}>
        <TabsList>
          <TabsTrigger value="runs">{t("tabRuns")}</TabsTrigger>
          <TabsTrigger value="schedules">{t("tabSchedules")}</TabsTrigger>
        </TabsList>
        <TabsContent value="runs" className="space-y-6 pt-4">
          <JobLauncher agents={agents} defaultAgentId={agentIdParam} />
          <JobsList agentId={agentIdParam} />
        </TabsContent>
        <TabsContent value="schedules" className="pt-4">
          <SchedulePanel agents={agents} />
        </TabsContent>
      </Tabs>
    </AppShell>
  );
}

// useSearchParams requires a Suspense boundary — same pattern as /agents.
export default function JobsLegacy() {
  return (
    <Suspense
      fallback={
        <div className="space-y-4 p-6" role="status" aria-busy="true">
          <Skeleton className="h-8 w-48" />
          <Skeleton className="h-10 w-full max-w-md" />
          <Skeleton className="h-40 w-full" />
        </div>
      }
    >
      <JobsPageInner />
    </Suspense>
  );
}
