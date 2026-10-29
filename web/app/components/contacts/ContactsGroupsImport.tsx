"use client";

import { useRef, useState } from "react";
import { ClipboardPaste, FolderPlus, Plus, Trash2, Upload, Users } from "lucide-react";
import { api } from "../../lib/api";
import PaginationControls from "../PaginationControls";
import { usePagination } from "../../lib/usePagination";
import Select from "../Select";
import { Button } from "../ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "../ui/card";
import { Input } from "../ui/input";
import { Label } from "../ui/label";
import { Textarea } from "../ui/textarea";

// Section 4 of the Contacts & request mapping screen (ContactsPanel.tsx):
// GROUPS + IMPORT, side by side - create groups, bulk-add numbers from
// .xlsx/.csv or pasted lines. Split out of ContactsPanel.tsx so that file
// stays under the file-size ratchet. Owns only the form state of these two
// cards; the group list, busy flag and flash/refresh plumbing stay in the
// panel so the other three sections see the same data.

export interface ContactGroup {
  id: string;
  name: string;
  description?: string;
  agentId?: string;
  agentName?: string;
  memberCount?: number;
}

export function SectionTitle({ icon, children }: { icon: React.ReactNode; children: React.ReactNode }) {
  return (
    <CardTitle className="flex items-center gap-2 text-base">
      <span className="inline-flex h-8 w-8 items-center justify-center rounded-lg bg-accent text-accent-foreground">
        {icon}
      </span>
      {children}
    </CardTitle>
  );
}

interface Option {
  value: string;
  label: string;
}

