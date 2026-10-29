"use client";

import { useMemo, useState } from "react";
import { Search } from "lucide-react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import AppShell from "../../components/AppShell";
import {
  WORKFLOW_TEMPLATE_CATEGORIES,
  WORKFLOW_TEMPLATES,
  categoryLabelKey,
  templateAgentSlots,
  templateNameKey,
  templateStepCount,
  templateTaglineKey,
} from "../../components/workflowTemplates";
import { useTranslations } from "../../lib/i18n";
import { Badge } from "../../components/ui/badge";
import { Button } from "../../components/ui/button";
import { Input } from "../../components/ui/input";
import { Card, CardContent } from "../../components/ui/card";
import PageHeader from "../../components/ax/PageHeader";
import { Section } from "../../components/ax/page-kit";
import PaginationControls from "../../components/PaginationControls";
import { usePagination } from "../../lib/usePagination";

const TEMPLATE_PAGE_SIZES = [12, 24, 48];

/**
 * Workflow template gallery - the workflow-side counterpart to
 * /agents/templates/page.tsx. Browsing here never creates anything; picking
 * a template navigates back to /workflows?template=<key>, which pre-fills
 * the create form there with a real starter graph (trigger, agent step,
 * output) already wired up on the canvas once created - nothing is created
 * until the person names it and submits (same "review before creating"
 * posture the agent template gallery uses).
 */
export default function WorkflowTemplatesPage() {
  const t = useTranslations("workflowTemplatesPage");
  const tTemplates = useTranslations("workflowTemplates");
  const tu = useTranslations("workflowStudioUi");
  const router = useRouter();
  const [query, setQuery] = useState("");
  const [category, setCategory] = useState<string | null>(null);

  // Card facts are derived from the real starter graph, so they can never drift from what Create builds.
  const facts = useMemo(
    () =>
      new Map(
        WORKFLOW_TEMPLATES.map(
          (tpl) => [tpl.key, { agents: templateAgentSlots(tpl), steps: templateStepCount(tpl) }] as const
        )
      ),
    []
  );

  const visible = useMemo(() => {
    const q = query.trim().toLowerCase();
    return WORKFLOW_TEMPLATES.filter((tpl) => {
      if (category && tpl.category !== category) return false;
      if (!q) return true;
      const haystack = [
        tTemplates(templateNameKey(tpl.key)),
        tTemplates(templateTaglineKey(tpl.key)),
        tTemplates(categoryLabelKey(tpl.category)),
        tpl.description,
      ]
        .join(" ")
        .toLowerCase();
      return haystack.includes(q);
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps -- tTemplates is stable per locale
  }, [query, category]);

  // Category order first, so each page shows whole category groups where it can.
  const ordered = useMemo(
    () =>
      [...visible].sort(
        (a, b) => WORKFLOW_TEMPLATE_CATEGORIES.indexOf(a.category) - WORKFLOW_TEMPLATE_CATEGORIES.indexOf(b.category)
      ),
    [visible]
  );
  const pg = usePagination(ordered, TEMPLATE_PAGE_SIZES[0]);

  function applyTemplate(template: (typeof WORKFLOW_TEMPLATES)[number]) {
    router.push(`/workflows?template=${encodeURIComponent(template.key)}`);
  }

  return (
    <AppShell eyebrow={t("eyebrow")}>
      <PageHeader
        title={t("title")}
        subtitle={
          <>
            {t("introPrefix")}{" "}
            <Link href="/workflows" className="font-medium text-primary underline-offset-4 hover:underline">
              {t("workflowsPageLinkText")}
            </Link>{" "}
            {t("introSuffix")}
          </>
        }
      />

      <div className="mb-6 space-y-3">
        <div className="relative w-full sm:max-w-sm">
          <Search
            size={15}
            className="pointer-events-none absolute start-3 top-1/2 -translate-y-1/2 text-muted-foreground"
            aria-hidden="true"
          />
          <Input
            type="search"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder={t("searchPlaceholder")}
            aria-label={t("searchPlaceholder")}
            className="ps-9"
          />
        </div>
        <div className="flex flex-wrap gap-2" role="group" aria-label={t("categoryFilterLabel")}>
          <Button
            size="sm"
            variant={category === null ? "default" : "outline"}
            aria-pressed={category === null}
            onClick={() => setCategory(null)}
          >
            {t("allCategories", { count: WORKFLOW_TEMPLATES.length })}
          </Button>
          {WORKFLOW_TEMPLATE_CATEGORIES.map((c) => (
            <Button
              key={c}
              size="sm"
              variant={category === c ? "default" : "outline"}
              aria-pressed={category === c}
              onClick={() => setCategory(c)}
            >
              {tTemplates(categoryLabelKey(c))}
            </Button>
          ))}
        </div>
      </div>

      {visible.length === 0 && (
        <p
          role="status"
          className="rounded-lg border border-dashed border-border px-4 py-8 text-center text-sm text-muted-foreground"
        >
          {t("noMatches")}
        </p>
      )}

      <div className="space-y-8">
        {WORKFLOW_TEMPLATE_CATEGORIES.map((cat) => {
          const templates = pg.pageItems.filter((tpl) => tpl.category === cat);
          if (templates.length === 0) return null;
          return (
            <Section key={cat} title={tTemplates(categoryLabelKey(cat))}>
              <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
                {templates.map((template) => {
                  const Icon = template.icon;
                  return (
                    <Card key={template.key} className="group gap-0 p-5 transition-colors hover:border-primary/40">
                      <CardContent className="flex h-full flex-col p-0">
                        <span className="mb-4 flex size-10 items-center justify-center rounded-lg bg-primary/10 text-primary">
                          <Icon size={18} aria-hidden="true" />
                        </span>
                        <h3 className="text-sm font-semibold text-foreground">
                          {tTemplates(templateNameKey(template.key))}
                        </h3>
                        <p className="mt-1 text-sm leading-relaxed text-muted-foreground">
                          {tTemplates(templateTaglineKey(template.key))}
                        </p>
                        <div className="mt-3 flex flex-wrap gap-1.5">
                          {facts.get(template.key)?.agents === 0 ? (
                            <Badge variant="accent">{t("noAgentNeeded")}</Badge>
                          ) : (
                            <Badge variant="secondary">
                              {t("agentsToAssign", { count: facts.get(template.key)?.agents ?? 0 })}
                            </Badge>
                          )}
                          <Badge variant="outline">
                            {t("stepsCount", { count: facts.get(template.key)?.steps ?? 0 })}
                          </Badge>
                        </div>
                        <div className="mt-auto pt-4">
                          <Button size="sm" onClick={() => applyTemplate(template)}>
                            {t("useTemplate")}
                          </Button>
                        </div>
                      </CardContent>
                    </Card>
                  );
                })}
              </div>
            </Section>
          );
        })}
      </div>

      <PaginationControls
        page={pg.page}
        totalPages={pg.totalPages}
        totalItems={pg.totalItems}
        pageSize={pg.pageSize}
        onPageChange={pg.setPage}
        onPageSizeChange={pg.setPageSize}
        pageSizeOptions={TEMPLATE_PAGE_SIZES}
        itemLabel={tu("templatesItemLabel")}
      />
    </AppShell>
  );
}
