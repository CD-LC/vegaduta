"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";

// Direct feedback (#554): "i want users and approvals section to be moved
// to platform maintenance" - the real AdminUsersPanel renders on
// /admin/maintenance for full admins, and on /admin/access (granular RBAC,
// 2026-09-23) for delegated MANAGE_USERS holders who can't reach
// maintenance at all. This route is kept as a redirect (rather than
// deleted outright) so any existing bookmark, browser-history entry, or
// external link to /admin/users still lands somewhere real instead of a
// 404. /admin/access is the better target for both audiences: admins have
// the panel there too, scoped users get it only there.
export default function AdminUsersRedirectPage(): null {
  const router = useRouter();
  useEffect(() => {
    router.replace("/admin/access");
  }, [router]);
  return null;
}
