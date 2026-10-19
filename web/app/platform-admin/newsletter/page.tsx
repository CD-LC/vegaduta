"use client";

import { useCallback, useEffect, useMemo, useState, type FormEvent } from "react";
import { motion } from "motion/react";
import { RefreshCw, Send, Megaphone, Users2, CheckCheck, Square, Mail, MailWarning } from "lucide-react";
import { api } from "../lib/api";
import AdminShell from "../components/AdminShell";
import { PageHint } from "../components/sections/common";
import PaginationControls from "../components/PaginationControls";
import { usePagination } from "../lib/usePagination";
import { toast } from "../lib/toastBus";
import { Button } from "../components/ui/button";
import { Input } from "../components/ui/input";
import { Label } from "../components/ui/label";
import { Textarea } from "../components/ui/textarea";
import { Badge } from "../components/ui/badge";
import { Alert, AlertDescription } from "../components/ui/alert";
import { Card, CardContent, CardHeader, CardTitle } from "../components/ui/card";
import { useConfirm } from "../components/admin/ConfirmDialog";
import { Checkbox } from "../../components/ui/checkbox";

// Newsletter / news broadcast - platform-admin writes a subject + HTML body
// once, picks which tenants (or leaves everything selected for "all"), and
// POST /api/platform/newsletter mails each selected tenant's admin - but
// only the ones where control_plane.tenant.news_opt_in is true (see
// NewsletterService's javadoc, core/platform). This screen shows every
// active tenant's opt-in status up front so it's obvious before sending
// which selected rows will actually be reached.

