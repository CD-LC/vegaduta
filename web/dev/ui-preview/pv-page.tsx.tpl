"use client";
// LOCAL PREVIEW ONLY (never committed): /ui-kit/pv/<route> renders the real page for <route> with a fake admin user and a mocked API.
import { useEffect, useMemo, useState, type ComponentType } from "react";
import { useParams } from "next/navigation";
import { AuthContext } from "../../../components/AuthProvider";
import { auth } from "../../../lib/auth";
import { LOADERS, ROUTES } from "../../pv-lib/loaders";
import { installMock, misses } from "../../pv-lib/mock";

const PERMS = ["MANAGE_USERS","MANAGE_BILLING","MANAGE_INTEGRATIONS","MANAGE_TENANT_SETTINGS","MANAGE_WORKFLOWS_ADMIN","VIEW_AUDIT_LOG","MANAGE_APPROVALS","MANAGE_DATA_GOVERNANCE","MANAGE_CHANNELS","MANAGE_AGENTS","MANAGE_GUARDRAILS","MANAGE_TOOLS","MANAGE_TEMPLATES","VIEW_USAGE","MANAGE_LEADS"];

if (typeof window !== "undefined") { installMock(); }

export default function Preview() {
  const params = useParams() as { slug?: string[] };
  const route = "/" + (params.slug || []).join("/");
  const [Page, setPage] = useState<ComponentType | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [ready, setReady] = useState(false);
  useEffect(() => {
    installMock();
    (auth as any).getToken = async () => "preview-token";
    setReady(true);
  }, []);
  useEffect(() => {
    setPage(null); setErr(null);
    const load = LOADERS[route];
    if (!load) return;
    load().then((m) => setPage(() => m.default)).catch((e) => setErr(String(e?.message || e)));
  }, [route]);
  const ctx = useMemo(() => ({
    username: "preview@example.com", firstName: "Preview", roles: ["tenant-admin", "platform-admin"], isAdmin: true, isPlatformAdmin: true,
    permissions: PERMS, permissionsLoaded: true, hasPermission: () => true, hasAnyPermission: () => true, hasAdminScope: true,
    logout: () => undefined, accountUrl: () => "#",
  }), []);
  if (!params.slug) {
    return (<div style={{ padding: 24, fontFamily: "sans-serif" }}><h1>Preview index</h1><p>{ROUTES.length} routes</p><ul style={{ columns: 3 }}>{ROUTES.map((r) => <li key={r}><a href={`/ui-kit/pv${r}`}>{r}</a></li>)}</ul></div>);
  }
  if (!ready) return null;
  if (!LOADERS[route]) return <p style={{ padding: 24 }}>No page for {route}</p>;
  if (err) return <pre style={{ padding: 24, color: "crimson" }}>{err}</pre>;
  if (!Page) return <p style={{ padding: 24 }}>Loading {route}...</p>;
  return (<AuthContext.Provider value={ctx as any}><Page /><span id="pv-misses" hidden>{misses.join("\n")}</span></AuthContext.Provider>);
}
