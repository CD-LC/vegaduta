"use client";

import { Suspense, useCallback, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import Link from "next/link";
import { AlertTriangle, ArrowRight, Bot, BrainCircuit } from "lucide-react";
import { api } from "../lib/api";
import AppShell from "../components/AppShell";
import ChatPanel from "../components/ChatPanel";
import Select from "../components/Select";
import { useTranslations } from "../lib/i18n";
import PageHeader from "../components/ax/PageHeader";
import { EmptyState } from "../components/ax/page-kit";
import { Button } from "../components/ui/button";
import { Card } from "../components/ui/card";
import { Label } from "../components/ui/label";
import PoweredByChip, { inferSource } from "../components/PoweredByChip";
import { initialAgentId, persistAgentSelection, resolveAgentId } from "./selection";

/**
 * Playground - chat with any one agent at a time. Split out from the old
 * single page (where Chat sat directly under the Agents panel, so picking
 * an agent there scrolled you to a chat box further down the same page)
 * into its own "Test" section page. Arriving via the "Chat →" link on an
 * Agents row (/playground?agent=<id>) pre-selects that agent - the one
 * cross-page link this split genuinely needed to keep the two feeling
 * connected rather than picking an agent from scratch every time.
 */
function PlaygroundInner() {
    const tHC = useTranslations("hcPlaygroundPlaygroundLegacy");
  const t = useTranslations("playground");
  const searchParams = useSearchParams();
  const [agents, setAgents] = useState([]);
  // URL first, then the last agent used in this browser - known on the first
  // render, so a reload never lands on the first agent in the list (see
  // ./selection.ts for why that mattered to resumed replies).
  const [selectedAgentId, setSelectedAgentId] = useState(() => initialAgentId(searchParams.get("agent")));
  // "Couldn't load" is NOT the same state as "you have none" (UX audit,
  // 2026-07-29). This page used to `.catch(() => setAgents([]))`, so a failed
  // /api/agents call rendered the identical "No agents yet - go create one"
  // empty state a brand-new tenant sees. That is actively misleading here:
  // the person may have ten working agents and is being told, in the app's
  // own voice, that they have zero. Tracking the failure separately lets the
  // render below tell the truth and offer a retry instead of a dead end.
  const [loadFailed, setLoadFailed] = useState(false);

  const loadAgents = useCallback(() => {
    setLoadFailed(false);
    api
      .listAgents()
      .then((data) => {
        setAgents(data);
        // Functional update: the stored/URL id set at first render is the
        // `current` here even though this callback closed over an older one.
        setSelectedAgentId((current) => resolveAgentId(data, searchParams.get("agent"), current));
      })
      .catch(() => {
        setAgents([]);
        setLoadFailed(true);
      });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    loadAgents();
  }, [loadAgents]);

  const selectedAgent = agents.find((a) => a.id === selectedAgentId) || null;
  // Mirror the selection (once it is a real agent in the list) to the address
  // bar and localStorage, so a reload or a copied link comes back to it.
  const persistedAgentId = selectedAgent?.id || "";
  useEffect(() => {
    if (persistedAgentId) persistAgentSelection(persistedAgentId);
  }, [persistedAgentId]);

  return (
    <AppShell eyebrow={t("eyebrow")}>
      <PageHeader
        eyebrow={t("eyebrow")}
        title={t("title")}
        subtitle="Chat with any one of your agents in an isolated sandbox — a fast way to test behaviour before it goes live on a channel."
        actions={
          // The agent playground tests the OUTPUT models; the decision
          // capability page (/capabilities/decision) tests the decision
          // providers that route to them - noul/choice/score questions
          // against the tenant's resolved key. Two halves of the same
          // "try it before it's live" surface.
          <Button asChild variant="outline" size="sm">
            <Link href="/capabilities/decision">
              <BrainCircuit aria-hidden="true" size={14} /> {tHC("hcPlaygroundPlaygroundLegacy.s5")} <ArrowRight aria-hidden="true" size={14} />
            </Link>
          </Button>
        }
      />
      {loadFailed ? (
        <EmptyState
          icon={AlertTriangle}
          title={tHC("hcPlaygroundPlaygroundLegacy.s4")}
          description="The request to load this workspace's agents failed, so this list is empty because we don't know — not because you have none. Check your connection and try again."
          action={
            <Button onClick={loadAgents} variant="outline">
              {tHC("hcPlaygroundPlaygroundLegacy.s3")}
            </Button>
          }
        />
      ) : agents.length === 0 ? (
        <EmptyState
          icon={Bot}
          title={t("noAgentsHeading")}
          description={t("noAgentsText")}
          action={
            <div className="flex flex-wrap items-center justify-center gap-3">
              <Button asChild>
                <Link href="/agents">{t("goToAgents")}</Link>
              </Button>
              <Link href="/help#agents" className="text-sm font-medium text-primary underline-offset-4 hover:underline">
                {tHC("hcPlaygroundPlaygroundLegacy.s2")}
              </Link>
            </div>
          }
        />
      ) : (
        <div className="space-y-6">
          <Card className="gap-2 p-5">
            <Label htmlFor="pg-agent-select">{t("agentLabel")}</Label>
            <div className="max-w-sm">
              <Select
                id="pg-agent-select"
                value={selectedAgentId}
                onChange={(e) => setSelectedAgentId(e.target.value)}
                options={agents.map((a) => ({ value: a.id, label: `${a.name} (${a.providerName})` }))}
              />
            </div>
            <p id="pg-agent-select-help" className="text-xs text-muted-foreground">
              {tHC("hcPlaygroundPlaygroundLegacy.s1")}
            </p>
            {/* Engine disclosure - the agent's configured provider/model.
             * "Will run on", not "powered by": each individual turn may still
             * answer on-device instead (edge tiers), and the turn's own badge
             * reports that when it happens. Inline copy per the en.json
             * concurrent-edit precedent (see build/page.tsx). */}
            {selectedAgent && (selectedAgent.providerName || selectedAgent.model) && (
              <PoweredByChip
                source={inferSource(selectedAgent.providerName)}
                provider={selectedAgent.providerName}
                model={selectedAgent.model}
                prefix="Runs on"
                title={
                  inferSource(selectedAgent.providerName) === "platform"
                    ? "Hosted turns run on VegaDūta's model pool; eligible turns may answer on-device instead - each reply is labeled."
                    : "Hosted turns run on this workspace's provider key; eligible turns may answer on-device instead - each reply is labeled."
                }
              />
            )}
          </Card>
          <ChatPanel agent={selectedAgent} initialInput={searchParams.get("prompt")?.slice(0, 600) || undefined} />
        </div>
      )}
    </AppShell>
  );
}

export default function PlaygroundLegacy() {
  return (
    <Suspense fallback={null}>
      <PlaygroundInner />
    </Suspense>
  );
}
