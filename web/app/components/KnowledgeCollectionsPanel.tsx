"use client";

import { forwardRef, useEffect, useImperativeHandle, useMemo, useState } from "react";
import { api } from "../lib/api";
import InfoTip from "./InfoTip";
import Select from "./Select";
import { Pencil, Trash2, Plus, Search } from "lucide-react";
import { useTranslations } from "../lib/i18n";
import { cn } from "../lib/utils";
import { Button } from "./ui/button";
import { Input } from "./ui/input";
import { Label } from "./ui/label";
import { Tooltip, TooltipContent, TooltipTrigger } from "./ui/tooltip";
import PaginationControls from "./PaginationControls";
import { usePagination } from "../lib/usePagination";
import { errorMessage } from "../lib/errors";
import { collectionNotEmptyDocumentCount } from "../lib/knowledgeErrors";

// Knowledge Stage F1 - CRUD for the hierarchical folders KnowledgePanel's
// uploads land in and AgentsPanel's per-agent allow-list scopes against.
// Every tenant gets one seeded "Default" collection (KnowledgeCollectionService,
// migration V15) that can never be deleted - the delete button is disabled
// for it client-side as a UX nicety, but the server enforces the same rule
// independently.
//
// Rebuilt this session (task #471) as the AXON sidebar list from the
// Knowledge.dc.html handoff - a color-dot + name row per collection, click
// to select (drives the center detail panel in KnowledgePanel.js), with the
// create/edit forms revealed inline instead of always-open. Each row's tag
// used to show the embedding model's short label (chunkCount/fileCount
// didn't exist on KnowledgeCollectionResponse yet at the time) - now that
// #534-539 added real fileCount/chunkCount to every collection, the tag
// shows file count instead: a real, per-collection fact, not the model -
// the model choice is still visible, just moved to the edit form (below)
// and the detail panel (KnowledgePanel.js), where it's explicitly labeled
// instead of competing with the name for attention in this list.
//
// A small fixed palette gives each row a distinct color dot purely for scan-
// ability (matches the mockup's per-row dot) - decorative only, cycles by
// list position, not tied to any real per-collection "color" field (none
// exists).
// Category palette (globals.css --cat-*), deliberately distinct from the
// --state-*/--ax-green/--ax-amber/--ax-red hues this console reserves for
// real execution-state meaning (success/warning/error) - a purely decorative
// cycling dot must never borrow those.
const DOT_COLORS = [
  "var(--cat-data)",
  "var(--cat-assistant)",
  "var(--cat-comms)",
  "var(--cat-creative)",
  "var(--cat-automation)",
];

interface KnowledgeCollection {
  id: string;
  name: string;
  description?: string | null;
  parentCollectionId?: string | null;
  embeddingModel?: string;
  isDefault?: boolean;
  fileCount?: number;
}

interface EmbeddingModelOption {
  key: string;
  label: string;
  isDefault?: boolean;
}

interface EditingCollection {
  id: string;
  name: string;
  description: string;
  parentCollectionId: string;
}

interface KnowledgeCollectionsPanelProps {
  collections: KnowledgeCollection[];
  onChanged: () => void;
  selectedId?: string | null;
  onSelect?: (id: string) => void;
}

