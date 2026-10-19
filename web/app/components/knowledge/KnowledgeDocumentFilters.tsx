"use client";

import { Search } from "lucide-react";
import Select from "../Select";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { DOC_SORTS, DOC_STATUS_GROUPS, type DocSort, type DocStatusGroup } from "./knowledgeDocs";

/** The file table's name filter, sort picker and status-group chips. */
export default function KnowledgeDocumentFilters({
  docQuery,
  setDocQuery,
  docSort,
  setDocSort,
  docGroup,
  setDocGroup,
  docCounts,
}: {
  docQuery: string;
  setDocQuery: (query: string) => void;
  docSort: DocSort;
  setDocSort: (sort: DocSort) => void;
  docGroup: DocStatusGroup;
  setDocGroup: (group: DocStatusGroup) => void;
  docCounts: Record<string, number>;
}) {
  return (
    <>
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <div className="relative min-w-0 flex-1 basis-44">
          <Search
            aria-hidden="true"
            size={14}
            className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground"
          />
          <Input
            type="search"
            value={docQuery}
            onChange={(e) => setDocQuery(e.target.value)}
            placeholder="Filter files by name"
            aria-label="Filter files by name"
            className="h-8 pl-9"
          />
        </div>
        <Select
          aria-label="Sort files"
          value={docSort}
          onChange={(e) => setDocSort(e.target.value as DocSort)}
          options={DOC_SORTS}
        />
      </div>
      <div role="group" aria-label="Filter files by status" className="mb-3 flex flex-wrap gap-1.5">
        {DOC_STATUS_GROUPS.filter((g) => g.id === "all" || docCounts[g.id] > 0).map((g) => (
          <Button
            key={g.id}
            type="button"
            size="sm"
            variant={docGroup === g.id ? "default" : "outline"}
            aria-pressed={docGroup === g.id}
            className="h-7 px-2.5 text-xs"
            onClick={() => setDocGroup(g.id)}
          >
            {g.label} <span className="tabular-nums">{docCounts[g.id]}</span>
          </Button>
        ))}
      </div>
    </>
  );
}
