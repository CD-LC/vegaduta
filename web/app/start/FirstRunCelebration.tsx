"use client";

import Link from "next/link";
import { ArrowRight, BookOpen, Compass, PartyPopper, SlidersHorizontal, Workflow } from "lucide-react";
import { Button } from "../components/ui/button";
import { Card } from "../components/ui/card";
import { academyHref } from "../lib/academyUrl";
import { elapsedParts } from "./events";

type T = (key: string, params?: Record<string, string | number>) => string;

/**
 * Shown after the first completed turn with the agent just created. It is a
 * status region, not a modal: it never steals focus or blocks the chat, and it
 * adds no motion at all.
 *
 * The time quoted is the real measured one (seconds since the first /start
 * visit, kept in the flow state), or omitted when it is unknown - never a
 * rounded-down flattering number.
 */
export default function FirstRunCelebration({
  agentId,
  seconds,
  onAnother,
  t,
}: {
  agentId: string;
  seconds: number | null;
  onAnother: () => void;
  t: T;
}) {
  const parts = elapsedParts(seconds);
  const time = parts
    ? parts.minutes > 0
      ? t("elapsedMinSec", { min: parts.minutes, sec: parts.seconds })
      : t("elapsedSec", { sec: parts.seconds })
    : null;

  const next: Array<{ href: string; label: string; icon: typeof SlidersHorizontal }> = [
    { href: `/agents?agentId=${encodeURIComponent(agentId)}`, label: t("nextCustomize"), icon: SlidersHorizontal },
    { href: "/knowledge", label: t("nextDocuments"), icon: BookOpen },
    { href: "/workflows", label: t("nextAutomate"), icon: Workflow },
    { href: academyHref(), label: t("nextLearn"), icon: Compass },
  ];

  return (
    <Card
      className="gap-4 border-primary/40 bg-primary/5 p-5"
      data-testid="start-first-run"
      role="status"
      aria-live="polite"
    >
      <div className="flex items-start gap-3">
        <span className="flex size-10 shrink-0 items-center justify-center rounded-lg bg-primary/15 text-primary">
          <PartyPopper className="size-5" aria-hidden="true" />
        </span>
        <div className="min-w-0">
          <h3 className="text-base font-semibold tracking-tight text-foreground">{t("celebrationTitle")}</h3>
          <p className="mt-1 text-sm leading-relaxed text-muted-foreground">
            {time ? t("celebrationBody", { time }) : t("celebrationBodyNoTime")}
          </p>
        </div>
      </div>

      <div>
        <h4 className="text-sm font-semibold text-foreground">{t("nextHeading")}</h4>
        <ul className="mt-2 grid list-none grid-cols-[repeat(auto-fit,minmax(min(100%,13rem),1fr))] gap-2 p-0">
          {next.map((item) => (
            <li key={item.href} className="min-w-0">
              <Button
                asChild
                variant="outline"
                size="sm"
                className="h-auto w-full justify-start whitespace-normal py-2 text-start"
              >
                <Link href={item.href}>
                  <item.icon aria-hidden="true" />
                  {item.label}
                </Link>
              </Button>
            </li>
          ))}
        </ul>
      </div>

      <div>
        <Button type="button" variant="ghost" size="sm" onClick={onAnother}>
          {t("tryAnother")}
          <ArrowRight aria-hidden="true" />
        </Button>
      </div>
    </Card>
  );
}
