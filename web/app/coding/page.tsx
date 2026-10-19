"use client";

import { useState } from "react";
import { FileDiff } from "lucide-react";
import AppShell from "../components/AppShell";
import PageHeader from "../components/ax/PageHeader";
import { usePageReady } from "../components/RouteTransition";
import CodingTasksPanel from "../components/codingTasks/CodingTasksPanel";
import { useTranslations } from "../lib/i18n";

// Coding tasks (core com.agenticai.core.agents.coding): an agent's model edits a repository in an isolated sandbox
// until the tests pass, then a person reviews the diff and approves or rejects it - created here or by asking an
// agent in chat (the coding_task tool, which always asks for confirmation first). The page owns the shell and the
// readiness signal; the panel owns everything else, including the "not enabled" state.
export default function CodingTasksPage() {
  const t = useTranslations("codingTasks");
  const [loaded, setLoaded] = useState(false);

  usePageReady(loaded);

  return (
    <AppShell eyebrow={t("eyebrow")}>
      <PageHeader icon={FileDiff} title={t("pageTitle")} subtitle={t("pageSubtitle")} />
      <CodingTasksPanel onLoaded={() => setLoaded(true)} />
    </AppShell>
  );
}
