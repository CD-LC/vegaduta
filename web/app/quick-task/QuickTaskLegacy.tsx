"use client";

// Standalone Quick Task route (#355) - previously this same component only
// ever rendered gated behind the now-removed "Simple mode" Dashboard swap
// (see page.js's history). Promoted to its own route + nav entry so it's
// always reachable as a fast alternative to building a full workflow,
// instead of disappearing entirely along with Simple mode.
import AppShell from "../components/AppShell";
import SimpleTaskRunner from "../components/SimpleTaskRunner";
import { useTranslations } from "../lib/i18n";

export default function QuickTaskLegacy() {
  const t = useTranslations("quickTaskPage");
  // SimpleTaskRunner renders its own PageHeader (the single page <h1>), so the
  // shell only supplies the topbar eyebrow — passing `title` too would
  // duplicate the heading.
  return (
    <AppShell eyebrow={t("eyebrow")}>
      <SimpleTaskRunner />
    </AppShell>
  );
}
