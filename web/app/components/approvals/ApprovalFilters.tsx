"use client";

import { useMemo } from "react";
import { Select } from "../ui/select";
import { Button } from "../ui/button";
import { useTranslations } from "../../lib/i18n";
import { AGE_FILTERS, NO_FILTERS, type AgeFilter, type QueueFilters } from "./approvalsQueueModel";

const ANY = "__any__";

/**
 * Agent / tool / age filters for the queue. The server can only filter by status, so these narrow the rows the
 * queue has loaded (the panel reads a larger window while one is active - see ApprovalsPanel).
 */
export default function ApprovalFilters({
  filters,
  onChange,
  agents,
  tools,
}: {
  filters: QueueFilters;
  onChange: (next: QueueFilters) => void;
  agents: string[];
  tools: string[];
}) {
  const t = useTranslations("approvalsQueue");
  const agentOptions = useMemo(
    () => [{ value: ANY, label: t("filterAnyAgent") }, ...agents.map((a) => ({ value: a, label: `${a.slice(0, 8)}…` }))],
    [agents, t]
  );
  const toolOptions = useMemo(() => [{ value: ANY, label: t("filterAnyTool") }, ...tools.map((x) => ({ value: x, label: x }))], [tools, t]);
  const ageOptions = useMemo(
    () => AGE_FILTERS.map((a) => ({ value: a || ANY, label: t(a ? `age_${a}` : "filterAnyAge") })),
    [t]
  );
  const active = !!(filters.agent || filters.tool || filters.age);

  // A selected value that is no longer in the loaded rows must still be shown and clearable.
  const withCurrent = (opts: Array<{ value: string; label: string }>, current: string) =>
    current && !opts.some((o) => o.value === current) ? [...opts, { value: current, label: current }] : opts;

  return (
    <div className="flex flex-wrap items-center gap-2" role="group" aria-label={t("filtersLabel")} data-testid="approvals-filters">
      <div className="min-w-36 flex-1">
        <Select
          aria-label={t("filterAgentLabel")}
          options={withCurrent(agentOptions, filters.agent)}
          value={filters.agent || ANY}
          onValueChange={(v) => onChange({ ...filters, agent: v === ANY ? "" : v })}
        />
      </div>
      <div className="min-w-36 flex-1">
        <Select
          aria-label={t("filterToolLabel")}
          options={withCurrent(toolOptions, filters.tool)}
          value={filters.tool || ANY}
          onValueChange={(v) => onChange({ ...filters, tool: v === ANY ? "" : v })}
        />
      </div>
      <div className="min-w-36 flex-1">
        <Select
          aria-label={t("filterAgeLabel")}
          options={ageOptions}
          value={filters.age || ANY}
          onValueChange={(v) => onChange({ ...filters, age: (v === ANY ? "" : v) as AgeFilter })}
        />
      </div>
      {active && (
        <Button type="button" size="sm" variant="ghost" onClick={() => onChange(NO_FILTERS)} data-testid="approvals-filters-clear">
          {t("filtersClear")}
        </Button>
      )}
    </div>
  );
}
