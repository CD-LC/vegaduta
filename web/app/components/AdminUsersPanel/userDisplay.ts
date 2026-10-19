import type { Dispatch, SetStateAction } from "react";

// TS mirror of AdminUserResponse.java (core/admin) - id/username/enabled/
// roles are always present on every row this panel renders (member column,
// status pill, role-edit checkboxes); email/firstName/lastName/permissions
// are optional the same way AdminUsersPanel's JSX already treats them (`u.email`
// rendered directly, `u.firstName || u.username` fallback in initials()).
export interface AdminUser {
  id: string;
  username: string;
  email?: string | null;
  firstName?: string | null;
  lastName?: string | null;
  enabled: boolean;
  roles: string[];
  permissions?: string[];
  invitePending?: boolean;
  inviteLink?: string | null;
}
// Purely decorative per-row avatar tint cycle (matches the "distinct color
// per row, cycles by position, no real per-user color field" pattern
// KnowledgeCollectionsPanel's DOT_COLORS already established) - not tied to
// role, just gives the table visual rhythm the way the mockup's varied
// avatar gradients do.
export const AVATAR_GRADIENTS = [
  "linear-gradient(135deg, var(--ax-violet), var(--ax-cyan))",
  "linear-gradient(135deg, var(--ax-cyan), var(--ax-green))",
  "linear-gradient(135deg, var(--ax-amber), var(--ax-red))",
  "linear-gradient(135deg, var(--ax-green), var(--ax-cyan))",
  "linear-gradient(135deg, var(--ax-red), var(--ax-violet))",
];

export function initials(u: AdminUser) {
  const first = (u.firstName || u.username || "?").trim();
  const last = (u.lastName || "").trim();
  const a = first[0] || "?";
  const b = last[0] || first[1] || "";
  return (a + b).toUpperCase();
}

export function displayName(u: AdminUser) {
  const full = `${u.firstName || ""} ${u.lastName || ""}`.trim();
  return full || u.username;
}

export function rolePillClass(role: string) {
  if (role === "tenant-admin") return "ax-mnt-role-pill ax-mnt-role-pill-owner";
  if (role === "agent-builder") return "ax-mnt-role-pill ax-mnt-role-pill-admin";
  return "ax-mnt-role-pill";
}

export function toggleInSet<T>(setState: Dispatch<SetStateAction<Set<T>>>, value: T) {
  setState((prev) => {
    const next = new Set(prev);
    if (next.has(value)) next.delete(value);
    else next.add(value);
    return next;
  });
}
