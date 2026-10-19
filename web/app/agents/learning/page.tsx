"use client";

import { Suspense, useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { ArrowLeft } from "lucide-react";
import { api } from "../../lib/api";
import AppShell from "../../components/AppShell";
import PageHeader from "../../components/ax/PageHeader";
import Select from "../../components/Select";
import { Button } from "../../components/ui/button";
import { EmptyState } from "../../components/ax/page-kit";
import { Sparkles } from "lucide-react";
import AgentLearningPanel from "../../components/agents/AgentLearningPanel";
import { Skeleton } from "../../components/ui/skeleton";
import { Alert, AlertDescription } from "../../components/ui/alert";
import { useTranslations } from "../../lib/i18n";

/**
 * Agent Learning - a dedicated read/observe surface for the reinforcement-
 * learning loop, split out of the Agents workbench rather than folded into it.
 *
 * Why a separate route instead of a section inside AgentWorkbench: the
 * workbench is a large, two-pane build-and-test screen under active concurrent
 * edits; a self-contained route keeps this observability surface from
 * competing with that file for space (and merges), and gives the reward
 * trend / arms table / policy controls room to breathe. It reuses the same
 * AppShell + PageHeader shell as every other console screen, and deep-links via
 * ?agentId= so a "View learning" action from the Agents page lands on the right
 * agent. The Agents nav item stays highlighted (its rail entry is exact:false).
 */
function AgentLearningInner() {
  const searchParams = useSearchParams();
  const tp = useTranslations("consolePolish");
  const [agents, setAgents] = useState<any[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [loadError, setLoadError] = useState(false);
  const [selectedAgentId, setSelectedAgentId] = useState<string | null>(searchParams.get("agentId"));

  const refresh = useCallback(async () => {
    setLoadError(false);
    try {
      const data = await api.listAgents();
      setAgents(data || []);
      setSelectedAgentId((cur) => cur || (data && data[0]?.id) || null);
    } catch {
      setAgents([]);
      setLoadError(true);
    } finally {
      setLoaded(true);
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
        title={tp("learningTitle")}
        subtitle={tp("learningSubtitle")}
        actions={
          <Button asChild variant="outline" size="sm">
            <Link href="/agents">
              <ArrowLeft size={14} aria-hidden="true" /> {tp("backToAgents")}
            </Link>
          </Button>
        }
      />

      {!loaded ? (
        <div className="space-y-3" aria-busy="true">
          <Skeleton className="h-10 w-72" />
          <Skeleton className="h-40 w-full" />
        </div>
      ) : loadError ? (
        <Alert variant="destructive">
          <AlertDescription className="flex flex-wrap items-center justify-between gap-2">
            <span>{tp("agentsLoadError")}</span>
            <Button type="button" size="sm" variant="outline" onClick={() => void refresh()}>
              {tp("retry")}
            </Button>
          </AlertDescription>
        </Alert>
      ) : agents.length === 0 ? (
        <EmptyState
          icon={Sparkles}
          title={tp("noAgentsTitle")}
          description={tp("learningNoAgentsBody")}
          action={
            <Button asChild size="sm">
              <Link href="/agents">{tp("goToAgents")}</Link>
            </Button>
          }
        />
      ) : (
        <div className="space-y-8">
          <div className="flex flex-wrap items-center gap-3">
            <span className="text-sm font-medium text-foreground">{tp("agentLabel")}</span>
            <Select
              className="min-w-56"
              value={selectedAgentId}
              onChange={(e: any) => setSelectedAgentId(e.target.value)}
              options={agentOptions}
              searchable
              placeholder={tp("selectAgent")}
            />
          </div>
          <AgentLearningPanel agent={selectedAgent} />
        </div>
      )}
    </AppShell>
  );
}

export default function AgentLearningPage() {
  return (
    <Suspense fallback={null}>
      <AgentLearningInner />
    </Suspense>
  );
}
