"use client";

import { useEffect, useState } from "react";
import { Loader2, UserPlus, Trash2 } from "lucide-react";
import { api } from "../../lib/api";
import { useTranslations } from "../../lib/i18n";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { Label } from "../ui/label";
import { Badge } from "../ui/badge";
import { Alert, AlertDescription } from "../ui/alert";
import { cn } from "../../lib/utils";
import { toast } from "../../lib/toastBus";
import PaginationControls from "../PaginationControls";
import { usePagination } from "../../lib/usePagination";

/**
 * Owner-facing "who has access" panel for cross-user Agent/Workflow sharing
 * (core/sharing's ShareController) - named teammates with a graded
 * RUNNER/VIEWER/EDITOR permission, NOT the anonymous public-link feature
 * (see ShareAgentPanel.tsx, a different feature entirely: one binary
 * shared/not-shared state, no named recipients). Generalized across both
 * resource types via the same `/api/{resourceKind}/{resourceId}/grants`
 * path the backend mounts at - `resourceType` picks which one.
 *
 * Ownership gate: no `createdBy`/owner field is surfaced to the frontend,
 * so this panel can't decide client-side whether the current user is the
 * owner. Instead it leans on the backend's own real access-control
 * decision: the initial `GET .../grants` call is owner/admin-only and
 * returns a 404 (never 403 - anti-enumeration, see
 * ResourceAccessDeniedException's javadoc, mapped in GlobalExceptionHandler)
 * for anyone else. A 404 here is therefore treated as "not permitted to
 * manage sharing on this resource" and the whole panel renders nothing -
 * the same "handle a 404 as a 404, no special forbidden messaging" posture
 * this codebase already uses everywhere else.
 *
 * That gate is genuinely owner-scoped as of this writing, not merely
 * admin-only: both `AgentResourceOwnerLookup` and
 * `WorkflowResourceOwnerLookup` are registered `@Component`s now, and the
 * `createdBy` columns they read were added and backfilled by tenant
 * migrations V151/V155 - so `ResourceAccessService#requireOwnerOrAdmin`
 * resolves a real owner rather than the `null` "legacy resource" fallback
 * that ShareController's javadoc describes from its initial build.
 *
 * Note `api.listResourceGrants` deliberately routes through
 * `toJsonQuietOn([404])`: this panel is mounted on EVERY agent detail view,
 * so the expected non-owner 404 must not raise the global error toast.
 */
const PERMISSION_LEVELS = ["RUNNER", "VIEWER", "EDITOR"];
const PERMISSION_LABEL_KEY: Record<string, string> = {
  RUNNER: "permissionRunnerLabel",
  VIEWER: "permissionViewerLabel",
  EDITOR: "permissionEditorLabel",
};
const PERMISSION_DESC_KEY: Record<string, string> = {
  RUNNER: "permissionRunnerDesc",
  VIEWER: "permissionViewerDesc",
  EDITOR: "permissionEditorDesc",
};

interface TeammateAccessPanelProps {
  resourceType: "AGENT" | "WORKFLOW";
  resourceId: string;
}

