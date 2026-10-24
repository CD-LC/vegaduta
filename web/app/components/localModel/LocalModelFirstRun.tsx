"use client";

import Link from "next/link";
import { Cpu } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import EmptyState from "../EmptyState";
import { Button } from "../ui/button";

/** First-run state on the Providers page: the setup card when nothing serves yet, or the
 *  "your local model already answers" card when the install's pool row carries a model
 *  (the platform pool is not a tenant provider, so the provider list stays empty). */
export default function LocalModelFirstRun({ servingTag }: { servingTag?: string | null }) {
  const t = useTranslations("localModelWizard");
  return (
    <div data-testid="local-model-first-run" className="mb-4">
      <EmptyState
        icon={Cpu}
        title={servingTag ? t("firstRunServingTitle") : t("firstRunTitle")}
        hint={servingTag ? t("firstRunServingBody", { tag: servingTag }) : t("firstRunBody")}
        action={
          <Button asChild size="sm" className="mt-3">
            <Link href={servingTag ? "/agents" : "/providers/local-model"}>
              {servingTag ? t("firstRunServingCta") : t("firstRunCta")}
            </Link>
          </Button>
        }
      />
    </div>
  );
}
