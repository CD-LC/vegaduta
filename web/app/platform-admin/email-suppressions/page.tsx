"use client";

import { useCallback, useEffect, useState } from "react";
import { MailX, RefreshCw, Search, Trash2 } from "lucide-react";
import AdminShell from "../components/AdminShell";
import { PageHint, SectionCard } from "../components/sections/common";
import PaginationControls from "../components/PaginationControls";
import { Button } from "../components/ui/button";
import { Input } from "../components/ui/input";
import { Badge } from "../components/ui/badge";
import { Alert, AlertDescription } from "../components/ui/alert";
import { Skeleton } from "../components/ui/skeleton";
import { opsApi, type EmailSuppressionPage } from "../lib/adminApi/operations";
import { toast } from "../lib/toastBus";
import { formatExact, formatRelative } from "../lib/platformFormat";
import { useConfirm } from "../components/admin/ConfirmDialog";
import { useTranslations } from "../../lib/i18n";

// Outbound-email suppression list (GET/DELETE /api/platform/email/suppressions).
// An address lands here after a hard bounce, a spam complaint or an
// unsubscribe; core never sends to it again until it is removed. Removing is
// the fix for "I never got the verification email" after the person fixed
// their mailbox - but removing a complaint re-opens a spam-report risk, so the
// confirm says so.

export default function EmailSuppressionsPage() {
    const tHC = useTranslations("hcPlatformadminEmailsuppressionsPage");
  const askConfirm = useConfirm();
  const [q, setQ] = useState("");
  const [applied, setApplied] = useState("");
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(50);
  const [data, setData] = useState<EmailSuppressionPage | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [removing, setRemoving] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      // `page` is 1-based, the same convention as the traces list meta.
      const res = await opsApi.emailSuppressions({ q: applied || undefined, page, limit: pageSize });
      setData({
        items: Array.isArray(res?.items) ? res.items : [],
        total: typeof res?.total === "number" ? res.total : 0,
      });
      setError(null);
    } catch (e: any) {
      setError(e?.message || "Could not load the suppression list.");
    } finally {
      setLoading(false);
    }
  }, [applied, page, pageSize]);

  useEffect(() => {
    void load();
  }, [load]);

  async function remove(email: string, reason: string) {
    const complaint = /complain|spam/i.test(reason || "");
    const msg = complaint
      ? `Remove ${email}? This address reported our email as spam. Sending to it again can hurt deliverability for every tenant.`
      : `Remove ${email} from the suppression list? Core will send to it again.`;
    if (!(await askConfirm(msg))) return;
    setRemoving(email);
    try {
      await opsApi.removeEmailSuppression(email);
      toast.success(`${email} removed from the suppression list.`);
      await load();
    } catch {
      // toJson already toasted the reason.
    } finally {
      setRemoving(null);
    }
  }

  const items = data?.items || [];
  const total = data?.total ?? 0;

  return (
    <AdminShell title={tHC("s5")}>
      <PageHint>
        {tHC("s4")}
      </PageHint>

      <SectionCard
        title={tHC("s3")}
        icon={MailX}
        subtitle={data ? `${total.toLocaleString("en-US")} address${total === 1 ? "" : "es"}` : undefined}
        actions={
          <form
            className="flex flex-wrap items-center gap-2"
            onSubmit={(e) => {
              e.preventDefault();
              setPage(1);
              setApplied(q.trim());
            }}
          >
            <Input
              aria-label={tHC("s2")}
              className="h-9 w-64"
              placeholder={tHC("s1")}
              value={q}
              onChange={(e) => setQ(e.target.value)}
            />
            <Button type="submit" size="sm">
              <Search /> Search
            </Button>
            <Button type="button" size="icon" variant="outline" aria-label="Refresh" onClick={() => void load()}>
              <RefreshCw />
            </Button>
          </form>
        }
      >
        {error && (
          <Alert variant="destructive">
            <AlertDescription>{error}</AlertDescription>
          </Alert>
        )}
        {loading && !data && <Skeleton className="h-32 w-full" />}
        {data && items.length === 0 && (
          <p className="text-sm text-muted-foreground">
            {applied ? "No suppressed address matches." : "The suppression list is empty."}
          </p>
        )}
        {items.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[640px] text-start text-sm">
              <thead className="text-xs text-muted-foreground">
                <tr className="border-b border-border">
                  <th className="py-2 pe-3 font-medium">Email</th>
                  <th className="py-2 pe-3 font-medium">Reason</th>
                  <th className="py-2 pe-3 font-medium">Source</th>
                  <th className="py-2 pe-3 font-medium">Added</th>
                  <th className="py-2 font-medium">
                    <span className="sr-only">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {items.map((s) => (
                  <tr key={s.email} className="border-b border-border/60">
                    <td className="py-2 pe-3 font-mono text-xs">{s.email}</td>
                    <td className="py-2 pe-3">
                      <Badge variant={/complain|spam/i.test(s.reason || "") ? "destructive" : "secondary"}>
                        {s.reason || "—"}
                      </Badge>
                    </td>
                    <td className="py-2 pe-3 text-xs text-muted-foreground">{s.source || "—"}</td>
                    <td className="py-2 pe-3 text-xs" title={formatExact(s.createdAt)}>
                      {formatRelative(s.createdAt)}
                    </td>
                    <td className="py-2 text-end">
                      <Button
                        size="sm"
                        variant="outline"
                        disabled={removing != null}
                        onClick={() => void remove(s.email, s.reason)}
                      >
                        <Trash2 /> {removing === s.email ? "Removing..." : "Remove"}
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {data && total > 0 && (
          <PaginationControls
            page={page}
            pageSize={pageSize}
            totalPages={Math.max(1, Math.ceil(total / pageSize))}
            totalItems={total}
            onPageChange={setPage}
            onPageSizeChange={(n: number) => {
              setPageSize(n);
              setPage(1);
            }}
            itemLabel="addresses"
          />
        )}
      </SectionCard>
    </AdminShell>
  );
}
