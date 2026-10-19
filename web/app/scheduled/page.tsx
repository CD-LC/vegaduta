"use client";

import { useState } from "react";
import { CalendarClock } from "lucide-react";
import AppShell from "../components/AppShell";
import PageHeader from "../components/ax/PageHeader";
import { usePageReady } from "../components/RouteTransition";
import ScheduledTasksPanel from "../components/scheduledTasks/ScheduledTasksPanel";
import { useTranslations } from "../lib/i18n";

// Scheduled tasks (core com.agenticai.core.agents.scheduledtask): prompts an agent runs by itself on a schedule -
// "every weekday at 8am summarise my unread email and send me the top 5" - created here or by asking an agent in
// chat (the schedule_task tool, which always asks for confirmation first). The page owns the shell and the
// readiness signal; the panel owns everything else, including the "not enabled" state.
export default function ScheduledTasksPage() {
  const t = useTranslations("scheduledTasks");
  const [loaded, setLoaded] = useState(false);

  usePageReady(loaded);

  return (
    <AppShell eyebrow={t("eyebrow")}>
      <PageHeader icon={CalendarClock} title={t("pageTitle")} subtitle={t("pageSubtitle")} />
      <ScheduledTasksPanel onLoaded={() => setLoaded(true)} />
    </AppShell>
  );
}
