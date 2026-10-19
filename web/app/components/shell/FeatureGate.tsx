"use client";

import type { ReactNode } from "react";
import { usePathname } from "next/navigation";
import { useTranslations } from "../../lib/i18n";
import { unavailableFeature } from "../../lib/editionCapabilities";
import { isPersonalInstall } from "../../lib/personalInstall";
import FeatureUnavailable from "../FeatureUnavailable";

/** Renders the shared "not available" state instead of a page this install cannot run. */
export default function FeatureGate({ children }: { children: ReactNode }) {
  const pathname = usePathname() ?? "";
  const tNav = useTranslations("nav");
  const gate = unavailableFeature(pathname);
  if (!gate) return <>{children}</>;
  return (
    <FeatureUnavailable
      feature={tNav(gate.navKey)}
      reason={gate.reason}
      edition={gate.scope === "personal" && isPersonalInstall() ? "personal" : "deployment"}
      icon={gate.icon}
    />
  );
}
