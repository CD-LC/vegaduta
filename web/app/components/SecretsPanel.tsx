"use client";

// The tenant secrets vault UI (core/secrets, /api/secrets).
//
// Two things about this panel are deliberate and should survive future edits:
//
// 1. THERE IS NO "REVEAL" AFFORDANCE. The backend has no endpoint that returns
//    a stored value (SecretVaultController's javadoc says why), so this panel
//    cannot show one even if someone adds a button. What it shows instead is
//    `preview` - the last four characters - which is enough to tell two keys
//    apart when deciding which to rotate. Do not add an eye-toggle here; the
//    one on /tools/credentials reveals a value the client already holds in
//    form state, which is a different situation.
//
// 2. THE COPYABLE THING IS THE REFERENCE, NEVER A VALUE. Each row copies
//    "{{secret.NAME}}" - the string you paste into an agent, tool or MCP
//    config. That indirection is the whole security model: the reference is
//    resolved inside the tool-execution boundary, so the secret never reaches
//    the model. Making the safe thing the one-click thing is what stops people
//    pasting the real key into a system prompt instead.

import { useCallback, useEffect, useMemo, useState, type FormEvent } from "react";
import { Building2, Code2, KeyRound, Globe, Lock, Search, User as UserIcon } from "lucide-react";
import { api, type VaultSecret } from "../lib/api";
import { useAuth } from "./AuthProvider";
import { useTranslations } from "../lib/i18n";
import { parseHostList } from "../lib/secretHosts";
import { Section, EmptyState } from "./ax/page-kit";
import { Button } from "./ui/button";
import { Input } from "./ui/input";
import { Label } from "./ui/label";
import { Alert, AlertDescription } from "./ui/alert";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "./ui/alert-dialog";
import { cn } from "../lib/utils";
import PaginationControls from "./PaginationControls";
import { usePagination } from "../lib/usePagination";
import SecretCard from "./secrets/SecretCard";
import ScopeChoice from "./secrets/ScopeChoice";

const KINDS: VaultSecret["kind"][] = ["API_KEY", "PASSWORD", "TOKEN", "BASIC_AUTH", "CUSTOM"];

// Mirrors SecretVaultService.VALID_NAME exactly. Validating client-side is a
// convenience, not a control - the server re-checks - but matching the pattern
// byte-for-byte means the inline hint can never contradict the 400 that a
// mismatch would produce.
const VALID_NAME = /^[A-Z][A-Z0-9_]{0,63}$/;

interface SecretsPanelProps {
  secrets: VaultSecret[];
  onChanged: () => void;
}

