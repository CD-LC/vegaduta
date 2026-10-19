"use client";

import AppShell from "../components/AppShell";
import PageHeader from "../components/ax/PageHeader";
import NotificationsList from "../components/notifications/NotificationsList";
import { useTranslations } from "../lib/i18n";

// Full notification history (the bell popover only shows the latest few).
// Any authenticated tenant member; no scope gate, same as the bell.
export default function NotificationsPage() {
  const t = useTranslations("auditNotifications");
  return (
    <AppShell eyebrow={t("notifPageEyebrow")}>
      <PageHeader title={t("notifPageTitle")} subtitle={t("notifPageSubtitle")} />
      <NotificationsList />
    </AppShell>
  );
}
