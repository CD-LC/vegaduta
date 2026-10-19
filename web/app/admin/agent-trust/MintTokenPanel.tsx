"use client";

// Inline mint-token panel for one delegation grant (agent trust console).

import { useState } from "react";
import { api } from "../../lib/api";
import type { DelegationGrant, MintedDelegationToken } from "../../lib/api/agentTrust";
import { Button } from "../../components/ui/button";
import { Input } from "../../components/ui/input";
import { Label } from "../../components/ui/label";
import { toast } from "../../lib/toastBus";
import { useTranslations } from "../../lib/i18n";
import { fmtTime } from "./trustFormat";

// ---- Mint-token inline panel ------------------------------------------------

export function MintTokenPanel({ agentId, grant }: { agentId: string; grant: DelegationGrant }) {
  const t = useTranslations("adminAgentTrustPage");
  const [open, setOpen] = useState(false);
  const [ttl, setTtl] = useState("");
  const [busy, setBusy] = useState(false);
  const [minted, setMinted] = useState<MintedDelegationToken | null>(null);
  const [copied, setCopied] = useState(false);

  const handleMint = async () => {
    setBusy(true);
    try {
      const res = await api.mintDelegationToken(
        agentId, grant.id, ttl ? Number(ttl) : undefined);
      setMinted(res);
    } catch (e: any) {
      toast.error(e?.message || t("mintFailed"));
    } finally {
      setBusy(false);
    }
  };

  const handleCopy = async () => {
    if (!minted) return;
    try {
      await navigator.clipboard.writeText(minted.token);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      toast.error(t("copyFailed"));
    }
  };

  if (!open) {
    return (
      <Button type="button" variant="outline" size="sm" onClick={() => setOpen(true)}>
        {t("mintToken")}
      </Button>
    );
  }

  return (
    <div className="mt-2 rounded-md border border-border p-3">
      {!minted ? (
        <div className="flex flex-wrap items-end gap-2">
          <div className="grid gap-1">
            <Label htmlFor={`ttl-${grant.id}`}>{t("mintTtlLabel")}</Label>
            <Input
              id={`ttl-${grant.id}`}
              className="w-36"
              inputMode="numeric"
              placeholder={String(grant.maxTokenTtlSeconds)}
              value={ttl}
              onChange={(e) => setTtl(e.target.value)}
            />
          </div>
          <Button type="button" size="sm" disabled={busy} onClick={handleMint}>
            {t("mintConfirm")}
          </Button>
          <Button type="button" variant="ghost" size="sm" onClick={() => setOpen(false)}>
            {t("cancel")}
          </Button>
        </div>
      ) : (
        <div className="grid gap-2">
          <p className="text-xs text-muted-foreground">{t("mintOnceHint")}</p>
          <pre className="max-h-32 overflow-auto rounded-md border border-border bg-secondary/40 p-2 font-mono text-xs break-all whitespace-pre-wrap">
            {minted.token}
          </pre>
          <div className="flex items-center gap-2 text-xs text-muted-foreground">
            <span>jti: <code className="font-mono">{minted.jti}</code></span>
            <span>· exp {fmtTime(minted.expiresAt)}</span>
            <Button type="button" variant="outline" size="sm" onClick={handleCopy}>
              {copied ? t("copied") : t("copyToken")}
            </Button>
            <Button type="button" variant="ghost" size="sm" onClick={() => { setOpen(false); setMinted(null); }}>
              {t("done")}
            </Button>
          </div>
        </div>
      )}
    </div>
  );
}
