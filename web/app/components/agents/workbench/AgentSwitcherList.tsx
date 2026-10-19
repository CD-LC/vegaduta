"use client";

import { useMemo, useState } from "react";
import { useTranslations } from "../../../lib/i18n";
import { usePagination } from "../../../lib/usePagination";
import { cn } from "../../../lib/utils";
import PaginationControls from "../../PaginationControls";
import { Input } from "../../ui/input";
import { AgentMark, AgentStateDot, agentCategory, agentState } from "../agentIdentity";
import type { WorkbenchAgent } from "../AgentWorkbench/types";

/** Switcher list sizes: small pages keep the popover short; the search finds the rest. */
const SWITCHER_PAGE_SIZES = [10, 25, 50];

/** True when the agent matches every whitespace-separated term of the query (name, category label, model). */
export function agentMatchesQuery(agent: WorkbenchAgent, categoryLabel: string, query: string): boolean {
  const terms = query.toLowerCase().split(/\s+/).filter(Boolean);
  if (terms.length === 0) return true;
  const haystack = `${agent.name ?? ""} ${categoryLabel} ${agent.model ?? ""}`.toLowerCase();
  return terms.every((term) => haystack.includes(term));
}

/**
 * The agent rows of the workbench switcher with a search box and the shared
 * pager, so a tenant with many agents is no longer an unsearchable, unpaged
 * scroll. Mounted only while the switcher is open, so the query and page reset
 * every time it is reopened.
 */
export default function AgentSwitcherList({
  agents,
  selectedAgentId,
  disabled,
  onPick,
}: {
  agents: WorkbenchAgent[];
  selectedAgentId: string | null;
  disabled: boolean;
  onPick: (agentId: string) => void;
}) {
  const t = useTranslations("agentWorkbenchSwitcher");
  const [query, setQuery] = useState("");
  const filtered = useMemo(
    () => agents.filter((a) => agentMatchesQuery(a, agentCategory(a).label, query)),
    [agents, query]
  );
  const pg = usePagination(filtered, SWITCHER_PAGE_SIZES[0]);

  return (
    <>
      {agents.length > SWITCHER_PAGE_SIZES[0] && (
        <li className="px-1 pb-1.5 pt-1">
          <Input
            type="search"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder={t("searchPlaceholder")}
            aria-label={t("searchLabel")}
            // Escape belongs to the listbox (closes the switcher); keep typing keys local.
            className="h-8 text-sm"
          />
        </li>
      )}
      {query.trim() && filtered.length === 0 && (
        <li className="px-2.5 py-3 text-xs text-muted-foreground">{t("noMatches", { query: query.trim() })}</li>
      )}
      {pg.pageItems.map((a) => {
        const rowState = agentState(a);
        const rowCategory = agentCategory(a);
        return (
          <li key={a.id}>
            <button
              type="button"
              role="option"
              aria-selected={a.id === selectedAgentId}
              className={cn(
                "card-interactive flex w-full items-center gap-2.5 rounded-lg border px-2.5 py-2 text-left disabled:cursor-not-allowed disabled:opacity-50",
                // elev-raised carries its own border colour, so the
                // transparent border is only for the unselected rows
                // (keeps every row the same height either way).
                a.id === selectedAgentId ? "elev-raised" : "border-transparent"
              )}
              style={{ ["--cat" as any]: rowCategory.token } as any}
              disabled={disabled}
              onClick={() => onPick(a.id)}
            >
              <AgentMark agent={a} size="md" />
              <span className="flex min-w-0 flex-1 flex-col">
                <span
                  className={cn(
                    "truncate text-sm",
                    a.id === selectedAgentId ? "font-semibold text-foreground" : "text-foreground/90"
                  )}
                >
                  {a.name}
                </span>
                <span className="truncate text-[0.6875rem] text-muted-foreground">
                  {rowCategory.label}
                  {a.model ? ` · ${a.model}` : ""}
                </span>
              </span>
              <AgentStateDot state={rowState} className="mr-0.5" />
            </button>
          </li>
        );
      })}
      {filtered.length > SWITCHER_PAGE_SIZES[0] && (
        <li className="px-1 pt-1.5">
          <PaginationControls
            page={pg.page}
            totalPages={pg.totalPages}
            totalItems={pg.totalItems}
            pageSize={pg.pageSize}
            onPageChange={pg.setPage}
            onPageSizeChange={pg.setPageSize}
            pageSizeOptions={SWITCHER_PAGE_SIZES}
            itemLabel={t("itemLabel")}
          />
        </li>
      )}
    </>
  );
}
