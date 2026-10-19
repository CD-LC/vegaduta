"use client";

import AppShell from "../components/AppShell";
import PageHeader from "../components/ax/PageHeader";
import { useTranslations } from "../lib/i18n";
import StartFlow from "./StartFlow";

/**
 * /start - the guided first-agent flow (onboarding lane, 2026-10-04).
 *
 * Reached by link only: the dashboard "Start here" card for a member with no
 * agents, or a direct URL. It is not a nav item (the sidebar is full) and
 * nothing redirects here. Authenticated like every console route, so it needs
 * no entry in publicRoutes.
 */
export default function StartPage() {
  const t = useTranslations("startFlow");
  return (
    <AppShell eyebrow={t("eyebrow")}>
      <PageHeader eyebrow={t("eyebrow")} title={t("title")} subtitle={t("subtitle")} />
      <StartFlow />
    </AppShell>
  );
}
