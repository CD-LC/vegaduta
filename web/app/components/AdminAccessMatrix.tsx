"use client";

import { useMemo, useState } from "react";
import { api } from "../lib/api";
import { useTranslations } from "../lib/i18n";
import type { AdminUser } from "./AdminUsersPanel";

// The full granular catalog, same hand-maintained mirror as
// AdminUsersPanel's PERMISSION_GROUPS - rendered as matrix COLUMNS, so the
// same grouping drives the column banding (each group's scopes sit next to
// each other under one band header).
const MATRIX_GROUPS: { key: string; permissions: string[] }[] = [
  { key: "permGroupPeople", permissions: ["MANAGE_USERS"] },
  { key: "permGroupMoney", permissions: ["MANAGE_BILLING", "VIEW_USAGE"] },
  { key: "permGroupPlatform", permissions: ["MANAGE_INTEGRATIONS", "MANAGE_TENANT_SETTINGS", "MANAGE_CHANNELS", "MANAGE_LEADS"] },
  {
    key: "permGroupBuild",
    permissions: ["MANAGE_WORKFLOWS_ADMIN", "MANAGE_AGENTS", "MANAGE_TOOLS", "MANAGE_GUARDRAILS", "MANAGE_TEMPLATES"],
  },
  { key: "permGroupOversight", permissions: ["VIEW_AUDIT_LOG", "MANAGE_APPROVALS", "MANAGE_DATA_GOVERNANCE"] },
];
const MATRIX_COLUMNS = MATRIX_GROUPS.flatMap((g) => g.permissions);

function shortName(permission: string) {
  return permission.replace("MANAGE_", "").replace("VIEW_", "").replace(/_/g, " ");
}

/**
 * The users x scopes access matrix (granular-RBAC "no.1" pass, 2026-09-23):
 * the whole tenant's permission posture on one screen, instead of opening
 * each member's editor one at a time. A cell tick calls the same
 * full-set-replace endpoint the row editor uses (PUT
 * /api/admin/users/{id}/permissions), so the two surfaces can never
 * disagree about what "granted" means.
 *
 * Deliberately read-mostly: a disabled member's cells render greyed (their
 * grants are preserved but inert - re-enable restores them exactly), and
 * the caller's own row renders read-only since the backend refuses
 * self-permission-edits anyway (a clickable self-cell would only ever
 * 400 - see UserAdminController#rejectSelfPermissionEdit).
 */
export default function AdminAccessMatrix({
  users,
  currentUserId,
  onChanged,
}: {
  users: AdminUser[];
  currentUserId?: string | null;
  onChanged: () => void;
}) {
  const t = useTranslations("adminAccessPage");
  const [busyCells, setBusyCells] = useState<Set<string>>(new Set());
  const [error, setError] = useState<string | null>(null);

  const cellBusy = (userId: string, permission: string) => busyCells.has(`${userId}:${permission}`);

  async function toggle(user: AdminUser, permission: string) {
    if (user.id === currentUserId || cellBusy(user.id, permission)) return;
    const current = new Set(user.permissions || []);
    if (current.has(permission)) current.delete(permission);
    else current.add(permission);
    setError(null);
    setBusyCells((prev) => new Set(prev).add(`${user.id}:${permission}`));
    try {
      await api.setUserPermissions(user.id, Array.from(current));
      onChanged();
    } catch (err) {
      setError((err as Error).message || t("toggleFailed"));
    } finally {
      setBusyCells((prev) => {
        const next = new Set(prev);
        next.delete(`${user.id}:${permission}`);
        return next;
      });
    }
  }

  const sorted = useMemo(
    () => [...users].sort((a, b) => (a.username || "").localeCompare(b.username || "")),
    [users]
  );

  return (
    <div className="ax-mnt-section" style={{ marginTop: 18 }}>
      <div className="ax-mnt-head">
        <div>
          <h2 className="ax-mnt-title">{t("matrixTitle")}</h2>
          <div className="ax-mnt-sub">{t("matrixHint")}</div>
        </div>
      </div>
      {error && <div className="ax-kb-error" role="alert">{error}</div>}
      <div style={{ overflowX: "auto" }}>
        <table style={{ borderCollapse: "collapse", fontSize: "0.78rem", minWidth: "100%" }}>
          <thead>
            <tr>
              <th style={{ textAlign: "left", padding: "6px 10px", position: "sticky", left: 0, background: "var(--surface-card)", zIndex: 1 }}>
                {t("memberColumn")}
              </th>
              {MATRIX_GROUPS.map((group) => (
                <th
                  key={group.key}
                  colSpan={group.permissions.length}
                  style={{ padding: "6px 4px", borderLeft: "1px solid var(--ax-border)", fontWeight: 600 }}
                >
                  {t(group.key)}
                </th>
              ))}
            </tr>
            <tr>
              <th style={{ padding: "2px 10px" }} />
              {MATRIX_COLUMNS.map((permission) => (
                <th
                  key={permission}
                  title={permission}
                  style={{
                    padding: "4px 4px",
                    borderLeft: "1px solid var(--ax-border)",
                    fontWeight: 500,
                    fontSize: "var(--fs-caption)",
                    writingMode: "vertical-rl",
                    textAlign: "left",
                    minWidth: 26,
                  }}
                >
                  {shortName(permission)}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {sorted.map((user) => {
              const held = new Set(user.permissions || []);
              const self = user.id === currentUserId;
              return (
                <tr key={user.id} style={{ opacity: user.enabled ? 1 : 0.45 }}>
                  <td
                    style={{
                      padding: "5px 10px",
                      position: "sticky",
                      left: 0,
                      background: "var(--surface-card)",
                      whiteSpace: "nowrap",
                    }}
                  >
                    <div style={{ fontWeight: 600 }}>{user.firstName || user.username}</div>
                    <div className="ax-mnt-sub" style={{ fontSize: "0.7rem" }}>{user.email || user.username}</div>
                  </td>
                  {MATRIX_COLUMNS.map((permission) => {
                    const granted = held.has(permission);
                    const busy = cellBusy(user.id, permission);
                    return (
                      <td
                        key={permission}
                        style={{ textAlign: "center", padding: "3px 4px", borderLeft: "1px solid var(--ax-border)" }}
                      >
                        <input
                          type="checkbox"
                          checked={granted}
                          disabled={busy || self}
                          title={self ? t("selfRowHint") : permission}
                          onChange={() => toggle(user, permission)}
                        />
                      </td>
                    );
                  })}
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
}
