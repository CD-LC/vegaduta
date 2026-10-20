"use client";

import { Server } from "lucide-react";
import AppShell from "../../components/AppShell";
import PageHeader from "../../components/ax/PageHeader";
import LocalModelWizard from "../../components/localModel/LocalModelWizard";
import LocalModelSettingsCard from "../../components/localModel/LocalModelSettingsCard";
import { Alert, AlertDescription, AlertTitle } from "../../components/ui/alert";
import { localModelAllowed } from "../../lib/personalInstall";
import { useTranslations } from "../../lib/i18n";

/** /providers/local-model - guided setup of an Ollama-served open-weight model and a first agent on it.
 *  Personal installs only: on lite and full the LLM pools are platform infrastructure the operator
 *  deploys; a tenant browsing the catalogue may look, but nothing here may change the server. */
export default function LocalModelSetupPage() {
  const t = useTranslations("localModelWizard");
  const tp = useTranslations("providersPage");
  return (
    <AppShell eyebrow={tp("pageEyebrow")}>
      <PageHeader title={t("title")} subtitle={t("subtitle")} />
      {localModelAllowed() ? (
        <>
          <LocalModelSettingsCard className="mb-6 max-w-3xl" />
          <LocalModelWizard className="max-w-3xl" />
        </>
      ) : (
        <Alert className="max-w-3xl" data-testid="localmodel-shared-notice">
          <Server className="size-4" />
          <AlertTitle>{t("sharedTitle")}</AlertTitle>
          <AlertDescription>
            <p>{t("sharedBody")}</p>
          </AlertDescription>
        </Alert>
      )}
    </AppShell>
  );
}
