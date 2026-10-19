"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { AlertTriangle, Brain, Plus, SearchX } from "lucide-react";
import { api } from "../lib/api";
import AppShell from "../components/AppShell";
import PageHeader from "../components/ax/PageHeader";
import EmptyState from "../components/EmptyState";
import PaginationControls from "../components/PaginationControls";
import { Button } from "../components/ui/button";
import { Badge } from "../components/ui/badge";
import { Input } from "../components/ui/input";
import { Tabs, TabsList, TabsTrigger } from "../components/ui/tabs";
import { Alert, AlertDescription, AlertTitle } from "../components/ui/alert";
import { useTranslations } from "../lib/i18n";
import { usePagination } from "../lib/usePagination";
import { usePageReady } from "../components/RouteTransition";
import LearnThisButton from "../learn/LearnThisButton";
import BlockEditor from "./BlockEditor";
import FactRow from "./FactRow";
import FactsToolbar from "./FactsToolbar";
import AddFactForm from "./AddFactForm";
import ImportMemoryCard from "./ImportMemoryCard";
import ImportChatsCard from "./ImportChatsCard";
import AgentsUsingNotes from "./AgentsUsingNotes";
import DangerZone from "./DangerZone";
// Named differently from the MemoryOverview response type imported below.
import MemoryFactsOverview from "./MemoryOverview";
import {
  ACTIVE_PROJECT_LABEL,
  DEFAULT_FACT_SORT,
  EMPTY_FACT_FILTER,
  activeProjectSlug,
  factAgentIds,
  filterFacts,
  hasWrittenNotes,
  projectBlocks as selectProjectBlocks,
  projectSlug,
  sortFacts,
  type FactFilter,
  type FactSort,
} from "./memoryFilters";
import {
  notesAreUnused,
  optedInAgentCount,
  resolveTab,
  tabFromHash,
  writtenNoteCount,
  type MemoryTab,
} from "./memoryTabs";
import type { MemoryAgent, MemoryBlock, MemoryFact, MemoryOverview } from "./memoryTypes";

/**
 * Memory - what the assistant remembers about you, in one place you can edit.
 * Backed by core's /api/memory (UserMemoryController), always scoped to the
 * signed-in user:
 *  - Blocks: short notes (persona, profile, general project notes) that are
 *    placed in every conversation with an agent that has shared memory on.
 *    Named project notes are only included when your message mentions the
 *    project - or on every turn for the one project marked active.
 *  - Agents that use your notes: the per-agent shared-memory switch, here
 *    because notes do nothing until at least one agent has it on.
 *  - Facts: things agents saved via remember_fact, plus ones added by hand;
 *    search, filter, edit, delete, and ask where each came from.
 *  - Import from another assistant (ChatGPT / Claude / Gemini): ImportMemoryCard, hidden when core answers 404.
 *  - Export / forget everything.
 *
 * Layout: five tabs in the order a person meets them (Notes, Assistants, Learned
 * facts, Import, Privacy), each with a one-line purpose and a live count, the
 * open tab mirrored in the URL hash so a section can be linked and survives a
 * reload. Every panel stays mounted (only hidden) so switching tabs never
 * throws away an unsaved note draft.
 * Controls whose backend flag is off (selfServiceEnabled, activeProjectEnabled)
 * are not rendered at all.
 */

const FIXED_BLOCKS = ["persona", "user_profile", "project_notes"] as const;
const FIXED_LABEL_KEY: Record<string, string> = {
  persona: "personaTitle",
  user_profile: "profileTitle",
  project_notes: "projectNotesTitle",
};
const FIXED_HINT_KEY: Record<string, string> = {
  persona: "personaHint",
  user_profile: "profileHint",
  project_notes: "projectNotesHint",
};
// Same rule as the server (MemoryBlocks): 3-40 chars, so a short name fails here with a clear message.
const SLUG_RE = /^[a-z0-9][a-z0-9-]{2,39}$/;

