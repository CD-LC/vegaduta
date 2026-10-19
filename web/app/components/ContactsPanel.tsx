"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Check, Loader2, Route, Save, X } from "lucide-react";
import { api } from "../lib/api";
import { useTranslations } from "../lib/i18n";
import { toast } from "../lib/toastBus";
import { useServerPages } from "../lib/useServerPages";
import { usePagination } from "../lib/usePagination";
import Select from "./Select";
import { Button } from "./ui/button";
import { Card, CardContent, CardDescription, CardHeader } from "./ui/card";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "./ui/table";
import ContactsGroupsImport, { SectionTitle, type ContactGroup } from "./contacts/ContactsGroupsImport";
import ContactsDirectoryCard from "./contacts/ContactsDirectoryCard";
import PendingApprovalsCard from "./contacts/PendingApprovalsCard";
import {
  BULK_CONCURRENCY,
  CHANNEL_META,
  CHANNEL_ORDER,
  ChannelChip,
  STACK_LABEL,
  type AgentOption,
  type BulkProgress,
  type ChannelRouting,
  type Contact,
  type PendingPairing,
} from "./contacts/shared";

// Contacts & request mapping screen (/admin/contacts, backend V238) - the
// dedicated surface the maintainer asked for once the per-channel "ROUTE INBOUND TO"
// box proved too scattered. Four jobs on one page, top to bottom:
//   1. CHANNEL ROUTING   - per-channel default agent + whether a never-seen
//      sender needs manual approval or goes straight through ("not always
//      every incoming request needs mapping - give the control to the user").
//   2. PENDING APPROVALS - the same pairing queue the Channels screen shows,
//      now with row selection + bulk approve / reject (2026-09-24 feedback:
//      "there is no bulk approve or bulk reject"). Core has no bulk endpoint
//      (ChannelPairingController is strictly per-session), so the bulk path
//      runs the per-session approve/reject calls through a bounded-
//      concurrency loop (BULK_CONCURRENCY at a time) with a progress count
//      and one summarising toast at the end.
//   3. CONTACTS          - the directory every channel shares; per-contact
//      and per-group agent overrides, inline editing, a real empty state
//      that says what creates a contact.
//   4. GROUPS + IMPORT   - create groups, bulk-add numbers from .xlsx/.csv
//      or pasted lines.
// CHANNEL_META mirrors ChannelsPanel's (kept local so this file doesn't
// reach into that one's module scope); labels are plain English here - the
// channel names are product nouns, not translatable copy. The brand colour
// comes from BrandIcons.tsx's CHANNEL_BRAND (the channels' own identity
// colours, deliberately not theme tokens - A13) and only paints a 6px dot
// next to a neutral initials tile (paper & ink rule: very light surfaces,
// colour reserved for meaning).
//
// Phone width (< sm, 640px): the three <Table>s each scroll sideways inside
// their card with nothing signalling it, so on a 375px screen the action
// columns (Save, Approve/Reject, Group/Agent/Delete) were simply unreachable
// (QA-01, 2026-09-24). Below `sm` each table is therefore swapped for a
// stacked list - channel chip · identifier on line 1, code/time on line 2,
// actions on line 3 - and the <Table> only renders from `sm` up. The two
// renderings share the same cell renderers (routingAgentSelect & co. below)
// so behaviour cannot drift between them.

