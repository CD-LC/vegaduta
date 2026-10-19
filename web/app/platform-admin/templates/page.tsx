"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { AnimatePresence, motion } from "motion/react";
import { RefreshCw, ListChecks, Globe2, Search } from "lucide-react";
import AdminShell from "../components/AdminShell";
import { PageHint, InfoNote } from "../components/sections/common";
import { Card, CardContent, CardHeader, CardTitle } from "../components/ui/card";
import { Tabs, TabsList, TabsTrigger, TabsContent } from "../components/ui/tabs";
import { Button } from "../components/ui/button";
import { Input } from "../components/ui/input";
import { Alert, AlertDescription } from "../components/ui/alert";
import { Skeleton } from "../components/ui/skeleton";
import PaginationControls from "../components/PaginationControls";
import { usePagination } from "../lib/usePagination";
import { api } from "../lib/api";
import { toast } from "../lib/toastBus";
import TemplateQueueCard from "../components/templates/TemplateQueueCard";
import type { CommunityTemplate, TemplateSummary } from "./types";
import { AdminErrorState } from "../components/admin/AdminKit";

// Community Templates - moderation queue
// (docs/COMMUNITY-TEMPLATES-CONTRACT-2026-07-30.md). Public submission of
// executable agent/workflow config is real attack surface (the doc's
// ClawHavoc reference), so nothing a member submits is publicly visible
// until an admin approves it here - that gate is the entire reason this
// screen exists. Backend (core-3, controlplane.community_template) confirmed
// live and verified 2026-07-30 (14/14 tests); every list below still
// degrades to a labeled error state rather than crashing if a call ever
// fails at runtime, per this app's usual apiFetch/toJson error-surface
// convention.

// /api/admin/templates/** is gated by the PERM_MANAGE_TEMPLATES permission,
// which is stored per tenant user - a platform-admin token carries none, so a
// bare "no permission" reads as the whole console being broken. Say what is
// actually wrong and who can fix it.
function templatesLoadError(err: any, fallback: string): string {
  if (err?.status === 403) {
    return (
      "This account can't reach the template moderation API (403). Community Templates moderation needs the " +
      "MANAGE_TEMPLATES permission, which a platform-admin token does not carry. Ask engineering to allow " +
      "ROLE_platform-admin on /api/admin/templates/** (tracked in docs/AGENT-COORDINATION.md)."
    );
  }
  return err?.message || fallback;
}

function ListSkeleton() {
  return (
    <div className="space-y-2">
      {[0, 1, 2].map((i) => (
        <Skeleton key={i} className="h-20 w-full" />
      ))}
    </div>
  );
}

