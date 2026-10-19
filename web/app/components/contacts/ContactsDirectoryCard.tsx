"use client";

import { useState } from "react";
import { Plus, Search, Trash2, Upload, UserCheck, UserPlus, Users, X } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import LoadMoreBar from "../LoadMoreBar";
import PaginationControls from "../PaginationControls";
import Select from "../Select";
import { Badge } from "../ui/badge";
import { Button } from "../ui/button";
import { Card, CardContent, CardDescription, CardHeader } from "../ui/card";
import { Input } from "../ui/input";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "../ui/table";
import { SectionTitle } from "./ContactsGroupsImport";
import { ChannelChip, STACK_LABEL, type Contact } from "./shared";

interface Option {
  value: string;
  label: string;
}

export interface NewContactDraft {
  channel: string;
  identifier: string;
  displayName: string;
  groupId: string;
  agentId: string;
}

/** Contacts directory: filters, add-contact form, table (>= sm) and stacked list (< sm). */
export default function ContactsDirectoryCard({
  contacts,
  filteredContacts,
  pagination,
  serverPages,
  loaded,
  filterChannel,
  setFilterChannel,
  filterGroup,
  setFilterGroup,
  query,
  setQuery,
  showAddContact,
  setShowAddContact,
  newContact,
  setNewContact,
  busy,
  onAddContact,
  channelOptions,
  groupOptions,
  agentOptions,
  onUpdateContact,
  onDeleteContact,
}: {
  contacts: Contact[];
  filteredContacts: Contact[];
  pagination: {
    pageItems: Contact[];
    page: number;
    totalPages: number;
    totalItems: number;
    pageSize: number;
    setPage: (page: number) => void;
    setPageSize: (size: number) => void;
  };
  serverPages: { total: number | null | undefined; hasMore: boolean; loadingMore: boolean; loadMore: () => unknown };
  loaded: boolean;
  filterChannel: string;
  setFilterChannel: (v: string) => void;
  filterGroup: string;
  setFilterGroup: (v: string) => void;
  query: string;
  setQuery: (v: string) => void;
  showAddContact: boolean;
  setShowAddContact: (fn: (v: boolean) => boolean) => void;
  newContact: NewContactDraft;
  setNewContact: (v: NewContactDraft) => void;
  busy: boolean;
  onAddContact: (e: React.FormEvent) => void;
  channelOptions: Option[];
  groupOptions: Option[];
  agentOptions: Option[];
  onUpdateContact: (contact: Contact, patch: Partial<Contact>) => void | Promise<void>;
  onDeleteContact: (contact: Contact) => void | Promise<void>;
}) {
  const t = useTranslations("contactsPage");
  // Click-again-to-confirm arming for the contact delete button (the group one
  // lives in ContactsGroupsImport.tsx).
  const [confirmDeleteContactId, setConfirmDeleteContactId] = useState<string | null>(null);
  const hasContacts = contacts.length > 0;
  const contactsPagination = pagination;
  const contactPages = serverPages;

  const contactStatus = (c: Contact) =>
    c.sessionId ? (
      <Badge variant="accent" title={t("statusLinkedHint")}>
        {t("statusLinked")}
      </Badge>
    ) : (
      <Badge variant="outline" title={t("statusDirectoryHint")}>
        {t("statusDirectory")}
      </Badge>
    );

  const contactGroupSelect = (c: Contact) => (
    <Select
      value={c.groupId || ""}
      onChange={(e) => onUpdateContact(c, { groupId: e.target.value })}
      options={[{ value: "", label: t("noGroup") }, ...groupOptions]}
      aria-label={t("colGroup")}
    />
  );

  const contactAgentSelect = (c: Contact) => (
    <Select
      value={c.agentId || ""}
      onChange={(e) => onUpdateContact(c, { agentId: e.target.value })}
      options={[{ value: "", label: t("channelDefault") }, ...agentOptions]}
      aria-label={t("colAgent")}
    />
  );

  const contactRoutesVia = (c: Contact) =>
    c.agentId ? t("agentOverride") : c.groupName ? t("groupAgent", { group: c.groupName }) : t("channelDefault");

  const contactDeleteButton = (c: Contact) => {
    const confirming = confirmDeleteContactId === c.id;
    return (
      <Button
        type="button"
        variant={confirming ? "destructive" : "ghost"}
        size="icon"
        className={
          confirming ? "shrink-0" : "shrink-0 text-muted-foreground hover:bg-destructive/10 hover:text-destructive"
        }
        onClick={() => {
          if (confirming) {
            setConfirmDeleteContactId(null);
            void onDeleteContact(c);
          } else {
            setConfirmDeleteContactId(c.id);
          }
        }}
        aria-label={confirming ? t("deleteContactConfirm") : t("deleteContact")}
        title={confirming ? t("deleteContactConfirm") : t("deleteContact")}
      >
        <Trash2 aria-hidden="true" size={16} />
      </Button>
    );
  };

  return (
    <Card className="gap-4">
      <CardHeader>
        <div className="flex flex-wrap items-center gap-2">
          <SectionTitle icon={<Users aria-hidden="true" size={16} />}>{t("contactsTitle")}</SectionTitle>
          {hasContacts && <Badge variant="secondary">{filteredContacts.length}</Badge>}
        </div>
        <CardDescription>{t("contactsHint")}</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        {/* Filters: one row */}
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-[180px_180px_minmax(0,1fr)_auto]">
          <Select
            value={filterChannel}
            onChange={(e) => setFilterChannel(e.target.value)}
            options={[{ value: "", label: t("allChannels") }, ...channelOptions]}
            aria-label={t("colChannel")}
          />
          <Select
            value={filterGroup}
            onChange={(e) => setFilterGroup(e.target.value)}
            options={[{ value: "", label: t("allGroups") }, ...groupOptions]}
            aria-label={t("filterByGroupLabel")}
          />
          <div className="relative">
            <Search
              size={16}
              className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground"
              aria-hidden="true"
            />
            <Input
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder={t("searchPlaceholder")}
              className="pl-9"
              aria-label={t("searchPlaceholder")}
            />
          </div>
          <Button
            type="button"
            variant={showAddContact ? "outline" : "default"}
            onClick={() => setShowAddContact((v) => !v)}
            aria-expanded={showAddContact}
          >
            {showAddContact ? <X aria-hidden="true" size={16} /> : <UserPlus aria-hidden="true" size={16} />}
            {showAddContact ? t("cancelButton") : t("addContactButton")}
          </Button>
        </div>

        {/* Add-contact form, collapsed until asked for */}
        {showAddContact && (
          <form
            onSubmit={onAddContact}
            className="grid grid-cols-1 gap-3 rounded-lg border border-border bg-muted/40 p-4 sm:grid-cols-2 lg:grid-cols-[150px_1.4fr_1fr_1fr_1fr_auto]"
          >
            <Select
              value={newContact.channel}
              onChange={(e) => setNewContact({ ...newContact, channel: e.target.value })}
              options={channelOptions}
            />
            <Input
              value={newContact.identifier}
              onChange={(e) => setNewContact({ ...newContact, identifier: e.target.value })}
              placeholder={t("identifierPlaceholder")}
              aria-label={t("identifierPlaceholder")}
              required
              autoFocus
            />
            <Input
              value={newContact.displayName}
              onChange={(e) => setNewContact({ ...newContact, displayName: e.target.value })}
              placeholder={t("namePlaceholder")}
              aria-label={t("namePlaceholder")}
            />
            <Select
              value={newContact.groupId}
              onChange={(e) => setNewContact({ ...newContact, groupId: e.target.value })}
              placeholder={t("groupPlaceholder")}
              options={[{ value: "", label: t("noGroup") }, ...groupOptions]}
            />
            <Select
              value={newContact.agentId}
              onChange={(e) => setNewContact({ ...newContact, agentId: e.target.value })}
              placeholder={t("agentPlaceholder")}
              options={[{ value: "", label: t("channelDefault") }, ...agentOptions]}
            />
            <Button type="submit" disabled={busy || !newContact.identifier.trim()}>
              <Plus aria-hidden="true" size={16} /> {t("addButton")}
            </Button>
          </form>
        )}

        {loaded && !hasContacts ? (
          <div className="flex flex-col items-center gap-3 rounded-lg border border-dashed border-border px-4 py-12 text-center">
            <span className="inline-flex h-12 w-12 items-center justify-center rounded-full bg-muted text-muted-foreground">
              <Users aria-hidden="true" size={22} />
            </span>
            <div className="text-base font-semibold text-foreground">{t("contactsEmptyTitle")}</div>
            <p className="max-w-md text-sm text-muted-foreground">{t("contactsEmptyBody")}</p>
            <ul className="mt-1 flex flex-wrap justify-center gap-2">
              <li className="inline-flex items-center gap-1.5 rounded-full border border-border bg-card px-3 py-1.5 text-sm text-foreground">
                <UserCheck aria-hidden="true" size={14} className="text-muted-foreground" /> {t("contactsEmptyApprove")}
              </li>
              <li className="inline-flex items-center gap-1.5 rounded-full border border-border bg-card px-3 py-1.5 text-sm text-foreground">
                <UserPlus aria-hidden="true" size={14} className="text-muted-foreground" /> {t("contactsEmptyAdd")}
              </li>
              <li className="inline-flex items-center gap-1.5 rounded-full border border-border bg-card px-3 py-1.5 text-sm text-foreground">
                <Upload aria-hidden="true" size={14} className="text-muted-foreground" /> {t("contactsEmptyImport")}
              </li>
            </ul>
          </div>
        ) : (
          <>
            {/* >= sm: directory table */}
            <div className="hidden sm:block">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>{t("colContact")}</TableHead>
                    <TableHead className="w-[120px]">{t("colStatus")}</TableHead>
                    <TableHead className="w-[200px]">{t("colGroup")}</TableHead>
                    <TableHead className="w-[220px]">{t("colAgent")}</TableHead>
                    <TableHead className="w-[150px]">{t("colRoutesVia")}</TableHead>
                    <TableHead className="w-[64px] text-right">
                      <span className="sr-only">{t("colActions")}</span>
                    </TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {contactsPagination.pageItems.map((c) => (
                    <TableRow key={c.id} className="h-14">
                      <TableCell className="min-w-[240px]">
                        <div className="flex items-center gap-3">
                          <ChannelChip channel={c.channel} />
                          <div className="min-w-0">
                            <div className="truncate font-mono text-sm text-foreground">{c.identifier}</div>
                            {c.displayName && (
                              <div className="truncate text-sm text-muted-foreground">{c.displayName}</div>
                            )}
                          </div>
                        </div>
                      </TableCell>
                      <TableCell>{contactStatus(c)}</TableCell>
                      <TableCell className="min-w-[180px]">{contactGroupSelect(c)}</TableCell>
                      <TableCell className="min-w-[200px]">{contactAgentSelect(c)}</TableCell>
                      <TableCell className="text-sm text-muted-foreground">{contactRoutesVia(c)}</TableCell>
                      <TableCell className="text-right">{contactDeleteButton(c)}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
            {/* < sm: stacked rows - identity on line 1, status + route on line 2, group/agent on line 3 */}
            <ul className="flex flex-col gap-2 sm:hidden" data-testid="contacts-stack">
              {contactsPagination.pageItems.map((c) => (
                <li key={c.id} className="flex flex-col gap-3 rounded-lg border border-border p-3">
                  <div className="flex items-start gap-3">
                    {/* chip + identifier wrap onto two lines when the number is long, so it is never cut to "+91 98xxx…" */}
                    <div className="flex min-w-0 flex-1 flex-wrap items-center gap-x-3 gap-y-1">
                      <ChannelChip channel={c.channel} />
                      <div className="min-w-0 max-w-full">
                        <div className="break-all font-mono text-sm text-foreground">{c.identifier}</div>
                        {c.displayName && <div className="truncate text-sm text-muted-foreground">{c.displayName}</div>}
                      </div>
                    </div>
                    {contactDeleteButton(c)}
                  </div>
                  <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm text-muted-foreground">
                    {contactStatus(c)}
                    <span className="inline-flex items-center gap-1.5">
                      <span className={STACK_LABEL}>{t("colRoutesVia")}</span>
                      {contactRoutesVia(c)}
                    </span>
                  </div>
                  <div className="grid grid-cols-2 gap-2">
                    <div className="flex min-w-0 flex-col gap-1.5">
                      <span className={STACK_LABEL}>{t("colGroup")}</span>
                      {contactGroupSelect(c)}
                    </div>
                    <div className="flex min-w-0 flex-col gap-1.5">
                      <span className={STACK_LABEL}>{t("colAgent")}</span>
                      {contactAgentSelect(c)}
                    </div>
                  </div>
                </li>
              ))}
            </ul>
            {loaded && hasContacts && filteredContacts.length === 0 && (
              <p className="py-6 text-center text-sm text-muted-foreground">{t("contactsNoMatch")}</p>
            )}
            <PaginationControls
              page={contactsPagination.page}
              totalPages={contactsPagination.totalPages}
              totalItems={contactsPagination.totalItems}
              pageSize={contactsPagination.pageSize}
              onPageChange={contactsPagination.setPage}
              onPageSizeChange={contactsPagination.setPageSize}
              itemLabel={t("itemLabelContacts")}
            />
            <LoadMoreBar
              loaded={contacts.length}
              total={contactPages.total}
              hasMore={contactPages.hasMore}
              loading={contactPages.loadingMore}
              onLoadMore={() => void contactPages.loadMore()}
              itemLabel={t("itemLabelContacts")}
            />
          </>
        )}
      </CardContent>
    </Card>
  );
}