export default function SecretsPanel({ secrets, onChanged }: SecretsPanelProps) {
  const tHC = useTranslations("hcComponentsSecretsPanel");
  const t = useTranslations("secretsPanel");
  const { hasPermission } = useAuth();
  const canManageTenantSecrets = hasPermission("MANAGE_INTEGRATIONS");

  const [adding, setAdding] = useState(false);
  const [name, setName] = useState("");
  const [scope, setScope] = useState<VaultSecret["scope"]>("USER");
  const [kind, setKind] = useState<VaultSecret["kind"]>("API_KEY");
  const [description, setDescription] = useState("");
  const [value, setValue] = useState("");
  const [expiresAt, setExpiresAt] = useState("");
  const [codeToolAccess, setCodeToolAccess] = useState(false);
  const [allowedHostsText, setAllowedHostsText] = useState("");

  // Per-card allowed-hosts editor (SEC-006): one secret at a time.
  const [editingHostsId, setEditingHostsId] = useState<string | null>(null);
  const [hostsDraft, setHostsDraft] = useState("");
  const [hostsBusy, setHostsBusy] = useState(false);
  const [hostsError, setHostsError] = useState<string | null>(null);

  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);
  const [copiedId, setCopiedId] = useState<string | null>(null);

  const [revoking, setRevoking] = useState<VaultSecret | null>(null);
  const [purging, setPurging] = useState<VaultSecret | null>(null);
  const [deleteBusy, setDeleteBusy] = useState(false);

  // A non-admin cannot write a workspace-wide secret (the server enforces this
  // too - SecretVaultController gates TENANT scope on ROLE_tenant-admin). Force
  // their scope selection to USER so the form cannot submit something that is
  // going to come back 403.
  useEffect(() => {
    if (!canManageTenantSecrets && scope === "TENANT") setScope("USER");
  }, [canManageTenantSecrets, scope]);

  function resetForm() {
    setName("");
    setScope(canManageTenantSecrets ? "TENANT" : "USER");
    setKind("API_KEY");
    setDescription("");
    setValue("");
    setExpiresAt("");
    setCodeToolAccess(false);
    setAllowedHostsText("");
  }

  /** Human sentence for a host-list validation failure (mirrors core's SecretHostPolicy). */
  function hostsErrorText(r: Extract<ReturnType<typeof parseHostList>, { ok: false }>): string {
    return t(`hostsError_${r.code}`, { entry: r.entry });
  }

  function startEditHosts(secret: VaultSecret) {
    setEditingHostsId(secret.id);
    setHostsDraft((secret.allowedHosts ?? []).join("\n"));
    setHostsError(null);
  }

  async function saveHosts(secret: VaultSecret) {
    const parsed = parseHostList(hostsDraft);
    if (parsed.ok === false) {
      setHostsError(hostsErrorText(parsed));
      return;
    }
    setHostsBusy(true);
    setHostsError(null);
    try {
      await api.updateSecretAllowedHosts(secret.id, parsed.hosts);
      setSuccess(t("hostsSavedSuccess", { name: secret.name }));
      setEditingHostsId(null);
      onChanged();
    } catch (err: any) {
      setHostsError(err?.message || t("hostsSaveFailedError"));
    } finally {
      setHostsBusy(false);
    }
  }

  /** Prefills the form for a rotation: same name and scope, empty value. */
  const startRotate = useCallback((secret: VaultSecret) => {
    setAdding(true);
    setName(secret.name);
    setScope(secret.scope);
    setKind(secret.kind);
    setDescription(secret.description ?? "");
    setValue("");
    setExpiresAt(secret.expiresAt ? secret.expiresAt.slice(0, 10) : "");
    setCodeToolAccess(secret.codeToolAccess);
    setAllowedHostsText((secret.allowedHosts ?? []).join("\n"));
    setError(null);
    setSuccess(null);
  }, []);

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setSuccess(null);

    if (!VALID_NAME.test(name.trim())) {
      setError(t("nameInvalidError"));
      return;
    }
    if (!value) {
      setError(t("valueRequiredError"));
      return;
    }

    const hosts = parseHostList(allowedHostsText);
    if (hosts.ok === false) {
      setError(hostsErrorText(hosts));
      return;
    }

    setBusy(true);
    try {
      await api.storeSecret({
        name: name.trim(),
        scope,
        kind,
        description: description.trim() || null,
        value,
        // <input type="date"> yields YYYY-MM-DD; the API wants an Instant.
        // End-of-day UTC, so "expires on the 5th" stays usable all of the 5th.
        expiresAt: expiresAt ? new Date(`${expiresAt}T23:59:59Z`).toISOString() : null,
        codeToolAccess,
        allowedHosts: hosts.hosts,
      });
      setSuccess(t("storedSuccess", { name: name.trim() }));
      // Clear the plaintext out of React state the moment it is no longer
      // needed - it has nowhere else to go, but leaving it in a live component
      // keeps it in a heap snapshot for as long as the page is open.
      setValue("");
      resetForm();
      setAdding(false);
      onChanged();
    } catch (err: any) {
      setError(err?.message || t("storeFailedError"));
    } finally {
      setBusy(false);
    }
  }

  async function handleDelete(secret: VaultSecret, purge: boolean) {
    setDeleteBusy(true);
    setError(null);
    try {
      if (purge) {
        await api.purgeSecret(secret.id);
        setSuccess(t("purgedSuccess", { name: secret.name }));
      } else {
        await api.revokeSecret(secret.id);
        setSuccess(t("revokedSuccess", { name: secret.name }));
      }
      setRevoking(null);
      setPurging(null);
      onChanged();
    } catch (err: any) {
      setError(err?.message || t("deleteFailedError"));
    } finally {
      setDeleteBusy(false);
    }
  }

  function handleCopy(secret: VaultSecret) {
    if (!navigator?.clipboard?.writeText) {
      setError(t("copyFailedError"));
      return;
    }
    navigator.clipboard.writeText(secret.reference).then(
      () => {
        setCopiedId(secret.id);
        setTimeout(() => setCopiedId((id) => (id === secret.id ? null : id)), 1500);
      },
      () => setError(t("copyFailedError"))
    );
  }

  const activeCount = secrets.filter((s) => s.status === "ACTIVE" && !s.expired).length;
  const tl = useTranslations("tenantLists");
  const [secretQuery, setSecretQuery] = useState("");
  const [secretStatus, setSecretStatus] = useState<"all" | "active" | "inactive">("all");
  const filteredSecrets = useMemo(() => {
    const q = secretQuery.trim().toLowerCase();
    return secrets.filter((s) => {
      const live = s.status === "ACTIVE" && !s.expired;
      if (secretStatus === "active" && !live) return false;
      if (secretStatus === "inactive" && live) return false;
      return (
        !q ||
        String(s.name ?? "")
          .toLowerCase()
          .includes(q)
      );
    });
  }, [secrets, secretQuery, secretStatus]);
  const secretsPagination = usePagination(filteredSecrets);

  return (
    <Section title={t("sectionLabel", { active: activeCount, total: secrets.length })} description={t("description")}>
      {error && (
        <Alert variant="destructive" className="mb-4" data-testid="secrets-error">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}
      {success && (
        <Alert className="mb-4" data-testid="secrets-success">
          <AlertDescription>{success}</AlertDescription>
        </Alert>
      )}

      {!canManageTenantSecrets && <p className="mb-4 text-sm text-muted-foreground">{t("nonAdminNote")}</p>}

      {!adding && (
        <Button
          size="sm"
          onClick={() => {
            resetForm();
            setAdding(true);
          }}
          data-testid="secrets-add"
          data-tour="secret-add"
        >
          <KeyRound aria-hidden="true" size={14} /> {t("addButton")}
        </Button>
      )}

      {adding && (
        <form onSubmit={handleSubmit} className="space-y-4" data-testid="secrets-form">
          <div className="grid gap-4 sm:grid-cols-2">
            <div className="space-y-1.5">
              <Label htmlFor="secret-name">{t("nameLabel")}</Label>
              <Input
                id="secret-name"
                value={name}
                onChange={(e) => setName(e.target.value.toUpperCase())}
                placeholder="STRIPE_KEY"
                autoFocus
                required
              />
              <p className="text-xs text-muted-foreground">{t("nameHint")}</p>
            </div>

            <div className="space-y-1.5">
              <Label htmlFor="secret-value">{t("valueLabel")}</Label>
              <Input
                id="secret-value"
                type="password"
                value={value}
                onChange={(e) => setValue(e.target.value)}
                placeholder={t("valuePlaceholder")}
                autoComplete="off"
                required
              />
              <p className="text-xs text-muted-foreground">{t("valueHint")}</p>
            </div>
          </div>

          {/* Segmented controls rather than <select>: there is no select
              primitive in app/components/ui, and a two- or five-option choice
              reads better as buttons anyway. */}
          <div className="space-y-1.5">
            <Label>{t("scopeLabel")}</Label>
            <div className="flex flex-wrap gap-2">
              <ScopeChoice
                active={scope === "TENANT"}
                disabled={!canManageTenantSecrets}
                onClick={() => setScope("TENANT")}
                icon={<Building2 aria-hidden="true" size={14} />}
                label={t("scopeTenant")}
                hint={t("scopeTenantHint")}
                testid="secrets-scope-tenant"
              />
              <ScopeChoice
                active={scope === "USER"}
                disabled={false}
                onClick={() => setScope("USER")}
                icon={<UserIcon aria-hidden="true" size={14} />}
                label={t("scopeUser")}
                hint={t("scopeUserHint")}
                testid="secrets-scope-user"
              />
            </div>
          </div>

          <div className="space-y-1.5">
            <Label>{t("kindLabel")}</Label>
            <div className="flex flex-wrap gap-2">
              {KINDS.map((k) => (
                <button
                  key={k}
                  type="button"
                  onClick={() => setKind(k)}
                  aria-pressed={kind === k}
                  className={cn(
                    "rounded-md px-3 py-1.5 text-xs font-medium",
                    kind === k && "border-primary bg-primary/10 text-primary"
                  )}
                >
                  {t(`kind${k}`)}
                </button>
              ))}
            </div>
            {kind === "PASSWORD" && <p className="text-xs text-muted-foreground">{t("kindPasswordNote")}</p>}
          </div>

          <div className="grid gap-4 sm:grid-cols-2">
            <div className="space-y-1.5">
              <Label htmlFor="secret-description">{t("descriptionLabel")}</Label>
              <Input
                id="secret-description"
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                placeholder={t("descriptionPlaceholder")}
              />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="secret-expires">{t("expiresLabel")}</Label>
              <Input id="secret-expires" type="date" value={expiresAt} onChange={(e) => setExpiresAt(e.target.value)} />
              <p className="text-xs text-muted-foreground">{t("expiresHint")}</p>
            </div>
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="secret-allowed-hosts" className="flex items-center gap-1.5">
              <Globe size={14} /> {t("allowedHostsLabel")}
            </Label>
            <textarea
              id="secret-allowed-hosts"
              data-testid="secrets-allowed-hosts"
              value={allowedHostsText}
              onChange={(e) => setAllowedHostsText(e.target.value)}
              rows={2}
              placeholder={tHC("s1")}
              spellCheck={false}
              autoComplete="off"
              className="w-full rounded-md border bg-transparent px-3 py-2 font-mono text-xs"
            />
            <p className="text-xs text-muted-foreground">{t("allowedHostsHint")}</p>
          </div>

          {/* Opt-in, default off. Deliberately worded as a disclosure rather
              than a feature: a program that holds a plaintext value can always
              return it, so this really does hand the value to anyone who can
              author a code tool here. */}
          <div className="space-y-1.5">
            <button
              type="button"
              onClick={() => setCodeToolAccess((v) => !v)}
              aria-pressed={codeToolAccess}
              data-testid="secrets-codetool-toggle"
              className={cn(
                "flex max-w-xl flex-col items-start gap-0.5 rounded-md px-3 py-2 text-start",
                codeToolAccess && "border-primary bg-primary/10"
              )}
            >
              <span className="flex items-center gap-1.5 text-xs font-medium">
                <Code2 aria-hidden="true" size={14} /> {t("codeToolAccessLabel")}
              </span>
              <span className="text-xs text-muted-foreground">{t("codeToolAccessHint")}</span>
            </button>
            {codeToolAccess && <p className="text-xs text-destructive">{t("codeToolAccessWarning")}</p>}
          </div>

          <div className="flex flex-wrap items-center gap-2">
            <Button type="submit" size="sm" disabled={busy} data-testid="secrets-save">
              <Lock aria-hidden="true" size={14} /> {busy ? t("savingButton") : t("saveButton")}
            </Button>
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => {
                setAdding(false);
                resetForm();
                setError(null);
              }}
            >
              {t("cancelButton")}
            </Button>
          </div>
        </form>
      )}

      {secrets.length === 0 && !adding ? (
        <EmptyState icon={KeyRound} title={t("emptyTitle")} description={t("emptyDescription")} />
      ) : (
        <>
          {secrets.length > 6 && (
            <div className="mt-6 flex flex-col gap-2 sm:flex-row sm:items-center">
              <div className="relative sm:max-w-xs sm:flex-1">
                <Search
                  size={14}
                  aria-hidden="true"
                  className="pointer-events-none absolute start-3 top-1/2 -translate-y-1/2 text-muted-foreground"
                />
                <Input
                  type="search"
                  value={secretQuery}
                  onChange={(e) => setSecretQuery(e.target.value)}
                  placeholder={tl("searchSecrets")}
                  aria-label={tl("searchSecrets")}
                  className="ps-8"
                />
              </div>
              <div role="group" aria-label={tl("secretStatusFilter")} className="flex gap-1.5">
                {(["all", "active", "inactive"] as const).map((v) => (
                  <Button
                    key={v}
                    type="button"
                    size="sm"
                    variant={secretStatus === v ? "default" : "outline"}
                    aria-pressed={secretStatus === v}
                    onClick={() => setSecretStatus(v)}
                  >
                    {tl(`secretStatus_${v}`)}
                  </Button>
                ))}
              </div>
            </div>
          )}
          {filteredSecrets.length === 0 && (
            <p role="status" className="mt-4 text-sm text-muted-foreground">
              {tl("noMatch")}
            </p>
          )}
          <div className="mt-6 grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {secretsPagination.pageItems.map((secret) => {
              return (
                <SecretCard
                  key={secret.id}
                  secret={secret}
                  copied={copiedId === secret.id}
                  editingHosts={editingHostsId === secret.id}
                  hostsDraft={hostsDraft}
                  setHostsDraft={setHostsDraft}
                  hostsBusy={hostsBusy}
                  hostsError={hostsError}
                  onSaveHosts={() => void saveHosts(secret)}
                  onCancelHosts={() => setEditingHostsId(null)}
                  onCopy={() => handleCopy(secret)}
                  onEditHosts={() => startEditHosts(secret)}
                  onRotate={() => startRotate(secret)}
                  onRevoke={() => setRevoking(secret)}
                  onPurge={() => setPurging(secret)}
                />
              );
            })}
          </div>
        </>
      )}
      <PaginationControls
        page={secretsPagination.page}
        totalPages={secretsPagination.totalPages}
        totalItems={secretsPagination.totalItems}
        pageSize={secretsPagination.pageSize}
        onPageChange={secretsPagination.setPage}
        onPageSizeChange={secretsPagination.setPageSize}
        itemLabel={t("itemLabelSecrets")}
      />

      {/* Revoke: reversible in the sense that the row and its history survive,
          so the copy is matter-of-fact. */}
      <AlertDialog open={!!revoking} onOpenChange={(open) => !open && setRevoking(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t("revokeConfirmTitle", { name: revoking?.name ?? "" })}</AlertDialogTitle>
            <AlertDialogDescription>{t("revokeConfirmBody")}</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={deleteBusy}>{t("cancelButton")}</AlertDialogCancel>
            <AlertDialogAction
              className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
              disabled={deleteBusy}
              onClick={(e) => {
                e.preventDefault();
                if (revoking) void handleDelete(revoking, false);
              }}
            >
              {deleteBusy ? t("revokingButton") : t("revokeButton")}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      {/* Purge is genuinely irreversible AND destroys the audit trail, so it
          gets its own dialog with blunter copy rather than sharing the one
          above with a swapped verb. */}
      <AlertDialog open={!!purging} onOpenChange={(open) => !open && setPurging(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t("purgeConfirmTitle", { name: purging?.name ?? "" })}</AlertDialogTitle>
            <AlertDialogDescription>{t("purgeConfirmBody")}</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={deleteBusy}>{t("cancelButton")}</AlertDialogCancel>
            <AlertDialogAction
              className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
              disabled={deleteBusy}
              onClick={(e) => {
                e.preventDefault();
                if (purging) void handleDelete(purging, true);
              }}
            >
              {deleteBusy ? t("purgingButton") : t("purgeConfirmButton")}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </Section>
  );
}
