"use client";

// /assistant - one box for a laptop (docs/architecture/PERSONAL-ASSISTANT-SURFACE-2026-10-17.md, Phase 1).
// Type anything; the dispatch ladder picks a chat turn, a background job, a routine or a research run,
// and the Assistant agent does the rest with its tools. Everything here calls endpoints that already
// exist; the pane and its hooks live under components/assistant.
// Since 2026-10-18 it is also where the Playground, Jobs and Quick Task pages lead: ?agent= picks
// any agent of the workspace, ?mode=background|task picks how the box sends, ?prompt= prefills it.
import { Suspense } from "react";
import AppShell from "../components/AppShell";
import AssistantPane from "../components/assistant/AssistantPane";
import { useTranslations } from "../lib/i18n";

export default function AssistantPage() {
  const t = useTranslations("assistant");
  return (
    <AppShell title={t("title")} eyebrow={t("eyebrow")} fillViewport collapsibleRail>
      {/* useSearchParams in the pane needs a Suspense boundary (same as /agents, /jobs did). */}
      <Suspense fallback={null}>
        <AssistantPane />
      </Suspense>
    </AppShell>
  );
}
