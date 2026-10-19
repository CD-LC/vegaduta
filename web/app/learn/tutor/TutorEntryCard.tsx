"use client";

import Link from "next/link";
import { ArrowRight, GraduationCap } from "lucide-react";

import { Button } from "../../components/ui/button";
import { Card } from "../../components/ui/card";
import { useTranslations } from "../../lib/i18n";
import { TUTOR_ROUTE, useTutorAvailable } from "./tutorAvailability";

/**
 * The way in from /learn: "Learn anything with a personal tutor". Renders
 * nothing until the server confirms the tutor is switched on, so a deployment
 * without it never shows a card that leads nowhere.
 */
export default function TutorEntryCard() {
  const t = useTranslations("tutorPage");
  const available = useTutorAvailable();
  if (!available) return null;
  return (
    <Card className="mb-8 flex-row flex-wrap items-center justify-between gap-4 p-5" data-testid="tutor-entry-card">
      <div className="flex min-w-0 items-start gap-3">
        <span className="flex size-10 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary">
          <GraduationCap className="size-5" aria-hidden="true" />
        </span>
        <div className="min-w-0">
          <h2 className="text-base font-semibold tracking-tight text-foreground">{t("entryTitle")}</h2>
          <p className="mt-1 max-w-[68ch] text-sm leading-relaxed text-muted-foreground">{t("entryBody")}</p>
        </div>
      </div>
      <Button asChild className="shrink-0">
        <Link href={TUTOR_ROUTE}>
          {t("entryCta")}
          <ArrowRight aria-hidden="true" />
        </Link>
      </Button>
    </Card>
  );
}
