"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { ArrowRight, Lightbulb, Sparkles, X } from "lucide-react";
import { Badge } from "../components/ui/badge";
import { Button } from "../components/ui/button";
import { Section } from "../components/ax/page-kit";
import {
  FEATURED_TEMPLATE_KEYS,
  WORKFLOW_TEMPLATES,
  templateAgentSlots,
  templateNameKey,
  templateTaglineKey,
} from "../components/workflowTemplates";
import { useTranslations } from "../lib/i18n";

const STORAGE_KEY = "vd.workflows.getStartedHidden";

/**
 * "Get started" block for /workflows: the industry-recipes banner and the
 * featured-template strip, shown below the workflow list and dismissible
 * (remembered per browser). A hidden block leaves a one-line link to bring it back.
 */
export default function WorkflowsGetStarted() {
  const t = useTranslations("workflowsPage");
  const tTemplates = useTranslations("workflowTemplates");
  const tg = useTranslations("workflowTemplatesPage");
  const tu = useTranslations("workflowStudioUi");
  const [hidden, setHidden] = useState(false);

  useEffect(() => {
    try {
      setHidden(window.localStorage.getItem(STORAGE_KEY) === "1");
    } catch {
      /* storage unavailable: stay visible */
    }
  }, []);

  const setAndStore = (next: boolean) => {
    setHidden(next);
    try {
      if (next) window.localStorage.setItem(STORAGE_KEY, "1");
      else window.localStorage.removeItem(STORAGE_KEY);
    } catch {
      /* ignore */
    }
  };

  if (hidden) {
    return (
      <Button type="button" variant="link" size="sm" className="px-0" onClick={() => setAndStore(false)}>
        {tu("getStartedShow")}
      </Button>
    );
  }

  return (
    <Section
      title={tu("getStartedTitle")}
      actions={
        <Button type="button" variant="ghost" size="sm" onClick={() => setAndStore(true)}>
          <X className="size-3.5" aria-hidden="true" />
          {tu("getStartedDismiss")}
        </Button>
      }
    >
      <Link
        href="/help/workflow-recipes"
        className="group flex flex-col items-start gap-4 overflow-hidden rounded-xl border border-primary/30 bg-gradient-to-r from-primary/15 via-primary/5 to-transparent p-4 transition-colors hover:border-primary/50 sm:flex-row sm:items-center sm:gap-5 sm:p-5"
      >
        <span className="flex size-11 shrink-0 items-center justify-center rounded-xl bg-primary/15 text-primary ring-1 ring-inset ring-primary/25">
          <Lightbulb className="size-5" aria-hidden="true" />
        </span>
        <div className="min-w-0 flex-1 space-y-1">
          <div className="flex flex-wrap items-center gap-2">
            <h3 className="text-base font-semibold tracking-tight text-foreground">{tu("recipesHeading")}</h3>
            <Badge variant="accent" className="gap-1">
              <Sparkles className="size-3" aria-hidden="true" />
              {tu("recipesGuide")}
            </Badge>
          </div>
          <p className="text-sm leading-relaxed text-muted-foreground">{tu("recipesBody")}</p>
        </div>
        <span className="inline-flex shrink-0 items-center gap-1.5 rounded-md bg-primary px-3.5 py-2 text-sm font-medium text-primary-foreground shadow-sm transition-transform group-hover:translate-x-0.5">
          {tu("recipesCta")}
          <ArrowRight className="size-4" aria-hidden="true" />
        </span>
      </Link>

      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h3 className="text-sm font-semibold tracking-tight text-foreground">{t("examplesTitle")}</h3>
        <Link href="/workflows/templates" className="text-sm text-primary hover:underline">
          {t("browseAllTemplates", { count: WORKFLOW_TEMPLATES.length })}
        </Link>
      </div>
      <ul className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
        {FEATURED_TEMPLATE_KEYS.map((key) => {
          const template = WORKFLOW_TEMPLATES.find((tpl) => tpl.key === key);
          if (!template) return null;
          const Icon = template.icon;
          return (
            <li key={key}>
              <Link
                href={`/workflows?template=${encodeURIComponent(key)}`}
                className="flex h-full items-start gap-3 rounded-xl border border-border bg-card p-4 transition-colors hover:border-primary/40"
              >
                <span className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary">
                  <Icon className="size-4" aria-hidden="true" />
                </span>
                <span className="min-w-0 space-y-1">
                  <span className="flex flex-wrap items-center gap-2 text-sm font-semibold text-foreground">
                    {tTemplates(templateNameKey(key))}
                    {templateAgentSlots(template) === 0 && <Badge variant="accent">{tg("noAgentNeeded")}</Badge>}
                  </span>
                  <span className="block text-sm leading-relaxed text-muted-foreground">
                    {tTemplates(templateTaglineKey(key))}
                  </span>
                </span>
              </Link>
            </li>
          );
        })}
      </ul>
    </Section>
  );
}
