"use client";

import { Fragment, useCallback, useEffect, useState } from "react";
import { RefreshCw, ShieldCheck, XCircle, CheckCircle2 } from "lucide-react";
import { api } from "../lib/api";
import AdminShell from "../components/AdminShell";
import { PageHint, InfoNote } from "../components/sections/common";
import { toast } from "../lib/toastBus";
import { Button } from "../components/ui/button";
import { Badge } from "../components/ui/badge";
import { Textarea } from "../components/ui/textarea";
import { Card, CardContent } from "../components/ui/card";
import { Input } from "../components/ui/input";
import { Skeleton } from "../components/ui/skeleton";
import PaginationControls from "../components/PaginationControls";
import { usePagination } from "../lib/usePagination";
import { useConfirm } from "../components/admin/ConfirmDialog";
import { AdminErrorState, AdminEmptyState } from "../components/admin/AdminKit";

// MCP-STDIO-SANDBOXING (2026-08-05) - platform-staff review queue for
// self-service custom/registry-browsed STDIO MCP registrations. Per
// McpReviewStatus.java's javadoc, every STDIO registration that ISN'T a
// remote (SSE/streamable-HTTP) connection and ISN'T a curated catalog entry
// starts PENDING and is refused a real connect/spawn until a human here
// approves it - the pentest's Critical C1 finding ("npx <any-package> is
// full RCE inside the shared core container") is what makes this gate exist
// at all, so approve/reject is a genuine security decision, not busywork.
//
// Internal ops screen, kept plain and dense (no motion/cards-with-icons
// polish like the Community Templates queue) per the same "functional, not
// a customer surface" posture the other list+action admin screens
// (llm-providers, budgets) already use - a reviewer needs to scan command/
// args fast, not admire the layout.
//
// Cross-tenant by nature (see api.listPendingMcpReviews's own comment): each
// row carries its own tenantId, required on every approve/reject call since
// a registration's UUID is only unique within its own tenant schema.

type PendingRow = {
  tenantId: string;
  id: string;
  name: string;
  command: string | null;
  args: string | null;
  requestedBy: string | null;
  createdAt: string | null;
};

function formatArgs(args: string | null): string {
  if (!args) return "";
  try {
    const parsed = JSON.parse(args);
    return Array.isArray(parsed) ? parsed.join(" ") : String(args);
  } catch {
    return args;
  }
}

function formatDateTime(iso: string | null): string {
  if (!iso) return "—";
  try {
    return new Date(iso).toLocaleString();
  } catch {
    return iso;
  }
}

