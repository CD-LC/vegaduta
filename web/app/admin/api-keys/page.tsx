"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { KeyRound, Lock, Plus } from "lucide-react";
import { useRequireScope } from "../../components/useRequireScope";
import AppShell from "../../components/AppShell";
import { usePageReady } from "../../components/RouteTransition";
import PageHeader from "../../components/ax/PageHeader";
import EmptyState from "../../components/EmptyState";
import { Alert, AlertDescription, AlertTitle } from "../../components/ui/alert";
import { Button } from "../../components/ui/button";
import ApiKeysList from "./ApiKeysList";
import { ConfirmDialog, KeyRevealDialog } from "./ApiKeyDialogs";
import { ErrorRetry, ListSkeleton } from "../commerce/listKit";
import { Input } from "../../components/ui/input";
import { Label } from "../../components/ui/label";
import { api } from "../../lib/api";
import type { TenantApiKeyCreatedResponse, TenantApiKeyResponse } from "../../lib/api/apiKeys";
import { toast } from "../../lib/toastBus";
import { useTranslations } from "../../lib/i18n";
import { errorMessage, errorStatus } from "../../lib/errors";

// Tenant REST API keys (core/tenancy/apikey, /api/tenant/api-keys, V483) -
// machine credentials so CI, partner systems and scripts can call /api/**
// with "Authorization: Bearer vdak_..." instead of an OIDC login. Same page
// skeleton as admin/mcp-servers (the sibling machine-credential surface):
// list + create + rotate + enable/disable + revoke, with the plaintext key
// shown exactly once in a dialog.
//
// The permission checkboxes mirror core/admin's Permission catalog exactly
// (the backend validates both that each name is a catalog value AND that the
// issuer holds it - a key can never carry a scope its issuer didn't have).

const ALL_PERMISSIONS = [
  "MANAGE_USERS",
  "MANAGE_BILLING",
  "VIEW_USAGE",
  "MANAGE_INTEGRATIONS",
  "MANAGE_TENANT_SETTINGS",
  "MANAGE_CHANNELS",
  "MANAGE_LEADS",
  "MANAGE_WORKFLOWS_ADMIN",
  "MANAGE_AGENTS",
  "MANAGE_TOOLS",
  "MANAGE_GUARDRAILS",
  "MANAGE_TEMPLATES",
  "VIEW_AUDIT_LOG",
  "MANAGE_APPROVALS",
  "MANAGE_DATA_GOVERNANCE",
];

