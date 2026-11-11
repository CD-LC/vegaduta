"use client";

import { useState, type Dispatch, type SetStateAction } from "react";
import { BookOpen, Database, Plus, Search } from "lucide-react";
import { SetupHint } from "../setup/SetupLinks";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { GUIDE_ANCHORS } from "./guide/agentChecklist";
import { CollapsibleSection } from "./AgentWorkbench/CollapsibleSection";
import { SUB_TEXT } from "./AgentWorkbench/styles";

/** Collections shown as chips per page. */
const KNOWLEDGE_PER_PAGE = 8;

interface Collection {
  id: string;
  name?: string;
}

/**
 * The "Knowledge collections" section of the agent workbench: the agent's attached
 * collections as chips (case-insensitive name search + client-side paging, both local
 * state) and the attach/save editor. Extracted from AgentWorkbench.tsx unchanged;
 * the parent passes `key={agent id}` so search and page reset when the agent changes.
 */
export default function KnowledgeCollectionsSection({
  t,
  isDraft,
  locked,
  collections,
  shownCollections,
  allowedCollectionIds,
  newAgentMode,
  knowledgeBaseEnabled,
  setKnowledgeBaseEnabled,
  editingKnowledge,
  setEditingKnowledge,
  knowledgeDraft,
  setKnowledgeDraft,
  toggleKnowledgeDraft,
  saveKnowledge,
  knowledgeBusy,
}: {
  t: (key: string, values?: any) => string;
  isDraft: boolean;
  locked: boolean;
  collections: Collection[];
  shownCollections: Collection[];
  allowedCollectionIds: string[] | undefined;
  newAgentMode: string;
  knowledgeBaseEnabled: boolean;
  setKnowledgeBaseEnabled: (v: boolean) => void;
  editingKnowledge: boolean;
  setEditingKnowledge: Dispatch<SetStateAction<boolean>>;
  knowledgeDraft: Set<string>;
  setKnowledgeDraft: (v: Set<string>) => void;
  toggleKnowledgeDraft: (id: string) => void;
  saveKnowledge: () => void;
  knowledgeBusy: boolean;
}) {
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(0);

  // Case-insensitive name filter + client-side pagination over the filtered
  // results (page resets to 0 on search change, see the search Input's onChange).
  const query = search.trim().toLowerCase();
  const filteredCollections = query
    ? shownCollections.filter((c) => (c.name || "").toLowerCase().includes(query))
    : shownCollections;
  const pageCount = Math.max(1, Math.ceil(filteredCollections.length / KNOWLEDGE_PER_PAGE));
  const pageClamped = Math.min(page, pageCount - 1);
  const pagedCollections = filteredCollections.slice(
    pageClamped * KNOWLEDGE_PER_PAGE,
    pageClamped * KNOWLEDGE_PER_PAGE + KNOWLEDGE_PER_PAGE
  );

  return (
    <CollapsibleSection
      icon={<BookOpen size={13} />}
      label={t("knowledgeCollectionsLabel")}
      cat="var(--cat-data)"
      anchorId={GUIDE_ANCHORS.knowledge}
      tourId="workbench-knowledge"
    >
      <SetupHint targets={["knowledge"]} lead="Upload documents and create collections:" className="mb-2" />
      {shownCollections.length > 0 && (
        <div className="relative mb-3">
          <Search
            size={13}
            className="pointer-events-none absolute start-3 top-1/2 -translate-y-1/2 text-muted-foreground"
          />
          <Input
            type="text"
            value={search}
            onChange={(e) => {
              setSearch(e.target.value);
              setPage(0);
            }}
            placeholder={t("collectionsSearchPlaceholder")}
            className="h-9 ps-8"
          />
        </div>
      )}
      <div className="flex flex-wrap items-center gap-2">
        {/* Empty states get the section's own category chip so a blank
            panel still says which subsystem it belongs to. */}
        {shownCollections.length === 0 && (
          <span className="elev-sunken flex flex-1 items-center gap-2.5 rounded-lg px-3 py-4">
            <span className="cat-chip cat-chip-sm" style={{ ["--cat" as any]: "var(--cat-data)" } as any}>
              <BookOpen size={12} />
            </span>
            <span className={SUB_TEXT}>{t("noCollectionsYet")}</span>
          </span>
        )}
        {shownCollections.length > 0 && filteredCollections.length === 0 && (
          <span className={SUB_TEXT}>{t("noCollectionsMatchSearch")}</span>
        )}
        {pagedCollections.map((c) => (
          <span
            className="inline-flex min-w-0 items-center gap-1.5 rounded-md border px-2 py-1 text-xs"
            key={c.id}
            style={
              {
                ["--cat" as any]: "var(--cat-data)",
                color: "var(--cat)",
                background: "color-mix(in srgb, var(--cat) 10%, transparent)",
                borderColor: "color-mix(in srgb, var(--cat) 26%, transparent)",
              } as any
            }
          >
            <Database size={11} strokeWidth={2} className="shrink-0" />
            <span className="min-w-0 max-w-[180px] truncate">{c.name}</span>
          </span>
        ))}
        {!isDraft && (
          <Button
            type="button"
            variant="outline"
            size="sm"
            disabled={locked}
            title={locked ? t("editLockedTooltip") : undefined}
            onClick={() => {
              if (locked) return;
              setEditingKnowledge((v) => {
                const next = !v;
                if (!next) {
                  // Closing without an explicit save (via this toggle rather
                  // than saveKnowledge's own success path) - re-hydrate the
                  // draft from the agent's real saved collections so an
                  // abandoned edit never survives a close.
                  setKnowledgeDraft(new Set(allowedCollectionIds || []));
                }
                return next;
              });
            }}
          >
            <Plus size={13} />
            {t("attachCollection")}
          </Button>
        )}
      </div>
      {pageCount > 1 && (
        <div className="mt-3 flex items-center justify-between gap-2">
          <Button
            type="button"
            variant="outline"
            size="sm"
            disabled={pageClamped <= 0}
            onClick={() => setPage(pageClamped - 1)}
          >
            {t("prevPage")}
          </Button>
          <span className="text-xs tabular-nums text-muted-foreground">
            {pageClamped + 1} / {pageCount}
          </span>
          <Button
            type="button"
            variant="outline"
            size="sm"
            disabled={pageClamped >= pageCount - 1}
            onClick={() => setPage(pageClamped + 1)}
          >
            {t("nextPage")}
          </Button>
        </div>
      )}
      {isDraft && (
        <label className="mt-3 flex items-center gap-2 text-sm text-foreground">
          <input
            type="checkbox"
            checked={newAgentMode === "SIMPLE" ? true : knowledgeBaseEnabled}
            disabled={newAgentMode === "SIMPLE"}
            onChange={(e) => setKnowledgeBaseEnabled(e.target.checked)}
            className="size-4 accent-primary"
          />
          {t("attachKnowledgeBaseLabel")}
          {newAgentMode === "SIMPLE" && <span className="text-muted-foreground">{t("alwaysOnSimpleModeSuffix")}</span>}
        </label>
      )}
      {editingKnowledge && !isDraft && (
        <div className="mt-3">
          <div className="flex flex-wrap gap-x-4 gap-y-2">
            {collections.map((c) => (
              <label key={c.id} className="flex items-center gap-1.5 text-sm text-foreground">
                <input
                  type="checkbox"
                  checked={knowledgeDraft.has(c.id)}
                  onChange={() => toggleKnowledgeDraft(c.id)}
                  className="size-4 accent-primary"
                />
                {c.name}
              </label>
            ))}
          </div>
          <p className="mt-2 text-xs text-muted-foreground">{t("unrestrictedAccessHint")}</p>
          <Button type="button" size="sm" className="mt-3" onClick={saveKnowledge} disabled={knowledgeBusy}>
            {knowledgeBusy ? t("saving") : t("saveKnowledgeAccess")}
          </Button>
        </div>
      )}
    </CollapsibleSection>
  );
}
