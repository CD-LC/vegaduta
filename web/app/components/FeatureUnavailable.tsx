"use client";

import { Lock } from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { useTranslations } from "../lib/i18n";
import { docPageHref } from "../lib/siteLinks";
import type { UnavailableReason } from "../lib/editionCapabilities";
import EmptyState from "./EmptyState";

interface FeatureUnavailableProps {
  /** Display name of the feature (the same label the nav uses). */
  feature: string;
  reason: UnavailableReason;
  /** "personal" = the personal installation; "deployment" = this deployment lacks a service. */
  edition?: "personal" | "deployment";
  icon?: LucideIcon;
}

/**
 * The one "not available on this install" state: icon, title, one-line reason, and how to get the
 * feature (Lite or Full edition, via Docker Compose or the Helm chart). Built on EmptyState so it
 * matches every other empty well in the console.
 */
export default function FeatureUnavailable({ feature, reason, edition = "personal", icon = Lock }: FeatureUnavailableProps) {
  const t = useTranslations("featureUnavailable");
  const title = edition === "personal" ? t("titlePersonal", { feature }) : t("titleDeployment", { feature });
  return (
    <div className="mx-auto w-full max-w-2xl py-10" data-testid="feature-unavailable" role="status">
      <EmptyState
        icon={icon}
        title={title}
        hint={
          <>
            {t(`reason.${reason}`)}
            <br />
            {t("guidance")}
          </>
        }
        action={
          <a className="btn btn-ghost mt-2" href={docPageHref("installation")} target="_blank" rel="noopener noreferrer">
            {t("docsLink")}
          </a>
        }
      />
    </div>
  );
}
