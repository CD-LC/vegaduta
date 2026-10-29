"use client";

import { useCallback, useEffect, useState } from "react";
import { Search, RefreshCw, UserCog, MailCheck, LogOut, Eye } from "lucide-react";
import AdminShell from "../components/AdminShell";
import { PageHint, SectionCard, SwitchToggle } from "../components/sections/common";
import { Badge } from "../components/ui/badge";
import { Button } from "../components/ui/button";
import { Input } from "../components/ui/input";
import { Alert, AlertDescription } from "../components/ui/alert";
import { Skeleton } from "../components/ui/skeleton";
import { api } from "../lib/api";
import { fetchPaged } from "../lib/pagedFetch";
import PaginationControls from "../components/PaginationControls";
import { pageForSizeChange } from "../lib/usePagination";
import { opsApi } from "../lib/adminApi/operations";
import { toast } from "../lib/toastBus";
import { UNAVAILABLE, formatDay, formatExact, formatRelative } from "../lib/platformFormat";
import ViewAsDialog, { type ViewAsTarget } from "../components/governance/ViewAsDialog";
import { useConfirm } from "../components/admin/ConfirmDialog";
import { AdminEmptyState, AdminErrorState } from "../components/admin/AdminKit";
import { Select } from "../../components/ui/select";
import { Checkbox } from "../../components/ui/checkbox";

// Cross-tenant user directory (PlatformUserController, /api/platform/users).
// Before this screen an operator had to open the Keycloak console to find,
// suspend or repair a user. Roles here are the tenant-assignable set only -
// the server refuses to grant platform-admin, and refuses to touch platform
// operators or the signed-in operator's own account at all.

const ASSIGNABLE_ROLES = [
  { id: "tenant-admin", label: "Tenant admin" },
  { id: "agent-builder", label: "Agent builder" },
  { id: "delegated-user", label: "Delegated user" },
  { id: "end-customer", label: "End customer" },
];

function UserDetailPanel({ userId, onChanged }: { userId: string; onChanged: () => void }) {
  const askConfirm = useConfirm();
  const [detail, setDetail] = useState<any>(null);
  const [error, setError] = useState<string | null>(null);
  const [roles, setRoles] = useState<string[]>([]);
  const [busy, setBusy] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const d = await api.getPlatformUser(userId);
      setDetail(d);
      setRoles(d.roles || []);
      setError(null);
    } catch (err: any) {
      setDetail(null);
      setError(err?.message || "Could not load this user.");
    }
  }, [userId]);

  useEffect(() => {
    setDetail(null);
    void load();
  }, [load]);

  async function run(name: string, fn: () => Promise<any>, ok: string) {
    setBusy(name);
    try {
      await fn();
      toast.success(ok);
      await load();
      onChanged();
    } catch {
      // toJson already toasted the reason.
    } finally {
      setBusy(null);
    }
  }

  if (error) {
    return (
      <Alert variant="destructive">
        <AlertDescription>{error}</AlertDescription>
      </Alert>
    );
  }
  if (!detail) return <Skeleton className="h-40 w-full" />;

  const u = detail.user;
  // Roles outside the assignable set are shown so the operator can see what a save
  // will remove: the server replaces the whole realm-role set (default-roles-* is exempt).
  const otherRoles = (detail.roles || []).filter(
    (r: string) => !ASSIGNABLE_ROLES.some((a) => a.id === r) && !r.startsWith("default-roles-")
  );
  const rolesDirty =
    JSON.stringify([...roles].sort()) !==
    JSON.stringify((detail.roles || []).filter((r: string) => ASSIGNABLE_ROLES.some((a) => a.id === r)).sort());
  const isOperator = (detail.roles || []).includes("platform-admin");

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2 text-sm font-semibold">
            {u.username}
            {!u.enabled && <Badge variant="destructive">Suspended</Badge>}
            {isOperator && <Badge variant="accent">Platform operator</Badge>}
          </div>
          <p className="text-xs text-muted-foreground">
            {u.email || "no email"} · tenant <code>{u.tenantId || "none"}</code>
            {u.emailVerified ? "" : " · email not verified"}
          </p>
        </div>
        <div className="flex items-center gap-2 text-sm">
          <span>Can sign in</span>
          <SwitchToggle
            on={u.enabled}
            label={`Sign-in enabled for ${u.username}`}
            disabled={busy != null || isOperator}
            onClick={async () => {
              if (
                u.enabled &&
                !(await askConfirm(`Suspend ${u.username}? They will be unable to sign in until restored.`))
              )
                return;
              void run(
                "enabled",
                () => api.setPlatformUserEnabled(u.id, !u.enabled),
                u.enabled ? "User suspended." : "User restored."
              );
            }}
          />
        </div>
      </div>

      {isOperator && (
        <Alert>
          <AlertDescription>
            Platform operators are managed in the Keycloak admin console. This screen only lets you view them.
          </AlertDescription>
        </Alert>
      )}

      <div className="space-y-2">
        <p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">Roles</p>
        <div className="flex flex-wrap gap-3">
          {ASSIGNABLE_ROLES.map((r) => (
            <label key={r.id} className="flex items-center gap-2 text-sm">
              <Checkbox
                checked={roles.includes(r.id)}
                disabled={isOperator || busy != null}
                onCheckedChange={(v) => setRoles(v ? [...roles, r.id] : roles.filter((x) => x !== r.id))}
              />
              {r.label}
            </label>
          ))}
        </div>
        {otherRoles.length > 0 && (
          <p className="text-[11px] text-[color:var(--state-warning)]">
            Also holds: {otherRoles.join(", ")}. Saving sets the roles to exactly the ticked boxes, so these will be
            removed (Keycloak&apos;s default-roles-* is always kept).
          </p>
        )}
        <Button
          size="sm"
          disabled={!rolesDirty || busy != null || isOperator}
          onClick={() => run("roles", () => api.setPlatformUserRoles(u.id, roles), "Roles updated.")}
        >
          {busy === "roles" ? "Saving..." : "Save roles"}
        </Button>
      </div>

      <div className="space-y-1">
        <p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">Granted permissions</p>
        {detail.permissions.length === 0 ? (
          <p className="text-xs text-muted-foreground">None stored.</p>
        ) : (
          <div className="flex flex-wrap gap-1.5">
            {detail.permissions.map((p: string) => (
              <Badge key={p} variant="outline">
                {p}
              </Badge>
            ))}
          </div>
        )}
      </div>

      <div className="flex flex-wrap gap-2">
        <Button
          size="sm"
          variant="outline"
          disabled={busy != null || isOperator || !u.email}
          onClick={() =>
            run("reset", () => api.sendPlatformUserPasswordReset(u.id), `Password-reset email sent to ${u.email}.`)
          }
        >
          <MailCheck /> Send password-reset email
        </Button>
      </div>
    </div>
  );
}

