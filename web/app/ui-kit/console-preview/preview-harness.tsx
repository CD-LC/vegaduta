"use client";

import { notFound } from "next/navigation";

// Shared harness for /ui-kit/console-preview/* - renders a REAL console
// route component (its default export, AppShell included) against the
// sample-data fetch mock, so every authenticated screen can be looked at
// with neither Keycloak nor core running. Same mechanism as
// ../dashboard-preview/page.tsx: installMockApi() wraps window.fetch at
// module-evaluation time, and a hand-made AuthContext.Provider stands in for
// the session (useAuth() throws on a public route otherwise). No tokens are
// faked - auth.getToken() keeps resolving null.
import { installMockApi } from "../dashboard-preview/mock-api";

if (typeof window !== "undefined") installMockApi();

import type { Context, ReactNode } from "react";
import { AuthContext } from "../../components/AuthProvider";
import { useTranslations } from "../../lib/i18n";

const PreviewAuthContext = AuthContext as unknown as Context<Record<string, unknown> | null>;

// Mirrors the real provider's value shape (see AuthProvider.tsx's return).
// `admin: true` flips isAdmin/hasAdminScope for the /admin/* screens, which
// redirect non-admins to "/" (and DashboardHome does the reverse).
export function buildPreviewAuth(admin: boolean) {
  return {
    username: admin ? "priya.admin.preview" : "priya.preview",
    firstName: "Priya",
    roles: admin ? ["tenant-admin", "tenant-user"] : ["tenant-user"],
    isAdmin: admin,
    isPlatformAdmin: false,
    permissions: [] as string[],
    permissionsLoaded: true,
    hasPermission: () => true,
    hasAnyPermission: () => true,
    hasAdminScope: admin,
    logout: () => {},
    accountUrl: (): string | null => null,
  };
}

function PreviewPill({ label }: { label: string }) {
  const tHC = useTranslations("hcUikitConsolepreviewPreviewharness");
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
      {tHC("s2")} {label} {tHC("s1")}
    </div>
  );
}

export default function ConsolePreview({
  label,
  admin = false,
  children,
}: {
  label: string;
  admin?: boolean;
  children: ReactNode;
}) {
  // Sample-data preview: never reachable in a production build.
  if (process.env.NODE_ENV === "production") notFound();
  return (
    <PreviewAuthContext.Provider value={buildPreviewAuth(admin)}>
      {children}
      <PreviewPill label={label} />
    </PreviewAuthContext.Provider>
  );
}
