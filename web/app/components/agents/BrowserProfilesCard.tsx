"use client";

import { useCallback, useEffect, useState } from "react";
import { Loader2 } from "lucide-react";
import { api } from "../../lib/api";
import type { BrowserProfile, BrowserProfileScope } from "../../lib/api/browserAutomation";
import {
  attachableProfiles,
  daysUntil,
  formatBytes,
  parseDomainInput,
  profileForAgent,
  profileStatus,
} from "../../lib/browserAutomation";
import { useTranslations } from "../../lib/i18n";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { Select } from "../ui/select";

// Saved logins (core /api/browser/profiles): list, create, attach one to this agent, forget its login,
// delete. Metadata only - the saved cookies never reach the browser. Self-contained (own fetch and
// busy/error state) like AgentGuardrailsCard, taking `locked` from the parent because the FREE-tier
// edit lock is a per-control obligation in the workbench.

export default function BrowserProfilesCard({
  agentId,
  locked,
  editLockedTooltip,
}: {
  agentId: string;
  locked: boolean;
  editLockedTooltip?: string;
}) {
  const t = useTranslations("browserAutomation");
  const [profiles, setProfiles] = useState<BrowserProfile[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [confirmDeleteId, setConfirmDeleteId] = useState<string | null>(null);
  const [pickId, setPickId] = useState("");
  // new-profile form
  const [name, setName] = useState("");
  const [domainText, setDomainText] = useState("");
  const [scope, setScope] = useState<BrowserProfileScope>("TENANT");
  const [ttl, setTtl] = useState("30");

  const load = useCallback(() => {
    setFailed(false);
    api
      .listBrowserProfiles()
      .then((list: BrowserProfile[]) => setProfiles(Array.isArray(list) ? list : []))
      .catch(() => setFailed(true));
  }, []);

  useEffect(() => {
    setProfiles(null);
    load();
  }, [load]);

  // Runs one change, then re-reads server truth (a 403/404 means ours is stale). api.ts has already raised
  // the global toast for a failure, so only a short inline line is added here.
  const run = async (change: () => Promise<unknown>, done?: string) => {
    if (busy) return;
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      await change();
      if (done) setNotice(done);
    } catch (e: unknown) {
      setError(e instanceof Error && e.message ? e.message : t("actionFailed"));
    } finally {
      setBusy(false);
      setConfirmDeleteId(null);
      load();
    }
  };

  const create = async () => {
    const trimmed = name.trim();
    if (!trimmed) {
      setError(t("needName"));
      return;
    }
    const { domains, invalid } = parseDomainInput(domainText);
    if (invalid.length > 0) {
      setError(t("invalidDomains", { list: invalid.join(", ") }));
      return;
    }
    if (domains.length === 0) {
      setError(t("needDomains"));
      return;
    }
    const ttlDays = Number.parseInt(ttl, 10);
    await run(
      () =>
        api.createBrowserProfile({
          name: trimmed,
          allowedDomains: domains,
          scope,
          ...(Number.isFinite(ttlDays) && ttlDays > 0 ? { ttlDays } : {}),
        }),
      t("profileCreated")
    );
    setName("");
    setDomainText("");
  };

  const current = profiles ? profileForAgent(profiles, agentId) : undefined;
  const options = profiles ? attachableProfiles(profiles, agentId) : [];
  const disabled = locked || busy;
  const now = new Date();

  return (
    <div className="mb-4" data-testid="browser-profiles-card">
      <div className="text-sm font-medium text-foreground">{t("profilesTitle")}</div>
      <p className="mt-0.5 text-xs text-muted-foreground">{t("profilesHelp")}</p>

      {profiles === null && !failed && (
        <div className="mt-2 flex items-center gap-2 text-xs text-muted-foreground">
          <Loader2 className="size-3.5 animate-spin" aria-hidden="true" />
          {t("loading")}
        </div>
      )}
      {failed && <p className="mt-2 text-xs text-destructive">{t("loadFailed")}</p>}
      {error && (
        <p role="alert" className="mt-2 text-xs text-destructive">
          {error}
        </p>
      )}
      {notice && (
        <p role="status" className="mt-2 text-xs text-muted-foreground">
          {notice}
        </p>
      )}

      {profiles && (
        <>
          <div
            className="mt-2 rounded-md border border-border bg-card px-3 py-2 text-xs"
            data-testid="browser-profile-current"
          >
            {current ? (
              <div className="flex flex-wrap items-center justify-between gap-2">
                <span className="text-foreground">{t("currentProfile", { name: current.name })}</span>
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  disabled={disabled}
                  title={locked ? editLockedTooltip : undefined}
                  onClick={() => void run(() => api.detachBrowserProfile(current.id, agentId))}
                >
                  {t("stopUsing")}
                </Button>
              </div>
            ) : (
              <span className="text-muted-foreground">{t("noCurrentProfile")}</span>
            )}
            {options.length > 0 && (
              <div className="mt-2 flex flex-wrap items-center gap-2">
                <Select
                  aria-label={t("useForAgent")}
                  className="min-w-0 flex-1 [&_button]:h-8 [&_button]:text-xs"
                  value={pickId}
                  disabled={disabled}
                  onValueChange={setPickId}
                  options={[
                    { value: "", label: "-" },
                    ...options.map((p) => ({
                      value: p.id,
                      label: `${p.name} (${p.scope === "USER" ? t("scopePersonal") : t("scopeShared")})`,
                    })),
                  ]}
                />
                <Button
                  type="button"
                  size="sm"
                  disabled={disabled || !pickId}
                  title={locked ? editLockedTooltip : undefined}
                  onClick={() => void run(() => api.attachBrowserProfile(pickId, agentId)).then(() => setPickId(""))}
                >
                  {t("useForAgent")}
                </Button>
              </div>
            )}
          </div>

          {profiles.length === 0 ? (
            <p className="mt-2 text-xs text-muted-foreground">{t("profilesEmpty")}</p>
          ) : (
            <ul className="mt-2 space-y-2">
              {profiles.map((p) => {
                const status = profileStatus(p);
                const days = daysUntil(p.expiresAt, now);
                return (
                  <li
                    key={p.id}
                    className="rounded-md border border-border bg-card px-3 py-2"
                    data-testid={`browser-profile-${p.id}`}
                  >
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <span className="text-sm font-medium text-foreground">
                        {p.name}{" "}
                        <span className="rounded-full border border-border px-1.5 py-px text-[11px] uppercase tracking-wide text-muted-foreground">
                          {p.scope === "USER" ? t("scopePersonal") : t("scopeShared")}
                        </span>
                      </span>
                      <span className="flex flex-wrap gap-1.5">
                        {p.hasState && (
                          <Button
                            type="button"
                            variant="outline"
                            size="sm"
                            disabled={busy}
                            onClick={() => void run(() => api.clearBrowserProfileState(p.id))}
                          >
                            {t("forgetLogin")}
                          </Button>
                        )}
                        {confirmDeleteId === p.id ? (
                          <Button
                            type="button"
                            variant="destructive"
                            size="sm"
                            disabled={busy}
                            onClick={() => void run(() => api.deleteBrowserProfile(p.id))}
                          >
                            {t("confirmDelete")}
                          </Button>
                        ) : (
                          <Button
                            type="button"
                            variant="outline"
                            size="sm"
                            disabled={busy}
                            onClick={() => setConfirmDeleteId(p.id)}
                          >
                            {t("deleteProfile")}
                          </Button>
                        )}
                      </span>
                    </div>
                    <div className="mt-1 text-xs text-muted-foreground">
                      {t("domainsLine", { domains: p.allowedDomains.join(", ") })}
                    </div>
                    <div className="mt-0.5 text-xs text-muted-foreground">
                      {status === "no-login" && t("statusNoLogin")}
                      {status === "active" &&
                        t("statusActive", {
                          size: formatBytes(p.stateBytes),
                          entries: p.stateEntries,
                          days: days === null ? "-" : Math.max(days, 0),
                        })}
                      {status === "expired" && t("statusExpired")}
                    </div>
                    {p.attachedAgentIds.length > 0 && (
                      <div className="mt-0.5 text-[11px] text-muted-foreground">
                        {t("usedByAgents", { count: p.attachedAgentIds.length })}
                      </div>
                    )}
                    {confirmDeleteId === p.id && (
                      <div className="mt-1 text-[11px] text-muted-foreground">{t("deleteHint")}</div>
                    )}
                  </li>
                );
              })}
            </ul>
          )}

          <div className="mt-3 rounded-md border border-dashed border-border px-3 py-2">
            <div className="text-xs font-medium text-foreground">{t("newProfileTitle")}</div>
            <div className="mt-2 grid grid-cols-1 gap-2 sm:grid-cols-2">
              <label className="block text-xs text-muted-foreground">
                {t("fieldName")}
                <Input
                  className="mt-1 h-8 text-xs"
                  value={name}
                  maxLength={120}
                  placeholder={t("fieldNamePlaceholder")}
                  disabled={disabled}
                  onChange={(e) => setName(e.target.value)}
                />
              </label>
              <label className="block text-xs text-muted-foreground">
                {t("fieldTtl")}
                <Input
                  className="mt-1 h-8 text-xs"
                  inputMode="numeric"
                  value={ttl}
                  disabled={disabled}
                  onChange={(e) => setTtl(e.target.value.replace(/[^0-9]/g, ""))}
                />
              </label>
              <label className="block text-xs text-muted-foreground sm:col-span-2">
                {t("fieldDomains")}
                <Input
                  className="mt-1 h-8 text-xs"
                  value={domainText}
                  placeholder={t("fieldDomainsPlaceholder")}
                  disabled={disabled}
                  onChange={(e) => setDomainText(e.target.value)}
                />
                <span className="mt-0.5 block text-[11px] text-muted-foreground">{t("fieldDomainsHint")}</span>
              </label>
              <label className="block text-xs text-muted-foreground">
                {t("fieldScope")}
                <Select
                  className="mt-1 [&_button]:h-8 [&_button]:text-xs"
                  aria-label={t("fieldScope")}
                  value={scope}
                  disabled={disabled}
                  onValueChange={(v) => setScope(v === "USER" ? "USER" : "TENANT")}
                  options={[
                    { value: "TENANT", label: t("scopeShared") },
                    { value: "USER", label: t("scopePersonal") },
                  ]}
                />
              </label>
            </div>
            <div className="mt-2 flex items-center gap-2">
              <Button
                type="button"
                size="sm"
                disabled={disabled}
                title={locked ? editLockedTooltip : undefined}
                onClick={() => void create()}
              >
                {busy ? t("creating") : t("createProfile")}
              </Button>
              {locked && <span className="text-[11px] text-muted-foreground">{t("lockedHint")}</span>}
            </div>
          </div>
        </>
      )}
    </div>
  );
}
