"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { ArrowRight } from "lucide-react";
import { api } from "../lib/api";
import AppShell from "../components/AppShell";
import { usePageReady } from "../components/RouteTransition";
import PageHeader from "../components/ax/PageHeader";
import ProvidersPanel from "../components/ProvidersPanel";
import { useTranslations } from "../lib/i18n";
import LocalModelFirstRun from "../components/localModel/LocalModelFirstRun";
import { localModelAllowed } from "../lib/personalInstall";
import { SetupHint } from "../components/setup/SetupLinks";
import { Button } from "../components/ui/button";

/**
 * Providers - connect and activate the LLM providers agents run on. First
 * stop for a brand-new tenant (an agent can't be created without at least
 * one active provider), which is why the Agents page links back here.
 *
 * AXON rebuild - same .ax-tl-topbar breadcrumb/next-step convention /tools
 * uses (no design_handoff_axon mockup exists for this screen; see
 * ProvidersPanel.js's comment).
 */
export default function ProvidersPage() {
  const t = useTranslations("providersPage");
  const tp = useTranslations("consolePolish");
  const tl = useTranslations("localModelWizard");
  const [providers, setProviders] = useState([]);
  const [loaded, setLoaded] = useState(false);

  const refresh = useCallback(() => {
    api
      .listProviders()
      .then(setProviders)
      .catch(() => setProviders([]))
      .finally(() => setLoaded(true));
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);
  usePageReady(loaded);

  const activeCount = providers.filter((p) => p.status === "ACTIVE").length;

  return (
    <AppShell eyebrow={t("pageEyebrow")}>
      <PageHeader
        title={t("pageTitle")}
        subtitle={
          <>
            {tp("providersIntro")}{" "}
            <span className="whitespace-nowrap">
              <span className="font-medium text-foreground tabular-nums">{activeCount}</span> {t("activeSuffix")}
            </span>
          </>
        }
        actions={
          <>
            {/* "AI Model Control" settings page - lets a tenant-admin pick a
             * preferred provider/model per AI-touching feature instead of
             * the platform default. Lives at its own route (/ai-models) but
             * is conceptually a BYOK-adjacent screen, so it's linked from
             * here rather than only reachable by typing the URL. */}
            <Button asChild variant="outline" size="sm">
              <Link href="/ai-models">
                {tp("aiModelControl")} <ArrowRight size={14} />
              </Link>
            </Button>
            {/* Decision providers (/capabilities/decision) are not chat
             * providers, so they can't appear in the list below, but they ARE
             * a bring-your-own-key surface. Linked from here because this is
             * the page a tenant opens thinking "where do I plug in my key?" */}
            <Button asChild variant="outline" size="sm">
              <Link href="/capabilities/decision">
                {tp("decisionProviders")} <ArrowRight size={14} />
              </Link>
            </Button>
            {/* Local-model wizard is personal-only (localModelAllowed): on lite and
             * full the pools are operator-managed, so the entry point stays off. */}
            {localModelAllowed() && (
              <Button asChild variant="outline" size="sm">
                <Link href="/providers/local-model">{tl("providersEntry")}</Link>
              </Button>
            )}
            <Button asChild variant="outline" size="sm">
              <Link href="/agents">
                {t("createAgentLink")} <ArrowRight size={14} />
              </Link>
            </Button>
          </>
        }
      />
      {localModelAllowed() && loaded && providers.length === 0 && <LocalModelFirstRun />}
      <SetupHint className="mb-4" lead={tp("providersSetupLead")} targets={["agents", "edge", "tools", "workflows"]} />
      <ProvidersPanel providers={providers} onChanged={refresh} />
    </AppShell>
  );
}
