"use client";

import { createContext, useContext, useEffect, useState } from "react";
import { api } from "../lib/api";
import { hydrateApiCache, prefetchApiGets } from "../lib/api/core";
import { warmPlatformModel } from "../lib/warmPlatformModel";
import { AuthContext } from "./AuthProvider";
import { useUsageQuota } from "./UsageQuota";

// Shared, session-lived shell chrome for the authenticated console —
// tenantSettings, account/avatar, the TOOLS-badge MCP attention count, and
// usage quota. Mounted once at the root layout (same position/pattern as
// SystemHealthProvider), so any page rendered as its child reads it via
// useAppChrome() instead of fetching it itself.
//
// Why this exists: AppShell is invoked per-page (there's no shared layout
// hosting it once — see RouteTransition.tsx's module comment for why), so it
// technically remounts on every single navigation. Before this provider, each
// of these four fetches lived in AppShell's own useEffect(() => {...}, [])
// and therefore re-ran on every click — tenantSettings, account, an MCP-list
// poll and a usage-quota fetch, none of which ever differ between console
// pages. That's 4-5 redundant round-trips to `core` paid on every navigation
// regardless of destination, which is most of why page-to-page transitions
// felt slow (perf audit, 2026-08-01) — RouteTransition's own loading cover
// just held the door shut on that latency instead of causing it.
//
// Gated on `username` (read via useContext(AuthContext) directly, NOT the
// useAuth() hook) rather than an unconditional effect like
// SystemHealthProvider's: unlike the health probe, these four endpoints all
// require a real tenant session and would 401 on every public/marketing page
// load otherwise (this provider is mounted at the root, above the
// authenticated/public route split — see layout.tsx). useAuth() hard-throws
// when there's no AuthContext.Provider above it, which is correct everywhere
// else (a real bug) but wrong here: on a public route, AuthProvider renders
// `children` with NO provider at all (nothing else has ever called
// useAuth() from a public route before this). useContext(AuthContext) never
// throws — it resolves to the `null` default there, same as the real
// provider's own `username: null` for an unauthenticated session — so gating
// on it reproduces AppShell's previous implicit safety (it only ever mounted
// for already-authenticated pages) on every route, public or not.

type TenantSettings = any;
type Account = any;

type AppChrome = {
  tenantSettings: TenantSettings | null;
  account: Account | null;
  avatarBlobUrl: string | null;
  /** Lets AvatarUploadModal push a fresh `account` in immediately post-upload, without a refetch. */
  setAccount: (account: Account) => void;
  mcpAttentionCount: number;
  usageQuota: ReturnType<typeof useUsageQuota>;
};

const DEFAULT: AppChrome = {
  tenantSettings: null,
  account: null,
  avatarBlobUrl: null,
  setAccount: () => {},
  mcpAttentionCount: 0,
  usageQuota: null,
};

const AppChromeContext = createContext<AppChrome>(DEFAULT);

