"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { ScrollText, TriangleAlert } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { api } from "../../lib/api";
import type { PromptDirectivesOverview } from "../../lib/api/promptDirectives";
import { errorMessage } from "../../lib/errors";
import AppShell from "../../components/AppShell";
import PageHeader from "../../components/ax/PageHeader";
import { useRequireScope } from "../../components/useRequireScope";
import { Alert, AlertDescription, AlertTitle } from "../../components/ui/alert";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "../../components/ui/card";
import EmptyState from "../../components/EmptyState";
import { Skeleton } from "../../components/ui/skeleton";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "../../components/ui/tabs";
import CustomInstructionsCard from "./CustomInstructionsCard";
import DirectiveCard from "./DirectiveCard";

// Every instruction the platform gives the model, in one place. Layering, lowest to highest
// precedence: built-in directives < these workspace settings < the agent's own System prompt.
export default function AdminInstructionsPage() {
  const t = useTranslations("promptDirectives");
  const allowed = useRequireScope("MANAGE_TENANT_SETTINGS");
  const [data, setData] = useState<PromptDirectivesOverview | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [tab, setTab] = useState("edit");

  const reload = useCallback(async () => {
    const next = await api.getPromptDirectives();
    setData(next);
    setLoadError(null);
  }, []);

  useEffect(() => {
    if (!allowed) return undefined;
    let cancelled = false;
    api
      .getPromptDirectives()
      .then((next: PromptDirectivesOverview) => {
        if (!cancelled) setData(next);
      })
      .catch((e: unknown) => {
        if (!cancelled) setLoadError(errorMessage(e, t("loadFailed")));
      });
    return () => {
      cancelled = true;
    };
  }, [allowed, t]);

  if (!allowed) return null;

  return (
    <AppShell eyebrow={t("eyebrow")}>
      <PageHeader eyebrow={t("eyebrow")} title={t("title")} subtitle={t("subtitle")} />

      <Alert>
        <ScrollText aria-hidden="true" />
        <AlertTitle>{t("layeringTitle")}</AlertTitle>
        <AlertDescription>
          {t("layeringBody")}{" "}
          <Link href="/agents" className="underline underline-offset-2">
            {t("agentsLink")}
          </Link>
        </AlertDescription>
      </Alert>

      {loadError && (
        <Alert variant="destructive">
          <TriangleAlert aria-hidden="true" />
          <AlertTitle>{t("loadFailedTitle")}</AlertTitle>
          <AlertDescription>{loadError}</AlertDescription>
        </Alert>
      )}

      {!data && !loadError && (
        <div className="flex flex-col gap-3" data-testid="instructions-loading">
          <Skeleton className="h-40 w-full" />
          <Skeleton className="h-20 w-full" />
          <Skeleton className="h-20 w-full" />
        </div>
      )}

      {data && (
        <Tabs value={tab} onValueChange={setTab}>
          <TabsList>
            <TabsTrigger value="edit">{t("tabEdit")}</TabsTrigger>
            <TabsTrigger value="preview">{t("tabPreview")}</TabsTrigger>
          </TabsList>

          <TabsContent value="edit" className="flex flex-col gap-6">
            <section aria-labelledby="custom-heading" className="flex flex-col gap-3">
              <h2 id="custom-heading" className="text-sm font-semibold">
                {t("customHeading")}
              </h2>
              <CustomInstructionsCard value={data.customInstructions} canEdit onChanged={reload} />
            </section>
            <section aria-labelledby="builtin-heading" className="flex flex-col gap-3">
              <h2 id="builtin-heading" className="text-sm font-semibold">
                {t("builtinHeading")}
              </h2>
              {data.directives.length === 0 ? (
                <EmptyState title={t("emptyTitle")} hint={t("emptyBody")} />
              ) : (
                data.directives.map((d) => <DirectiveCard key={d.key} directive={d} canEdit onChanged={reload} />)
              )}
            </section>
          </TabsContent>

          <TabsContent value="preview">
            <Card>
              <CardHeader>
                <CardTitle className="text-sm">{t("previewTitle")}</CardTitle>
                <CardDescription className="text-xs">{t("previewBody")}</CardDescription>
              </CardHeader>
              <CardContent>
                {data.assembledPreview.trim() === "" ? (
                  <p className="text-sm text-muted-foreground">{t("previewEmpty")}</p>
                ) : (
                  <pre
                    tabIndex={0}
                    role="region"
                    aria-label={t("previewTitle")}
                    className="max-h-[32rem] overflow-auto whitespace-pre-wrap break-words rounded-md border border-border bg-muted p-3 font-sans text-xs"
                  >
                    {data.assembledPreview}
                  </pre>
                )}
              </CardContent>
            </Card>
          </TabsContent>
        </Tabs>
      )}
    </AppShell>
  );
}
