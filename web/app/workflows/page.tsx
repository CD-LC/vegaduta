"use client";

import { Suspense } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import Link from "next/link";
import { Lightbulb, LayoutTemplate, Upload } from "lucide-react";
import AppShell from "../components/AppShell";
import PageHeader from "../components/ax/PageHeader";
import { Button } from "../components/ui/button";
import { Skeleton } from "../components/ui/skeleton";
import WorkflowDesignerPanel from "../components/WorkflowDesignerPanel";
import WorkflowsGetStarted from "./WorkflowsGetStarted";
import DescribeComposer from "../components/workflow/DescribeComposer";
import { useTranslations } from "../lib/i18n";
import { usePortabilityEnabled } from "../lib/usePortabilityEnabled";

/**
 * Workflows list - the "Build" section's flagship page. Uses the shared
 * PageHeader (visible h1 + subtitle) that every other console screen uses,
 * with the eyebrow rendered in the AppShell topbar. Opening a workflow
 * navigates to /workflows/[id], a dedicated full-height canvas page.
 */
function WorkflowsPageInner() {
  const t = useTranslations("workflowsPage");
  const tp = useTranslations("workflowPortability");
  const tu = useTranslations("workflowStudioUi");
  const portable = usePortabilityEnabled();
  const router = useRouter();
  const searchParams = useSearchParams();

  return (
    <AppShell eyebrow={t("eyebrow")}>
      <PageHeader
        title={t("title")}
        subtitle={
          <>
            {t("introPart1")}{" "}
            <Link href="/agents" className="text-primary hover:underline">
              {t("agentsLinkText")}
            </Link>{" "}
            {t("introPart2")}{" "}
            <Link href="/knowledge" className="text-primary hover:underline">
              {t("knowledgeLinkText")}
            </Link>{" "}
            {t("introPart3")}
          </>
        }
        actions={
          <>
            {portable && (
              <Button asChild variant="outline" size="sm">
                <Link href="/workflows/import">
                  <Upload className="h-4 w-4" aria-hidden="true" />
                  {tp("importButton")}
                </Link>
              </Button>
            )}
            <Button asChild variant="outline" size="sm">
              <Link href="/workflows/templates">
                <LayoutTemplate className="h-4 w-4" aria-hidden="true" />
                {tu("templatesButton")}
              </Link>
            </Button>
            <Button asChild variant="outline" size="sm">
              <Link href="/help/workflow-recipes">
                <Lightbulb className="h-4 w-4" aria-hidden="true" />
                {tu("recipesButton")}
              </Link>
            </Button>
          </>
        }
      />

      {/* "Describe it, automate it" (nlflow W3): one sentence -> a DRAFT workflow -> its canvas. */}
      <DescribeComposer onCreated={(id: string) => router.push(`/workflows/${id}`)} />

      <WorkflowDesignerPanel
        onOpen={(id: string) => router.push(`/workflows/${id}`)}
        initialTemplateKey={searchParams.get("template")}
      />

      <div className="mt-8">
        <WorkflowsGetStarted />
      </div>
    </AppShell>
  );
}

// /workflows/templates links back here as /workflows?template=<key> - reading
// it via useSearchParams requires a Suspense boundary in Next.js, same
// pattern /agents/page.tsx already established for its own ?template= deep
// link.
export default function WorkflowsPage() {
  return (
    <Suspense fallback={<Skeleton className="h-40 w-full rounded-xl" />}>
      <WorkflowsPageInner />
    </Suspense>
  );
}
