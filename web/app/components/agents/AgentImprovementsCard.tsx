"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { Lightbulb, Loader2, Sparkles } from "lucide-react";
import { api } from "../../lib/api";
import type { AgentImprovementSuggestion } from "../../lib/api/agents";
import { useTranslations } from "../../lib/i18n";
import { usePagination } from "../../lib/usePagination";
import PaginationControls from "../PaginationControls";
import { EmptyState, Section } from "../ax/page-kit";
import { Alert, AlertDescription } from "../ui/alert";
import { Badge } from "../ui/badge";
import { Button } from "../ui/button";
import { Card } from "../ui/card";
import { Skeleton } from "../ui/skeleton";

/*
 * Improvement suggestions for one agent (AgentImprovementController): scan
 * for suggestions (Generate - spends LLM budget on this agent), review each
 * one's rationale and proposed text, then Accept (applies it) or Dismiss.
 */
type Filter = "PENDING" | "ACCEPTED" | "DISMISSED" | "ALL";

export default function AgentImprovementsCard({ agentId }: { agentId: string | null }) {
  const t = useTranslations("agentsUi");
  const [rows, setRows] = useState<AgentImprovementSuggestion[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [generating, setGenerating] = useState(false);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [filter, setFilter] = useState<Filter>("PENDING");

  const load = useCallback(async () => {
    if (!agentId) return;
    setError(null);
    setRows(null);
    try {
      setRows((await api.listAgentImprovements(agentId)) || []);
    } catch (e) {
      setError(e instanceof Error ? e.message : t("improvementsLoadFailed"));
    }
  }, [agentId, t]);

  useEffect(() => {
    void load();
  }, [load]);

  const visible = useMemo(
    () => (rows ?? []).filter((r) => filter === "ALL" || r.status === filter),
    [rows, filter]
  );
  const pg = usePagination(visible, 10);

  async function generate() {
    if (!agentId) return;
    setGenerating(true);
    setError(null);
    setNotice(null);
    try {
      const created = await api.generateAgentImprovements(agentId);
      setNotice(t("improvementsGenerated", { count: created?.length ?? 0 }));
      setRows((await api.listAgentImprovements(agentId)) || []);
      setFilter("PENDING");
    } catch (e) {
      setError(e instanceof Error ? e.message : t("improvementsGenerateFailed"));
    } finally {
      setGenerating(false);
    }
  }

  async function decide(id: string, action: "accept" | "dismiss") {
    if (!agentId) return;
    setBusyId(id);
    setError(null);
    setNotice(null);
    try {
      const updated =
        action === "accept"
          ? await api.acceptAgentImprovement(agentId, id)
          : await api.dismissAgentImprovement(agentId, id);
      setRows((cur) => (cur ?? []).map((r) => (r.id === id ? { ...r, ...updated } : r)));
      setNotice(action === "accept" ? t("improvementAccepted") : t("improvementDismissed"));
    } catch (e) {
      setError(e instanceof Error ? e.message : t("improvementActionFailed"));
    } finally {
      setBusyId(null);
    }
  }

  const filters: { key: Filter; label: string }[] = [
    { key: "PENDING", label: t("filterPending") },
    { key: "ACCEPTED", label: t("filterAccepted") },
    { key: "DISMISSED", label: t("filterDismissed") },
    { key: "ALL", label: t("filterAll") },
  ];

  return (
    <Section
      title={t("improvementsTitle")}
      description={t("improvementsDescription")}
      actions={
        <Button type="button" size="sm" onClick={() => void generate()} disabled={!agentId || generating}>
          {generating ? (
            <Loader2 className="size-3.5 animate-spin" aria-hidden="true" />
          ) : (
            <Sparkles size={14} aria-hidden="true" />
          )}
          {generating ? t("improvementsGenerating") : t("improvementsGenerate")}
        </Button>
      }
    >
      {error && (
        <Alert variant="destructive">
          <AlertDescription className="flex flex-wrap items-center justify-between gap-2">
            <span>{error}</span>
            <Button type="button" size="sm" variant="outline" onClick={() => void load()}>
              {t("retry")}
            </Button>
          </AlertDescription>
        </Alert>
      )}
      {notice && (
        <p className="text-sm text-muted-foreground" role="status">
          {notice}
        </p>
      )}

      <div className="flex flex-wrap gap-2" role="group" aria-label={t("improvementsFilter")}>
        {filters.map((f) => (
          <Button
            key={f.key}
            type="button"
            size="sm"
            variant={filter === f.key ? "default" : "outline"}
            aria-pressed={filter === f.key}
            onClick={() => setFilter(f.key)}
          >
            {f.label}
          </Button>
        ))}
      </div>

      {rows === null && !error && (
        <div className="space-y-2" aria-busy="true">
          <Skeleton className="h-20 w-full" />
          <Skeleton className="h-20 w-full" />
        </div>
      )}

      {rows !== null && visible.length === 0 && (
        <EmptyState
          icon={Lightbulb}
          title={t("improvementsEmptyTitle")}
          description={t("improvementsEmptyBody")}
        />
      )}

      {pg.pageItems.length > 0 && (
        <ul className="space-y-3">
          {pg.pageItems.map((s) => (
            <li key={s.id}>
              <Card className="space-y-2 p-4">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="min-w-0 break-words text-sm font-semibold text-foreground">{s.title}</span>
                  <Badge variant="category">{s.kind.replace(/_/g, " ").toLowerCase()}</Badge>
                  <Badge
                    variant="state"
                    state={
                      s.status === "ACCEPTED"
                        ? "var(--state-success)"
                        : s.status === "DISMISSED"
                          ? "var(--state-idle)"
                          : "var(--state-warning)"
                    }
                  >
                    {s.status.toLowerCase()}
                  </Badge>
                  <span className="text-xs text-muted-foreground">{new Date(s.createdAt).toLocaleString()}</span>
                </div>
                {s.rationale && <p className="text-sm text-muted-foreground">{s.rationale}</p>}
                {s.suggestedText && (
                  <pre className="max-h-48 overflow-auto whitespace-pre-wrap break-words rounded-md bg-muted/50 p-2 font-mono text-xs text-foreground">
                    {s.suggestedText}
                  </pre>
                )}
                {s.status === "PENDING" && (
                  <div className="flex flex-wrap gap-2">
                    <Button type="button" size="sm" disabled={busyId !== null} onClick={() => void decide(s.id, "accept")}>
                      {t("improvementAccept")}
                    </Button>
                    <Button
                      type="button"
                      size="sm"
                      variant="outline"
                      disabled={busyId !== null}
                      onClick={() => void decide(s.id, "dismiss")}
                    >
                      {t("improvementDismiss")}
                    </Button>
                  </div>
                )}
              </Card>
            </li>
          ))}
        </ul>
      )}

      <PaginationControls
        page={pg.page}
        totalPages={pg.totalPages}
        totalItems={pg.totalItems}
        pageSize={pg.pageSize}
        onPageChange={pg.setPage}
        onPageSizeChange={pg.setPageSize}
        itemLabel={t("improvementsItemLabel")}
      />
    </Section>
  );
}