export default function NewsletterPage() {
  const askConfirm = useConfirm();
  const [recipients, setRecipients] = useState([]);
  const [recipientsError, setRecipientsError] = useState(null);
  const [loadingRecipients, setLoadingRecipients] = useState(true);
  const [selectedIds, setSelectedIds] = useState(() => new Set());
  const [query, setQuery] = useState("");

  const [subject, setSubject] = useState("");
  const [bodyHtml, setBodyHtml] = useState("");
  const [bodyText, setBodyText] = useState("");
  const [sending, setSending] = useState(false);
  const [lastResult, setLastResult] = useState(null);

  const refresh = useCallback(async () => {
    setLoadingRecipients(true);
    try {
      const list = await api.getNewsletterRecipients();
      setRecipients(list);
      setRecipientsError(null);
      // Default selection: every opted-in, reachable tenant - the common
      // case ("broadcast to everyone who said yes") needs zero clicks;
      // opted-out/unreachable rows start unchecked since sending to them
      // would just be silently skipped server-side anyway.
      setSelectedIds(new Set(list.filter((r) => r.newsOptIn && r.adminEmail).map((r) => r.tenantId)));
    } catch (err) {
      setRecipients([]);
      setRecipientsError((err as Error).message);
    } finally {
      setLoadingRecipients(false);
    }
  }, []);

  useEffect(() => {
    refresh().catch((e) => console.error("Could not load newsletter recipients", e));
  }, [refresh]);

  const filteredRecipients = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return recipients;
    return recipients.filter((r) => r.name?.toLowerCase().includes(q) || r.adminEmail?.toLowerCase().includes(q));
  }, [recipients, query]);

  const allFilteredSelected =
    filteredRecipients.length > 0 && filteredRecipients.every((r) => selectedIds.has(r.tenantId));
  const pg = usePagination(filteredRecipients, 50);
  const optedInCount = recipients.filter((r) => r.newsOptIn).length;
  const selectedOptedInCount = recipients.filter((r) => r.newsOptIn && selectedIds.has(r.tenantId)).length;

  function toggleOne(tenantId: string) {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(tenantId)) next.delete(tenantId);
      else next.add(tenantId);
      return next;
    });
  }

  function toggleSelectAll() {
    setSelectedIds((prev) => {
      if (allFilteredSelected) {
        const next = new Set(prev);
        for (const r of filteredRecipients) next.delete(r.tenantId);
        return next;
      }
      const next = new Set(prev);
      for (const r of filteredRecipients) next.add(r.tenantId);
      return next;
    });
  }

  async function handleSend(e: FormEvent) {
    e.preventDefault();
    if (!subject.trim() || !bodyHtml.trim()) {
      toast.error("Subject and body are both required.");
      return;
    }
    if (selectedIds.size === 0) {
      toast.error("Select at least one tenant to send to.");
      return;
    }
    if (
      !(await askConfirm(
        `Send "${subject.trim()}" to ${selectedOptedInCount} opted-in tenant${selectedOptedInCount === 1 ? "" : "s"} now? This cannot be undone.`
      ))
    ) {
      return;
    }
    setSending(true);
    try {
      const result = await api.sendNewsletter({
        subject: subject.trim(),
        bodyHtml,
        // Optional text/plain alternative; blank -> server derives one from bodyHtml.
        bodyText: bodyText.trim() ? bodyText : null,
        recipientTenantIds: Array.from(selectedIds),
      });
      setLastResult(result);
      toast.success(`Newsletter sent - ${result.sent} sent, ${result.skipped} skipped.`);
    } catch (err) {
      toast.error((err as Error).message || "Could not send the newsletter.");
    } finally {
      setSending(false);
    }
  }

  return (
    <AdminShell
      title="Newsletter"
      actions={
        <Button variant="outline" size="sm" onClick={refresh} disabled={loadingRecipients} title="Refresh tenant list">
          <RefreshCw size={14} className={loadingRecipients ? "animate-spin" : undefined} /> Refresh
        </Button>
      }
    >
      <PageHint>
        Broadcast a message to tenant admins. Only tenants that opted in to platform news (<code>news_opt_in</code>, set
        at signup) are actually mailed - opted-out or unreachable tenants below are shown so you can see why
        they&rsquo;d be skipped, even if selected.
      </PageHint>

      <form onSubmit={handleSend}>
        <motion.div
          className="mb-5"
          initial={{ opacity: 0, y: 12 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.35, delay: 0.025, ease: [0.16, 1, 0.3, 1] }}
        >
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2 text-sm">
                <Megaphone size={16} className="text-primary" /> Compose
              </CardTitle>
              <p className="text-xs text-muted-foreground">Subject and body - body accepts HTML.</p>
            </CardHeader>
            <CardContent className="space-y-3">
              <div className="space-y-1.5">
                <Label htmlFor="newsletter-subject" className="text-xs text-muted-foreground">
                  Subject
                </Label>
                <Input
                  id="newsletter-subject"
                  value={subject}
                  onChange={(e) => setSubject(e.target.value)}
                  placeholder="e.g. New in VegaDūta this month"
                />
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="newsletter-body" className="text-xs text-muted-foreground">
                  Body (HTML)
                </Label>
                <Textarea
                  id="newsletter-body"
                  rows={10}
                  value={bodyHtml}
                  onChange={(e) => setBodyHtml(e.target.value)}
                  placeholder="<p>Hi there - here's what's new...</p>"
                  className="font-mono text-xs"
                />
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="newsletter-body-text" className="text-xs text-muted-foreground">
                  Plain-text body (optional)
                </Label>
                <Textarea
                  id="newsletter-body-text"
                  rows={6}
                  value={bodyText}
                  onChange={(e) => setBodyText(e.target.value)}
                  placeholder="Leave blank to derive the text/plain part from the HTML above."
                  className="font-mono text-xs"
                />
                <p className="text-[11px] text-muted-foreground">
                  Sent as the text/plain half of a multipart/alternative email. A hand-written version reads better in
                  text-only clients than the automatic tag-stripped one.
                </p>
              </div>
            </CardContent>
          </Card>
        </motion.div>

        <motion.div
          className="mb-5"
          initial={{ opacity: 0, y: 12 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.35, delay: 0.05, ease: [0.16, 1, 0.3, 1] }}
        >
          <Card>
            <CardHeader className="flex flex-row flex-wrap items-start justify-between gap-3">
              <div>
                <CardTitle className="flex items-center gap-2 text-sm">
                  <Users2 size={16} className="text-primary" /> Recipients
                </CardTitle>
                <p className="mt-1 text-xs text-muted-foreground">
                  {selectedOptedInCount} of {optedInCount} opted-in tenant{optedInCount === 1 ? "" : "s"} selected ·{" "}
                  {recipients.length} active tenant{recipients.length === 1 ? "" : "s"} total
                </p>
              </div>
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={toggleSelectAll}
                disabled={filteredRecipients.length === 0}
              >
                {allFilteredSelected ? <Square size={13} /> : <CheckCheck size={13} />}
                {allFilteredSelected ? "Deselect all" : "Select all"}
              </Button>
            </CardHeader>
            <CardContent className="space-y-3">
              {recipientsError && (
                <Alert variant="destructive">
                  <AlertDescription>{recipientsError}</AlertDescription>
                </Alert>
              )}

              {recipients.length > 0 && (
                <Input
                  aria-label="Search tenants"
                  value={query}
                  onChange={(e) => setQuery(e.target.value)}
                  onKeyDown={(e) => {
                    // This input lives inside the send <form> - without this,
                    // Enter while filtering recipients submits the newsletter
                    // send flow instead of just... filtering.
                    if (e.key === "Enter") e.preventDefault();
                  }}
                  placeholder="Search tenants…"
                  className="max-w-xs"
                />
              )}

              {!loadingRecipients && filteredRecipients.length === 0 && !recipientsError && (
                <p className="text-xs text-muted-foreground">No active tenants match.</p>
              )}

              <div className="max-h-96 space-y-1.5 overflow-y-auto">
                {pg.pageItems.map((r) => {
                  const reachable = r.newsOptIn && !!r.adminEmail;
                  return (
                    <label
                      key={r.tenantId}
                      className="flex flex-wrap items-center gap-3 rounded-lg border border-border px-3 py-2 text-sm hover:bg-accent/40"
                    >
                      <Checkbox checked={selectedIds.has(r.tenantId)} onCheckedChange={() => toggleOne(r.tenantId)} />
                      <span className="min-w-0 flex-1 truncate font-medium text-foreground">{r.name}</span>
                      <span className="min-w-0 max-w-full shrink truncate font-mono text-xs text-muted-foreground">
                        {r.adminEmail || "no admin email"}
                      </span>
                      {reachable ? (
                        <Badge
                          variant="outline"
                          className="shrink-0 border-[var(--success-border)] bg-[var(--success-bg)] text-[var(--success-text)]"
                        >
                          <Mail size={11} /> opted in
                        </Badge>
                      ) : (
                        <Badge
                          variant="secondary"
                          className="shrink-0"
                          title={!r.adminEmail ? "No tenant-admin email on file" : "Not opted in to platform news"}
                        >
                          <MailWarning size={11} /> {r.newsOptIn ? "unreachable" : "opted out"}
                        </Badge>
                      )}
                    </label>
                  );
                })}
              </div>
              <PaginationControls
                page={pg.page}
                totalPages={pg.totalPages}
                totalItems={pg.totalItems}
                pageSize={pg.pageSize}
                onPageChange={pg.setPage}
                onPageSizeChange={pg.setPageSize}
                itemLabel="tenants"
              />
            </CardContent>
          </Card>
        </motion.div>

        <div className="flex flex-wrap items-center gap-3">
          <Button type="submit" disabled={sending}>
            <Send size={14} />
            {sending
              ? "Sending…"
              : `Send to ${selectedOptedInCount || 0} tenant${selectedOptedInCount === 1 ? "" : "s"}`}
          </Button>
          {lastResult && (
            <Badge
              variant="outline"
              className={
                lastResult.sent > 0
                  ? "border-[var(--success-border)] bg-[var(--success-bg)] text-[var(--success-text)]"
                  : "border-[var(--warning-border)] bg-[var(--warning-bg)] text-[var(--warning-text)]"
              }
            >
              Last send: {lastResult.sent} sent, {lastResult.skipped} skipped
            </Badge>
          )}
        </div>
      </form>
    </AdminShell>
  );
}
