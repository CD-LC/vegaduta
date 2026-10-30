"use client";

import type { ComponentType } from "react";
import Select from "../Select";
import { Button } from "../ui/button";
import { Card, CardContent } from "../ui/card";
import { WORKFLOW_SORTS, type WorkflowSort } from "./workflowList";
import { useTranslations } from "../../lib/i18n";

/** The workflow list's stat tiles (total, active, capacity). */
export function WorkflowStatTiles({
  stats,
}: {
  stats: { key: string; label: string; value: string; icon: ComponentType<{ size?: number; strokeWidth?: number }> }[];
}) {
  return (
    <div className="grid gap-4 sm:grid-cols-3">
      {stats.map((s) => {
        const Icon = s.icon;
        return (
          <Card key={s.key} className="gap-0 py-0">
            <CardContent className="flex items-center gap-4 p-4">
              <div className="flex size-10 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary">
                <Icon size={18} strokeWidth={2.1} />
              </div>
              <div className="min-w-0">
                <div className="text-xs font-medium uppercase tracking-wide text-muted-foreground">{s.label}</div>
                <div className="text-2xl font-semibold tabular-nums text-foreground">{s.value}</div>
              </div>
            </CardContent>
          </Card>
        );
      })}
    </div>
  );
}

/** Status chips (All + one per status present) and the sort picker above the list. */
export function WorkflowListFilters({
  total,
  statuses,
  statusFilter,
  setStatusFilter,
  sort,
  setSort,
}: {
  total: number;
  statuses: { status: string; count: number }[];
  statusFilter: string;
  setStatusFilter: (status: string) => void;
  sort: WorkflowSort;
  setSort: (sort: WorkflowSort) => void;
}) {
    const tHC = useTranslations("hcComponentsWorkflowWorkflowListChrome");
  return (
    <div className="flex flex-wrap items-center justify-between gap-2">
      <div role="group" aria-label={tHC("s2")} className="flex flex-wrap gap-1.5">
        <Button
          type="button"
          size="sm"
          variant={statusFilter === "" ? "default" : "outline"}
          aria-pressed={statusFilter === ""}
          className="h-7 px-2.5 text-xs"
          onClick={() => setStatusFilter("")}
        >
          All <span className="tabular-nums">{total}</span>
        </Button>
        {statuses.map((s) => (
          <Button
            key={s.status}
            type="button"
            size="sm"
            variant={statusFilter === s.status ? "default" : "outline"}
            aria-pressed={statusFilter === s.status}
            className="h-7 px-2.5 text-xs capitalize"
            onClick={() => setStatusFilter(s.status)}
          >
            {s.status.toLowerCase()} <span className="tabular-nums">{s.count}</span>
          </Button>
        ))}
      </div>
      <Select
        aria-label={tHC("s1")}
        value={sort}
        onChange={(e) => setSort(e.target.value as WorkflowSort)}
        options={WORKFLOW_SORTS}
      />
    </div>
  );
}
