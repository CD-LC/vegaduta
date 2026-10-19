"use client";

import Link from "next/link";
import { Cpu } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import EmptyState from "../EmptyState";
import { Button } from "../ui/button";

/** First-run empty state: shown on the Providers page when no provider has been added yet. */
export default function LocalModelFirstRun() {
  const t = useTranslations("localModelWizard");
  return (
    <div data-testid="local-model-first-run" className="mb-4">
      <EmptyState
        icon={Cpu}
        title={t("firstRunTitle")}
        hint={t("firstRunBody")}
        action={
          <Button asChild size="sm" className="mt-3">
            <Link href="/providers/local-model">{t("firstRunCta")}</Link>
          </Button>
        }
      />
    </div>
  );
}
