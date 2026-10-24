"use client";

import { Server } from "lucide-react";
import AppShell from "../../components/AppShell";
import PageHeader from "../../components/ax/PageHeader";
import LocalModelWizard from "../../components/localModel/LocalModelWizard";
import LocalModelSettingsCard from "../../components/localModel/LocalModelSettingsCard";
import { Alert, AlertDescription, AlertTitle } from "../../components/ui/alert";
import { localModelAllowed } from "../../lib/personalInstall";
import { localModelManagedHere } from "../../lib/api/localModels";
import { useTranslations } from "../../lib/i18n";
import { useEffect, useState } from "react";

/** /providers/local-model - guided setup of an Ollama-served open-weight model and a first agent on it.
 *  Personal installs always; on lite and full the wizard opens when the install actually runs a
 *  local model (reachable model server or a pool row serving it), so a self-hosted operator can
 *  manage it while a hosted tenant still sees the "managed for you" notice. */
export default function LocalModelSetupPage() {
  const t = useTranslations("localModelWizard");
  const tp = useTranslations("providersPage");
  const [allowed, setAllowed] = useState<boolean | null>(() => (localModelAllowed() ? true : null));
  useEffect(() => {
    if (allowed !== null) return undefined;
    let live = true;
    void localModelManagedHere().then((v) => {
      if (live) setAllowed(v);
    });
    return () => {
      live = false;
    };
  }, [allowed]);
  return (
    <AppShell eyebrow={tp("pageEyebrow")}>
      <PageHeader title={t("title")} subtitle={t("subtitle")} />
      {allowed === true ? (
        <>
          <LocalModelSettingsCard className="mb-6 max-w-3xl" />
          <LocalModelWizard className="max-w-3xl" />
        </>
      ) : allowed === null ? null : (
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