export default function ContactsPanel({ agents }: { agents?: AgentOption[] }) {
  const t = useTranslations("contactsPage");
  // The directory is server-capped (newest first); older contacts are paged in with offset.
  const contactFetch = useCallback(
    (offset: number, limit: number) => api.listChannelContactsPage(undefined, undefined, offset, limit),
    []
  );
  const contactPages = useServerPages<Contact>(contactFetch, 500);
  const contacts = contactPages.rows;
  const setContacts = contactPages.setRows;
  const [groups, setGroups] = useState<ContactGroup[]>([]);
  const [routing, setRouting] = useState<ChannelRouting[]>([]);
  const [pending, setPending] = useState<PendingPairing[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);

  const [filterChannel, setFilterChannel] = useState("");
  const [filterGroup, setFilterGroup] = useState("");
  const [query, setQuery] = useState("");
  const [showAddContact, setShowAddContact] = useState(false);

  // Per-channel routing edits, keyed by channel name; a row falls back to
  // the server's saved value until the admin touches it.
  const [routingDraft, setRoutingDraft] = useState<Record<string, { agentId: string; autoApprove: boolean }>>({});
  const [routingSaving, setRoutingSaving] = useState<string | null>(null);

  // Pending-queue selection (session ids) + the in-flight bulk action.
  const [selected, setSelected] = useState<Set<string>>(() => new Set());
  const [bulk, setBulk] = useState<BulkProgress | null>(null);
  const [rowBusy, setRowBusy] = useState<Set<string>>(() => new Set());
  // Two "select all on this page" boxes: the table head (>= sm) and the
  // stacked-list header (< sm). Only one is displayed at a time, both get
  // the indeterminate state.
  const selectAllRef = useRef<HTMLInputElement | null>(null);
  const selectAllStackRef = useRef<HTMLInputElement | null>(null);

  const [newContact, setNewContact] = useState({
    channel: "whatsapp",
    identifier: "",
    displayName: "",
    groupId: "",
    agentId: "",
  });

  const agentOptions = (agents || []).map((a) => ({ value: a.id, label: a.name }));
  const groupOptions = groups.map((g) => ({ value: g.id, label: g.name }));
  const channelOptions = CHANNEL_ORDER.map((k) => ({ value: k, label: CHANNEL_META[k].label }));

  const refresh = useCallback(() => {
    const c = contactPages.reload();
    const g = api
      .listContactGroups()
      .then((rows) => setGroups(Array.isArray(rows) ? rows : []))
      .catch(() => setGroups([]));
    const r = api
      .listChannelRouting()
      .then((rows) => setRouting(Array.isArray(rows) ? rows : []))
      .catch(() => setRouting([]));
    const p = api
      .listPendingPairings()
      .then((rows) => setPending(Array.isArray(rows) ? rows : []))
      .catch(() => setPending([]));
    void Promise.allSettled([c, g, r, p]).finally(() => setLoaded(true));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [contactPages.reload]);

  useEffect(() => {
    refresh();
  }, [refresh]);

  // Drop selections whose row left the queue (approved/rejected elsewhere,
  // or refreshed away) so "Approve N" never counts a ghost.
  useEffect(() => {
    setSelected((prev) => {
      const live = new Set(pending.map((p) => p.sessionId));
      let changed = false;
      const next = new Set<string>();
      prev.forEach((id) => {
        if (live.has(id)) next.add(id);
        else changed = true;
      });
      return changed ? next : prev;
    });
  }, [pending]);

  const filteredContacts = useMemo(
    () =>
      contacts.filter((c) => {
        if (filterChannel && (c.channel || "").toLowerCase() !== filterChannel) return false;
        if (filterGroup && c.groupId !== filterGroup) return false;
        if (query) {
          const q = query.toLowerCase();
          if (!(c.identifier || "").toLowerCase().includes(q) && !(c.displayName || "").toLowerCase().includes(q))
            return false;
        }
        return true;
      }),
    [contacts, filterChannel, filterGroup, query]
  );

  // Search and the channel/group filters run client-side over the loaded rows, so while one is
  // active keep paging older contacts in until everything is loaded - a match must never hide
  // beyond the first 500. Stops on its own (hasMore false) and on a failed page.
  const filtersActive = Boolean(query || filterChannel || filterGroup);
  useEffect(() => {
    if (
      filtersActive &&
      contactPages.hasMore &&
      !contactPages.loadingMore &&
      !contactPages.loading &&
      !contactPages.loadError
    ) {
      void contactPages.loadMore();
    }
  }, [
    filtersActive,
    contactPages.hasMore,
    contactPages.loadingMore,
    contactPages.loading,
    contactPages.loadError,
    contactPages.rows.length,
  ]); // eslint-disable-line react-hooks/exhaustive-deps

  // Client-side pagination (lib/usePagination) for the three unbounded
  // directories below - the pairing queue, the contact list, and groups.
  // Channel routing stays unpaged on purpose: it's a fixed one-row-per-
  // channel table, not a record list.
  const contactsPagination = usePagination(filteredContacts);
  const pendingPagination = usePagination(pending);

  const pageIds = pendingPagination.pageItems.map((p) => p.sessionId);
  const pageSelectedCount = pageIds.filter((id) => selected.has(id)).length;
  const allOnPageSelected = pageIds.length > 0 && pageSelectedCount === pageIds.length;
  useEffect(() => {
    const indeterminate = pageSelectedCount > 0 && !allOnPageSelected;
    if (selectAllRef.current) selectAllRef.current.indeterminate = indeterminate;
    if (selectAllStackRef.current) selectAllStackRef.current.indeterminate = indeterminate;
  }, [pageSelectedCount, allOnPageSelected]);

  const flash = (message: string | null, isError = false) => {
    if (isError) {
      setError(message);
      setSuccess(null);
    } else {
      setSuccess(message);
      setError(null);
    }
  };

  // ---------------- channel routing ----------------

  const routingValueFor = (row: ChannelRouting) =>
    routingDraft[row.channel] || { agentId: row.defaultAgentId || "", autoApprove: row.autoApproveNewSenders };

  const saveRouting = async (row: ChannelRouting) => {
    const draft = routingValueFor(row);
    if (!draft.agentId) {
      flash(t("routingNeedsAgent"), true);
      return;
    }
    setRoutingSaving(row.channel);
    setError(null);
    try {
      await api.setChannelRouting(row.channel, draft.agentId || null, draft.autoApprove);
      flash(t("routingSaved", { channel: CHANNEL_META[row.channel]?.label || row.channel }));
      refresh();
    } catch (err: any) {
      flash(err.message || t("saveError"), true);
    } finally {
      setRoutingSaving(null);
    }
  };

  // ---------------- contacts ----------------

  const addContact = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newContact.identifier.trim()) return;
    setBusy(true);
    setError(null);
    try {
      await api.createChannelContact({
        channel: newContact.channel,
        identifier: newContact.identifier.trim(),
        displayName: newContact.displayName.trim() || null,
        groupId: newContact.groupId || null,
        agentId: newContact.agentId || null,
      });
      setNewContact({ ...newContact, identifier: "", displayName: "" });
      flash(t("contactAdded"));
      refresh();
    } catch (err: any) {
      flash(err.message || t("saveError"), true);
    } finally {
      setBusy(false);
    }
  };

  const updateContact = async (contact: Contact, patch: Partial<Contact>) => {
    try {
      await api.updateChannelContact(contact.id, {
        displayName: patch.displayName !== undefined ? patch.displayName : contact.displayName || null,
        groupId: patch.groupId !== undefined ? patch.groupId || null : contact.groupId || null,
        agentId: patch.agentId !== undefined ? patch.agentId || null : contact.agentId || null,
      });
      refresh();
    } catch (err: any) {
      flash(err.message || t("saveError"), true);
    }
  };

  const deleteContact = async (contact: Contact) => {
    try {
      await api.deleteChannelContact(contact.id);
      setContacts((prev) => prev.filter((c) => c.id !== contact.id));
    } catch (err: any) {
      flash(err.message || t("saveError"), true);
    }
  };

  // ---------------- pending approvals ----------------

  const markRowBusy = (id: string, on: boolean) =>
    setRowBusy((prev) => {
      const next = new Set(prev);
      if (on) next.add(id);
      else next.delete(id);
      return next;
    });

  const approvePending = async (sessionId: string) => {
    markRowBusy(sessionId, true);
    try {
      await api.approvePairing(sessionId);
      toast.success(t("approvedToast"));
      refresh();
    } catch (err: any) {
      flash(err.message || t("saveError"), true);
    } finally {
      markRowBusy(sessionId, false);
    }
  };

  const rejectPending = async (sessionId: string) => {
    markRowBusy(sessionId, true);
    try {
      await api.rejectPairing(sessionId);
      toast.info(t("rejectedToast"));
      refresh();
    } catch (err: any) {
      flash(err.message || t("saveError"), true);
    } finally {
      markRowBusy(sessionId, false);
    }
  };

  const toggleSelected = (id: string) =>
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });

  const toggleSelectAllOnPage = () =>
    setSelected((prev) => {
      const next = new Set(prev);
      if (allOnPageSelected) pageIds.forEach((id) => next.delete(id));
      else pageIds.forEach((id) => next.add(id));
      return next;
    });

  const clearSelection = () => setSelected(new Set());

  // Bulk approve / reject: no server-side bulk endpoint exists, so drain the
  // selected ids through BULK_CONCURRENCY workers over the per-session API.
  // Failures stay selected so one more click retries just those.
  const runBulk = async (kind: "approve" | "reject") => {
    const ids = Array.from(selected);
    if (ids.length === 0 || bulk) return;
    setBulk({ kind, total: ids.length, done: 0 });
    setError(null);
    const queue = [...ids];
    const failed: string[] = [];
    let ok = 0;
    const worker = async () => {
      while (queue.length > 0) {
        const id = queue.shift() as string;
        try {
          if (kind === "approve") await api.approvePairing(id);
          else await api.rejectPairing(id);
          ok += 1;
        } catch {
          failed.push(id);
        }
        setBulk((prev) => (prev ? { ...prev, done: prev.done + 1 } : prev));
      }
    };
    await Promise.all(Array.from({ length: Math.min(BULK_CONCURRENCY, ids.length) }, worker));
    setBulk(null);
    setSelected(new Set(failed));
    if (failed.length === 0) {
      if (kind === "approve") toast.success(t("bulkApproved", { count: String(ok) }));
      else toast.info(t("bulkRejected", { count: String(ok) }));
    } else {
      toast.warn(t("bulkPartial", { ok: String(ok), failed: String(failed.length) }));
    }
    refresh();
  };

  const selectedCount = selected.size;

  // ---------------- shared cell renderers (table >= sm, stacked list < sm) ----------------

  const routingAgentSelect = (row: ChannelRouting, draft: { agentId: string; autoApprove: boolean }) => (
    <Select
      value={draft.agentId}
      onChange={(e) => setRoutingDraft((prev) => ({ ...prev, [row.channel]: { ...draft, agentId: e.target.value } }))}
      placeholder={t("chooseAgentPlaceholder")}
      options={[{ value: "", label: t("chooseAgentPlaceholder") }, ...agentOptions]}
    />
  );

  const routingModeSelect = (row: ChannelRouting, draft: { agentId: string; autoApprove: boolean }) => (
    <Select
      value={draft.autoApprove ? "auto" : "review"}
      onChange={(e) =>
        setRoutingDraft((prev) => ({
          ...prev,
          [row.channel]: { ...draft, autoApprove: e.target.value === "auto" },
        }))
      }
      options={[
        { value: "review", label: t("modeReview") },
        { value: "auto", label: t("modeAuto") },
      ]}
    />
  );

  const routingSaveButton = (
    row: ChannelRouting,
    draft: { agentId: string; autoApprove: boolean },
    dirty: boolean,
    className?: string
  ) => (
    <Button
      type="button"
      variant={dirty ? "default" : "outline"}
      size="sm"
      className={className}
      disabled={routingSaving === row.channel || !dirty || !draft.agentId}
      onClick={() => saveRouting(row)}
    >
      {routingSaving === row.channel ? (
        <Loader2 aria-hidden="true" size={14} className="animate-spin" />
      ) : (
        <Save aria-hidden="true" size={14} />
      )}
      {t("saveButton")}
    </Button>
  );

  const routingDirty = (row: ChannelRouting, draft: { agentId: string; autoApprove: boolean }) =>
    (draft.agentId || "") !== (row.defaultAgentId || "") || draft.autoApprove !== row.autoApproveNewSenders;

  return (
    <div className="flex flex-col gap-6">
      {error && (
        <div
          role="alert"
          className="rounded-lg border border-destructive/30 bg-destructive/5 px-4 py-3 text-sm text-destructive"
        >
          {error}
        </div>
      )}
      {success && (
        <div
          role="status"
          className="rounded-lg border px-4 py-3 text-sm"
          style={{
            borderColor: "var(--success-border)",
            background: "var(--success-bg)",
            color: "var(--success-text)",
          }}
        >
          {success}
        </div>
      )}

      {/* ---- 1. Channel routing: default agent + new-sender mode per channel ---- */}
      <Card className="gap-4">
        <CardHeader>
          <SectionTitle icon={<Route aria-hidden="true" size={16} />}>{t("routingTitle")}</SectionTitle>
          <CardDescription>{t("routingHint")}</CardDescription>
        </CardHeader>
        <CardContent>
          {/* >= sm: one row per channel */}
          <div className="hidden sm:block">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="w-[170px]">{t("colChannel")}</TableHead>
                  <TableHead>{t("colAgent")}</TableHead>
                  <TableHead className="w-[260px]">{t("colNewSenders")}</TableHead>
                  <TableHead className="w-[120px] text-right">{t("colActions")}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {routing.map((row) => {
                  const draft = routingValueFor(row);
                  const dirty = routingDirty(row, draft);
                  return (
                    <TableRow key={row.channel} className="h-14">
                      <TableCell>
                        <ChannelChip channel={row.channel} />
                      </TableCell>
                      <TableCell className="min-w-[200px]">{routingAgentSelect(row, draft)}</TableCell>
                      <TableCell className="min-w-[220px]">{routingModeSelect(row, draft)}</TableCell>
                      <TableCell className="text-right">{routingSaveButton(row, draft, dirty)}</TableCell>
                    </TableRow>
                  );
                })}
              </TableBody>
            </Table>
          </div>
          {/* < sm: stacked rows, every control full width and reachable */}
          <ul className="flex flex-col divide-y divide-border sm:hidden" data-testid="routing-stack">
            {routing.map((row) => {
              const draft = routingValueFor(row);
              const dirty = routingDirty(row, draft);
              return (
                <li key={row.channel} className="flex flex-col gap-3 py-4 first:pt-0 last:pb-0">
                  <ChannelChip channel={row.channel} />
                  <div className="flex flex-col gap-1.5">
                    <span className={STACK_LABEL}>{t("colAgent")}</span>
                    {routingAgentSelect(row, draft)}
                  </div>
                  <div className="flex flex-col gap-1.5">
                    <span className={STACK_LABEL}>{t("colNewSenders")}</span>
                    {routingModeSelect(row, draft)}
                  </div>
                  {routingSaveButton(row, draft, dirty, "h-10 w-full")}
                </li>
              );
            })}
          </ul>
          {loaded && routing.length === 0 && <p className="py-4 text-sm text-muted-foreground">{t("routingEmpty")}</p>}
        </CardContent>
      </Card>

      {/* ---- 2. Pending approvals: selectable queue + bulk approve / reject ---- */}
      <PendingApprovalsCard
        pending={pending}
        pagination={pendingPagination}
        selected={selected}
        rowBusy={rowBusy}
        bulk={bulk}
        allOnPageSelected={allOnPageSelected}
        selectAllRef={selectAllRef}
        selectAllStackRef={selectAllStackRef}
        onToggleSelectAll={toggleSelectAllOnPage}
        onToggleSelected={toggleSelected}
        onApprove={approvePending}
        onReject={rejectPending}
      />

      {/* Floating bulk-action bar: appears the moment one queue row is ticked.
          Near-opaque (navigation-surface rule) so it stays readable over any
          table; sits under the toast stack (z 1200) and above page content. */}
      {selectedCount > 0 && (
        <div className="pointer-events-none fixed inset-x-0 bottom-5 z-[60] flex justify-center px-4">
          <div
            role="toolbar"
            aria-label={t("selectedCount", { count: String(selectedCount) })}
            className="pointer-events-auto flex max-w-full flex-wrap items-center justify-center gap-2 rounded-2xl border border-border px-3 py-2 shadow-[var(--shadow-popover)] backdrop-blur-md sm:rounded-full"
            style={{ background: "color-mix(in oklab, var(--surface-raised) 96%, transparent)" }}
          >
            <span className="px-2 font-mono text-xs uppercase tracking-[0.12em] text-muted-foreground">
              {bulk
                ? t("bulkProgress", { done: String(bulk.done), total: String(bulk.total) })
                : t("selectedCount", { count: String(selectedCount) })}
            </span>
            <Button type="button" size="sm" disabled={!!bulk} onClick={() => runBulk("approve")}>
              {bulk?.kind === "approve" ? (
                <Loader2 aria-hidden="true" size={14} className="animate-spin" />
              ) : (
                <Check aria-hidden="true" size={14} />
              )}
              {t("approveN", { count: String(selectedCount) })}
            </Button>
            <Button
              type="button"
              size="sm"
              variant="outline"
              className="border-destructive/30 text-destructive hover:bg-destructive/10 hover:text-destructive"
              disabled={!!bulk}
              onClick={() => runBulk("reject")}
            >
              {bulk?.kind === "reject" ? (
                <Loader2 aria-hidden="true" size={14} className="animate-spin" />
              ) : (
                <X aria-hidden="true" size={14} />
              )}
              {t("rejectN", { count: String(selectedCount) })}
            </Button>
            <Button type="button" size="sm" variant="ghost" disabled={!!bulk} onClick={clearSelection}>
              <X aria-hidden="true" size={14} /> {t("clearSelection")}
            </Button>
          </div>
        </div>
      )}

      {/* ---- 3. Contacts directory ---- */}
      <ContactsDirectoryCard
        contacts={contacts}
        filteredContacts={filteredContacts}
        pagination={contactsPagination}
        serverPages={contactPages}
        loaded={loaded}
        filterChannel={filterChannel}
        setFilterChannel={setFilterChannel}
        filterGroup={filterGroup}
        setFilterGroup={setFilterGroup}
        query={query}
        setQuery={setQuery}
        showAddContact={showAddContact}
        setShowAddContact={setShowAddContact}
        newContact={newContact}
        setNewContact={setNewContact}
        busy={busy}
        onAddContact={addContact}
        channelOptions={channelOptions}
        groupOptions={groupOptions}
        agentOptions={agentOptions}
        onUpdateContact={updateContact}
        onDeleteContact={deleteContact}
      />

      {/* ---- 4. Groups + import, side by side (contacts/ContactsGroupsImport.tsx) ---- */}
      <ContactsGroupsImport
        t={t}
        agentOptions={agentOptions}
        groupOptions={groupOptions}
        channelOptions={channelOptions}
        groups={groups}
        loaded={loaded}
        busy={busy}
        setBusy={setBusy}
        flash={flash}
        setError={setError}
        refresh={refresh}
      />
    </div>
  );
}
