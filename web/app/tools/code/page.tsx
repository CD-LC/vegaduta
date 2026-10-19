"use client";

import { useState } from "react";
import Link from "next/link";
import { ArrowLeft, ShieldCheck } from "lucide-react";
import AppShell from "../../components/AppShell";
import PageHeader from "../../components/ax/PageHeader";
import { usePageReady } from "../../components/RouteTransition";
import CustomCodeToolsPanel from "../../components/CustomCodeToolsPanel";
import { Alert, AlertDescription, AlertTitle } from "../../components/ui/alert";
import { Button } from "../../components/ui/button";
import { useTranslations } from "../../lib/i18n";

/**
 * Custom Code Tools - its own screen under /tools rather than a third panel
 * on /tools itself (same call as /tools/servers and /tools/credentials):
 * writing and validating a program needs the full page width, and the
 * admin-only + sandbox-gated access story would otherwise have to be
 * explained inline on a page most of whose content isn't admin-gated.
 *
 * Page owns the chrome (header, back link, the standing explainer); the
 * panel owns the fetch and every bit of interaction state, matching
 * UserToolScriptsPanel - the sibling this feature is modelled on - rather
 * than /tools' own "page fetches, panel renders" split, since nothing on
 * this screen needs the data except the panel itself.
 */
export default function CustomCodeToolsPage() {
  const t = useTranslations("customCodeTools");
  const [loaded, setLoaded] = useState(false);
  usePageReady(loaded);

  return (
    <AppShell eyebrow={t("pageEyebrow")}>
      <PageHeader
        title={t("pageTitle")}
        subtitle={t("pageSubtitle")}
        actions={
          <Button variant="outline" size="sm" asChild>
            <Link href="/tools">
              <ArrowLeft size={14} aria-hidden="true" /> {t("backToTools")}
            </Link>
          </Button>
        }
      />

      {/* Standing explainer, not a dismissible tip: the sandbox guarantees
          and the "the model reads your description to decide when to call
          this" rule are the two things a person needs in mind every single
          time they write one of these, not just the first time. */}
      <Alert variant="info" className="mb-6">
        <ShieldCheck aria-hidden="true" />
        <AlertTitle>{t("explainerTitle")}</AlertTitle>
        <AlertDescription>
          <p>{t("explainerSandbox")}</p>
          <p>{t("explainerAdmin")}</p>
          <p>{t("explainerToolName")}</p>
        </AlertDescription>
      </Alert>

      <CustomCodeToolsPanel onLoaded={() => setLoaded(true)} />
    </AppShell>
  );
}
