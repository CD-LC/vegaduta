"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { ChevronRight } from "lucide-react";
import { useAuth } from "../AuthProvider";
import { useTranslations } from "../../lib/i18n";
import { CONTROLS_HELP_HREF, controlAreaForPath, reachableAreas } from "./controlAreas";

/**
 * Wayfinding for the control pages (runtime settings, instructions, approvals, guardrails, turn inspector): a
 * breadcrumb (Console > Controls > this page) and a strip of links to the other control areas, so a person who found
 * one of them can find the rest without going back to the menu. Renders nothing on any other page, and never offers a
 * page the person's scopes would refuse.
 */
export default function ControlsBreadcrumb() {
  const pathname = usePathname();
  const area = controlAreaForPath(pathname);
  if (!area) return null;
  return <ControlsBreadcrumbBody currentId={area.id} />;
}

function ControlsBreadcrumbBody({ currentId }: { currentId: string }) {
  const t = useTranslations("controls");
  const { isAdmin, hasPermission } = useAuth();
  const related = reachableAreas({ isAdmin, hasPermission }).filter((a) => a.id !== currentId);
  return (
    <div className="mb-4 flex flex-col gap-2 text-sm sm:flex-row sm:flex-wrap sm:items-center sm:justify-between" data-testid="controls-wayfinding">
      <nav aria-label={t("breadcrumb.label")}>
        <ol className="flex flex-wrap items-center gap-1 text-muted-foreground">
          <li>
            <Link href="/" className="rounded underline-offset-2 hover:text-foreground hover:underline focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-ring">
              {t("breadcrumb.console")}
            </Link>
          </li>
          <li aria-hidden="true">
            <ChevronRight className="size-3.5" />
          </li>
          <li>
            <Link href={CONTROLS_HELP_HREF} className="rounded underline-offset-2 hover:text-foreground hover:underline focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-ring">
              {t("breadcrumb.controls")}
            </Link>
          </li>
          <li aria-hidden="true">
            <ChevronRight className="size-3.5" />
          </li>
          <li aria-current="page" className="font-medium text-foreground">
            {t(`areas.${currentId}.title`)}
          </li>
        </ol>
      </nav>
      <nav aria-label={t("related.label")}>
        <ul className="flex flex-wrap items-center gap-x-3 gap-y-1">
          {related.map((a) => (
            <li key={a.id}>
              <Link
                href={a.href}
                className="rounded text-primary underline-offset-2 hover:underline focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-ring"
              >
                {t(`areas.${a.id}.title`)}
              </Link>
            </li>
          ))}
        </ul>
      </nav>
    </div>
  );
}
