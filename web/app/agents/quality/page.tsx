"use client";

import { Suspense, useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { ArrowLeft, FlaskConical, Gauge, GraduationCap } from "lucide-react";
import { api } from "../../lib/api";
import AppShell from "../../components/AppShell";
import PageHeader from "../../components/ax/PageHeader";
import Select from "../../components/Select";
import { Button } from "../../components/ui/button";
import { EmptyState } from "../../components/ax/page-kit";
import AgentQualityPanel from "../../components/agents/AgentQualityPanel";
import EvalDatasetsSection from "./EvalDatasetsSection";
import EvalGateSection from "./EvalGateSection";
import JudgeCalibrationSection from "./JudgeCalibrationSection";
import EvalStatisticsSection from "./EvalStatisticsSection";
import SimulationSection from "./SimulationSection";
import AttackSection from "./AttackSection";
import SolutionsTab from "./SolutionsTab";
import AgentImprovementsCard from "../../components/agents/AgentImprovementsCard";
import { Skeleton } from "../../components/ui/skeleton";
import { Alert, AlertDescription } from "../../components/ui/alert";
import { Tabs, TabsList, TabsTrigger } from "../../components/ui/tabs";
import { useTranslations } from "../../lib/i18n";

/**
 * Agent Quality - a dedicated read/observe surface for QualityController's
 * eval-scoring data (core/quality), split out of the Agents workbench the
 * same way /agents/learning is (see that page's own comment for the full
 * rationale: the workbench is a large, actively-edited build-and-test screen
 * and this observability surface deserves room of its own rather than
 * competing with it for space/merges). Reuses the same AppShell + PageHeader
 * shell as every other console screen and deep-links via ?agentId= so a
 * "View quality" action from the Agents page lands on the right agent.
 *
 * The surface grew past one screenful, so the sections are split into tabs
 * by FUNCTION rather than stacked top-to-bottom (this replaces the earlier
 * in-page anchor index - the tab bar IS the index now):
 *
 *   live         - AgentQualityPanel: the online half. How the agent scores
 *                  on real conversations, turn by turn.
 *   datasets     - EvalDatasetsSection + EvalGateSection + JudgeCalibrationSection: the offline half.
 *                  Golden-case datasets and their regression runs, plus the
 *                  promotion gate those runs feed ("would promoting HEAD be
 *                  allowed right now") - kept together because the gate is
 *                  configured ON a dataset and evaluated over its runs.
 *   simulations  - SimulationSection: adversarial persona conversations,
 *                  a different kind of test than dataset replays.
 *   reliability  - EvalStatisticsSection: the measurement layer. Kept LAST
 *                  among the agent tabs for the same reason it used to sit
 *                  last on the page: it reinterprets the numbers the other
 *                  tabs report as bare point estimates, so a reader needs
 *                  to have seen them first.
 *   solutions    - SolutionsTab: the proof ledger of an applied envoy -
 *                  not agent-scoped, so the agent picker hides on this tab.
 *
 * The active tab and selected agent are mirrored into ?tab=/?agentId= (via
 * router.replace, no scroll jump) so every tab is a shareable deep link.
 * The Agent Lab and Learning links next to the picker leave the page
 * entirely, like the anchor-index pills they replace did.
 *
 * Unlike /agents/learning, this surface is PREMIUM+-gated on the backend
 * (QualityController#assertPremiumPlan) - AgentQualityPanel handles that
 * itself (inline upgrade card on 403), so this page doesn't need its own
 * plan check.
 */
type QualityTab = "live" | "datasets" | "simulations" | "reliability" | "solutions";

function parseTab(raw: string | null): QualityTab {
  if (raw === "datasets" || raw === "simulations" || raw === "reliability" || raw === "solutions") {
    return raw;
  }
  // "agents" was the pre-tabs default and old deep links may carry it; it
  // showed what is now the "live" tab. Anything unknown lands there too.
  return "live";
}

function AgentQualityInner() {
  const searchParams = useSearchParams();
  const router = useRouter();
  const pathname = usePathname();
  const tb = useTranslations("brief");
  const tq = useTranslations("agentsUi");
  const tp = useTranslations("consolePolish");
  const [tab, setTabState] = useState<QualityTab>(() => parseTab(searchParams.get("tab")));
  const [agents, setAgents] = useState<any[]>([]);
  const [agentsLoaded, setAgentsLoaded] = useState(false);
  const [agentsError, setAgentsError] = useState(false);
  const [selectedAgentId, setSelectedAgentId] = useState<string | null>(searchParams.get("agentId"));
  // Set by AttackSection: false on a core without attack scenarios, so nothing waits on a hidden section.
  const [attackAvailable, setAttackAvailable] = useState(false);
  // A deep link to #attack (the workbench's "Test this agent" link) arrives while the
  // section is still loading its scenarios, so the browser's own hash scroll finds
  // nothing. Scroll once, when the section first appears; never on later agent switches.
  const attackScrolledRef = useRef(false);
  useEffect(() => {
    if (!attackAvailable || attackScrolledRef.current) return;
    attackScrolledRef.current = true;
    if (typeof window === "undefined" || window.location.hash !== "#attack") return;
    const el = document.getElementById("attack");
    if (el && typeof el.scrollIntoView === "function") el.scrollIntoView({ block: "start" });
  }, [attackAvailable]);

  // Keep the address bar a shareable deep link - /agents/quality?tab=…&agentId=… -
  // without a scroll jump on every click.
  const syncUrl = useCallback(
    (nextTab: QualityTab, agentId: string | null) => {
      const params = new URLSearchParams();
      params.set("tab", nextTab);
      if (agentId) params.set("agentId", agentId);
      router.replace(`${pathname}?${params.toString()}`, { scroll: false });
    },
    [router, pathname]
  );

  const setTab = useCallback(
    (v: string) => {
      const next = parseTab(v);
      setTabState(next);
      syncUrl(next, selectedAgentId);
    },
    [syncUrl, selectedAgentId]
  );

  const selectAgent = useCallback(
    (id: string | null) => {
      setSelectedAgentId(id);
      syncUrl(tab, id);
    },
    [syncUrl, tab]
  );

  const refresh = useCallback(async () => {
    setAgentsError(false);
    try {
      const data = await api.listAgents();
      setAgents(data || []);
      setSelectedAgentId((cur) => cur || (data && data[0]?.id) || null);
    } catch {
      setAgents([]);
      setAgentsError(true);
    } finally {
      setAgentsLoaded(true);
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const selectedAgent = agents.find((a) => a.id === selectedAgentId) || null;
  const agentOptions = agents.map((a) => ({ value: a.id, label: a.name }));

  return (
    <AppShell eyebrow={tp("agentsEyebrow")}>
      <PageHeader
        title={tp("qualityTitle")}
        subtitle={tp("qualitySubtitle")}
        actions={
          <>
            {/* The Academy covers this surface from the outside - the OSS
                console keeps a plain link to it, never a lesson id. */}
            <Button asChild variant="outline" size="sm">
              <Link href="/agents">
                <ArrowLeft size={14} aria-hidden="true" /> {tp("backToAgents")}
              </Link>
            </Button>
          </>
        }
      />

      <Tabs value={tab} onValueChange={setTab} className="mb-6">
        <TabsList>
          <TabsTrigger value="live">{tp("qualityTabLive")}</TabsTrigger>
          <TabsTrigger value="datasets">{tp("qualityTabDatasets")}</TabsTrigger>
          <TabsTrigger value="simulations">{tp("qualityTabSimulations")}</TabsTrigger>
          <TabsTrigger value="reliability">{tp("qualityTabReliability")}</TabsTrigger>
          <TabsTrigger value="solutions">{tb("solutions.tab")}</TabsTrigger>
        </TabsList>
      </Tabs>

      {tab === "solutions" ? (
        <SolutionsTab />
      ) : !agentsLoaded ? (
        <div className="space-y-3" aria-busy="true">
          <Skeleton className="h-10 w-72" />
          <Skeleton className="h-40 w-full" />
        </div>
      ) : agentsError ? (
        <Alert variant="destructive">
          <AlertDescription className="flex flex-wrap items-center justify-between gap-2">
            <span>{tq("agentsLoadError")}</span>
            <Button type="button" size="sm" variant="outline" onClick={() => void refresh()}>
              {tq("retry")}
            </Button>
          </AlertDescription>
        </Alert>
      ) : agents.length === 0 ? (
        <EmptyState
          icon={Gauge}
          title={tp("noAgentsTitle")}
          description={tp("qualityNoAgentsBody")}
          action={
            <Button asChild size="sm">
              <Link href="/agents">{tp("goToAgents")}</Link>
            </Button>
          }
        />
      ) : (
        <div className="space-y-8">
          <div className="flex flex-wrap items-center gap-3" role="group" aria-label={tp("agentLabel")}>
            <span className="text-sm font-medium text-foreground">{tp("agentLabel")}</span>
            <Select
              className="min-w-56"
              value={selectedAgentId}
              onChange={(e: any) => selectAgent(e.target.value)}
              options={agentOptions}
              searchable
              placeholder={tp("selectAgent")}
            />
            {/* Cross-page neighbours of the quality surface: Agent Lab lives
                inside the playground overlay (/agents?agentId=..&lab=open
                opens it) and Learning is the sibling observability route. */}
            <div className="ms-auto flex flex-wrap items-center gap-2">
              <Link
                href={selectedAgentId ? `/agents?agentId=${selectedAgentId}&lab=open` : "/agents"}
                className="inline-flex items-center gap-1 rounded-full border border-border bg-card px-3 py-1 text-xs font-medium text-muted-foreground transition-colors hover:bg-accent/50 hover:text-foreground"
              >
                <FlaskConical size={11} aria-hidden="true" /> {tp("agentLab")}
              </Link>
              <Link
                href={selectedAgentId ? `/agents/learning?agentId=${selectedAgentId}` : "/agents/learning"}
                className="inline-flex items-center gap-1 rounded-full border border-border bg-card px-3 py-1 text-xs font-medium text-muted-foreground transition-colors hover:bg-accent/50 hover:text-foreground"
              >
                <GraduationCap size={11} aria-hidden="true" /> {tp("learningLink")}
              </Link>
            </div>
          </div>

          {tab === "live" && (
            <>
              <AgentQualityPanel agent={selectedAgent} />
              <AgentImprovementsCard agentId={selectedAgentId} />
            </>
          )}

          {tab === "datasets" && (
            <>
              {/* Offline half of the quality story (eval-datasets workstream):
                  golden-case datasets + regression-run history for the
                  selected agent. Colocated in this route folder — see the
                  component's own javadoc-style header for the api.ts /
                  en.json cross-workstream notes. */}
              <EvalDatasetsSection agentId={selectedAgentId} />
              {/* The gate the runs feed: whether promoting the agent's
                  current head config to Production would be allowed right
                  now, plus the configure/disable/remove controls. Self-hides
                  on a core that predates EvalGateController (404) - see the
                  component header. */}
              <EvalGateSection agentId={selectedAgentId} />
              {/* Can the LLM judge behind those runs be trusted? Stored human-labelled sets, kappa and its drift
                  over time. Self-hides when calibration is off on this core (404) or on the FREE plan (403). */}
              <JudgeCalibrationSection agentId={selectedAgentId} />
            </>
          )}

          {/* Adversarial persona simulations (V266/V344): bounded multi-turn
              conversations with tools disabled, judged transcripts, per-run
              budget caps and session cancel. Self-hides like the gate. */}
          {tab === "simulations" && (
            <>
              {/* "Attack my agent": poisoned email / web page / MCP description / document
                  scenarios with an attack-success-rate report. Self-hides when core has
                  the scenarios switched off. */}
              <div id="attack">
                <AttackSection agentId={selectedAgentId} onAvailabilityChange={setAttackAvailable} />
              </div>
              <SimulationSection agentId={selectedAgentId} />
            </>
          )}

          {/* The statistics layer, kept as the last agent tab: it
              reinterprets the quantities the other tabs render as bare
              point estimates - n=1 numbers by construction - as measurements
              (estimate + interval + independent-rollout count), badges
              anything at n=1 as unreplicated, and is the only surface
              allowed to compare two runs, because it is the only one that
              can refuse to when the paired interval straddles zero or the
              gap sits inside this tenant's measured noise floor.

              Its backend is core/quality's EvalReliabilityController
              (/quality/reliability, /reliability/noise-floor,
              /reliability/compare) - pooled per-DATASET reliability rather
              than per-run, reconciled 2026-10-03 in evalStatisticsApi.ts.
              The section self-hides per endpoint on a 404, renders the
              plan gate inline on a 403, and says so honestly when the
              shared api client has no methods for it. It never renders a
              number it did not receive. */}
          {tab === "reliability" && <EvalStatisticsSection agentId={selectedAgentId} />}
        </div>
      )}
    </AppShell>
  );
}

export default function AgentQualityPage() {
  return (
    <Suspense fallback={<Skeleton className="m-4 h-40" />}>
      <AgentQualityInner />
    </Suspense>
  );
}
