"use client";

import { AlertTriangle } from "lucide-react";
import { Alert, AlertDescription, AlertTitle } from "../components/ui/alert";
import { Button } from "../components/ui/button";
import { useTranslations } from "../lib/i18n";

interface FilesAlertsProps {
  loadFailed: boolean;
  partialFailureCount: number;
  onRetryDocs: () => void;
  artifactsFailed: boolean;
  artifactPartialFailureCount: number;
  onRetryArtifacts: () => void;
  docsCapped: { shown: number; total: number } | null;
  /** Images / speech / video come back in one unpaged response; true when one reached the cap. */
  artifactsCapped: boolean;
  downloadError: string | null;
}

/** Every load-failure, partial-failure and truncation notice of the Files page. A failure never reads as "empty". */
export default function FilesAlerts(p: FilesAlertsProps) {
  const t = useTranslations("filesPage");
  const tf = useTranslations("tenantFiles");
  return (
    <>
      {p.loadFailed && (
        <Alert variant="destructive" className="mb-6">
          <AlertTriangle aria-hidden="true" />
          <AlertTitle>{t("docsFailedTitle")}</AlertTitle>
          <AlertDescription>
            <p>{t("docsFailedBody")}</p>
            <Button variant="outline" size="sm" className="mt-2" onClick={p.onRetryDocs}>
              {t("retry")}
            </Button>
          </AlertDescription>
        </Alert>
      )}

      {!p.loadFailed && p.partialFailureCount > 0 && (
        <Alert variant="warning" className="mb-6">
          <AlertTriangle aria-hidden="true" />
          <AlertTitle>{t("collectionsPartialTitle")}</AlertTitle>
          <AlertDescription>
            {t("collectionsPartialBody", { count: p.partialFailureCount })}{" "}
            <Button variant="outline" size="sm" className="ml-1" onClick={p.onRetryDocs}>
              {t("retry")}
            </Button>
          </AlertDescription>
        </Alert>
      )}

      {p.artifactsFailed && (
        <Alert variant="destructive" className="mb-6">
          <AlertTriangle aria-hidden="true" />
          <AlertTitle>{t("artifactsFailedTitle")}</AlertTitle>
          <AlertDescription>
            <p>{t("artifactsFailedBody")}</p>
            <Button variant="outline" size="sm" className="mt-2" onClick={p.onRetryArtifacts}>
              {t("retry")}
            </Button>
          </AlertDescription>
        </Alert>
      )}

      {!p.artifactsFailed && p.artifactPartialFailureCount > 0 && (
        <Alert variant="warning" className="mb-6">
          <AlertTriangle aria-hidden="true" />
          <AlertTitle>{t("artifactsPartialTitle")}</AlertTitle>
          <AlertDescription>
            {t("artifactsPartialBody", { count: p.artifactPartialFailureCount })}{" "}
            <Button variant="outline" size="sm" className="ml-1" onClick={p.onRetryArtifacts}>
              {t("retry")}
            </Button>
          </AlertDescription>
        </Alert>
      )}

      {p.docsCapped && (
        <Alert className="mb-6">
          <AlertTriangle aria-hidden="true" />
          <AlertDescription>{tf("documentsCapped", { shown: p.docsCapped.shown, total: p.docsCapped.total })}</AlertDescription>
        </Alert>
      )}

      {p.artifactsCapped && (
        <Alert className="mb-6" role="note">
          <AlertTriangle aria-hidden="true" />
          <AlertDescription>{t("artifactsCapped")}</AlertDescription>
        </Alert>
      )}

      {p.downloadError && (
        <Alert variant="destructive" className="mb-6">
          <AlertTriangle aria-hidden="true" />
          <AlertDescription>{p.downloadError}</AlertDescription>
        </Alert>
      )}
    </>
  );
}
