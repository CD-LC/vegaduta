import { Plus } from "lucide-react";
import { useTranslations } from "../../lib/i18n";

// Realm roles as defined in deploy/keycloak/agentic-ai-realm.json - kept in
// sync by hand (same "no dynamic role discovery yet" tradeoff the rest of
// this admin portal makes; see KeycloakAdminClient.java's own known-
// limitations note about not caching tokens - this is the frontend
// equivalent of "good enough for admin-panel usage volumes, revisit later").
// Ordered least-to-most-restrictive is wrong here on purpose - tenant-admin
// is the one truly elevated role in this realm, so it's listed first and
// gets the "elevated" pill treatment below; there is no real Owner/Admin/
// Builder/Member/Viewer ladder in this system the way the AXON mockup's
// example data shows, so the footer note (bottom of this file) states our
// actual four roles instead of the mockup's fictional ones.
export const REALM_ROLES = ["tenant-admin", "agent-builder", "delegated-user", "end-customer"];

// Fine-grained permission catalog, layered on top of the roles above (see
// core/admin/.../permission/Permission.java) - same hand-maintained-mirror
// tradeoff as REALM_ROLES. Seeded from a user's roles at creation/role-change
// time (DefaultPermissionBundles), then freely editable per person. As of
// 2026-09-23 these are LOAD-BEARING: SecurityConfig's PERM_* matchers enforce
// them on the backend, so what an admin ticks here is exactly what the
// person can reach - grant deliberately.
//
// Grouped by domain so 14 scopes stay scannable; the flat list is what the
// API actually sends (a Set of names - grouping is presentation only).
const PERMISSION_GROUPS: { key: string; permissions: string[] }[] = [
  { key: "permGroupPeople", permissions: ["MANAGE_USERS"] },
  { key: "permGroupMoney", permissions: ["MANAGE_BILLING", "VIEW_USAGE"] },
  {
    key: "permGroupPlatform",
    permissions: ["MANAGE_INTEGRATIONS", "MANAGE_TENANT_SETTINGS", "MANAGE_CHANNELS", "MANAGE_LEADS"],
  },
  {
    key: "permGroupBuild",
    permissions: ["MANAGE_WORKFLOWS_ADMIN", "MANAGE_AGENTS", "MANAGE_TOOLS", "MANAGE_GUARDRAILS", "MANAGE_TEMPLATES"],
  },
  { key: "permGroupOversight", permissions: ["VIEW_AUDIT_LOG", "MANAGE_APPROVALS", "MANAGE_DATA_GOVERNANCE"] },
];

// One-click scope bundles (granular-RBAC "no.1" pass) - the common job
// shapes an admin actually wants, so scoping a teammate is a choice of
// persona, not a 14-checkbox reading exercise. Applying a preset UNIONS
// its scopes into the current selection (it never removes anything - the
// admin still owns the final set and can untick individually).
const PERMISSION_PRESETS: { labelKey: string; permissions: string[] }[] = [
  { labelKey: "presetSupportLead", permissions: ["MANAGE_CHANNELS", "MANAGE_APPROVALS", "VIEW_AUDIT_LOG"] },
  { labelKey: "presetFinance", permissions: ["MANAGE_BILLING", "VIEW_USAGE"] },
  {
    labelKey: "presetOpsEngineer",
    permissions: ["MANAGE_AGENTS", "MANAGE_WORKFLOWS_ADMIN", "MANAGE_INTEGRATIONS", "MANAGE_TOOLS"],
  },
  { labelKey: "presetAuditor", permissions: ["VIEW_AUDIT_LOG", "VIEW_USAGE"] },
];

/**
 * The grouped permission editor, shared between the create form and the
 * per-row edit panel: preset chips on top, then the catalog grouped by
 * domain. `onApplyPreset` unions a preset's scopes into the selection.
 */
export function PermissionPicker({
  selected,
  onToggle,
  onApplyPreset,
  testId,
}: {
  selected: Set<string>;
  onToggle: (permission: string) => void;
  onApplyPreset: (permissions: string[]) => void;
  testId?: string;
}) {
  const t = useTranslations("adminUsersPanel");
  return (
    <div>
      <div className="ax-mnt-roleedit-checks" style={{ marginBottom: 6 }}>
        {PERMISSION_PRESETS.map((preset) => (
          <button
            key={preset.labelKey}
            type="button"
            className="ax-mnt-role-pill"
            title={preset.permissions.join(", ")}
            onClick={() => onApplyPreset(preset.permissions)}
          >
            <Plus size={11} aria-hidden="true" /> {t(preset.labelKey)}
          </button>
        ))}
      </div>
      {PERMISSION_GROUPS.map((group) => (
        <div key={group.key} style={{ marginBottom: 6 }}>
          <div className="ax-mnt-sub" style={{ marginBottom: 2 }}>
            {t(group.key)}
          </div>
          <div className="ax-mnt-roleedit-checks" data-testid={testId}>
            {group.permissions.map((p) => (
              <label key={p}>
                <input type="checkbox" checked={selected.has(p)} onChange={() => onToggle(p)} />
                {p}
              </label>
            ))}
          </div>
        </div>
      ))}
    </div>
  );
}
