"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { ArrowRight, Search } from "lucide-react";
import { api } from "../../lib/api";
import { Card } from "../ui/card";
import { Button } from "../ui/button";
import { useTranslations } from "../../lib/i18n";
import { researchAvailability } from "./researchRun";

/**
 * A one-row pointer to /research for screens where people already ask for
 * things (home, Quick Task). It asks the server whether research can be
 * started right now and renders NOTHING unless the answer is yes - switched
 * off, missing search key, an older core without the endpoint, or a failed
 * request all look the same from here: no card. The status call is quiet, so
 * a deployment without the feature never sees an error for it.
 */
export default function ResearchEntryCard({ className }: { className?: string }) {
  const t = useTranslations("researchPage");
  const [ready, setReady] = useState(false);

  useEffect(() => {
    let cancelled = false;
    Promise.resolve()
      .then(() => api.getResearchStatus())
      .then((status) => {
        if (!cancelled) setReady(researchAvailability(status) === "ready");
      })
      .catch(() => {
        // Not available here - stay hidden.
      });
    return () => {
      cancelled = true;
    };
  }, []);

  if (!ready) return null;

  return (
    <Card className={`gap-3 p-4 sm:flex-row sm:items-center ${className ?? ""}`} data-testid="research-entry-card">
      <div className="flex min-w-0 flex-1 items-start gap-3">
        <span className="flex size-9 shrink-0 items-center justify-center rounded-full bg-primary/10 text-primary">
          <Search className="size-4" aria-hidden="true" />
        </span>
        <div className="min-w-0">
          <p className="text-sm font-medium text-foreground">{t("entryTitle")}</p>
          <p className="text-xs text-muted-foreground">{t("entryBody")}</p>
        </div>
      </div>
      <Button asChild size="sm" variant="outline" className="shrink-0 self-start sm:self-center">
        <Link href="/research">
          {t("entryCta")} <ArrowRight className="size-4" aria-hidden="true" />
        </Link>
      </Button>
    </Card>
  );
}