export default function AdminApiKeysPage() {
  const t = useTranslations("adminApiKeysPage");
  const tu = useTranslations("adminCommerceUi");
  // MANAGE_USERS - the whole route family is PERM_MANAGE_USERS server-side
  // (TenantFeatureRules): issuing a credential that can hold scopes IS
  // permission administration.
  const allowed = useRequireScope("MANAGE_USERS");

  const [keys, setKeys] = useState<TenantApiKeyResponse[]>([]);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [loaded, setLoaded] = useState(false);
  // Issuing keys is plan-gated (developer-API entitlement): 402/403 from the
  // API shows an upsell instead of a late error toast.
  const [planGated, setPlanGated] = useState(false);

  const [createName, setCreateName] = useState("");
  const [createPerms, setCreatePerms] = useState(() => new Set<string>());
  const [createExpiry, setCreateExpiry] = useState("");
  const [createBusy, setCreateBusy] = useState(false);

  const [busyIds, setBusyIds] = useState(() => new Set<string>());
  const [reveal, setReveal] = useState<{ title: string; rawApiKey: string } | null>(null);
  const [confirm, setConfirm] = useState<{ type: "rotate" | "revoke"; key: TenantApiKeyResponse } | null>(null);

  const refresh = useCallback(async () => {
    try {
      setKeys(await api.listApiKeys());
      setLoadError(null);
    } catch (err) {
      if (errorStatus(err) === 402) setPlanGated(true);
      else setLoadError(errorMessage(err, t("loadErrorFallback")));
    } finally {
      setLoaded(true);
    }
  }, [t]);

  useEffect(() => {
    if (allowed) void refresh();
  }, [allowed, refresh]);
  usePageReady(loaded || !allowed);

  function togglePerm(perm: string) {
    setCreatePerms((prev) => {
      const next = new Set(prev);
      if (next.has(perm)) next.delete(perm);
      else next.add(perm);
      return next;
    });
  }

  function replaceKey(updated: TenantApiKeyResponse) {
    setKeys((prev) => prev.map((k) => (k.id === updated.id ? updated : k)));
  }

  async function handleCreate() {
    if (!createName.trim()) {
      toast.error(t("createErrorFallback"));
      return;
    }
    setCreateBusy(true);
    try {
      const result: TenantApiKeyCreatedResponse = await api.createApiKey({
        name: createName.trim(),
        permissions: Array.from(createPerms),
        expiresAt: createExpiry ? new Date(createExpiry).toISOString() : null,
      });
      setKeys((prev) => [result.key, ...prev]);
      setCreateName("");
      setCreatePerms(new Set());
      setCreateExpiry("");
      toast.success(t("createSuccessToast", { name: result.key.name }));
      setReveal({ title: t("revealTitleCreate"), rawApiKey: result.apiKey });
    } catch (err) {
      if (errorStatus(err) === 402 || errorStatus(err) === 403) setPlanGated(true);
      toast.error(errorMessage(err, t("createErrorFallback")));
    } finally {
      setCreateBusy(false);
    }
  }

  async function handleToggle(key: TenantApiKeyResponse) {
    setBusyIds((prev) => new Set(prev).add(key.id));
    try {
      const updated = await api.setApiKeyEnabled(key.id, !key.enabled);
      replaceKey(updated);
      toast.success(t(updated.enabled ? "enabledToast" : "disabledToast", { name: key.name }));
    } catch (err) {
      toast.error(errorMessage(err, t("toggleError")));
    } finally {
      setBusyIds((prev) => {
        const next = new Set(prev);
        next.delete(key.id);
        return next;
      });
    }
  }

  async function handleConfirm() {
    if (!confirm) return;
    const { type, key } = confirm;
    setBusyIds((prev) => new Set(prev).add(key.id));
    try {
      if (type === "rotate") {
        const result = await api.rotateApiKey(key.id);
        replaceKey(result.key);
        setReveal({ title: t("revealTitleRotate"), rawApiKey: result.apiKey });
      } else {
        await api.revokeApiKey(key.id);
        setKeys((prev) => prev.filter((k) => k.id !== key.id));
        toast.success(t("revokedToast", { name: key.name }));
      }
      setConfirm(null);
    } catch (err) {
      toast.error(errorMessage(err, t("actionErrorFallback")));
    } finally {
      setBusyIds((prev) => {
        const next = new Set(prev);
        next.delete(key.id);
        return next;
      });
    }
  }

  if (!allowed) return null;

  return (
    <AppShell eyebrow={t("pageEyebrow")}>
      <PageHeader title={t("pageTitle")} subtitle={t("pageSubtitle")} />

      <div className="wf-panel" style={{ marginBottom: "1rem" }}>
        <h3 style={{ marginTop: 0 }}>{t("createHeading")}</h3>
        <div className="row" style={{ alignItems: "flex-end", flexWrap: "wrap", gap: "0.75rem" }}>
          <div style={{ minWidth: 220 }}>
            <Label htmlFor="api-key-name">{t("nameLabel")}</Label>
            <Input
              id="api-key-name"
              value={createName}
              onChange={(e) => setCreateName(e.target.value)}
              placeholder={t("namePlaceholder")}
            />
          </div>
          <div>
            <Label htmlFor="api-key-expiry">{t("expiryLabel")}</Label>
            <Input
              id="api-key-expiry"
              type="datetime-local"
              value={createExpiry}
              onChange={(e) => setCreateExpiry(e.target.value)}
            />
          </div>
          <Button type="button" onClick={handleCreate} disabled={createBusy || !createName.trim()}>
            <Plus size={14} aria-hidden="true" />
            {t("createButton")}
          </Button>
        </div>
        <fieldset style={{ marginTop: "0.75rem", border: 0, padding: 0, minInlineSize: 0 }}>
          <legend className="text-sm font-medium">{t("permissionsLabel")}</legend>
          <p className="hint" style={{ marginTop: 2 }}>{t("permissionsHint")}</p>
          <div style={{ display: "flex", flexWrap: "wrap", gap: "0.5rem 1rem", marginTop: "0.4rem" }}>
            {ALL_PERMISSIONS.map((perm) => (
              <label key={perm} style={{ display: "flex", alignItems: "center", gap: 6, fontSize: "0.8rem" }}>
                <input
                  type="checkbox"
                  checked={createPerms.has(perm)}
                  onChange={() => togglePerm(perm)}
                />
                {perm}
              </label>
            ))}
          </div>
        </fieldset>
      </div>

      {planGated && (
        <Alert variant="warning" className="mb-4">
          <Lock aria-hidden="true" />
          <AlertTitle>{tu("apiKeysPlanGateTitle")}</AlertTitle>
          <AlertDescription>
            {tu("apiKeysPlanGateBody")}{" "}
            <Link href="/admin/billing" className="font-medium underline">
              {tu("apiKeysPlanGateLink")}
            </Link>
          </AlertDescription>
        </Alert>
      )}

      {loadError && <ErrorRetry message={loadError} onRetry={() => void refresh()} />}

      {!loaded ? (
        <ListSkeleton rows={4} />
      ) : keys.length === 0 && !loadError ? (
        <EmptyState
          icon={KeyRound}
          title={t("emptyTitle")}
          hint={t("emptyHint")}
        />
      ) : (
        <ApiKeysList
          keys={keys}
          busyIds={busyIds}
          onToggle={handleToggle}
          onRotate={(key) => setConfirm({ type: "rotate", key })}
          onRevoke={(key) => setConfirm({ type: "revoke", key })}
        />
      )}

      {reveal && (
        <KeyRevealDialog
          title={reveal.title}
          rawApiKey={reveal.rawApiKey}
          onClose={() => setReveal(null)}
          t={t}
        />
      )}
      {confirm && (
        <ConfirmDialog
          title={confirm.type === "rotate" ? t("confirmRotateTitle") : t("confirmRevokeTitle")}
          body={
            confirm.type === "rotate"
              ? t("confirmRotateBody", { name: confirm.key.name })
              : t("confirmRevokeBody", { name: confirm.key.name })
          }
          confirmLabel={confirm.type === "rotate" ? t("confirmRotateButton") : t("confirmRevokeButton")}
          busy={busyIds.has(confirm.key.id)}
          onCancel={() => setConfirm(null)}
          onConfirm={handleConfirm}
        />
      )}
    </AppShell>
  );
}