export default function PlatformUsersPage() {
  const askConfirm = useConfirm();
  const [q, setQ] = useState("");
  // Tenant filter: pick from the roster, or paste a tenant UUID / schema name
  // (the server accepts either). A pasted value wins over the dropdown.
  const [tenantPick, setTenantPick] = useState("");
  const [tenantId, setTenantId] = useState("");
  const [tenantOptions, setTenantOptions] = useState<{ tenantId: string; name: string; status?: string }[]>([]);
  const [applied, setApplied] = useState({ q: "", tenantId: "" });
  const [data, setData] = useState<any>(null);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(25);
  const [hasMore, setHasMore] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [loggingOut, setLoggingOut] = useState<string | null>(null);
  const [viewAsTarget, setViewAsTarget] = useState<ViewAsTarget | null>(null);

  // Ends every Keycloak session of one user (POST /users/{id}/logout). Their
  // current access token stays valid until it expires (minutes); the refresh
  // is what stops working, so the next silent renewal sends them to sign-in.
  async function forceLogout(u: any) {
    if (!(await askConfirm(`Force-logout ${u.username}? Every session they have is ended; they must sign in again.`)))
      return;
    setLoggingOut(u.id);
    try {
      await opsApi.logoutUser(u.id);
      toast.success(`${u.username} was signed out everywhere.`);
    } catch {
      // toJson already toasted the reason.
    } finally {
      setLoggingOut(null);
    }
  }

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const qs = new URLSearchParams({ limit: String(pageSize), offset: String((page - 1) * pageSize) });
      if (applied.q) qs.set("q", applied.q);
      if (applied.tenantId) qs.set("tenantId", applied.tenantId);
      const res = await fetchPaged<any>(`/api/platform/users?${qs}`);
      setData({ ...res.body, total: res.total ?? res.body?.total });
      setHasMore(res.hasMore);
      setError(null);
    } catch (err: any) {
      setError(err?.message || "Could not load users.");
    } finally {
      setLoading(false);
    }
  }, [applied, page, pageSize]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    // Dropdown options only - on failure the paste box still works.
    api
      .getPlatformTenants()
      .then((rows: any) =>
        setTenantOptions(
          (Array.isArray(rows) ? rows : []).slice().sort((a: any, b: any) => (a.name || "").localeCompare(b.name || ""))
        )
      )
      .catch(() => setTenantOptions([]));
  }, []);

  function submit(e: any) {
    e.preventDefault();
    setPage(1);
    setApplied({ q: q.trim(), tenantId: tenantId.trim() || tenantPick });
  }

  const users: any[] = data?.users || [];

  return (
    <AdminShell title="Platform Users">
      <PageHint>
        Every user across every tenant. Search by name or email, narrow to one tenant, then suspend, restore, re-role or
        send a password reset. Platform operators and your own account are read-only here by design.
      </PageHint>

      <SectionCard
        title="Directory"
        subtitle={
          data
            ? `${data.total} match${data.total === 1 ? "" : "es"}${data.total > users.length ? `, showing ${users.length}` : ""}`
            : undefined
        }
        icon={UserCog}
        actions={
          <form onSubmit={submit} className="flex flex-wrap items-center gap-2">
            <Input
              aria-label="Search users"
              className="h-9 w-56"
              placeholder="Name or email"
              value={q}
              onChange={(e) => setQ(e.target.value)}
            />
            <Select
              aria-label="Tenant filter"
              className="max-w-56"
              value={tenantPick}
              onValueChange={(v) => {
                setTenantPick(v);
                setTenantId("");
                setPage(1);
                setApplied({ q: q.trim(), tenantId: v });
              }}
              options={[
                { value: "", label: "All tenants" },
                ...tenantOptions.map((t) => ({
                  value: t.tenantId,
                  label: `${t.name}${t.status && t.status !== "ACTIVE" ? ` (${t.status})` : ""}`,
                })),
              ]}
            />
            <Input
              aria-label="Tenant id or schema"
              className="h-9 w-56"
              placeholder="or paste tenant UUID / schema"
              value={tenantId}
              onChange={(e) => setTenantId(e.target.value)}
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
        {loading && !data && <Skeleton className="h-24 w-full" />}
        {error && <AdminErrorState message={error} onRetry={() => void load()} />}
        {!loading && !error && users.length === 0 && (
          <AdminEmptyState title="No users match" description="Adjust the search or filters and try again." />
        )}
        {users.map((u) => (
          <div
            key={u.id}
            className={`flex w-full flex-wrap items-stretch gap-2 rounded-lg border text-sm transition-colors ${
              selected === u.id ? "border-primary/50 bg-accent/50" : "border-border"
            }`}
          >
            <button
              type="button"
              onClick={() => setSelected(selected === u.id ? null : u.id)}
              aria-expanded={selected === u.id}
              className="flex min-w-0 flex-1 flex-wrap items-center justify-between gap-2 rounded-lg border-0 bg-transparent p-3 text-start hover:bg-accent"
            >
              <span className="min-w-0">
                <span className="font-medium">{u.username}</span>
                <span className="ms-2 text-xs text-muted-foreground">{u.email}</span>
                <span className="mt-0.5 block text-[11px] text-muted-foreground">
                  Created <span title={formatExact(u.createdAt)}>{formatDay(u.createdAt)}</span> · Last seen{" "}
                  <span title={formatExact(u.lastSeenAt)}>
                    {u.lastSeenAt ? formatRelative(u.lastSeenAt) : UNAVAILABLE}
                  </span>
                </span>
              </span>
              <span className="flex items-center gap-2 text-xs text-muted-foreground">
                {u.tenantName ? (
                  <span title={u.tenantId || undefined}>{u.tenantName}</span>
                ) : (
                  <code>{u.tenantId ? u.tenantId.slice(0, 8) : "no tenant"}</code>
                )}
                {!u.enabled && <Badge variant="destructive">Suspended</Badge>}
              </span>
            </button>
            <div className="flex items-center gap-2 pe-3">
              {u.tenantId && (
                <Button
                  type="button"
                  size="sm"
                  variant="outline"
                  onClick={() =>
                    setViewAsTarget({
                      tenantId: u.tenantId,
                      userId: u.id,
                      label: u.email || u.username,
                      tenantName: u.tenantName,
                    })
                  }
                  title="Open the tenant app as this user, read-only (audited)"
                >
                  <Eye /> View as (read-only)
                </Button>
              )}
              <Button
                type="button"
                size="sm"
                variant="outline"
                disabled={loggingOut != null}
                onClick={() => void forceLogout(u)}
                title="End every session this user has"
              >
                <LogOut /> {loggingOut === u.id ? "Signing out..." : "Force logout"}
              </Button>
            </div>
          </div>
        ))}
        <PaginationControls
          page={page}
          pageSize={pageSize}
          totalItems={typeof data?.total === "number" ? data.total : undefined}
          hasMore={hasMore}
          pageItemCount={users.length}
          loading={loading}
          itemLabel="users"
          onPageChange={setPage}
          onPageSizeChange={(n) => {
            setPage(pageForSizeChange(page, pageSize, n));
            setPageSize(n);
          }}
        />
      </SectionCard>

      <ViewAsDialog target={viewAsTarget} onClose={() => setViewAsTarget(null)} />

      {selected && (
        <SectionCard title="User" icon={UserCog}>
          <UserDetailPanel userId={selected} onChanged={() => void load()} />
        </SectionCard>
      )}
    </AdminShell>
  );
}
