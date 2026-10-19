"use client";

import { Search, X } from "lucide-react";
import Select from "../components/Select";
import { Button } from "../components/ui/button";
import { Input } from "../components/ui/input";
import { useTranslations } from "../lib/i18n";
import {
  EMPTY_FACT_FILTER,
  isFactFilterActive,
  type FactFilter,
  type FactScopeFilter,
  type FactSort,
} from "./memoryFilters";

/**
 * Search + scope + agent filters over the facts list. Purely controlled: the
 * page owns the filter and runs it through filterFacts().
 */
export default function FactsToolbar({
  filter,
  onChange,
  agentOptions,
  shown,
  total,
  sort,
  onSortChange,
}: {
  filter: FactFilter;
  onChange: (next: FactFilter) => void;
  /** Agents that own at least one private fact. */
  agentOptions: { id: string; name: string }[];
  shown: number;
  total: number;
  sort: FactSort;
  onSortChange: (next: FactSort) => void;
}) {
  const t = useTranslations("memoryPage");
  const active = isFactFilterActive(filter);

  return (
    <div className="flex flex-wrap items-center gap-2" data-testid="memory-facts-toolbar">
      <div className="relative min-w-0 flex-1 basis-56">
        <Search
          size={14}
          className="pointer-events-none absolute left-2.5 top-1/2 -translate-y-1/2 text-muted-foreground"
          aria-hidden="true"
        />
        <Input
          type="search"
          value={filter.query}
          onChange={(e) => onChange({ ...filter, query: e.target.value })}
          placeholder={t("searchPlaceholder")}
          aria-label={t("searchPlaceholder")}
          className="pl-8"
        />
      </div>
      <div className="min-w-[9.5rem] flex-1 sm:w-44 sm:flex-none">
        <Select
          aria-label={t("filterScopeLabel")}
          value={filter.scope}
          onChange={(e) => {
            const scope = e.target.value as FactScopeFilter;
            // Shared facts belong to no agent, so an agent pick cannot survive that scope.
            onChange({ ...filter, scope, agentId: scope === "shared" ? "" : filter.agentId });
          }}
          options={[
            { value: "all", label: t("filterScopeAll") },
            { value: "agent", label: t("filterScopeAgent") },
            { value: "shared", label: t("filterScopeShared") },
          ]}
        />
      </div>
      {agentOptions.length > 0 && filter.scope !== "shared" && (
        <div className="min-w-[9.5rem] flex-1 sm:w-44 sm:flex-none">
          <Select
            aria-label={t("filterAgentLabel")}
            value={filter.agentId}
            onChange={(e) => onChange({ ...filter, agentId: e.target.value })}
            options={[
              { value: "", label: t("filterAgentAll") },
              ...agentOptions.map((a) => ({ value: a.id, label: a.name })),
            ]}
          />
        </div>
      )}
      <div className="min-w-[9.5rem] flex-1 sm:w-44 sm:flex-none">
        <Select
          aria-label={t("sortLabel")}
          value={sort}
          onChange={(e) => onSortChange(e.target.value as FactSort)}
          options={[
            { value: "newest", label: t("sortNewest") },
            { value: "oldest", label: t("sortOldest") },
            { value: "mostUsed", label: t("sortMostUsed") },
            { value: "neverUsed", label: t("sortNeverUsed") },
          ]}
        />
      </div>
      {active && (
        <>
          <Button variant="ghost" size="sm" onClick={() => onChange(EMPTY_FACT_FILTER)}>
            <X size={14} aria-hidden="true" /> {t("filterClear")}
          </Button>
          <span className="text-xs tabular-nums text-muted-foreground" role="status">
            {t("filterCount", { shown, total })}
          </span>
        </>
      )}
    </div>
  );
}