export default function TeammateAccessPanel({ resourceType, resourceId }: TeammateAccessPanelProps) {
  const t = useTranslations("sharing");
  const resourceKind = resourceType === "AGENT" ? "agents" : "workflows";

  const [loading, setLoading] = useState(true);
  const [denied, setDenied] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [grants, setGrants] = useState<any[]>([]);

  const [email, setEmail] = useState("");
  const [permissionLevel, setPermissionLevel] = useState("RUNNER");
  const [submitting, setSubmitting] = useState(false);
  const [addError, setAddError] = useState<string | null>(null);
  const [busyGrantId, setBusyGrantId] = useState<string | null>(null);
  // Above the loading/denied early returns below - hook order must not
  // depend on the owner-gate resolving first.
  const grantsPagination = usePagination(grants);

  useEffect(() => {
    if (!resourceId) return undefined;
    let cancelled = false;
    setLoading(true);
    setDenied(false);
    setLoadError(null);
    api
      .listResourceGrants(resourceKind, resourceId)
      .then((list: any) => {
        if (!cancelled) setGrants(list || []);
      })
      .catch((err: any) => {
        if (cancelled) return;
        // Anti-enumeration: a non-owner gets the same 404 as a resource that
        // doesn't exist at all - see this component's header comment. Any
        // OTHER failure (network down, 500) is a real problem worth surfacing.
        if (err?.status === 404) {
          setDenied(true);
        } else {
          setLoadError(err?.message || t("errorLoadFailed"));
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [resourceKind, resourceId]);

  async function handleAdd(e: any) {
    e.preventDefault();
    if (!email.trim() || submitting) return;
    setSubmitting(true);
    setAddError(null);
    try {
      const created = await api.grantResourceAccess(resourceKind, resourceId, email.trim(), permissionLevel);
      setGrants((prev) => [created, ...prev.filter((g) => g.granteeUserId !== created.granteeUserId)]);
      toast.success(t("addSuccess", { email: created.granteeEmail || email.trim() }));
      setEmail("");
    } catch (err: any) {
      // The backend's own InvalidGranteeException message is already the
      // deliberately generic, non-leaky copy this needs ("couldn't share
      // with this recipient, confirm they're in your org") - toJson already
      // raised the global toast, this is just the inline, form-local echo.
      setAddError(err?.message || t("addErrorGeneric"));
    } finally {
      setSubmitting(false);
    }
  }

  async function handleRevoke(grant: any) {
    if (busyGrantId) return;
    const label = grant.granteeEmail || grant.granteeUserId;
    if (!window.confirm(t("revokeConfirm", { name: label }))) return;
    setBusyGrantId(grant.id);
    try {
      await api.revokeResourceAccess(resourceKind, resourceId, grant.id);
      setGrants((prev) => prev.filter((g) => g.id !== grant.id));
      toast.success(t("revokeSuccess"));
    } catch {
      // toJson already raised the global failure toast.
    } finally {
      setBusyGrantId(null);
    }
  }

  // Mirrors ShareAgentPanel's own "return null while loading" - avoids a
  // flash of empty chrome before the owner-only gate above has resolved.
  if (loading) return null;
  if (denied) return null;

  return (
    <div className="flex flex-col gap-4">
      {loadError && (
        <Alert variant="destructive">
          <AlertDescription>{loadError}</AlertDescription>
        </Alert>
      )}

      {!loadError && (
        <div className="flex flex-col gap-2">
          <div className="text-xs font-medium text-muted-foreground">{t("listHeading")}</div>
          {grants.length === 0 ? (
            <p className="text-sm text-muted-foreground">{t("emptyState")}</p>
          ) : (
            <div className="flex flex-col gap-2">
              {grantsPagination.pageItems.map((g) => (
                <div
                  key={g.id}
                  className="flex items-center justify-between gap-2 rounded-lg border border-border bg-secondary/30 px-3 py-2"
                >
                  <div className="min-w-0 flex-1">
                    <div className="truncate text-sm text-foreground">{g.granteeEmail || t("unknownUserFallback")}</div>
                    <div className="text-xs text-muted-foreground">
                      {t("grantedAtLabel", { date: g.grantedAt ? new Date(g.grantedAt).toLocaleDateString() : "" })}
                    </div>
                  </div>
                  <Badge variant="outline" className="shrink-0">
                    {t(PERMISSION_LABEL_KEY[g.permissionLevel] || "permissionRunnerLabel")}
                  </Badge>
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    className="shrink-0"
                    onClick={() => handleRevoke(g)}
                    disabled={busyGrantId === g.id}
                  >
                    {busyGrantId === g.id ? <Loader2 size={12} className="animate-spin" /> : <Trash2 size={12} />}
                    {t("revokeButton")}
                  </Button>
                </div>
              ))}
            </div>
          )}
          <PaginationControls
            page={grantsPagination.page}
            totalPages={grantsPagination.totalPages}
            totalItems={grantsPagination.totalItems}
            pageSize={grantsPagination.pageSize}
            onPageChange={grantsPagination.setPage}
            onPageSizeChange={grantsPagination.setPageSize}
            itemLabel={t("itemLabelMembers")}
          />
        </div>
      )}

      <form onSubmit={handleAdd} className="flex flex-col gap-2.5 rounded-lg border border-border p-3">
        <div className="text-xs font-medium text-muted-foreground">{t("addTeammateHeading")}</div>

        <div className="flex flex-col gap-1.5">
          <Label htmlFor={`teammate-email-${resourceId}`} className="text-xs">
            {t("emailLabel")}
          </Label>
          <Input
            id={`teammate-email-${resourceId}`}
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            placeholder={t("emailPlaceholder")}
            required
          />
        </div>

        <div className="flex flex-col gap-1.5">
          <Label className="text-xs" id={`teammate-permission-label-${resourceId}`}>
            {t("permissionLabel")}
          </Label>
          <div className="grid grid-cols-3 gap-2" role="radiogroup" aria-labelledby={`teammate-permission-label-${resourceId}`}>
            {PERMISSION_LEVELS.map((level) => (
              <button
                key={level}
                type="button"
                role="radio"
                aria-checked={permissionLevel === level}
                className={cn(
                  "min-h-10 rounded-lg border px-2.5 py-2 text-xs font-medium transition-colors",
                  permissionLevel === level
                    ? "border-primary bg-primary/10 text-foreground"
                    : "border-border bg-card text-muted-foreground hover:bg-accent"
                )}
                onClick={() => setPermissionLevel(level)}
              >
                {t(PERMISSION_LABEL_KEY[level])}
              </button>
            ))}
          </div>
          <p className="text-xs text-muted-foreground">{t(PERMISSION_DESC_KEY[permissionLevel])}</p>
        </div>

        {addError && <p className="text-xs text-destructive">{addError}</p>}

        <Button type="submit" size="sm" disabled={submitting || !email.trim()} className="self-start">
          {submitting ? <Loader2 size={12} className="animate-spin" /> : <UserPlus size={12} />}
          {t("addButton")}
        </Button>
      </form>
    </div>
  );
}
