"use client";

// Delegation grant wizard for the agent trust console (page.tsx).

import { Select } from "../../components/ui/select";
import { useState } from "react";
import { api } from "../../lib/api";
import type { AgentWallet } from "../../lib/api/agentTrust";
import { Button } from "../../components/ui/button";
import { Card } from "../../components/ui/card";
import { Input } from "../../components/ui/input";
import { Label } from "../../components/ui/label";
import { toast } from "../../lib/toastBus";
import { useTranslations } from "../../lib/i18n";
import { csvList } from "./trustFormat";

// ---- Grant wizard ----------------------------------------------------------

interface GrantForm {
  audience: string;
  scopes: string;
  ttl: string;
  expiresAt: string;
  walletId: string;
  spendCap: string;
}

const EMPTY_GRANT: GrantForm = {
  audience: "",
  scopes: "",
  ttl: "3600",
  expiresAt: "",
  walletId: "",
  spendCap: "",
};

export function GrantWizard({
  agentId,
  wallets,
  onGranted,
}: {
  agentId: string;
  wallets: AgentWallet[];
  onGranted: () => void;
}) {
    const tHC = useTranslations("hcAdminAgenttrustGrantWizard");
  const t = useTranslations("adminAgentTrustPage");
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState<GrantForm>(EMPTY_GRANT);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const hasPayScope = (form.scopes || "").split(",").some((s) => s.trim().startsWith("pay:"));

  const handleCreate = async () => {
    const scopes = csvList(form.scopes);
    if (!form.audience.trim() || !scopes || !form.expiresAt) {
      setError(t("grantInvalid"));
      return;
    }
    const expiresAt = new Date(form.expiresAt);
    if (Number.isNaN(expiresAt.getTime())) {
      setError(t("grantInvalid"));
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await api.createDelegationGrant(agentId, {
        audience: form.audience.trim(),
        scopes,
        maxTokenTtlSeconds: form.ttl ? Number(form.ttl) : null,
        expiresAt: expiresAt.toISOString(),
        walletId: form.walletId || null,
        spendCapMinor: form.spendCap ? Math.round(Number(form.spendCap)) : null,
      });
      toast.success(t("grantCreated"));
      setOpen(false);
      setForm(EMPTY_GRANT);
      onGranted();
    } catch (e: any) {
      setError(e?.message || t("grantFailed"));
    } finally {
      setBusy(false);
    }
  };

  if (!open) {
    return (
      <Button type="button" size="sm" onClick={() => setOpen(true)}>
        {t("newGrant")}
      </Button>
    );
  }

  return (
    <Card className="p-4">
      <div className="mb-3 flex items-center justify-between">
        <h3 className="text-sm font-semibold">{t("grantWizardTitle")}</h3>
        <Button type="button" variant="ghost" size="sm" onClick={() => { setOpen(false); setError(null); }}>
          {t("cancel")}
        </Button>
      </div>
      <div className="grid gap-3 sm:grid-cols-2">
        <div className="grid gap-1.5">
          <Label htmlFor="grant-audience">{t("fieldAudience")}</Label>
          <Input
            id="grant-audience"
            value={form.audience}
            placeholder={tHC("hcAdminAgenttrustGrantWizard.s2")}
            onChange={(e) => setForm((f) => ({ ...f, audience: e.target.value }))}
          />
          <p className="text-xs text-muted-foreground">{t("fieldAudienceHint")}</p>
        </div>
        <div className="grid gap-1.5">
          <Label htmlFor="grant-scopes">{t("fieldScopes")}</Label>
          <Input
            id="grant-scopes"
            value={form.scopes}
            placeholder={tHC("hcAdminAgenttrustGrantWizard.s1")}
            onChange={(e) => setForm((f) => ({ ...f, scopes: e.target.value }))}
          />
          <p className="text-xs text-muted-foreground">{t("fieldScopesHint")}</p>
        </div>
        <div className="grid gap-1.5">
          <Label htmlFor="grant-ttl">{t("fieldTtl")}</Label>
          <Input
            id="grant-ttl"
            inputMode="numeric"
            value={form.ttl}
            onChange={(e) => setForm((f) => ({ ...f, ttl: e.target.value }))}
          />
        </div>
        <div className="grid gap-1.5">
          <Label htmlFor="grant-exp">{t("fieldGrantExpiry")}</Label>
          <Input
            id="grant-exp"
            type="datetime-local"
            value={form.expiresAt}
            onChange={(e) => setForm((f) => ({ ...f, expiresAt: e.target.value }))}
          />
        </div>
        {hasPayScope && (
          <>
            <div className="grid gap-1.5">
              <Label htmlFor="grant-wallet">{t("fieldWallet")}</Label>
              <Select
                id="grant-wallet"
                value={form.walletId}
                onValueChange={(v) => setForm((f) => ({ ...f, walletId: v }))}
                options={[
                  { value: "", label: t("walletPick") },
                  ...wallets.map((w) => ({
                    value: w.id,
                    label: `${w.currency} · ${w.id.slice(0, 8)}… · bal ${w.balanceMinor}`,
                  })),
                ]}
              />
              <p className="text-xs text-muted-foreground">{t("fieldWalletHint")}</p>
            </div>
            <div className="grid gap-1.5">
              <Label htmlFor="grant-cap">{t("fieldSpendCap")}</Label>
              <Input
                id="grant-cap"
                inputMode="numeric"
                placeholder={t("fieldSpendCapPlaceholder")}
                value={form.spendCap}
                onChange={(e) => setForm((f) => ({ ...f, spendCap: e.target.value }))}
              />
            </div>
          </>
        )}
      </div>
      {error && <div className="mt-3 text-sm text-destructive">{error}</div>}
      <div className="mt-4 flex gap-2">
        <Button type="button" size="sm" disabled={busy} onClick={handleCreate}>
          {t("createGrant")}
        </Button>
      </div>
    </Card>
  );
}