export default function ContactsGroupsImport({
  t,
  agentOptions,
  groupOptions,
  channelOptions,
  groups,
  loaded,
  busy,
  setBusy,
  flash,
  setError,
  refresh,
}: {
  t: any;
  agentOptions: Option[];
  groupOptions: Option[];
  channelOptions: Option[];
  groups: ContactGroup[];
  loaded: boolean;
  busy: boolean;
  setBusy: (busy: boolean) => void;
  flash: (message: string | null, isError?: boolean) => void;
  setError: (message: string | null) => void;
  refresh: () => void;
}) {
  const [newGroup, setNewGroup] = useState({ name: "", description: "", agentId: "" });
  const [importChannel, setImportChannel] = useState("whatsapp");
  const [importGroupId, setImportGroupId] = useState("");
  const [importText, setImportText] = useState("");
  const [importFile, setImportFile] = useState<File | null>(null);
  const [importResult, setImportResult] = useState<any>(null);
  const fileRef = useRef<HTMLInputElement | null>(null);
  const [confirmDeleteGroupId, setConfirmDeleteGroupId] = useState<string | null>(null);
  // Click-again-to-confirm arming for the group delete button (same shape
  // as the contact delete button in ContactsPanel).
  const groupsPagination = usePagination(groups);

  // ---------------- groups ----------------

  const addGroup = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newGroup.name.trim()) return;
    setBusy(true);
    setError(null);
    try {
      await api.createContactGroup({
        name: newGroup.name.trim(),
        description: newGroup.description.trim() || null,
        agentId: newGroup.agentId || null,
      });
      setNewGroup({ name: "", description: "", agentId: "" });
      flash(t("groupCreated"));
      refresh();
    } catch (err: any) {
      flash(err.message || t("saveError"), true);
    } finally {
      setBusy(false);
    }
  };

  const deleteGroup = async (group: ContactGroup) => {
    try {
      await api.deleteContactGroup(group.id);
      refresh();
    } catch (err: any) {
      flash(err.message || t("saveError"), true);
    }
  };

  // ---------------- import ----------------

  const runImport = async (file: File | null) => {
    setBusy(true);
    setError(null);
    setImportResult(null);
    try {
      const result = file
        ? await api.importChannelContactsFile(importChannel, file, importGroupId || null)
        : await api.importChannelContactsText(importChannel, importText, importGroupId || null);
      setImportResult(result);
      if (file) {
        setImportFile(null);
        if (fileRef.current) fileRef.current.value = "";
      }
      if (!file) setImportText("");
      refresh();
    } catch (err: any) {
      flash(err.message || t("importError"), true);
    } finally {
      setBusy(false);
    }
  };
  return (
    <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
      <Card className="gap-4">
        <CardHeader>
          <SectionTitle icon={<FolderPlus aria-hidden="true" size={16} />}>{t("groupsTitle")}</SectionTitle>
          <CardDescription>{t("groupsHint")}</CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          <form onSubmit={addGroup} className="grid grid-cols-1 gap-3 sm:grid-cols-[1fr_1fr_auto]">
            <Input
              value={newGroup.name}
              onChange={(e) => setNewGroup({ ...newGroup, name: e.target.value })}
              placeholder={t("groupNamePlaceholder")}
              aria-label={t("groupNamePlaceholder")}
              required
            />
            <Select
              value={newGroup.agentId}
              onChange={(e) => setNewGroup({ ...newGroup, agentId: e.target.value })}
              placeholder={t("groupAgentPlaceholder")}
              options={[{ value: "", label: t("channelDefault") }, ...agentOptions]}
            />
            <Button type="submit" disabled={busy || !newGroup.name.trim()}>
              <Plus aria-hidden="true" size={16} /> {t("createGroupButton")}
            </Button>
          </form>
          <div className="flex flex-col divide-y divide-border">
            {groupsPagination.pageItems.map((g) => (
              <div key={g.id} className="flex min-h-[3.5rem] items-center gap-3 py-2">
                <span className="inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-md bg-muted text-muted-foreground">
                  <Users aria-hidden="true" size={15} />
                </span>
                <div className="min-w-0 flex-1">
                  <div className="truncate text-sm font-medium text-foreground">{g.name}</div>
                  <div className="text-sm text-muted-foreground">
                    {t("groupMeta", { count: String(g.memberCount ?? 0), agent: g.agentName || t("channelDefault") })}
                  </div>
                </div>
                <Button
                  type="button"
                  variant={confirmDeleteGroupId === g.id ? "destructive" : "ghost"}
                  size="icon"
                  className={
                    confirmDeleteGroupId === g.id
                      ? ""
                      : "text-muted-foreground hover:bg-destructive/10 hover:text-destructive"
                  }
                  onClick={() => {
                    if (confirmDeleteGroupId === g.id) {
                      setConfirmDeleteGroupId(null);
                      void deleteGroup(g);
                    } else {
                      setConfirmDeleteGroupId(g.id);
                    }
                  }}
                  aria-label={confirmDeleteGroupId === g.id ? t("deleteGroupConfirm") : t("deleteGroup")}
                  title={confirmDeleteGroupId === g.id ? t("deleteGroupConfirm") : t("deleteGroup")}
                >
                  <Trash2 aria-hidden="true" size={16} />
                </Button>
              </div>
            ))}
            {loaded && groups.length === 0 && <p className="py-4 text-sm text-muted-foreground">{t("groupsEmpty")}</p>}
          </div>
          <PaginationControls
            page={groupsPagination.page}
            totalPages={groupsPagination.totalPages}
            totalItems={groupsPagination.totalItems}
            pageSize={groupsPagination.pageSize}
            onPageChange={groupsPagination.setPage}
            onPageSizeChange={groupsPagination.setPageSize}
            itemLabel={t("itemLabelGroups")}
          />
        </CardContent>
      </Card>

      <Card className="gap-4">
        <CardHeader>
          <SectionTitle icon={<Upload aria-hidden="true" size={16} />}>{t("importTitle")}</SectionTitle>
          <CardDescription>{t("importHint")}</CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <Select value={importChannel} onChange={(e) => setImportChannel(e.target.value)} options={channelOptions} />
            <Select
              value={importGroupId}
              onChange={(e) => setImportGroupId(e.target.value)}
              options={[{ value: "", label: t("noGroup") }, ...groupOptions]}
            />
          </div>
          <div className="flex flex-col gap-3 sm:flex-row sm:items-center">
            <input
              ref={fileRef}
              type="file"
              accept=".xlsx,.xls,.csv,.txt"
              className="w-full min-w-0 text-sm text-muted-foreground file:me-3 file:rounded-full file:border file:border-border file:bg-card file:px-3 file:py-1.5 file:text-sm file:font-medium file:text-foreground"
              onChange={(e) => setImportFile(e.target.files?.[0] || null)}
              aria-label={t("importFileButton")}
            />
            <Button
              type="button"
              variant="outline"
              className="shrink-0"
              disabled={busy || !importFile}
              onClick={() => runImport(importFile)}
            >
              <Upload aria-hidden="true" size={16} /> {t("importFileButton")}
            </Button>
          </div>
          <div className="flex flex-col gap-2">
            <Label className="text-sm text-muted-foreground">{t("importPasteLabel")}</Label>
            <Textarea
              className="min-h-[96px] font-mono text-sm"
              aria-label={t("importPasteLabel")}
              value={importText}
              onChange={(e) => setImportText(e.target.value)}
              placeholder={"+15551234567, Jane Doe, VIP\n+15559876543"}
            />
            <div>
              <Button
                type="button"
                variant="outline"
                disabled={busy || !importText.trim()}
                onClick={() => runImport(null)}
              >
                <ClipboardPaste aria-hidden="true" size={16} /> {t("importTextButton")}
              </Button>
            </div>
          </div>
          {importResult && (
            <div className="rounded-lg border border-border bg-muted/40 p-4 text-sm">
              <span className="font-medium text-foreground">
                {t("importResult", { created: String(importResult.created), updated: String(importResult.updated) })}
              </span>
              {(importResult.errors || []).length > 0 && (
                <ul className="mt-2 list-disc ps-5 text-destructive">
                  {importResult.errors.slice(0, 5).map((e: string, i: number) => (
                    <li key={i}>{e}</li>
                  ))}
                </ul>
              )}
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
