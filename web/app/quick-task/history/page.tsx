"use client";

import Link from "next/link";
import AppShell from "../../components/AppShell";
import QuickTaskHistoryList from "../../components/dashboard/QuickTaskHistoryList";
import PageHeader from "../../components/ax/PageHeader";
import { Section } from "../../components/ax/page-kit";
import { Button } from "../../components/ui/button";
import { useTranslations } from "../../lib/i18n";

// Direct feedback: "task history should be in another page" - previously
// this list lived inline on the Quick Task screen (toggled open/closed in
// place). Given its own route here, the same way /admin/audit-tail gives
// AdminEventTail a whole page instead of a small in-place widget (#553).
// Same data/component, no new backend: QuickTaskHistoryList still reads
// GET /api/quick-task/recent and /{taskId}.
export default function QuickTaskHistoryPage() {
  const t = useTranslations("quickTaskHistoryPage");
  return (
    <AppShell eyebrow={t("eyebrow")}>
      <PageHeader
        eyebrow={t("eyebrow")}
        title={t("title")}
        subtitle={t("hintPrefix")}
        actions={
          <Button asChild variant="outline" size="sm">
            <Link href="/quick-task">{t("quickTaskLink")}</Link>
          </Button>
        }
      />
      <Section>
        <QuickTaskHistoryList />
      </Section>
    </AppShell>
  );
}