function PendingQueueTab() {
  const [templates, setTemplates] = useState<CommunityTemplate[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [search, setSearch] = useState("");

  const refresh = useCallback(async (isRefresh = false) => {
    if (isRefresh) setRefreshing(true);
    else setLoading(true);
    try {
      const list = await api.getAdminTemplatesPending();
      setTemplates(Array.isArray(list) ? list : []);
      setError(null);
    } catch (err: any) {
      setTemplates([]);
      setError(templatesLoadError(err, "Could not load the pending queue."));
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, []);

  useEffect(() => {
    refresh().catch((e) => console.error("Failed to load the pending template queue", e));
  }, [refresh]);

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    if (!q) return templates;
    return templates.filter((t) =>
      [t.title, t.summary, t.category, t.submittedBy, t.sourceTenantName, t.sourceTenantId]
        .filter(Boolean)
        .some((f) => String(f).toLowerCase().includes(q))
    );
  }, [templates, search]);

  const { page, pageSize, totalPages, totalItems, pageItems, setPage, setPageSize } = usePagination(filtered);

  async function handleApprove(id: string) {
    const updated = await api.approveAdminTemplate(id);
    setTemplates((prev) => prev.filter((t) => t.id !== id));
    setExpandedId((cur) => (cur === id ? null : cur));
    toast.success(`Approved${updated?.title ? ` "${updated.title}"` : ""} - now public in the gallery.`);
  }

  async function handleReject(id: string, reason: string) {
    await api.rejectAdminTemplate(id, reason);
    setTemplates((prev) => prev.filter((t) => t.id !== id));
    setExpandedId((cur) => (cur === id ? null : cur));
    toast.success("Rejected - the submitter can see the reason and resubmit.");
  }

  return (
    <Card>
      <CardHeader className="flex flex-row flex-wrap items-start justify-between gap-3">
        <div>
          <CardTitle className="flex items-center gap-2 text-sm">
            <ListChecks size={16} className="text-primary" /> Pending review
          </CardTitle>
          <p className="mt-1 text-xs text-muted-foreground">
            {loading ? "Loading…" : `${totalItems} submission${totalItems === 1 ? "" : "s"} awaiting a decision`}
          </p>
        </div>
        <Button variant="outline" size="sm" onClick={() => refresh(true)} disabled={refreshing || loading}>
          <RefreshCw size={14} className={refreshing ? "animate-spin" : undefined} /> Refresh
        </Button>
      </CardHeader>
      <CardContent className="space-y-3">
        {error && (
          <Alert variant="destructive">
            <AlertDescription>{error}</AlertDescription>
          </Alert>
        )}

        {loading && !error && <ListSkeleton />}

        {!loading && !error && templates.length === 0 && (
          <p className="rounded-lg border border-border bg-secondary/20 px-4 py-6 text-center text-sm text-muted-foreground">
            Nothing waiting on review - the queue is empty.
          </p>
        )}

        {!loading && !error && templates.length > 0 && (
          <>
            <div className="relative max-w-sm">
              <Search
                size={14}
                className="pointer-events-none absolute left-2.5 top-1/2 -translate-y-1/2 text-muted-foreground"
              />
              <Input
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                placeholder="Search title, submitter, tenant…"
                className="pl-8"
              />
            </div>
            {filtered.length === 0 && (
              <p className="text-sm text-muted-foreground">No submissions match &ldquo;{search}&rdquo;.</p>
            )}
            <AnimatePresence initial={false}>
              {pageItems.map((t) => (
                <TemplateQueueCard
                  key={t.id}
                  template={t}
                  mode="pending"
                  expanded={expandedId === t.id}
                  onToggleExpand={() => setExpandedId((cur) => (cur === t.id ? null : t.id))}
                  onApprove={() => handleApprove(t.id)}
                  onReject={(reason) => handleReject(t.id, reason)}
                />
              ))}
            </AnimatePresence>
            <PaginationControls
              page={page}
              totalPages={totalPages}
              totalItems={totalItems}
              pageSize={pageSize}
              onPageChange={setPage}
              onPageSizeChange={setPageSize}
              itemLabel="submissions"
            />
          </>
        )}
      </CardContent>
    </Card>
  );
}

function PublishedTab() {
  const [templates, setTemplates] = useState<TemplateSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [search, setSearch] = useState("");

  const refresh = useCallback(async (isRefresh = false) => {
    if (isRefresh) setRefreshing(true);
    else setLoading(true);
    try {
      const result = await api.getApprovedAdminTemplates();
      // Defensive against a wrapped/paginated response shape even though the
      // confirmed contract is a plain TemplateSummary[] - costs nothing if
      // it's already an array.
      const list = Array.isArray(result) ? result : result?.items || result?.content || [];
      setTemplates(list);
      setError(null);
    } catch (err: any) {
      setTemplates([]);
      setError(templatesLoadError(err, "Could not load published templates."));
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, []);

  useEffect(() => {
    refresh().catch((e) => console.error("Failed to load published templates", e));
  }, [refresh]);

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    if (!q) return templates;
    return templates.filter((t) =>
      [t.title, t.summary, t.category].filter(Boolean).some((f) => String(f).toLowerCase().includes(q))
    );
  }, [templates, search]);
  const pg = usePagination(filtered);

  async function handleWithdraw(id: string, reason: string) {
    await api.withdrawAdminTemplate(id, reason);
    setTemplates((prev) => prev.filter((t) => t.id !== id));
    setExpandedId((cur) => (cur === id ? null : cur));
    toast.success("Withdrawn - no longer public in the gallery.");
  }

  return (
    <Card>
      <CardHeader className="flex flex-row flex-wrap items-start justify-between gap-3">
        <div>
          <CardTitle className="flex items-center gap-2 text-sm">
            <Globe2 size={16} className="text-primary" /> Published (withdraw)
          </CardTitle>
          <p className="mt-1 text-xs text-muted-foreground">
            Approved templates currently public in the gallery - pull one after a report with{" "}
            <code className="font-mono">POST /api/admin/templates/{"{id}"}/withdraw</code>.
          </p>
        </div>
        <Button variant="outline" size="sm" onClick={() => refresh(true)} disabled={refreshing || loading}>
          <RefreshCw size={14} className={refreshing ? "animate-spin" : undefined} /> Refresh
        </Button>
      </CardHeader>
      <CardContent className="space-y-3">
        <p className="text-xs text-muted-foreground">
          Reads the admin-scoped approved list (<code className="font-mono">GET /api/admin/templates/approved</code>)
          purely to find a template to withdraw - it&rsquo;s a summary (no config, no submitter/tenant history), since
          that was already reviewed in full at approval time.
        </p>
        {error && <AdminErrorState className="mb-4" message={error} onRetry={() => refresh(true)} />}
        {loading && !error && <ListSkeleton />}
        {!loading && !error && templates.length === 0 && (
          <p className="rounded-lg border border-border bg-secondary/20 px-4 py-6 text-center text-sm text-muted-foreground">
            Nothing published yet.
          </p>
        )}
        {!loading && !error && templates.length > 0 && (
          <Input
            aria-label="Search published templates"
            className="h-9 max-w-sm"
            placeholder="Search title, summary or category"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
        )}
        {!loading && !error && templates.length > 0 && filtered.length === 0 && (
          <p className="text-center text-sm text-muted-foreground">No published templates match.</p>
        )}
        {!loading && !error && templates.length > 0 && (
          <AnimatePresence initial={false}>
            {pg.pageItems.map((t) => (
              <TemplateQueueCard
                key={t.id}
                template={t}
                mode="published"
                expanded={expandedId === t.id}
                onToggleExpand={() => setExpandedId((cur) => (cur === t.id ? null : t.id))}
                onWithdraw={(reason) => handleWithdraw(t.id, reason)}
              />
            ))}
          </AnimatePresence>
        )}
        <PaginationControls
          page={pg.page}
          totalPages={pg.totalPages}
          totalItems={pg.totalItems}
          pageSize={pg.pageSize}
          onPageChange={pg.setPage}
          onPageSizeChange={pg.setPageSize}
          itemLabel="templates"
        />
      </CardContent>
    </Card>
  );
}

export default function CommunityTemplatesPage() {
  const [tab, setTab] = useState("pending");

  return (
    <AdminShell title="Community Templates">
      <PageHint>
        The moderation gate for the community template gallery - review a submitted agent or workflow&rsquo;s full,
        sanitised config before it ever becomes public. Nothing here is auto-approved.
      </PageHint>

      <InfoNote>
        A template is a design snapshot only (system prompt, model/provider choice, tool/MCP names, guardrail settings,
        workflow graph) - it never carries API keys, tenant ids, or PII. Still, it is executable configuration a
        stranger could import into their own tenant, so read it in full before approving.
      </InfoNote>

      <motion.div
        initial={{ opacity: 0, y: 12 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.35, ease: [0.16, 1, 0.3, 1] }}
      >
        <Tabs value={tab} onValueChange={setTab}>
          <TabsList className="max-w-md">
            <TabsTrigger value="pending">Pending review</TabsTrigger>
            <TabsTrigger value="published">Published</TabsTrigger>
          </TabsList>
          <TabsContent value="pending">
            <PendingQueueTab />
          </TabsContent>
          <TabsContent value="published">
            <PublishedTab />
          </TabsContent>
        </Tabs>
      </motion.div>
    </AdminShell>
  );
}
