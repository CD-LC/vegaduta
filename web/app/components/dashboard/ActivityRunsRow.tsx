"use client";

import Link from "next/link";
import { Bot, Workflow } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { Button } from "../ui/button";
import { Section, EmptyState } from "../ax/page-kit";
import { Card } from "../ui/card";
import { Badge } from "../ui/badge";
import { runColor, runLabel } from "./dashboardFormat";

/* eslint-disable @typescript-eslint/no-explicit-any */
/**
 * Agent activity | Workflow runs. Not deferred on first run: content-visibility
 * skipped painting the "No agents yet" EmptyState (and its Create agent
 * button) until a scroll, which for a brand-new member meant the page's one
 * useful action was invisible.
 */
export default function ActivityRunsRow({
  agents,
  workflows,
  firstRun,
}: {
  agents: any[];
  workflows: any[];
  firstRun: boolean;
}) {
  const t = useTranslations("dashboard");
  const topAgents = agents.slice(0, 4);
  const topRuns = workflows.slice(0, 3);
  return (
    <div className={(firstRun ? "" : "defer-offscreen ") + "grid grid-cols-1 gap-4 md:grid-cols-2"}>
      <Section
        title={t("agentActivity")}
        actions={
          <Button asChild variant="link" className="h-auto p-0 text-sm">
            <Link href="/agents">{t("manageArrow")}</Link>
          </Button>
        }
      >
        <Card className="gap-0 p-2">
          {topAgents.length === 0 ? (
            <EmptyState
              icon={Bot}
              title={t("noAgentsYet")}
              action={
                <Button asChild size="sm">
                  <Link href="/agents">{t("createAgentCta")}</Link>
                </Button>
              }
            />
          ) : (
            <ul className="divide-y divide-border">
              {topAgents.map((agent, i) => {
                const modelInfo = [agent.providerName, agent.model].filter(Boolean).join(" · ");
                return (
                  <li key={agent.id || agent.name} className="flex items-center gap-3 px-3 py-3">
                    <span
                      className="size-2 shrink-0 rounded-full"
                      style={{ background: i % 2 === 0 ? "var(--ax-cyan)" : "var(--ax-violet)" }}
                    />
                    <span className="truncate text-sm font-medium text-foreground">{agent.name}</span>
                    <span className="ms-auto truncate text-xs text-muted-foreground">{modelInfo || "—"}</span>
                  </li>
                );
              })}
            </ul>
          )}
        </Card>
      </Section>

      <Section
        title={t("workflowRuns")}
        actions={
          <Button asChild variant="link" className="h-auto p-0 text-sm">
            <Link href="/workflows">{t("historyArrow")}</Link>
          </Button>
        }
      >
        <Card className="gap-0 p-2">
          {topRuns.length === 0 ? (
            <EmptyState
              icon={Workflow}
              title={t("noWorkflowsYet")}
              action={
                <Button asChild size="sm">
                  <Link href="/workflows">{t("createWorkflowCta")}</Link>
                </Button>
              }
            />
          ) : (
            <ul className="divide-y divide-border">
              {topRuns.map((wf) => {
                const status = wf.status;
                return (
                  <li key={wf.id || wf.name} className="flex items-center gap-3 px-3 py-3">
                    <span className="size-2 shrink-0 rounded-full" style={{ background: runColor(status) }} />
                    <span className="truncate text-sm font-medium text-foreground">
                      {wf.name || t("untitledWorkflow")}
                    </span>
                    <Badge variant="outline" className="ms-auto tabular-nums" style={{ color: runColor(status) }}>
                      {runLabel(status, t)}
                    </Badge>
                  </li>
                );
              })}
            </ul>
          )}
        </Card>
      </Section>
    </div>
  );
}
