"use client";

import { useState } from "react";
import { Table2 } from "lucide-react";
import AppShell from "../components/AppShell";
import PageHeader from "../components/ax/PageHeader";
import { usePageReady } from "../components/RouteTransition";
import DataTablesPanel from "../components/dataTables/DataTablesPanel";
import { useTranslations } from "../lib/i18n";

// Data Tables (core com.agenticai.core.tables): spreadsheet-like tables that workflows (the Data Table node) and
// agents (data_table_query / data_table_write) read and write to keep state between runs. The page owns the shell
// and the readiness signal; the panel owns everything else, including the "not enabled" and "Premium" states.
export default function DataTablesPage() {
  const t = useTranslations("dataTables");
  const [loaded, setLoaded] = useState(false);

  usePageReady(loaded);

  return (
    <AppShell eyebrow={t("eyebrow")}>
      <PageHeader icon={Table2} title={t("pageTitle")} subtitle={t("pageSubtitle")} />
      <DataTablesPanel onLoaded={() => setLoaded(true)} />
    </AppShell>
  );
}
