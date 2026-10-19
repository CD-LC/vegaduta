"use client";

import { useEffect, useMemo, useState, type FormEvent } from "react";
import { api } from "../lib/api";
import EmptyState from "./EmptyState";
import PaginationControls from "./PaginationControls";
import { usePagination } from "../lib/usePagination";
import { Users, Search, Plus, MoreHorizontal, UserCheck, UserX, ChevronDown } from "lucide-react";
import { useTranslations } from "../lib/i18n";
import { Button } from "./ui/button";
import { ConfirmDialog } from "./ui/confirm-dialog";
import { errorMessage } from "../lib/errors";
import { PermissionPicker, REALM_ROLES } from "./AdminUsersPanel/permissions";
import InviteLinkNotice from "./AdminUsersPanel/InviteLinkNotice";
import { isLocalAuth } from "../lib/authMode";
import {
  AVATAR_GRADIENTS,
  displayName,
  initials,
  rolePillClass,
  toggleInSet,
  type AdminUser,
} from "./AdminUsersPanel/userDisplay";

export type { AdminUser } from "./AdminUsersPanel/userDisplay";

interface AdminUsersPanelProps {
  users: AdminUser[];
  onChanged: () => void;
  onCreated?: (user: AdminUser) => void;
}

// AXON Admin Users.dc.html rebuild (#474, Screen 10) - the MEMBER/ROLE/
// STATUS/LAST ACTIVE grid table, ported onto the .ax-mnt-* classes
// (globals.css) rather than the plain .mcp-grid card list this used before.
// "LAST ACTIVE" renders "-" for every row - see globals.css's comment on
// .ax-mnt-lastactive for why (no lastLogin field exists on AdminUserResponse
// today; this is an honest gap, not a design deviation to hide).
export default function AdminUsersPanel({ users, onChanged, onCreated }: AdminUsersPanelProps) {
  const t = useTranslations("adminUsersPanel");
  const tg = useTranslations("f2GapsAdmin");
  const [disableTarget, setDisableTarget] = useState<AdminUser | null>(null);
  const [username, setUsername] = useState("");
  const [email, setEmail] = useState("");
  const [firstName, setFirstName] = useState("");
  const [lastName, setLastName] = useState("");
  const [temporaryPassword, setTemporaryPassword] = useState("");
  // Invite-by-email is the default (granular-RBAC pass): the new user sets
  // their own password through Keycloak's action link, so a credential
  // never transits this admin's screen, chat, or clipboard. Uncheck to fall
  // back to the shared-temporary-password flow (e.g. the workspace mailbox
  // isn't reachable yet).
  const [inviteByEmail, setInviteByEmail] = useState(true);
  // Sets, not a single string - the post-creation edit UI below already let
  // an admin pick multiple roles; creation being stuck at a single <Select>
  // was an inconsistency, not a deliberate restriction (both send the exact
  // same shape to the backend now: initialRoles/initialPermissions arrays).
  const [initialRoles, setInitialRoles] = useState<Set<string>>(new Set());
  const [initialPermissions, setInitialPermissions] = useState<Set<string>>(new Set());
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);
  // No mail server on a personal install (local auth): an invite is a one-time link to copy.
  const linkInvites = isLocalAuth();
  const [inviteLink, setInviteLink] = useState<{ email: string; link: string } | null>(null);
  const [roleEdits, setRoleEdits] = useState<Record<string, Set<string>>>({}); // userId -> Set<string> being edited
  const [permissionEdits, setPermissionEdits] = useState<Record<string, Set<string>>>({}); // userId -> Set<string> being edited
  const [showCreate, setShowCreate] = useState(false);
  // Moves focus into the create form when it opens (replaces the autoFocus prop).
  useEffect(() => {
    if (showCreate) document.getElementById("admin-create-username")?.focus();
  }, [showCreate]);
  const [query, setQuery] = useState("");

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return users;
    return users.filter(
      (u) =>
        u.username?.toLowerCase().includes(q) ||
        u.email?.toLowerCase().includes(q) ||
        displayName(u).toLowerCase().includes(q)
    );
  }, [users, query]);

  const activeCount = users.filter((u) => u.enabled).length;
  const suspendedCount = users.length - activeCount;

  const { page, pageSize, pageItems, totalPages, totalItems, setPage, setPageSize } = usePagination(filtered);

  async function handleCreate(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setError(null);
    setSuccess(null);
    setInviteLink(null);
    setBusy(true);
    try {
      // firstName/lastName required - this realm's Keycloak User Profile
      // config requires both, and without them the new user could create
      // successfully but never actually log in (found verifying signup
      // end-to-end; same fix applied there, see SignupRequest's javadoc).
      const created = await api.createAdminUser({
        username,
        email: email.trim(),
        firstName,
        lastName,
        // inviteByEmail sends NO password at all - the backend's invite path
        // creates the user credential-less and emails them a set-password
        // action link (null, not "", so the @Size(min=8) check stays out of
        // the way).
        temporaryPassword: inviteByEmail ? null : temporaryPassword,
        inviteByEmail,
        initialRoles: Array.from(initialRoles),
        initialPermissions: Array.from(initialPermissions),
      });
      setUsername("");
      setEmail("");
      setFirstName("");
      setLastName("");
      setTemporaryPassword("");
      setInviteByEmail(true);
      setInitialRoles(new Set());
      setInitialPermissions(new Set());
      if (created.inviteLink) setInviteLink({ email: email.trim(), link: created.inviteLink });
      else setSuccess(t("userCreatedSuccess", { username }));
      setShowCreate(false);
      // Show the new user immediately from the POST response itself, rather
      // than relying solely on onChanged()'s follow-up GET - closes a real
      // race found running the full-platform showcase spec against this
      // exact flow. onChanged() still runs right after for full resync.
      onCreated?.(created);
      onChanged();
    } catch (err) {
      setError(errorMessage(err, t("userCreationFailed")));
    } finally {
      setBusy(false);
    }
  }

  async function newInviteLink(user: AdminUser) {
    setError(null);
    setSuccess(null);
    try {
      const res = await api.reissueInviteLink(user.id);
      if (res.inviteLink) setInviteLink({ email: user.email || user.username, link: res.inviteLink });
    } catch (err) {
      setError(errorMessage(err, t("userCreationFailed")));
    }
  }

  function startEditingRoles(user: AdminUser) {
    setError(null);
    setRoleEdits((prev) => ({ ...prev, [user.id]: new Set(user.roles) }));
  }

  function toggleRole(userId: string, role: string) {
    setRoleEdits((prev) => {
      const next = new Set(prev[userId]);
      if (next.has(role)) next.delete(role);
      else next.add(role);
      return { ...prev, [userId]: next };
    });
  }

  async function saveRoles(userId: string) {
    setError(null);
    setBusy(true);
    try {
      await api.setUserRoles(userId, Array.from(roleEdits[userId] || []));
      setRoleEdits((prev) => {
        const next = { ...prev };
        delete next[userId];
        return next;
      });
      setSuccess(t("rolesUpdated"));
      onChanged();
    } catch (err) {
      setError(errorMessage(err, t("rolesUpdateFailed")));
    } finally {
      setBusy(false);
    }
  }

  // Independent of role editing - a user's permissions are seeded from their
  // roles but freely adjustable afterward, so this is its own edit
  // panel/state, structurally identical to the role trio above.
  function startEditingPermissions(user: AdminUser) {
    setError(null);
    setPermissionEdits((prev) => ({ ...prev, [user.id]: new Set(user.permissions) }));
  }

  function togglePermission(userId: string, permission: string) {
    setPermissionEdits((prev) => {
      const next = new Set(prev[userId]);
      if (next.has(permission)) next.delete(permission);
      else next.add(permission);
      return { ...prev, [userId]: next };
    });
  }

  async function savePermissions(userId: string) {
    setError(null);
    setBusy(true);
    try {
      await api.setUserPermissions(userId, Array.from(permissionEdits[userId] || []));
      setPermissionEdits((prev) => {
        const next = { ...prev };
        delete next[userId];
        return next;
      });
      setSuccess(t("permissionsUpdated"));
      onChanged();
    } catch (err) {
      setError(errorMessage(err, t("permissionsUpdateFailed")));
    } finally {
      setBusy(false);
    }
  }

  // Direct feedback: "provide disable option on created user" - suspends
  // (or restores) sign-in without deleting the account. Tracks in-flight
  // ids separately from the shared `busy` flag so toggling one row doesn't
  // grey out every other row's controls at once.
  const [enabledBusyIds, setEnabledBusyIds] = useState<Set<string>>(new Set());

  function requestToggle(user: AdminUser) {
    if (user.enabled) setDisableTarget(user);
    else void toggleEnabled(user);
  }

  async function toggleEnabled(user: AdminUser) {
    const nextEnabled = !user.enabled;
    setError(null);
    setEnabledBusyIds((prev) => new Set(prev).add(user.id));
    try {
      await api.setUserEnabled(user.id, nextEnabled);
      setSuccess(
        nextEnabled
          ? t("userEnabledSuccess", { name: displayName(user) })
          : t("userDisabledSuccess", { name: displayName(user) })
      );
      onChanged();
    } catch (err) {
      setError(errorMessage(err, t("accessChangeFailed")));
    } finally {
      setEnabledBusyIds((prev) => {
        const next = new Set(prev);
        next.delete(user.id);
        return next;
      });
    }
  }

  return (
    <div className="ax-mnt-section">
      <div className="ax-mnt-head">
        <div>
          <h2 className="ax-mnt-title">{t("teamRolesTitle")}</h2>
          <div className="ax-mnt-sub">
            {t("teamSubtitle", {
              count: users.length,
              plural: users.length === 1 ? "" : "s",
              active: activeCount,
              suspended: suspendedCount,
            })}
          </div>
        </div>
        <div className="ax-mnt-head-actions">
          <div className="ax-tl-search-row">
            <Search size={13} aria-hidden="true" />
            <input
              className="ax-tl-search-input"
              type="text"
              placeholder={t("searchPeoplePlaceholder")}
              aria-label={t("searchPeoplePlaceholder")}
              value={query}
              onChange={(e) => setQuery(e.target.value)}
            />
          </div>
          <Button type="button" size="sm" onClick={() => setShowCreate((v) => !v)}>
            <Plus aria-hidden="true" className="size-[13px]" /> {t("invitePeople")}
          </Button>
        </div>
      </div>

      {error && (
        <div className="ax-kb-error" role="alert">
          {error}
        </div>
      )}
      {inviteLink && (
        <InviteLinkNotice email={inviteLink.email} link={inviteLink.link} onDismiss={() => setInviteLink(null)} />
      )}
      {success && (
        <div className="ax-kb-success" role="status">
          {success}
        </div>
      )}

      {showCreate && (
        <form onSubmit={handleCreate} className="ax-mnt-roleedit" style={{ marginBottom: 14 }}>
          <div className="ax-kb-form-field">
            <label htmlFor="admin-create-username">{t("usernameLabel")}</label>
            <input
              id="admin-create-username"
              className="ax-kb-form-input"
              type="text"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              required
              minLength={3}
              maxLength={255}
            />
          </div>
          <div className="ax-kb-form-field">
            <label htmlFor="admin-create-email">{t("emailLabel")}</label>
            <input
              id="admin-create-email"
              className="ax-kb-form-input"
              type="email"
              inputMode="email"
              autoComplete="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              required
            />
          </div>
          <div className="ax-kb-form-field">
            <label htmlFor="admin-create-first-name">{t("firstNameLabel")}</label>
            <input
              id="admin-create-first-name"
              className="ax-kb-form-input"
              type="text"
              value={firstName}
              onChange={(e) => setFirstName(e.target.value)}
              required
            />
          </div>
          <div className="ax-kb-form-field">
            <label htmlFor="admin-create-last-name">{t("lastNameLabel")}</label>
            <input
              id="admin-create-last-name"
              className="ax-kb-form-input"
              type="text"
              value={lastName}
              onChange={(e) => setLastName(e.target.value)}
              required
            />
          </div>
          <div className="ax-kb-form-field">
            <label className="ax-mnt-roleedit-checks" style={{ display: "flex", alignItems: "center", gap: 6 }}>
              <input type="checkbox" checked={inviteByEmail} onChange={(e) => setInviteByEmail(e.target.checked)} />
              {linkInvites ? t("inviteByLinkLabel") : t("inviteByEmailLabel")}
            </label>
          </div>
          {!inviteByEmail && (
            <div className="ax-kb-form-field">
              <label htmlFor="admin-create-temp-password">{t("temporaryPasswordLabel")}</label>
              <input
                id="admin-create-temp-password"
                className="ax-kb-form-input"
                type="password"
                value={temporaryPassword}
                onChange={(e) => setTemporaryPassword(e.target.value)}
                minLength={12}
                maxLength={128}
                required
              />
            </div>
          )}
          <div className="ax-kb-form-field">
            <label>{t("initialRolesLabel")}</label>
            <div className="ax-mnt-roleedit-checks" data-testid="admin-create-user-roles">
              {REALM_ROLES.map((r) => (
                <label key={r}>
                  <input
                    type="checkbox"
                    checked={initialRoles.has(r)}
                    onChange={() => toggleInSet(setInitialRoles, r)}
                  />
                  {r}
                </label>
              ))}
            </div>
          </div>
          <div className="ax-kb-form-field">
            <label>{t("initialPermissionsLabel")}</label>
            <PermissionPicker
              selected={initialPermissions}
              onToggle={(p) => toggleInSet(setInitialPermissions, p)}
              onApplyPreset={(perms) => setInitialPermissions((prev) => new Set([...prev, ...perms]))}
              testId="admin-create-user-permissions"
            />
          </div>
          <div className="ax-kb-form-actions">
            <Button type="button" variant="outline" size="sm" disabled={busy} onClick={() => setShowCreate(false)}>
              {t("cancel")}
            </Button>
            <Button type="submit" size="sm" disabled={busy}>
              {busy ? t("creating") : t("createUser")}
            </Button>
          </div>
        </form>
      )}

      {users.length === 0 ? (
        <EmptyState icon={Users} title={t("noUsersTitle")} hint={t("noUsersHint")} />
      ) : (
        <div className="ax-mnt-table" role="table" aria-label={t("teamRolesTitle")}>
          <div className="ax-mnt-table-head ax-mnt-row-grid" role="row">
            <span role="columnheader">{t("memberColumn")}</span>
            <span role="columnheader">{t("roleColumn")}</span>
            <span role="columnheader">{t("statusColumn")}</span>
            <span role="columnheader">{t("lastActiveColumn")}</span>
            <span role="columnheader" />
          </div>

          {filtered.length === 0 && <div className="ax-mnt-appr-empty">{t("noMatchesText", { query })}</div>}

          {pageItems.map((u: AdminUser, i: number) => {
            const editing = roleEdits[u.id];
            const editingPermissions = permissionEdits[u.id];
            const primaryRole = u.roles[0];
            const permissions = u.permissions || [];
            return (
              <div key={u.id} className="ax-mnt-user-row ax-mnt-row-grid" role="row">
                <div className="ax-mnt-member" role="cell">
                  <div
                    className="ax-mnt-avatar"
                    style={{ background: AVATAR_GRADIENTS[i % AVATAR_GRADIENTS.length], opacity: u.enabled ? 1 : 0.6 }}
                  >
                    {initials(u)}
                  </div>
                  <div className="ax-mnt-member-text">
                    <div className="ax-mnt-member-name">{displayName(u)}</div>
                    <div className="ax-mnt-member-email">{u.email}</div>
                  </div>
                </div>

                <span className="row" role="cell" style={{ gap: 6, flexWrap: "wrap" }}>
                  {primaryRole ? (
                    <button
                      type="button"
                      className={rolePillClass(primaryRole)}
                      aria-expanded={!!editing}
                      onClick={() => (editing ? undefined : startEditingRoles(u))}
                    >
                      {primaryRole}
                      {u.roles.length > 1 ? ` +${u.roles.length - 1}` : ""} <ChevronDown size={11} aria-hidden="true" />
                    </button>
                  ) : (
                    <button
                      type="button"
                      className="ax-mnt-role-pill ax-mnt-role-pill-none"
                      aria-expanded={!!editing}
                      onClick={() => startEditingRoles(u)}
                    >
                      {t("noRoles")} <ChevronDown size={11} aria-hidden="true" />
                    </button>
                  )}
                  <button
                    type="button"
                    className="ax-mnt-role-pill ax-mnt-role-pill-none"
                    title={t("editPermissionsTooltip")}
                    aria-expanded={!!editingPermissions}
                    onClick={() => (editingPermissions ? undefined : startEditingPermissions(u))}
                  >
                    {permissions.length > 0
                      ? `${permissions.length} ${t("permissionsLabel").toLowerCase()}`
                      : t("noPermissions")}{" "}
                    <ChevronDown size={11} aria-hidden="true" />
                  </button>
                </span>

                <div
                  className={`ax-mnt-status ${u.enabled ? "ax-mnt-status-active" : "ax-mnt-status-suspended"}`}
                  role="cell"
                >
                  <span className="ax-mnt-status-dot" />
                  {u.enabled ? t("statusActive") : t("statusSuspended")}
                </div>

                <span className="ax-mnt-lastactive" role="cell">
                  &mdash;
                </span>

                <span className="row" role="cell" style={{ gap: 6, justifySelf: "end" }}>
                  {linkInvites && u.invitePending && (
                    <Button type="button" variant="outline" size="sm" onClick={() => newInviteLink(u)}>
                      {t("newInviteLink")}
                    </Button>
                  )}
                  {/* Direct feedback: "provide disable option on created
                   * user" - a small text-badge toggle next to the role menu,
                   * same ax-wf-btn chrome the rest of this panel already
                   * uses for row-scoped actions. */}
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    disabled={enabledBusyIds.has(u.id)}
                    title={u.enabled ? t("disableTooltip") : t("enableTooltip")}
                    onClick={() => requestToggle(u)}
                  >
                    {u.enabled ? (
                      <UserX aria-hidden="true" className="size-3" />
                    ) : (
                      <UserCheck aria-hidden="true" className="size-3" />
                    )}
                    {enabledBusyIds.has(u.id) ? "…" : u.enabled ? t("disableLabel") : t("enableLabel")}
                  </Button>
                  <button
                    type="button"
                    className="ax-mnt-row-menu"
                    title={t("editRolesTooltip")}
                    onClick={() => startEditingRoles(u)}
                  >
                    <MoreHorizontal aria-hidden="true" size={15} />
                  </button>
                </span>

                {editing && (
                  <div className="ax-mnt-roleedit">
                    <div className="ax-mnt-roleedit-checks">
                      {REALM_ROLES.map((r) => (
                        <label key={r}>
                          <input type="checkbox" checked={editing.has(r)} onChange={() => toggleRole(u.id, r)} />
                          {r}
                        </label>
                      ))}
                    </div>
                    <div className="ax-kb-form-actions">
                      <Button
                        type="button"
                        variant="outline"
                        size="sm"
                        disabled={busy}
                        onClick={() =>
                          setRoleEdits((prev) => {
                            const next = { ...prev };
                            delete next[u.id];
                            return next;
                          })
                        }
                      >
                        {t("cancel")}
                      </Button>
                      <Button type="button" size="sm" disabled={busy} onClick={() => saveRoles(u.id)}>
                        {t("save")}
                      </Button>
                    </div>
                  </div>
                )}

                {editingPermissions && (
                  <div className="ax-mnt-roleedit">
                    <PermissionPicker
                      selected={editingPermissions}
                      onToggle={(p) => togglePermission(u.id, p)}
                      onApplyPreset={(perms) =>
                        setPermissionEdits((prev) => ({
                          ...prev,
                          [u.id]: new Set([...(prev[u.id] || []), ...perms]),
                        }))
                      }
                    />
                    <div className="ax-kb-form-actions">
                      <Button
                        type="button"
                        variant="outline"
                        size="sm"
                        disabled={busy}
                        onClick={() =>
                          setPermissionEdits((prev) => {
                            const next = { ...prev };
                            delete next[u.id];
                            return next;
                          })
                        }
                      >
                        {t("cancel")}
                      </Button>
                      <Button type="button" size="sm" disabled={busy} onClick={() => savePermissions(u.id)}>
                        {t("save")}
                      </Button>
                    </div>
                  </div>
                )}
              </div>
            );
          })}

          <PaginationControls
            page={page}
            totalPages={totalPages}
            totalItems={totalItems}
            pageSize={pageSize}
            onPageChange={setPage}
            onPageSizeChange={setPageSize}
            itemLabel={t("itemLabelPeople")}
          />

          <div className="ax-mnt-table-footer">
            {t("tableFooter", { roles: REALM_ROLES.join(" → ") })}
            {" · "}
            {t("permissionsHint")}
          </div>
        </div>
      )}
      <ConfirmDialog
        open={disableTarget !== null}
        onOpenChange={(o) => {
          if (!o) setDisableTarget(null);
        }}
        title={tg("disableUserTitle")}
        description={disableTarget ? t("disableConfirm", { name: displayName(disableTarget) }) : undefined}
        confirmLabel={tg("disable")}
        cancelLabel={tg("cancel")}
        destructive
        onConfirm={async () => {
          if (disableTarget) await toggleEnabled(disableTarget);
        }}
      />
    </div>
  );
}
