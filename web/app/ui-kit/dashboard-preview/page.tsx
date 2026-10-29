"use client";

import { notFound } from "next/navigation";

// /ui-kit/dashboard-preview - the REAL signed-in console (AppShell wrapping
// DashboardHome, both untouched) rendered against sample data, so a designer
// can look at the dashboard without Keycloak or core running.
//
// How it works:
//   * /ui-kit is in PUBLIC_ROUTES, so AuthProvider renders children with no
//     session and NO AuthContext. useAuth() throws without one, so this page
//     supplies an anonymous-but-named context of its own (below). No tokens
//     are faked - auth.getToken() keeps resolving null.
//   * installMockApi() (./mock-api.ts) wraps window.fetch at module-evaluation
//     time, before any effect runs, so the root SystemHealthProvider probe and
//     the dashboard's first fetches all hit the sample data.
//   * DashboardHome already renders <AppShell eyebrow=...> around itself, so
//     it is mounted directly here - wrapping it again would nest two shells.
import { installMockApi } from "./mock-api";

if (typeof window !== "undefined") installMockApi();

import type { ReactNode } from "react";
import type { Context } from "react";
import { AuthContext } from "../../components/AuthProvider";
import DashboardHome from "../../components/dashboard/DashboardHome";
import { useTranslations } from "../../lib/i18n";

// AuthContext is createContext(null) in AuthProvider.tsx (untyped value); the
// real provider hands out this exact shape (see its return statement).
const PreviewAuthContext = AuthContext as unknown as Context<Record<string, unknown> | null>;

const previewAuth = {
  username: "priya.preview",
  firstName: "Priya",
  roles: ["tenant-user"],
  isAdmin: false, // must stay false - DashboardHome redirects admins to /admin
  isPlatformAdmin: false,
  permissions: [] as string[],
  permissionsLoaded: true,
  // Show every member nav item; the real gate is server-side anyway.
  hasPermission: () => true,
  hasAnyPermission: () => true,
  hasAdminScope: false,
  logout: () => {},
  accountUrl: (): string | null => null,
};

function PreviewPill() {
    const tHC = useTranslations("hcUikitDashboardpreviewPage");
  return (
    <div
      role="status"
      aria-live="polite"
      style={{
        position: "fixed",
        right: 16,
        bottom: 16,
        zIndex: 2147483000,
        padding: "6px 12px",
        borderRadius: 9999,
        fontSize: 12,
        lineHeight: "16px",
        fontWeight: 600,
        letterSpacing: "0.01em",
        color: "#fff",
        background: "rgba(180, 83, 9, 0.95)",
        boxShadow: "0 4px 14px rgba(0,0,0,0.25)",
        pointerEvents: "none",
        fontFamily: "ui-sans-serif, system-ui, sans-serif",
      }}
    >
      {tHC("hcUikitDashboardpreviewPage.s1")}
    </div>
  );
}

function PreviewAuthProvider({ children }: { children: ReactNode }) {
  return <PreviewAuthContext.Provider value={previewAuth}>{children}</PreviewAuthContext.Provider>;
}

export default function DashboardPreviewPage() {
  // Sample-data preview: never reachable in a production build.
  if (process.env.NODE_ENV === "production") notFound();
  return (
    <PreviewAuthProvider>
      <DashboardHome />
      <PreviewPill />
    </PreviewAuthProvider>
  );
}
