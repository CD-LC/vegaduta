"use client";

import AppShell from "../../components/AppShell";
import PageHeader from "../../components/ax/PageHeader";
import LocalModelWizard from "../../components/localModel/LocalModelWizard";
import LocalModelSettingsCard from "../../components/localModel/LocalModelSettingsCard";
import { useTranslations } from "../../lib/i18n";

/** /providers/local-model - guided setup of an Ollama-served open-weight model and a first agent on it. */
export default function LocalModelSetupPage() {
  const t = useTranslations("localModelWizard");
  const tp = useTranslations("providersPage");
  return (
    <AppShell eyebrow={tp("pageEyebrow")}>
      <PageHeader title={t("title")} subtitle={t("subtitle")} />
      <LocalModelSettingsCard className="mb-6 max-w-3xl" />
      <LocalModelWizard className="max-w-3xl" />
    </AppShell>
  );
}
