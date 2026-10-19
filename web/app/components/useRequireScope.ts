"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "./AuthProvider";

/**
 * Page-level guard for the granular-RBAC admin screens (2026-09-23).
 *
 * Replaces the old `isAdmin`-boolean guards on /admin/* pages: a caller
 * whose effective permissions (GET /api/me) lack `permission` is redirected
 * back to "/" and the page renders nothing. This is a UX gate ONLY - the
 * real enforcement is SecurityConfig's PERM_* matcher on the endpoints the
 * page calls, so a forged answer here can never grant anything.
 *
 * Returns whether the caller may render. Pages that ALSO use the flag for
 * load effects should gate those on the returned value, same shape as the
 * old `if (!isAdmin) return` pattern they replace.
 */
export function useRequireScope(permission: string): boolean {
  const { hasPermission, permissionsLoaded } = useAuth();
  const router = useRouter();
  const allowed = hasPermission(permission);
  useEffect(() => {
    // Only act once /api/me has answered: on a hard load the effect mounts
    // while the fetch is still in flight (permissions === []), and an early
    // replace("/") was bouncing every scoped /admin/* page before the real
    // answer could arrive (2026-09-23 prod regression).
    if (permissionsLoaded && !allowed) router.replace("/");
  }, [allowed, permissionsLoaded, router]);
  return permissionsLoaded && allowed;
}
