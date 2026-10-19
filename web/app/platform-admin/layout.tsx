import PlatformAdminGate from "./components/PlatformAdminGate";
import { ConfirmProvider } from "./components/admin/ConfirmDialog";

// Platform-admin section layout (2026 console merge: the standalone
// web-admin/ Next.js app moved in here, so there is ONE console).
//
// What this section deliberately does NOT carry over from the old
// web-admin/app/layout.tsx, and why:
//   - <html>/<body>, fonts (Geist/Geist Mono/Yatra One), MotionConfig,
//     ToastHost, the runtime __ENV script, CSP nonce: all already provided
//     by web/app/layout.tsx, the root layout this nests under.
//   - i18n: the tenant console is 30-locale; this section stays
//     ENGLISH-ONLY, exactly as web-admin always was (it never shipped a
//     messages/ bundle). Admin strings are inline English in the page
//     components. If this section is ever localized, port the strings into
//     web/messages/ and web/app/lib/i18n-route-namespaces.generated.ts.
//   - Its own keycloak-js client (agentic-ai-platform-admin): replaced by
//     web/'s shared auth.ts - same realm, same session, same
//     PLATFORM_ADMIN_ROLE check (now via the root AuthProvider context in
//     PlatformAdminGate).
//
// Auth gating: the root AuthProvider handles Keycloak init and the
// unauthenticated -> sign-in redirect for /platform-admin/*;
// PlatformAdminGate below owns the authenticated-but-not-platform-admin
// "Access restricted" screen. Server-side enforcement is unchanged: core's
// SecurityConfig maps /api/platform/** -> ROLE_platform-admin, and
// web/proxy.js gates this section's privileged route handlers
// (/platform-admin/api/academy/**) through lib/serverAuth.ts.

// Every route here is an authenticated admin screen - never let Next.js
// prerender or cache them (the standalone app carried the same directive on
// its root layout after the shared-cache incident it documents).
export const dynamic = "force-dynamic";

export default function PlatformAdminLayout({ children }: { children: React.ReactNode }) {
  return (
    <ConfirmProvider>
      <PlatformAdminGate>{children}</PlatformAdminGate>
    </ConfirmProvider>
  );
}