export default function McpReviewQueuePage() {
  const askConfirm = useConfirm();
  const [rows, setRows] = useState<PendingRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busyKey, setBusyKey] = useState<string | null>(null);
  // Which row's reject-reason field is expanded - key is `${tenantId}:${id}`,
  // same composite key used for busyKey (a registration id alone isn't
  // globally unique across tenant schemas, see the file header comment).
  const [rejectingKey, setRejectingKey] = useState<string | null>(null);
  const [reason, setReason] = useState("");

  const [search, setSearch] = useState("");

  const rowKey = (r: PendingRow) => `${r.tenantId}:${r.id}`;

  const refresh = useCallback(async (isRefresh = false) => {
    if (isRefresh) setRefreshing(true);
    else setLoading(true);
    try {
      const list = await api.listPendingMcpReviews();
      setRows(Array.isArray(list) ? list : []);
      setError(null);
    } catch (err: any) {
      setRows([]);
      setError(err?.message || "Could not load the MCP review queue.");
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, []);

  useEffect(() => {
    refresh().catch((e) => console.error("Could not load the MCP review queue", e));
  }, [refresh]);

  async function handleApprove(row: PendingRow) {
    const command = `${row.command || ""}${row.args ? ` ${formatArgs(row.args)}` : ""}`.trim();
    if (
      !(await askConfirm(
        `Approve "${row.name}"? This lets "${command}" run inside the shared container for tenant ${row.tenantId}.`
      ))
    ) {
      return;
    }
    const key = rowKey(row);
    setBusyKey(key);
    try {
      await api.approveMcpReview(row.tenantId, row.id);
      setRows((prev) => prev.filter((r) => rowKey(r) !== key));
      if (rejectingKey === key) setRejectingKey(null);
      toast.success(`Approved "${row.name}" - it can connect now.`);
    } catch (err: any) {
      toast.error(err?.message || "Could not approve this registration.");
    } finally {
      setBusyKey(null);
    }
  }

  async function handleReject(row: PendingRow) {
    const key = rowKey(row);
    setBusyKey(key);
    try {
      await api.rejectMcpReview(row.tenantId, row.id, reason.trim());
      setRows((prev) => prev.filter((r) => rowKey(r) !== key));
      setRejectingKey(null);
      setReason("");
      toast.success(`Rejected "${row.name}" - the tenant will see the reason.`);
    } catch (err: any) {
      toast.error(err?.message || "Could not reject this registration.");
    } finally {
      setBusyKey(null);
    }
  }

  const needle = search.trim().toLowerCase();
  const filtered = needle
    ? rows.filter((r) =>
        [r.tenantId, r.name, r.command, r.requestedBy, r.args].some((v) =>
          String(v ?? "")
            .toLowerCase()
            .includes(needle)
        )
      )
    : rows;
  const pg = usePagination(filtered, 10);

  return (
    <AdminShell
      title="MCP Review Queue"
      actions={
        <Button variant="outline" size="sm" onClick={() => refresh(true)} disabled={refreshing || loading}>
          <RefreshCw size={14} className={refreshing ? "animate-spin" : undefined} /> Refresh
        </Button>
      }
    >
      <PageHint>
        Self-service custom or registry-browsed STDIO MCP registrations (hand-typed, or added via a tenant&rsquo;s
        &ldquo;Browse registry&rdquo; search) can never spawn a process until approved here - remote (SSE/HTTP)
        connections and curated catalog entries skip this gate entirely and never appear below.
      </PageHint>
      <InfoNote>
        Approving lets this exact command/args run inside the shared core container for that tenant going forward. Look
        at the command and args as real, executable input - this queue exists because no automated allowlist could
        safely close that gap on its own.
      </InfoNote>

      {error && <AdminErrorState className="mb-4" message={error} onRetry={() => refresh(true)} />}

      {rows.length > 0 && (
        <Input
          aria-label="Search registrations"
          className="mb-4 h-9 max-w-sm"
          placeholder="Search tenant, server, command"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />
      )}

      <Card>
        <CardContent className="p-0">
          {loading ? (
            <div className="space-y-2 p-4">
              <Skeleton className="h-12 w-full" />
              <Skeleton className="h-12 w-full" />
            </div>
          ) : rows.length === 0 ? (
            <div className="flex flex-col items-center gap-2 p-8 text-center">
              <CheckCircle2 size={20} className="text-muted-foreground" />
              <p className="text-sm text-muted-foreground">Nothing waiting on review - the queue is empty.</p>
            </div>
          ) : filtered.length === 0 ? (
            <AdminEmptyState title="No registrations match" description="Try a different search or status." />
          ) : (
            <>
              {/* Stacked cards below md - the wide table's min-width put the
                  Approve/Reject column a long way off-screen on phones,
                  behind an undiscoverable horizontal scroll with no
                  alternative. */}
              <div className="divide-y divide-border md:hidden">
                {pg.pageItems.map((row) => {
                  const key = rowKey(row);
                  const busy = busyKey === key;
                  const isRejecting = rejectingKey === key;
                  return (
                    <div key={key} className="space-y-2 p-3">
                      <div className="flex flex-wrap items-center justify-between gap-2">
                        <div className="font-medium text-foreground">{row.tenantId}</div>
                        <span className="text-xs text-muted-foreground">{formatDateTime(row.createdAt)}</span>
                      </div>
                      <div className="flex items-center gap-1.5">
                        <span className="font-medium text-foreground">{row.name}</span>
                        <Badge variant="secondary" className="text-[10px] uppercase">
                          STDIO
                        </Badge>
                      </div>
                      <code className="block whitespace-pre-wrap break-words rounded bg-muted px-1.5 py-1 font-mono text-[11px]">
                        {row.command}
                        {row.args ? ` ${formatArgs(row.args)}` : ""}
                      </code>
                      <div className="text-xs text-muted-foreground">Requested by {row.requestedBy || "—"}</div>
                      <div className="flex gap-2">
                        <Button
                          variant="default"
                          size="sm"
                          disabled={busy}
                          onClick={() => handleApprove(row)}
                          aria-label={`Approve ${row.name}`}
                        >
                          <ShieldCheck size={13} /> {busy && !isRejecting ? "Approving…" : "Approve"}
                        </Button>
                        <Button
                          variant="outline"
                          size="sm"
                          disabled={busy}
                          className="text-destructive hover:bg-destructive/10"
                          onClick={() => {
                            setRejectingKey(isRejecting ? null : key);
                            setReason("");
                          }}
                          aria-label={`Reject ${row.name}`}
                        >
                          <XCircle size={13} /> {isRejecting ? "Cancel" : "Reject"}
                        </Button>
                      </div>
                      {isRejecting && (
                        <div className="flex flex-col gap-2 rounded-md bg-secondary/20 p-2">
                          <Textarea
                            value={reason}
                            onChange={(e) => setReason(e.target.value)}
                            placeholder='Reason (optional) - shown to the tenant, e.g. "no published source for this package"'
                            className="min-h-16 resize-y text-sm"
                            disabled={busy}
                          />
                          <Button variant="destructive" size="sm" disabled={busy} onClick={() => handleReject(row)}>
                            {busy ? "Rejecting…" : "Confirm reject"}
                          </Button>
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>

              <div className="hidden overflow-x-auto md:block">
                <table className="w-full min-w-[64rem] border-collapse text-sm">
                  <thead>
                    <tr className="border-b border-border text-start text-xs uppercase tracking-wide text-muted-foreground">
                      <th className="px-3 py-2 font-medium">Tenant</th>
                      <th className="px-3 py-2 font-medium">Server</th>
                      <th className="px-3 py-2 font-medium">Command</th>
                      <th className="px-3 py-2 font-medium">Requested by</th>
                      <th className="px-3 py-2 font-medium">Created</th>
                      <th className="px-3 py-2 text-end font-medium">Actions</th>
                    </tr>
                  </thead>
                  <tbody>
                    {pg.pageItems.map((row) => {
                      const key = rowKey(row);
                      const busy = busyKey === key;
                      const isRejecting = rejectingKey === key;
                      return (
                        <Fragment key={key}>
                          <tr className="border-b border-border/60 align-top last:border-0">
                            <td className="px-3 py-2">
                              <div className="font-medium text-foreground">{row.tenantId}</div>
                            </td>
                            <td className="px-3 py-2">
                              <div className="flex items-center gap-1.5">
                                <span className="font-medium text-foreground">{row.name}</span>
                                <Badge variant="secondary" className="text-[10px] uppercase">
                                  STDIO
                                </Badge>
                              </div>
                            </td>
                            <td className="px-3 py-2">
                              <code className="block max-w-[22rem] whitespace-pre-wrap break-words rounded bg-muted px-1.5 py-1 font-mono text-[11px]">
                                {row.command}
                                {row.args ? ` ${formatArgs(row.args)}` : ""}
                              </code>
                            </td>
                            <td className="px-3 py-2 text-xs text-muted-foreground">{row.requestedBy || "—"}</td>
                            <td className="px-3 py-2 whitespace-nowrap text-xs text-muted-foreground">
                              {formatDateTime(row.createdAt)}
                            </td>
                            <td className="px-3 py-2">
                              <div className="flex justify-end gap-2">
                                <Button
                                  variant="default"
                                  size="sm"
                                  disabled={busy}
                                  onClick={() => handleApprove(row)}
                                  aria-label={`Approve ${row.name}`}
                                >
                                  <ShieldCheck size={13} /> {busy && !isRejecting ? "Approving…" : "Approve"}
                                </Button>
                                <Button
                                  variant="outline"
                                  size="sm"
                                  disabled={busy}
                                  className="text-destructive hover:bg-destructive/10"
                                  onClick={() => {
                                    setRejectingKey(isRejecting ? null : key);
                                    setReason("");
                                  }}
                                  aria-label={`Reject ${row.name}`}
                                >
                                  <XCircle size={13} /> {isRejecting ? "Cancel" : "Reject"}
                                </Button>
                              </div>
                            </td>
                          </tr>
                          {isRejecting && (
                            <tr className="border-b border-border/60 bg-secondary/20 last:border-0">
                              <td colSpan={6} className="px-3 py-3">
                                <div className="flex flex-col gap-2 sm:flex-row sm:items-start">
                                  <Textarea
                                    value={reason}
                                    onChange={(e) => setReason(e.target.value)}
                                    placeholder='Reason (optional) - shown to the tenant, e.g. "no published source for this package"'
                                    className="min-h-16 flex-1 resize-y text-sm"
                                    disabled={busy}
                                  />
                                  <Button
                                    variant="destructive"
                                    size="sm"
                                    disabled={busy}
                                    onClick={() => handleReject(row)}
                                    className="shrink-0"
                                  >
                                    {busy ? "Rejecting…" : "Confirm reject"}
                                  </Button>
                                </div>
                              </td>
                            </tr>
                          )}
                        </Fragment>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            </>
          )}
        </CardContent>
      </Card>
      <PaginationControls
        page={pg.page}
        totalPages={pg.totalPages}
        totalItems={pg.totalItems}
        pageSize={pg.pageSize}
        onPageChange={pg.setPage}
        onPageSizeChange={pg.setPageSize}
        itemLabel="registrations"
      />
    </AdminShell>
  );
}
