import type { SettingRole } from "../../lib/api/settings";

/** The role the settings API will apply to this person's writes (tenant writes need the tenant-admin role). */
export function settingsRoleFor(auth: {
  isAdmin: boolean;
  isPlatformAdmin: boolean;
  hasPermission: (name: string) => boolean;
}): SettingRole {
  if (auth.isAdmin || auth.isPlatformAdmin) return "TENANT_ADMIN";
  if (auth.hasPermission("MANAGE_AGENTS")) return "EDITOR";
  return "VIEWER";
}
