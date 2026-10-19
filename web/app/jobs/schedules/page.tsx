"use client";

import { useEffect, useState } from "react";
import { CalendarClock } from "lucide-react";
import { api } from "../../lib/api";
import AppShell from "../../components/AppShell";
import PageHeader from "../../components/ax/PageHeader";
import SchedulePanel from "../../components/jobs/SchedulePanel";
import { usePageReady } from "../../components/RouteTransition";
import { useTranslations } from "../../lib/i18n";

/**
 * An agent's recurring job schedule (cron or interval, with a sentinel) - the "Schedules" tab of
 * the old /jobs page, kept whole because it is a different thing from /scheduled's routines: it
 * re-runs a job goal on the agent-jobs runtime. Reached from the Work rail's Routines section.
 */
export default function JobSchedulesPage() {
  const t = useTranslations("jobsPage");
  const [agents, setAgents] = useState<unknown[]>([]);
  const [loaded, setLoaded] = useState(false);
  useEffect(() => {
    api
      .listAgents()
      .then((data: unknown) => setAgents(Array.isArray(data) ? data : []))
      .catch(() => setAgents([]))
      .finally(() => setLoaded(true));
  }, []);
  usePageReady(loaded);
  return (
    <AppShell eyebrow={t("eyebrow")}>
      <PageHeader icon={CalendarClock} title={t("tabSchedules")} subtitle={t("schedulesSubtitle")} />
      <SchedulePanel agents={agents as never} />
    </AppShell>
  );
}
