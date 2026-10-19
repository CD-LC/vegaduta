"use client";

import { useEffect, useMemo, useState, type ReactNode } from "react";
import { ADMIN_ROLE, PLATFORM_ADMIN_ROLE, auth } from "../../lib/auth";
import { api } from "../../lib/api";
import { useTranslations } from "../../lib/i18n";
import { AuthContext } from "../AuthProvider";
import AppShell from "../AppShell";
import TemplatesPublicChrome from "../templates/TemplatesPublicChrome";

/**
 * Chrome for the "library" routes (/templates, /templates/mine, /templates/[id], /directory).
 *
 * These routes are PUBLIC (lib/publicRoutes.ts: crawlers and logged-out visitors must read them), so AuthProvider
 * gives them no AuthContext and AppShell would throw. This wrapper keeps both audiences right:
 *  - first paint, crawlers and signed-out visitors: the standalone public chrome (server-rendered children intact);
 *  - once auth.init() reports a Keycloak session: the same children inside the console shell (rail, topbar, search),
 *    with an AuthContext built from the same auth getters AuthProvider uses, so a signed-in member does not feel
 *    they left the product when they open the library.
 * It never forces a login: auth.init() uses check-sso, same as TemplatesPublicChrome.
 */
export default function LibraryChrome({
  children,
  footer,
  eyebrowKey,
}: {
  children: ReactNode;
  /** Footer for the public chrome only (the console shell has its own). */
  footer?: ReactNode;
  /** Key in the "libraryPages" namespace for the console topbar eyebrow. */
  eyebrowKey: "eyebrowDirectory" | "eyebrowTemplates" | "eyebrowMine";
}) {
  const [authed, setAuthed] = useState(false);
  useEffect(() => {
    let cancelled = false;
    void auth.init().then((kc) => {
      if (!cancelled && kc) setAuthed(true);
    });
    return () => {
      cancelled = true;
    };
  }, []);

  if (!authed) return <TemplatesPublicChrome footer={footer}>{children}</TemplatesPublicChrome>;
  return <ConsoleFrame eyebrowKey={eyebrowKey}>{children}</ConsoleFrame>;
}

function ConsoleFrame({ children, eyebrowKey }: { children: ReactNode; eyebrowKey: string }) {
  const t = useTranslations("libraryPages");
  const [permissions, setPermissions] = useState<string[]>([]);
  const [permissionsLoaded, setPermissionsLoaded] = useState(false);

  useEffect(() => {
    let cancelled = false;
    api
      .getMe()
      .then((me: any) => {
        if (!cancelled) setPermissions(me?.permissions ?? []);
      })
      .catch(() => {
        /* fail closed: the server still enforces every permission */
      })
      .finally(() => {
        if (!cancelled) setPermissionsLoaded(true);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const value = useMemo(() => {
    const roles = auth.getRoles();
    const isAdmin = roles.includes(ADMIN_ROLE);
    return {
      username: auth.getUsername(),
      firstName: auth.getFirstName(),
      roles,
      isAdmin,
      isPlatformAdmin: roles.includes(PLATFORM_ADMIN_ROLE),
      permissions,
      permissionsLoaded,
      hasPermission: (name: string) => permissions.includes(name),
      hasAnyPermission: (...names: string[]) => names.some((n) => permissions.includes(n)),
      hasAdminScope: isAdmin || permissions.length > 0,
      logout: auth.logout,
      accountUrl: auth.accountUrl,
    };
  }, [permissions, permissionsLoaded]);

  return (
    <AuthContext.Provider value={value as any}>
      <AppShell eyebrow={t(eyebrowKey)}>{children}</AppShell>
    </AuthContext.Provider>
  );
}
