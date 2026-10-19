import type { LucideIcon } from "lucide-react";

// Shapes of the primary-navigation blueprint (see navSections.ts).

export interface NavItem {
  href: string;
  labelKey: string;
  icon: LucideIcon;
  exact: boolean;
  // Static blueprint entries are always `null`; the one live override
  // (TOOLS, see the .map() below that computes `navSections`) sets `badge`
  // to the numeric mcpAttentionCount and `badgeVariant` to "attention".
  badge: number | null;
  badgeVariant: "attention" | null;
  adminHidden: boolean;
  adminOnly: boolean;
  // Granular RBAC (2026-09-23): when set, this row shows only to a caller
  // whose effective permissions (GET /api/me → useAuth().hasPermission)
  // include the named scope - the same PERM_* authority SecurityConfig
  // requires on the backing endpoints. adminOnly still means "the
  // tenant-admin ROLE specifically" and is reserved for the two truly
  // role-shaped screens (/admin overview, /admin/maintenance); everything
  // else is scope-gated so a delegated user sees exactly the admin surfaces
  // their grants cover, and a tenant-admin sees only what their own grants
  // still cover (grants bind admins too - see Permission.java).
  perm?: string;
}

export interface NavSection {
  labelKey: string | null;
  cat: string;
  adminOnly: boolean;
  items: NavItem[];
  // Hub-rail glyph (2026-09-23 hub redesign): the icon-only rail shows one
  // button per labeled section; hovering/focusing it slides out the flyout
  // panel of that group's destinations. Sections without one (the lone HOME
  // row) stay direct links.
  icon?: LucideIcon;
}
