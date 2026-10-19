"use client";

import { useState } from "react";
import Link from "next/link";
import { AlertTriangle } from "lucide-react";
import { api } from "../lib/api";
import PaginationControls from "../components/PaginationControls";
import { Alert, AlertDescription, AlertTitle } from "../components/ui/alert";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "../components/ui/card";
import { Switch } from "../components/ui/switch";
import { useTranslations } from "../lib/i18n";
import { usePagination } from "../lib/usePagination";
import type { MemoryAgent } from "./memoryTypes";

/**
 * Which agents actually read the notes above. Notes are only placed in
 * conversations with agents that have shared memory on, and that is off by
 * default - so this card puts the switch next to the notes and warns when
 * notes exist but no agent would ever see them.
 */
export default function AgentsUsingNotes({
  agents,
  agentsFailed,
  notesWritten,
  onChanged,
}: {
  /** null while loading. */
  agents: MemoryAgent[] | null;
  agentsFailed: boolean;
  notesWritten: boolean;
  /** Called with the agent id and its new setting after the server accepted it. */
  onChanged: (agentId: string, enabled: boolean) => void;
}) {
  const t = useTranslations("memoryPage");
  const [savingId, setSavingId] = useState<string | null>(null);
  const pagination = usePagination(agents || []);

  const optedIn = (agents || []).filter((a) => a.shareMemoryWithTenant).length;
  const noneOptedIn = agents != null && agents.length > 0 && optedIn === 0;

  const toggle = async (agent: MemoryAgent, enabled: boolean) => {
    setSavingId(agent.id);
    try {
      await api.setAgentShareMemory(agent.id, enabled);
      onChanged(agent.id, enabled);
    } catch {
      /* toJson already showed why (for example, no edit access to that agent) */
    } finally {
      setSavingId(null);
    }
  };

  return (
    <Card elevation="raised" data-testid="memory-agents-using-notes">
      <CardHeader>
        <CardTitle>{t("agentsHeading")}</CardTitle>
        <CardDescription>{t("agentsIntro")}</CardDescription>
      </CardHeader>
      <CardContent className="space-y-3">
        {notesWritten && noneOptedIn && (
          <Alert variant="warning">
            <AlertTriangle aria-hidden="true" />
            <AlertTitle>{t("agentsNoneTitle")}</AlertTitle>
            <AlertDescription>{t("agentsNoneBody")}</AlertDescription>
          </Alert>
        )}
        {agentsFailed && <p className="text-sm text-muted-foreground">{t("agentsLoadFailed")}</p>}
        {!agentsFailed && agents == null && <p className="text-sm text-muted-foreground">{t("agentsLoading")}</p>}
        {agents != null && agents.length === 0 && (
          <p className="text-sm text-muted-foreground">
            {t("agentsEmpty")}{" "}
            <Link href="/agents" className="font-medium text-primary underline-offset-4 hover:underline">
              {t("agentsEmptyLink")}
            </Link>
          </p>
        )}
        {agents != null && agents.length > 0 && (
          <>
            <p className="text-xs text-muted-foreground" role="status">
              {t("agentsCount", { count: optedIn, total: agents.length })}
            </p>
            <ul className="divide-y divide-border rounded-lg border border-border">
              {pagination.pageItems.map((agent) => (
                <li key={agent.id} className="flex items-center justify-between gap-3 px-3 py-2">
                  <span className="min-w-0">
                    <span className="block truncate text-sm text-foreground">{agent.name}</span>
                    {agent.editable === false && (
                      <span className="block text-xs text-muted-foreground">{t("agentsReadOnly")}</span>
                    )}
                  </span>
                  <Switch
                    checked={!!agent.shareMemoryWithTenant}
                    disabled={savingId === agent.id || agent.editable === false}
                    aria-label={t("agentsSwitchLabel", { name: agent.name })}
                    onCheckedChange={(enabled) => toggle(agent, enabled)}
                  />
                </li>
              ))}
            </ul>
            <PaginationControls
              page={pagination.page}
              totalPages={pagination.totalPages}
              totalItems={pagination.totalItems}
              pageSize={pagination.pageSize}
              onPageChange={pagination.setPage}
              onPageSizeChange={pagination.setPageSize}
              itemLabel={t("agentsItemLabel")}
            />
            <p className="text-xs text-muted-foreground">{t("agentsSharedNote")}</p>
          </>
        )}
      </CardContent>
    </Card>
  );
}
