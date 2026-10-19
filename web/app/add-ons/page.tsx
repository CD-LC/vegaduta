"use client";

import AppShell from "../components/AppShell";
import AddonsPanel from "../components/addons/AddonsPanel";
import { useTranslations } from "../lib/i18n";

/**
 * /add-ons - optional extras for the personal install (web search, documents, the Assistant's browser,
 * local model packs). Backed by core/providers `AddonController` (/api/addons); what the page shows and why
 * is written down in docs/console/add-ons.md.
 */
export default function AddonsPage() {
  const t = useTranslations("addons");
  return (
    <AppShell eyebrow={t("eyebrow")}>
      <AddonsPanel />
    </AppShell>
  );
}
