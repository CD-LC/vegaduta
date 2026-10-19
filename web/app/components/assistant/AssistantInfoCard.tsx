"use client";

// The header "i": what this assistant actually is, and where the person can
// take it further - create agents, tune them, give them tools - with the
// Academy (free) as the learning path. academyUrl() comes from
// NEXT_PUBLIC_ACADEMY_URL; personal/compose builds default it to the public
// Academy, so the link is never a dead localhost URL.

import { Info } from "lucide-react";
import Link from "next/link";
import { useTranslations } from "../../lib/i18n";
import { academyUrl } from "../../lib/academyUrl";
import { Button } from "../ui/button";
import { Popover, PopoverContent, PopoverTrigger } from "../ui/popover";

export default function AssistantInfoCard() {
  const t = useTranslations("assistant");
  const academy = academyUrl();
  return (
    <Popover>
      <PopoverTrigger asChild>
        <Button
          type="button"
          size="icon"
          variant="ghost"
          aria-label={t("infoAria")}
          title={t("infoAria")}
        >
          <Info size={16} aria-hidden="true" />
        </Button>
      </PopoverTrigger>
      <PopoverContent className="w-72 space-y-3 p-3 text-sm">
        <p className="font-medium text-foreground">{t("infoTitle")}</p>
        <p className="text-xs leading-relaxed text-muted-foreground">{t("infoBody")}</p>
        <ul className="space-y-1 text-xs text-muted-foreground">
          <li className="flex gap-1.5">
            <span aria-hidden="true" className="text-primary">•</span>
            <Link href="/agents" className="text-primary underline-offset-2 hover:underline">
              {t("infoCreateAgents")}
            </Link>
          </li>
          <li className="flex gap-1.5">
            <span aria-hidden="true" className="text-primary">•</span>
            <span>{t("infoTune")}</span>
          </li>
          <li className="flex gap-1.5">
            <span aria-hidden="true" className="text-primary">•</span>
            <Link href="/connect" className="text-primary underline-offset-2 hover:underline">
              {t("infoAddTools")}
            </Link>
          </li>
        </ul>
        {academy && (
          <a
            href={academy}
            target="_blank"
            rel="noopener noreferrer"
            className="block rounded-md border border-border bg-secondary/40 px-2.5 py-2 text-xs font-medium text-primary underline-offset-2 hover:underline"
          >
            {t("infoAcademy")}
          </a>
        )}
      </PopoverContent>
    </Popover>
  );
}
