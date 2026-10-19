"use client";

import { AlertTriangle, Info } from "lucide-react";
import { Alert, AlertDescription, AlertTitle } from "../ui/alert";
import { useTranslations } from "../../lib/i18n";
import type { ResearchAvailability } from "./researchRun";

/**
 * Says, in plain words, why research cannot be started right now. Renders
 * nothing when it is ready. Driven only by GET /api/research/status - the page
 * never guesses availability from a failed start.
 */
export default function ResearchStatusBanner({ availability }: { availability: ResearchAvailability }) {
  const t = useTranslations("researchPage");
  if (availability === "ready") return null;

  if (availability === "needsSearchKey") {
    return (
      <Alert variant="warning" data-testid="research-banner-needs-key">
        <AlertTriangle aria-hidden="true" />
        <AlertTitle>{t("needsKeyTitle")}</AlertTitle>
        <AlertDescription>{t("needsKeyBody")}</AlertDescription>
      </Alert>
    );
  }

  if (availability === "researchOff") {
    return (
      <Alert variant="info" data-testid="research-banner-off">
        <Info aria-hidden="true" />
        <AlertTitle>{t("offTitle")}</AlertTitle>
        <AlertDescription>{t("offBody")}</AlertDescription>
      </Alert>
    );
  }

  return (
    <Alert variant="info" data-testid="research-banner-unavailable">
      <Info aria-hidden="true" />
      <AlertTitle>{t("unavailableTitle")}</AlertTitle>
      <AlertDescription>{t("unavailableBody")}</AlertDescription>
    </Alert>
  );
}
