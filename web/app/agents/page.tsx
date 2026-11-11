"use client";

import { Suspense, useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { Gauge, LayoutList, LibraryBig, PackageOpen, Sparkles, Upload } from "lucide-react";
import { api } from "../lib/api";
import AppShell from "../components/AppShell";
import AgentWorkbench from "../components/agents/AgentWorkbench";
import AgentsIndex from "../components/agents/AgentsIndex";
import { openAgentHref } from "../components/agents/AgentWorkbench/agentLinks";
import { Skeleton } from "../components/ui/skeleton";
import PageHeader from "../components/ax/PageHeader";
import { Button } from "../components/ui/button";
import { useTranslations } from "../lib/i18n";
import { SetupHint } from "../components/setup/SetupLinks";
import { usePageReady } from "../components/RouteTransition";

/**
 * Agents - the AXON "Agents + Playground" build-and-test screen (design
 * handoff Screen 3, Agents.dc.html): a two-pane layout - identity/persona/
 * model/knowledge/tools config on the left, a live chat Playground on the
 * right - replacing the previous stacked create-form-then-list layout
 * (AgentsPanel.tsx, deleted in the 2026-07-29 dead-code pass once it had
 * been unrouted and unimported for months; see git history). Still fetches its own
 * providers/collections/agents, same per-route data-fetch pattern as before.
 */
function AgentsPageInner() {
  const t = useTranslations("agentsPage");
  // The index labels, subtitles and errors live in the shared agentsUi bundle (AgentsIndex uses it
  // too); only the eyebrow and title are this page's own.
  const tu = useTranslations("agentsUi");
  const tb = useTranslations("agentBehavior");
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const [providers, setProviders] = useState([]);
  const [agents, setAgents] = useState([]);
  const [collections, setCollections] = useState([]);
  // Deep-link support (?agentId=) - same pattern /agents/learning already
  // uses. Needed so links from /shared-with-me land on the actual granted
  // agent instead of AgentWorkbench's own "most recently created" default.
  const [selectedAgentId, setSelectedAgentId] = useState(searchParams.get("agentId"));
  // Gate the route-transition cover until this page's own data has loaded, so
  // the AgentWorkbench's empty/"activate a provider first" state never flashes
  // before providers/agents arrive (see usePageReady / RouteTransition).
  const [loaded, setLoaded] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  // /agents is the workbench: once the agents list lands it auto-selects the
  // most recent saved agent (useWorkbenchBase), and a tenant with none gets
  // the deterministic first-run draft - the behaviour the console's /agents
  // links and e2e specs assume. The index of agents is the explicit
  // ?view=index surface, reached through the workbench's "All agents" button;
  // the ?section/&focus= deep links without a chosen agent also land there
  // ("pick an agent"). Every other param combination opens the workbench.
  const wantedSection = searchParams.get("section");
  const wantedFocus = searchParams.get("focus");
  const view = searchParams.get("view");
  const agentPicked =
    !!selectedAgentId ||
    searchParams.get("new") === "1" ||
    !!searchParams.get("template") ||
    searchParams.get("lab") === "open" ||
    view === "workbench";
  const showWorkbench = agentPicked || (view !== "index" && !wantedSection && !wantedFocus);

  const refresh = useCallback(async () => {
    // All three reads are independent - firing them together instead of
    // awaiting collections after providers+agents removes a full backend
    // round trip from this page's time-to-ready on every mount. Each slice is
    // stored as its OWN fetch resolves rather than after allSettled: the
    // workbench auto-selects off `agents`, and holding it until the slowest
    // of the three resolved left the page in the draft/no-selection state
    // for as long as the slowest read took (issue #294: 20-30s+ on stacks
    // where collections is slow). Per-source failure semantics unchanged - a
    // collections failure still leaves providers/agents populated (and vice
    // versa); `loaded` still waits for all three so agentsLoaded and the
    // route-transition hold keep their old meaning.
    const providersP = api
      .listProviders()
      .then(setProviders)
      // Surfaced by the workbench's own "activate a provider first"
      // validation if providers stay empty - no separate banner needed here.
      .catch(() => {});
    const agentsP = api
      .listAgents()
      .then((list) => {
        setAgents(list);
        setLoadError(null);
      })
      .catch(() => setLoadError(tu("loadError")));
    const collectionsP = api
      .listCollections()
      .then(setCollections)
      .catch(() => setCollections([]));
    await Promise.allSettled([providersP, agentsP, collectionsP]);
    setLoaded(true);
  }, [tu]);

  useEffect(() => {
    void refresh();
  }, [refresh]);
  usePageReady(loaded);

  // The URL is the source of truth: the "All agents" link and browser back
  // change ?agentId= without going through handleSelect.
  const urlAgentId = searchParams.get("agentId");
  useEffect(() => {
    setSelectedAgentId(urlAgentId);
  }, [urlAgentId]);

  // Keep the URL's agentId in sync with in-app switcher selections (shallow,
  // no reload) so a refresh preserves the current agent instead of reverting
  // to the original deep-linked/default one. Same router.replace + scroll:false
  // pattern TemplatesGallery uses to reflect state in the URL.
  const handleSelect = useCallback(
    (agentId: string | null) => {
      setSelectedAgentId(agentId);
      const params = new URLSearchParams(searchParams.toString());
      if (agentId) {
        params.set("agentId", agentId);
      } else {
        params.delete("agentId");
        // Deselecting inside the workbench (new draft, after a delete) stays
        // there; the index is reached through the "All agents" button.
        params.set("view", "workbench");
      }
      // ?template= is a one-shot deep link: the workbench applied it on
      // arrival. Left in the URL it would re-apply on a refresh over whatever
      // agent or fresh draft the person has since moved to.
      params.delete("template");
      params.delete("new");
      const qs = params.toString();
      router.replace(`${pathname}${qs ? `?${qs}` : ""}`, { scroll: false });
    },
    [pathname, router, searchParams]
  );

  // A link that arrived here without an agent but asking for a place inside one (?section=behavior&focus=<setting>,
  // from the "Where do I control...?" page) keeps that request through the choice of agent.
  const agentHref = (id: string) => openAgentHref(id, { section: wantedSection, focus: wantedFocus });

  return (
    <AppShell eyebrow={t("eyebrow")}>
      <PageHeader
        title={t("title")}
        subtitle={showWorkbench ? tu("subtitleWorkbench") : tu("subtitleIndex")}
        actions={
          <>
            {showWorkbench && (
              <Button asChild variant="outline" size="sm">
                {/* Bare /agents auto-opens the most recent agent again - the index needs the explicit view param. */}
                <Link href="/agents?view=index">
                  <LayoutList size={14} aria-hidden="true" /> {tu("allAgents")}
                </Link>
              </Button>
            )}
            <Button asChild variant="outline" size="sm">
              <Link href={selectedAgentId ? `/agents/quality?agentId=${selectedAgentId}` : "/agents/quality"}>
                <Gauge size={14} aria-hidden="true" /> {tu("quality")}
              </Link>
            </Button>
            <Button asChild variant="outline" size="sm">
              <Link href={selectedAgentId ? `/agents/learning?agentId=${selectedAgentId}` : "/agents/learning"}>
                <Sparkles size={14} aria-hidden="true" /> {tu("learning")}
              </Link>
            </Button>
            <Button asChild variant="outline" size="sm">
              <Link href="/agents/templates">
                <LibraryBig size={14} aria-hidden="true" /> {tu("templates")}
              </Link>
            </Button>
            <Button asChild variant="outline" size="sm">
              <Link href="/agents/bundles">
                <PackageOpen size={14} aria-hidden="true" /> {tu("bundles")}
              </Link>
            </Button>
            <Button asChild variant="outline" size="sm">
              <Link href="/agents/import">
                <Upload size={14} aria-hidden="true" /> {tu("import")}
              </Link>
            </Button>
          </>
        }
      />
      {showWorkbench ? (
        <>
          <SetupHint
            className="mb-3"
            lead={tu("relatedSetup")}
            targets={["providers", "tools", "knowledge", "edge", "channels", "workflows"]}
          />
          <AgentWorkbench
            agents={agents}
            // Until listAgents settles the list is the initial [], which the
            // checklist would read as "zero agents" and flash its first-run
            // "Pick a way to start" panel for a second.
            agentsLoaded={loaded}
            providers={providers}
            collections={collections}
            selectedAgentId={selectedAgentId}
            onSelect={handleSelect}
            onChanged={refresh}
            initialTemplateKey={searchParams.get("template")}
            // /agents/quality's "Agent Lab" topic link arrives as ?lab=open.
            initialLabOpen={searchParams.get("lab") === "open"}
            startInDraft={searchParams.get("new") === "1"}
          />
        </>
      ) : (
        <>
          {wantedSection === "behavior" ? (
            <p className="mb-3 text-sm text-muted-foreground" data-testid="agents-pick-for-behavior">
              {tb("pickAgent")}
            </p>
          ) : null}
          <AgentsIndex
            agents={agents}
            loaded={loaded}
            error={loadError}
            onRetry={() => void refresh()}
            onChanged={() => void refresh()}
            agentHref={agentHref}
            newHref="/agents?new=1"
          />
        </>
      )}
    </AppShell>
  );
}

// /agents/templates links back here as /agents?template=<key> - reading it
// via useSearchParams requires a Suspense boundary in Next.js, same pattern
// already established for /playground and the Google/X OAuth callback pages.
export default function AgentsPage() {
  return (
    <Suspense fallback={<Skeleton className="m-4 h-40" />}>
      <AgentsPageInner />
    </Suspense>
  );
}
