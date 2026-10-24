"use client";

import Link from "next/link";
import { BookOpen, SlidersHorizontal } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import AppShell from "../../components/AppShell";
import { useAuth } from "../../components/AuthProvider";
import PageHeader from "../../components/ax/PageHeader";
import { Button } from "../../components/ui/button";
import RuntimeSettingsView from "./RuntimeSettingsView";
import { settingsRoleFor } from "./settingsRole";

// Workspace runtime settings: the behaviour knobs of the agent runtime (limits, approvals, quality checks,
// guardrails, memory, delegation, models) for the whole workspace. Reading is open to every member, so the page
// renders for everyone and shows the values read-only below tenant admin; the server enforces the writes.
//
// Deep links: /admin/runtime-settings#<setting key> scrolls to and focuses that setting (for docs and error
// messages), #group-<group id> scrolls to a group.

export default function AdminRuntimeSettingsPage() {
  const t = useTranslations("runtimeSettings");
  const auth = useAuth();
  const role = settingsRoleFor(auth);

  return (
    <AppShell eyebrow={t("eyebrow")}>
      <PageHeader
        eyebrow={t("eyebrow")}
        title={t("title")}
        subtitle={t("subtitle")}
        icon={SlidersHorizontal}
        actions={
          <Button asChild variant="outline" size="sm">
            <Link href="/help/docs" data-testid="runtime-docs-link">
              <BookOpen aria-hidden="true" />
              {t("docsLink")}
            </Link>
          </Button>
        }
      />
      <RuntimeSettingsView viewerRole={role} />
    </AppShell>
  );
}
