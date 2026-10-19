"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useTranslations } from "../../lib/i18n";
import { useAuth } from "../../components/AuthProvider";
import { useRequireScope } from "../../components/useRequireScope";
import { usePageReady } from "../../components/RouteTransition";
import AppShell from "../../components/AppShell";
import PageHeader from "../../components/ax/PageHeader";
import AdminUsersPanel, { type AdminUser } from "../../components/AdminUsersPanel";
import AdminAccessMatrix from "../../components/AdminAccessMatrix";
import { api } from "../../lib/api";

// Team access (/admin/access) - the scoped user-administration home of the
// granular-RBAC pass (2026-09-23). /admin/maintenance still hosts the same
// AdminUsersPanel for full tenant-admins; THIS page is what a delegated
// MANAGE_USERS holder gets - the member/role/permission editor plus the
// users-x-scopes access matrix, nothing else (no telemetry deck, no data
// lifecycle). Guarded on the MANAGE_USERS scope, which is exactly what
// SecurityConfig requires on the /api/admin/users/** endpoints it calls.
export default function AdminAccessPage() {
  const t = useTranslations("adminAccessPage");
  const { username } = useAuth();
  const allowed = useRequireScope("MANAGE_USERS");
  const [users, setUsers] = useState<AdminUser[]>([]);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [loaded, setLoaded] = useState(false);
  const requestIdRef = useRef(0);

  const refresh = useCallback(async () => {
    const requestId = ++requestIdRef.current;
    try {
      const data = await api.listAdminUsers();
      if (requestId !== requestIdRef.current) return;
      setUsers(data);
      setLoadError(null);
    } catch (err) {
      if (requestId !== requestIdRef.current) return;
      setLoadError((err as Error).message);
    } finally {
      if (requestId === requestIdRef.current) setLoaded(true);
    }
  }, []);

  useEffect(() => {
    if (allowed) void refresh();
  }, [allowed, refresh]);

  usePageReady(loaded || !allowed);

  if (!allowed) return null;

  const handleUserCreated = (user: AdminUser) => setUsers((prev) => [...prev, user]);
  // The matrix needs the caller's own Keycloak id to render the self row
  // read-only; the users list carries it as `id` but the caller's own row
  // is found by username (the JWT subject IS the Keycloak user id - see
  // TenantContextFilter - so this is the same identity either way).
  const selfId = users.find((u) => u.username === username)?.id ?? null;

  return (
    <AppShell eyebrow={t("pageEyebrow")}>
      <PageHeader title={t("pageTitle")} />
      {loadError && <div className="ax-kb-error">{loadError}</div>}
      <AdminUsersPanel users={users} onChanged={refresh} onCreated={handleUserCreated} />
      <AdminAccessMatrix users={users} currentUserId={selfId} onChanged={refresh} />
    </AppShell>
  );
}
