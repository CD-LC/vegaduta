"use client";

import { PackageCheck } from "lucide-react";
import AppShell from "../components/AppShell";
import AddonsPanel from "../components/addons/AddonsPanel";
import { Alert, AlertDescription, AlertTitle } from "../components/ui/alert";
import { isFullInstall } from "../lib/personalInstall";
import { useTranslations } from "../lib/i18n";

/**
 * /add-ons - optional extras for personal and lite installs (web search, documents, the Assistant's
 * browser, local model packs). Backed by core/providers `AddonController` (/api/addons); what the page
 * shows and why is written down in docs/console/add-ons.md. On the full tier every add-on already
 * ships - the page stays reachable for deep links but explains rather than offering switches.
 */
export default function AddonsPage() {
  const t = useTranslations("addons");
  return (
    <AppShell eyebrow={t("eyebrow")}>
      {isFullInstall() ? (
        <Alert className="max-w-2xl" data-testid="addons-full-managed">
          <PackageCheck className="size-4" />
          <AlertTitle>{t("fullManagedTitle")}</AlertTitle>
          <AlertDescription>
            <p>{t("fullManagedBody")}</p>
          </AlertDescription>
        </Alert>
      ) : (
        <AddonsPanel />
      )}
    </AppShell>
  );
}