const KnowledgeCollectionsPanel = forwardRef(function KnowledgeCollectionsPanel(
  { collections, onChanged, selectedId, onSelect }: KnowledgeCollectionsPanelProps,
  ref
) {
  const t = useTranslations("knowledgeCollectionsPanel");
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [parentCollectionId, setParentCollectionId] = useState("");
  const [embeddingModel, setEmbeddingModel] = useState("");
  const [embeddingModels, setEmbeddingModels] = useState<EmbeddingModelOption[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);
  const [editing, setEditing] = useState<EditingCollection | null>(null);
  const [showCreate, setShowCreate] = useState(false);
  // Delete is destructive and irreversible - require a second click on the
  // same row's trash button before it actually fires, same two-step confirm
  // pattern DevicesPanel's confirmRevokeId uses.
  const [confirmDeleteId, setConfirmDeleteId] = useState<string | null>(null);
  // The sidebar list is unbounded (one row per tenant collection) - same
  // shared client-side pagination every other record list uses.
  const tl = useTranslations("tenantLists");
  const [collectionQuery, setCollectionQuery] = useState("");
  const filteredCollections = useMemo(() => {
    const q = collectionQuery.trim().toLowerCase();
    return q ? collections.filter((c: any) => String(c.name ?? "").toLowerCase().includes(q)) : collections;
  }, [collections, collectionQuery]);
  const collectionsPagination = usePagination(filteredCollections);

  useImperativeHandle(ref, () => ({
    openCreateForm: () => {
      setEditing(null);
      setShowCreate(true);
    },
  }));

  // Multi-model ingestion - fetched once; falls back to a hardcoded default
  // key if the request fails, so the create form never blocks on this.
  useEffect(() => {
    api
      .listEmbeddingModels()
      .then((models) => {
        setEmbeddingModels(models);
        const def = models.find((m: EmbeddingModelOption) => m.isDefault) || models[0];
        if (def) setEmbeddingModel(def.key);
      })
      .catch(() => setEmbeddingModels([]));
  }, []);

  function modelLabel(key: string) {
    return embeddingModels.find((m) => m.key === key)?.label || key;
  }

  async function handleCreate(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setSuccess(null);
    if (!name.trim()) {
      setError(t("nameRequiredError"));
      return;
    }
    setBusy(true);
    try {
      const created = await api.createCollection({
        name,
        description: description || null,
        parentCollectionId: parentCollectionId || null,
        embeddingModel: embeddingModel || undefined,
      });
      setName("");
      setDescription("");
      setParentCollectionId("");
      setSuccess(t("collectionCreatedSuccess", { name: created.name }));
      setShowCreate(false);
      onChanged();
      onSelect?.(created.id);
    } catch (err) {
      setError(errorMessage(err, t("createCollectionError")));
    } finally {
      setBusy(false);
    }
  }

  function startEdit(collection: KnowledgeCollection) {
    setError(null);
    setSuccess(null);
    setShowCreate(false);
    setEditing({
      id: collection.id,
      name: collection.name,
      description: collection.description || "",
      parentCollectionId: collection.parentCollectionId || "",
    });
  }

  async function saveEdit(e?: React.FormEvent) {
    e?.preventDefault();
    if (!editing) return;
    setError(null);
    if (!editing.name.trim()) {
      setError(t("nameRequiredError"));
      return;
    }
    setBusy(true);
    try {
      await api.updateCollection(editing.id, {
        name: editing.name,
        description: editing.description || null,
        parentCollectionId: editing.parentCollectionId || null,
      });
      setSuccess(t("collectionUpdatedSuccess"));
      setEditing(null);
      onChanged();
    } catch (err) {
      setError(errorMessage(err, t("updateCollectionError")));
    } finally {
      setBusy(false);
    }
  }

  async function handleDelete(collection: KnowledgeCollection) {
    setError(null);
    setSuccess(null);
    setBusy(true);
    try {
      await api.deleteCollection(collection.id);
      setSuccess(t("collectionDeletedSuccess", { name: collection.name }));
      onChanged();
    } catch (err) {
      // 409 = files still in it: say how many in one localized line (the
      // server detail quotes ids and an API path). Other 409s (default
      // collection, orphan chunks) keep the server's own, readable text.
      const files = collectionNotEmptyDocumentCount(err);
      setError(
        files != null
          ? files === 1
            ? t("deleteCollectionNotEmptyOne")
            : t("deleteCollectionNotEmptyMany", { count: files })
          : errorMessage(err, t("deleteCollectionError"))
      );
    } finally {
      setBusy(false);
      setConfirmDeleteId(null);
    }
  }

  // First click arms the confirm state on that row; a second click on the
  // same trash button actually deletes. Clicking another row's trash button
  // re-arms for that row instead of confirming the original one.
  function handleDeleteClick(collection: KnowledgeCollection) {
    if (confirmDeleteId === collection.id) {
      void handleDelete(collection);
    } else {
      setConfirmDeleteId(collection.id);
    }
  }

  return (
    <div className="space-y-3 lg:sticky lg:top-24 lg:self-start">
      <div className="font-mono text-xs font-semibold uppercase tracking-wider text-muted-foreground">
        {t("sidebarLabel")}
      </div>

      {error && (
        <div
          role="alert"
          className="rounded-lg border border-[var(--danger-border)] bg-[var(--danger-bg)] px-3 py-2 text-sm text-[var(--danger-text)] [overflow-wrap:anywhere]"
        >
          {error}
        </div>
      )}
      {success && (
        <div
          role="status"
          className="rounded-lg border border-[var(--success-border)] bg-[var(--success-bg)] px-3 py-2 text-sm text-[var(--success-text)]"
        >
          {success}
        </div>
      )}

      {collections.length > 5 && (
        <div className="relative">
          <Search
            size={14}
            aria-hidden="true"
            className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground"
          />
          <Input
            type="search"
            value={collectionQuery}
            onChange={(e) => setCollectionQuery(e.target.value)}
            placeholder={tl("searchCollections")}
            aria-label={tl("searchCollections")}
            className="pl-8"
          />
        </div>
      )}
      {collections.length > 0 && filteredCollections.length === 0 && (
        <p role="status" className="text-sm text-muted-foreground">
          {tl("noMatch")}
        </p>
      )}
      <div className="space-y-1">
        {collectionsPagination.pageItems.map((c, i) =>
          editing?.id === c.id ? (
            <form key={c.id} onSubmit={saveEdit} className="space-y-3 rounded-xl border border-border bg-card p-4">
              <div className="space-y-1.5">
                <Label htmlFor={`kb-edit-name-${c.id}`}>{t("nameLabel")}</Label>
                <Input
                  id={`kb-edit-name-${c.id}`}
                  type="text"
                  value={editing.name}
                  onChange={(e) => setEditing({ ...editing, name: e.target.value })}
                  required
                />
              </div>
              <div className="space-y-1.5">
                <Label htmlFor={`kb-edit-description-${c.id}`}>{t("descriptionLabel")}</Label>
                <Input
                  id={`kb-edit-description-${c.id}`}
                  type="text"
                  value={editing.description}
                  onChange={(e) => setEditing({ ...editing, description: e.target.value })}
                />
              </div>
              <div className="space-y-1.5">
                <Label htmlFor={`kb-edit-parent-${c.id}`}>{t("parentLabel")}</Label>
                <Select
                  id={`kb-edit-parent-${c.id}`}
                  value={editing.parentCollectionId}
                  onChange={(e) => setEditing({ ...editing, parentCollectionId: e.target.value })}
                  placeholder={t("topLevelOption")}
                  options={[
                    { value: "", label: t("topLevelOption") },
                    ...collections.filter((c2) => c2.id !== c.id).map((c2) => ({ value: c2.id, label: c2.name })),
                  ]}
                />
              </div>
              <p className="text-xs text-muted-foreground">
                {t("embeddingModelFixedHint", { model: modelLabel(c.embeddingModel) })}
              </p>
              <div className="flex justify-end gap-2">
                <Button type="button" variant="outline" size="sm" disabled={busy} onClick={() => setEditing(null)}>
                  {t("cancelButton")}
                </Button>
                <Button type="submit" size="sm" disabled={busy}>
                  {t("saveButton")}
                </Button>
              </div>
            </form>
          ) : (
            <div
              key={c.id}
              role="group"
              className={cn(
                "group flex items-center gap-2 rounded-lg border px-2.5 py-2 transition-colors",
                selectedId === c.id ? "border-primary/30 bg-accent" : "border-transparent hover:bg-accent/60"
              )}
            >
              <button
                type="button"
                onClick={() => onSelect?.(c.id)}
                aria-current={selectedId === c.id ? "true" : undefined}
                className="flex min-w-0 flex-1 items-center gap-2.5 text-left"
              >
                <span
                  className="size-2 shrink-0 rounded-full"
                  style={{ background: DOT_COLORS[i % DOT_COLORS.length] }}
                />
                <span className="truncate text-sm font-medium text-foreground">{c.name}</span>
                <span className="ml-auto shrink-0 whitespace-nowrap text-xs text-muted-foreground tabular-nums">
                  {c.isDefault
                    ? t("defaultTag")
                    : t("fileCountTag", { count: c.fileCount ?? 0, plural: c.fileCount === 1 ? "" : "s" })}
                </span>
              </button>
              <div className="flex shrink-0 items-center gap-0.5 opacity-100 transition-opacity sm:opacity-0 sm:group-hover:opacity-100 sm:focus-within:opacity-100">
                <Tooltip>
                  <TooltipTrigger asChild>
                    <button
                      type="button"
                      className="flex size-6 items-center justify-center rounded border-0 bg-transparent p-0 text-muted-foreground shadow-none transition-colors hover:bg-secondary hover:text-foreground"
                      onClick={(e) => {
                        e.stopPropagation();
                        startEdit(c);
                      }}
                      aria-label={t("editCollectionTooltip")}
                    >
                      <Pencil aria-hidden="true" size={12} />
                    </button>
                  </TooltipTrigger>
                  <TooltipContent>{t("editCollectionTooltip")}</TooltipContent>
                </Tooltip>
                {!c.isDefault && (
                  <Tooltip>
                    <TooltipTrigger asChild>
                      <button
                        type="button"
                        className={cn(
                          "flex size-6 items-center justify-center rounded border-0 p-0 shadow-none transition-colors",
                          confirmDeleteId === c.id
                            ? "bg-destructive/15 text-destructive"
                            : "bg-transparent text-muted-foreground hover:bg-secondary hover:text-destructive"
                        )}
                        onClick={(e) => {
                          e.stopPropagation();
                          if (!busy) handleDeleteClick(c);
                        }}
                        aria-label={confirmDeleteId === c.id ? t("confirmDeleteTooltip") : t("deleteCollectionTooltip")}
                      >
                        <Trash2 aria-hidden="true" size={12} />
                      </button>
                    </TooltipTrigger>
                    <TooltipContent>
                      {confirmDeleteId === c.id ? t("confirmDeleteTooltip") : t("deleteCollectionTooltip")}
                    </TooltipContent>
                  </Tooltip>
                )}
              </div>
            </div>
          )
        )}
      </div>
      <PaginationControls
        page={collectionsPagination.page}
        totalPages={collectionsPagination.totalPages}
        totalItems={collectionsPagination.totalItems}
        pageSize={collectionsPagination.pageSize}
        onPageChange={collectionsPagination.setPage}
        onPageSizeChange={collectionsPagination.setPageSize}
        itemLabel={t("itemLabelCollections")}
      />

      {showCreate ? (
        <form onSubmit={handleCreate} className="space-y-3 rounded-xl border border-border bg-card p-4">
          <div className="space-y-1.5">
            <Label htmlFor="kb-new-collection-name">{t("nameLabel")}</Label>
            <Input
              id="kb-new-collection-name"
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              aria-describedby="kb-new-collection-name-help"
              required
              autoFocus
            />
            <p id="kb-new-collection-name-help" className="text-xs text-muted-foreground">
              A collection groups related files, for example “Return policy” or “Price list”.
            </p>
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="kb-new-collection-description">{t("descriptionLabel")}</Label>
            <Input
              id="kb-new-collection-description"
              type="text"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
            />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="kb-new-collection-parent">{t("parentOptionalLabel")}</Label>
            <Select
              id="kb-new-collection-parent"
              value={parentCollectionId}
              onChange={(e) => setParentCollectionId(e.target.value)}
              placeholder={t("topLevelOption")}
              options={[
                { value: "", label: t("topLevelOption") },
                ...collections.map((c) => ({ value: c.id, label: c.name })),
              ]}
            />
          </div>
          <div className="space-y-1.5">
            <Label>
              {t("embeddingModelLabel")} <InfoTip text={t("embeddingModelInfoTip")} />
            </Label>
            <Select
              value={embeddingModel}
              onChange={(e) => setEmbeddingModel(e.target.value)}
              options={embeddingModels.map((m) => ({ value: m.key, label: m.label }))}
            />
          </div>
          <div className="flex justify-end gap-2">
            <Button type="button" variant="outline" size="sm" disabled={busy} onClick={() => setShowCreate(false)}>
              {t("cancelButton")}
            </Button>
            <Button type="submit" size="sm" disabled={busy}>
              {busy ? t("creatingButton") : t("createButton")}
            </Button>
          </div>
        </form>
      ) : (
        <button
          type="button"
          className="flex w-full items-center justify-center gap-1.5 rounded-lg border border-dashed border-border px-3 py-2 text-sm font-medium text-muted-foreground transition-colors hover:border-primary/40 hover:bg-accent hover:text-foreground"
          onClick={() => setShowCreate(true)}
        >
          <Plus aria-hidden="true" size={14} /> {t("newCollectionButton")}
        </button>
      )}
    </div>
  );
});

export default KnowledgeCollectionsPanel;