export default function MemoryPage() {
  const t = useTranslations("memoryPage");
  const [data, setData] = useState<MemoryOverview | null>(null);
  const [failed, setFailed] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const [agents, setAgents] = useState<MemoryAgent[] | null>(null);
  const [agentsFailed, setAgentsFailed] = useState(false);
  const [newProject, setNewProject] = useState("");
  const [projectError, setProjectError] = useState<string | null>(null);
  const [filter, setFilter] = useState<FactFilter>(EMPTY_FACT_FILTER);
  const [sort, setSort] = useState<FactSort>(DEFAULT_FACT_SORT);
  const [activeBusy, setActiveBusy] = useState(false);
  // Set when export / forget-everything answered 404 after the page had loaded.
  const [selfServiceGone, setSelfServiceGone] = useState(false);
  // The tab asked for by the URL hash or a click; the one actually shown is resolved against what exists.
  const [requestedTab, setRequestedTab] = useState<MemoryTab | null>(null);

  useEffect(() => {
    const read = () => setRequestedTab(tabFromHash(window.location.hash));
    read();
    window.addEventListener("hashchange", read);
    return () => window.removeEventListener("hashchange", read);
  }, []);

  const refresh = useCallback(() => {
    api
      .getMemory()
      .then((o: MemoryOverview) => {
        setFailed(false);
        setData(o);
      })
      .catch(() => setFailed(true))
      .finally(() => setLoaded(true));
  }, []);

  useEffect(() => {
    refresh();
    // Names for the "learned by <agent>" line and rows for the notes switch list.
    api
      .listAgents()
      .then((list: any) => {
        const arr: any[] = Array.isArray(list) ? list : list?.content || [];
        setAgents(
          arr
            .filter((a) => a && a.id)
            .map((a) => ({
              id: String(a.id),
              name: String(a.name || a.id),
              shareMemoryWithTenant: !!a.shareMemoryWithTenant,
              editable: a.editable !== false,
            }))
        );
      })
      .catch(() => setAgentsFailed(true));
  }, [refresh]);
  usePageReady(loaded);

  const agentNames = useMemo(() => {
    const map: Record<string, string> = {};
    (agents || []).forEach((a) => {
      map[a.id] = a.name;
    });
    return map;
  }, [agents]);

  const blockByLabel = useMemo(() => {
    const m = new Map<string, MemoryBlock>();
    (data?.blocks || []).forEach((b) => m.set(b.label, b));
    return m;
  }, [data]);
  const projectBlocks = useMemo(() => selectProjectBlocks(data?.blocks), [data]);
  const activeSlug = useMemo(() => activeProjectSlug(data?.blocks), [data]);
  const maxChars = data?.limits.maxCharsPerBlock || 1200;

  const filteredFacts = useMemo(() => sortFacts(filterFacts(data?.facts, filter), sort), [data, filter, sort]);
  const factAgentOptions = useMemo(
    () => factAgentIds(data?.facts).map((id) => ({ id, name: agentNames[id] || t("filterAgentUnknown") })),
    [data, agentNames, t]
  );
  // Hooks stay above every conditional render below.
  const factsPagination = usePagination(filteredFacts);

  const selfService = data?.selfServiceEnabled === true && !selfServiceGone;
  const activeProjectEnabled = data?.activeProjectEnabled === true;

  const blocksOn = !!(data?.available && data.blocksEnabled);
  const availableTabs = useMemo<MemoryTab[]>(() => {
    const tabs: MemoryTab[] = [];
    if (blocksOn) tabs.push("notes", "assistants");
    if (data?.available) tabs.push("facts");
    tabs.push("import");
    if (selfService) tabs.push("privacy");
    return tabs;
  }, [blocksOn, data?.available, selfService]);
  const tab = resolveTab(requestedTab, availableTabs);
  const selectTab = (next: string) => {
    const id = tabFromHash(next);
    if (!id) return;
    setRequestedTab(id);
    // replaceState, not location.hash: switching tabs is not navigation, so Back should leave the page.
    window.history.replaceState(null, "", `#${id}`);
  };
  const notesCount = writtenNoteCount(data?.blocks);
  const optedIn = optedInAgentCount(agents);
  const notesUnused = notesAreUnused(data?.blocks, agents);
  const tabCount: Partial<Record<MemoryTab, string>> = {};
  if (notesCount > 0) tabCount.notes = String(notesCount);
  if (agents && agents.length > 0) tabCount.assistants = `${optedIn}/${agents.length}`;
  if (data?.available && data.facts.length > 0) tabCount.facts = String(data.facts.length);

  const saveBlock = async (label: string, content: string): Promise<boolean> => {
    try {
      await api.saveMemoryBlock(label, content);
      refresh();
      return true;
    } catch {
      /* toJson already surfaced the server's message */
      return false;
    }
  };
  const clearBlock = async (label: string) => {
    try {
      await api.deleteMemoryBlock(label);
      refresh();
    } catch {
      /* surfaced by toJson */
    }
  };
  const editFact = async (fact: MemoryFact, content: string) => {
    await api.editMemoryFact(fact.scope, fact.id, content);
    refresh();
  };
  const deleteFact = async (fact: MemoryFact) => {
    try {
      await api.deleteMemoryFact(fact.scope, fact.id);
      refresh();
    } catch {
      /* surfaced by toJson */
    }
  };

  const setActiveProject = async (slug: string | null) => {
    setActiveBusy(true);
    try {
      if (slug) await api.saveMemoryBlock(ACTIVE_PROJECT_LABEL, slug);
      else await api.deleteMemoryBlock(ACTIVE_PROJECT_LABEL);
      refresh();
    } catch {
      /* surfaced by toJson */
    } finally {
      setActiveBusy(false);
    }
  };

  const addProject = () => {
    const slug = newProject.trim().toLowerCase().replace(/\s+/g, "-");
    if (!SLUG_RE.test(slug)) {
      setProjectError(t("projectNameInvalid"));
      return;
    }
    if (blockByLabel.has(`project:${slug}`)) {
      setProjectError(t("projectExists"));
      return;
    }
    setProjectError(null);
    // The block exists once it has content: seed it with a placeholder line the user can edit straight away.
    void saveBlock(`project:${slug}`, t("projectSeed", { name: slug })).then((ok) => {
      if (ok) setNewProject("");
    });
  };

  const onAgentShareChanged = (agentId: string, enabled: boolean) => {
    setAgents((list) => (list || []).map((a) => (a.id === agentId ? { ...a, shareMemoryWithTenant: enabled } : a)));
  };

  return (
    <AppShell eyebrow={t("eyebrow")}>
      <PageHeader title={t("title")} subtitle={t("subtitle")} actions={<LearnThisButton path="/memory" />} />

      {failed && (
        <Alert variant="destructive" className="mb-6">
          <AlertTriangle aria-hidden="true" />
          <AlertTitle>{t("loadFailedTitle")}</AlertTitle>
          <AlertDescription>
            <p>{t("loadFailedBody")}</p>
            <Button variant="outline" size="sm" className="mt-2" onClick={refresh}>
              {t("retry")}
            </Button>
          </AlertDescription>
        </Alert>
      )}

      {data && !data.available && (
        <Alert variant="warning" className="mb-6">
          <AlertTriangle aria-hidden="true" />
          <AlertTitle>{t("unavailableTitle")}</AlertTitle>
          <AlertDescription>{t("unavailableBody")}</AlertDescription>
        </Alert>
      )}

      {loaded && tab && (
        <Tabs value={tab} onValueChange={selectTab} className="gap-3">
          <TabsList aria-label={t("tabsAria")} className="h-auto">
            {availableTabs.map((id) => (
              <TabsTrigger
                key={id}
                value={id}
                id={`memory-tab-${id}`}
                aria-controls={`memory-panel-${id}`}
                className="gap-2 px-4 py-2 text-sm"
              >
                {t(`tab_${id}`)}
                {tabCount[id] && (
                  <Badge variant="secondary" className="px-1.5 py-0 text-[11px] font-medium">
                    {tabCount[id]}
                  </Badge>
                )}
              </TabsTrigger>
            ))}
          </TabsList>
          <p className="text-sm text-muted-foreground" aria-live="polite">
            {t(`tab_${tab}_purpose`)}
          </p>

          {blocksOn && data && (
            <>
              <div
                id="memory-panel-notes"
                role="tabpanel"
                aria-labelledby="memory-tab-notes"
                hidden={tab !== "notes"}
                className="space-y-4 pt-2"
              >
                {notesUnused && (
                  <Alert variant="warning">
                    <AlertTriangle aria-hidden="true" />
                    <AlertTitle>{t("notesUnusedTitle")}</AlertTitle>
                    <AlertDescription>
                      <p>{t("notesUnusedBody")}</p>
                      <Button variant="outline" size="sm" className="mt-2" onClick={() => selectTab("assistants")}>
                        {t("notesUnusedAction")}
                      </Button>
                    </AlertDescription>
                  </Alert>
                )}
                <div>
                  <h2 className="text-base font-semibold text-foreground">{t("blocksHeading")}</h2>
                  <p className="text-sm text-muted-foreground">{t("blocksIntro")}</p>
                </div>
                <div className="grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-3">
                  {FIXED_BLOCKS.map((label) => (
                    <BlockEditor
                      key={label}
                      label={label}
                      title={t(FIXED_LABEL_KEY[label])}
                      hint={t(FIXED_HINT_KEY[label])}
                      saved={blockByLabel.get(label)?.content || ""}
                      maxChars={maxChars}
                      onSave={saveBlock}
                      onClear={clearBlock}
                    />
                  ))}
                </div>

                <div className="border-t border-border pt-6">
                  <h3 className="text-sm font-semibold text-foreground">{t("projectsHeading")}</h3>
                  <p className="text-sm text-muted-foreground">
                    {activeProjectEnabled ? t("projectsIntroActive") : t("projectsIntro")}
                  </p>
                </div>
                <div className="flex flex-wrap items-center gap-2">
                  <Input
                    value={newProject}
                    onChange={(e) => setNewProject(e.target.value)}
                    placeholder={t("projectNamePlaceholder")}
                    aria-label={t("projectNamePlaceholder")}
                    className="max-w-xs"
                    onKeyDown={(e) => {
                      if (e.key === "Enter") addProject();
                    }}
                  />
                  <Button
                    variant="outline"
                    size="sm"
                    disabled={!newProject.trim() || projectBlocks.length >= data.limits.maxProjectBlocks}
                    onClick={addProject}
                  >
                    <Plus size={14} aria-hidden="true" /> {t("addProject")}
                  </Button>
                  {projectError && <span className="text-xs text-destructive">{projectError}</span>}
                </div>
                {projectBlocks.length > 0 && (
                  <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
                    {projectBlocks.map((b) => {
                      const slug = projectSlug(b.label);
                      const isActive = activeProjectEnabled && activeSlug === slug;
                      return (
                        <BlockEditor
                          key={b.label}
                          label={b.label}
                          title={slug}
                          hint={isActive ? t("activeProjectHint") : undefined}
                          saved={b.content}
                          maxChars={maxChars}
                          onSave={saveBlock}
                          onClear={clearBlock}
                          titleExtra={isActive ? <Badge variant="accent">{t("activeBadge")}</Badge> : undefined}
                          actions={
                            activeProjectEnabled ? (
                              <Button
                                variant="outline"
                                size="sm"
                                disabled={activeBusy}
                                onClick={() => setActiveProject(isActive ? null : slug)}
                              >
                                {isActive ? t("activeUnset") : t("activeSet")}
                              </Button>
                            ) : undefined
                          }
                        />
                      );
                    })}
                  </div>
                )}
              </div>

              <div
                id="memory-panel-assistants"
                role="tabpanel"
                aria-labelledby="memory-tab-assistants"
                hidden={tab !== "assistants"}
                className="pt-2"
              >
                <AgentsUsingNotes
                  agents={agents}
                  agentsFailed={agentsFailed}
                  notesWritten={hasWrittenNotes(data.blocks)}
                  onChanged={onAgentShareChanged}
                />
              </div>
            </>
          )}

          {data?.available && (
            <div
              id="memory-panel-facts"
              role="tabpanel"
              aria-labelledby="memory-tab-facts"
              hidden={tab !== "facts"}
              className="space-y-3 pt-2"
            >
              <div>
                <h2 className="text-base font-semibold text-foreground">{t("factsHeading")}</h2>
                <p className="text-sm text-muted-foreground">{t("factsIntro")}</p>
              </div>
              <MemoryFactsOverview facts={data.facts} />
              {selfService && (
                <AddFactForm agents={agents || []} maxChars={data.limits.maxFactChars} onAdded={refresh} />
              )}
              {data.facts.length === 0 ? (
                <EmptyState icon={Brain} title={t("factsEmptyTitle")} hint={t("factsEmptyHint")} />
              ) : (
                <>
                  <FactsToolbar
                    filter={filter}
                    onChange={setFilter}
                    agentOptions={factAgentOptions}
                    shown={filteredFacts.length}
                    total={data.facts.length}
                    sort={sort}
                    onSortChange={setSort}
                  />
                  {filteredFacts.length === 0 ? (
                    <EmptyState icon={SearchX} title={t("factsNoMatchTitle")} hint={t("factsNoMatchHint")} />
                  ) : (
                    <ul className="space-y-2">
                      {factsPagination.pageItems.map((f) => (
                        <FactRow
                          key={`${f.scope}:${f.id}`}
                          fact={f}
                          agentName={f.agentId ? agentNames[f.agentId] || null : null}
                          agentNames={agentNames}
                          maxChars={data.limits.maxFactChars}
                          onEdit={editFact}
                          onDelete={deleteFact}
                        />
                      ))}
                    </ul>
                  )}
                  <PaginationControls
                    page={factsPagination.page}
                    totalPages={factsPagination.totalPages}
                    totalItems={factsPagination.totalItems}
                    pageSize={factsPagination.pageSize}
                    onPageChange={factsPagination.setPage}
                    onPageSizeChange={factsPagination.setPageSize}
                    itemLabel={t("factsItemLabel")}
                  />
                </>
              )}
            </div>
          )}

          <div
            id="memory-panel-import"
            role="tabpanel"
            aria-labelledby="memory-tab-import"
            hidden={tab !== "import"}
            className="space-y-6 pt-2"
          >
            {data?.available && selfService && (
              <ImportMemoryCard agents={agents || []} facts={data.facts} onImported={refresh} />
            )}
            <ImportChatsCard onMemoriesSaved={refresh} />
          </div>

          {data && selfService && (
            <div
              id="memory-panel-privacy"
              role="tabpanel"
              aria-labelledby="memory-tab-privacy"
              hidden={tab !== "privacy"}
              className="pt-2"
            >
              <DangerZone onForgotten={refresh} onUnavailable={() => setSelfServiceGone(true)} />
            </div>
          )}
        </Tabs>
      )}
    </AppShell>
  );
}
