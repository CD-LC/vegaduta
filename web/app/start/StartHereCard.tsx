"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { Rocket, X } from "lucide-react";
import { useTranslations } from "../lib/i18n";
import { Button } from "../components/ui/button";
import { Card } from "../components/ui/card";
import { dismissStartHere, isResumable, isStartHereDismissed, loadFlow } from "./flow";

/**
 * The non-forcing way into /start: a dismissible "Start here" card on the home
 * dashboard for a member with ZERO agents. It links; nothing redirects, and
 * dismissing it is remembered in this browser (localStorage, never required).
 *
 * Mounted by DashboardHome only while its first-run state is true (agents
 * loaded from a reachable core and there are none), so it never claims "you
 * have no agents" from a list that failed to load.
 *
 * Reads storage after mount, not during render, so the markup is identical on
 * the server and the first client render.
 */
export default function StartHereCard() {
  const t = useTranslations("startFlow");
  const [visible, setVisible] = useState(false);
  const [resume, setResume] = useState(false);

  useEffect(() => {
    if (isStartHereDismissed()) return;
    setResume(isResumable(loadFlow()));
    setVisible(true);
  }, []);

  if (!visible) return null;

  return (
    <Card className="gap-0 p-5" data-testid="start-here-card">
      <div className="flex items-start gap-4">
        <span className="flex size-10 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary">
          <Rocket className="size-5" aria-hidden="true" />
        </span>
        <div className="min-w-0 flex-1">
          <h2 className="text-base font-semibold tracking-tight text-foreground">{t("hereTitle")}</h2>
          <p className="mt-1 max-w-[68ch] text-sm leading-relaxed text-muted-foreground">{t("hereBody")}</p>
          <div className="mt-3">
            <Button asChild size="sm">
              <Link href="/start">{resume ? t("hereResume") : t("hereCta")}</Link>
            </Button>
          </div>
        </div>
        <Button
          type="button"
          variant="ghost"
          size="icon"
          className="-me-2 -mt-2 shrink-0"
          aria-label={t("hereDismiss")}
          onClick={() => {
            dismissStartHere();
            setVisible(false);
          }}
        >
          <X className="size-4" aria-hidden="true" />
        </Button>
      </div>
    </Card>
  );
}