export function AppChromeProvider({ children }: { children: React.ReactNode }) {
  const username = useContext(AuthContext)?.username ?? null;

  // Best-effort, read-only — just enough to show the org name in the topbar
  // (see TenantSettingsController). Never blocks rendering if it fails or
  // hasn't resolved yet. Same never-blocks posture as before, just fetched
  // once per session instead of once per navigation.
  const [tenantSettings, setTenantSettings] = useState<TenantSettings | null>(null);
  useEffect(() => {
    if (!username) return;
    api
      .getTenantSettings()
      .then(setTenantSettings)
      .catch(() => setTenantSettings(null));
  }, [username]);

  // Session boot warm-up (see prefetchApiGets' comment in lib/api/core.ts):
  // the reads nearly every console route's usePageReady gate waits on, fired
  // the moment auth resolves instead of when each page mounts - so the first
  // navigation renders from the SWR cache rather than paying a cold ~500ms
  // round trip per read. This provider is the right place for the same
  // reason the four fetches above live here: it mounts once per session at
  // the root (AppShell remounts every navigation) and the `username` gate
  // keeps the reads from 401ing on public/marketing routes. A user switch
  // re-fires it (username changes), which is also correct - the previous
  // identity's entries were already flushed by the 401 on its last call.
  useEffect(() => {
    if (!username) return;
    // L2 first: seed the in-memory cache from IndexedDB so the prefetch below
    // (and any page that mounts first) is served from last session's data.
    void hydrateApiCache();
    // Wake the platform model now, so the first chat does not pay its cold start (often a minute).
    warmPlatformModel();
    prefetchApiGets([
      "/api/agents",
      "/api/workflows",
      "/api/providers",
      "/api/knowledge/collections",
      // /api/system/status is NOT warmed here: SystemHealthProvider already
      // fetches it on mount (lib/sharedPoll.ts shares that one request).
      "/api/skills",
      "/api/templates",
    ]);
  }, [username]);

  // Profile picture (AccountController.java) — same best-effort posture.
  // `account` carries avatarUrl (a fixed "/api/account/avatar" path, or null
  // if nothing's been uploaded yet); the actual bytes are fetched separately
  // as a blob below since a plain <img src> can't carry the bearer token that
  // endpoint requires (same reasoning as api.downloadKnowledgeDocumentFile).
  const [account, setAccount] = useState<Account | null>(null);
  const [avatarBlobUrl, setAvatarBlobUrl] = useState<string | null>(null);
  useEffect(() => {
    if (!username) return;
    api
      .getAccount()
      .then(setAccount)
      .catch(() => setAccount(null));
  }, [username]);
  useEffect(() => {
    if (!account?.avatarUrl) {
      setAvatarBlobUrl(null);
      return undefined;
    }
    let cancelled = false;
    let objectUrl: string | null = null;
    api
      .getAccountAvatarBlob()
      .then((blob: Blob | null) => {
        if (cancelled || !blob) return;
        objectUrl = URL.createObjectURL(blob);
        setAvatarBlobUrl(objectUrl);
      })
      .catch(() => {
        // Best-effort like every fetch in this file - a failed avatar pull
        // just leaves the initials fallback in place, never an unhandled
        // rejection.
      });
    return () => {
      cancelled = true;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [account?.avatarUrl]);

  // TOOLS nav badge: count of this tenant's enabled MCP servers that need
  // attention. Polls every 30s for the life of the session now, instead of
  // restarting a fresh 30s interval (and re-fetching immediately) on every
  // navigation.
  const [mcpAttentionCount, setMcpAttentionCount] = useState(0);
  useEffect(() => {
    if (!username) return undefined;
    let cancelled = false;
    function poll() {
      api
        .listMcpServers()
        .then((servers: any) => {
          if (cancelled || !Array.isArray(servers)) return;
          const count = servers.filter((s: any) => {
            if (!s?.enabled) return false;
            const status = typeof s?.usageStatus === "string" ? s.usageStatus : null;
            if (status === "UNTESTED" || status === "FAILED") return true;
            if (s?.dynamic && s?.connectionStatus === "ERROR") return true;
            return false;
          }).length;
          setMcpAttentionCount(count);
        })
        .catch(() => {
          // Leave the last-known count in place rather than zeroing it out —
          // a failed probe isn't evidence the problem went away.
        });
    }
    poll();
    const id = window.setInterval(poll, 30000);
    return () => {
      cancelled = true;
      window.clearInterval(id);
    };
  }, [username]);

  // Usage quota. THIS component only mounts once per session (root layout)
  // rather than once per page, which is the perf half of why the fetch lives
  // here — but "root layout" also means "above the public/authenticated route
  // split", so it must be gated on `username` like the three fetches above it.
  // It wasn't: this line read `useUsageQuota()` with no argument until
  // 2026-08-16, which is how a logged-out visitor on /terms, /privacy, /help,
  // /features (etc.) ended up firing an authenticated GET
  // /api/tenant/usage-quota — and re-firing it every 30s — on pure marketing
  // and legal pages, 401ing every time. The header comment at the top of this
  // file already claimed the `username` gate covered every fetch here; it now
  // actually does. The gate itself lives inside the hook (see UsageQuota.tsx)
  // so a future call site can't silently drop it again.
  const usageQuota = useUsageQuota(username);

  const value: AppChrome = {
    tenantSettings,
    account,
    avatarBlobUrl,
    setAccount,
    mcpAttentionCount,
    usageQuota,
  };

  return <AppChromeContext.Provider value={value}>{children}</AppChromeContext.Provider>;
}

export function useAppChrome(): AppChrome {
  return useContext(AppChromeContext);
}
