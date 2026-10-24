"use client";

import { Signpost } from "lucide-react";
import AppShell from "../../components/AppShell";
import PageHeader from "../../components/ax/PageHeader";
import ControlsFinder from "../../components/controls/ControlsFinder";
import { useTranslations } from "../../lib/i18n";

// "Where do I control...?": the map from what you want to change to the page that changes it. The settings list is
// built from the settings catalog (GET /api/settings/catalog), so it is complete by construction. Each section has an
// id equal to the catalog's docsAnchor, so /help/controls#<docsAnchor> opens that part of the list.
export default function ControlsHelpPage() {
  const t = useTranslations("controls");
  return (
    <AppShell eyebrow={t("eyebrow")}>
      <PageHeader eyebrow={t("eyebrow")} title={t("title")} subtitle={t("subtitle")} icon={Signpost} />
      <ControlsFinder />
    </AppShell>
  );
}
