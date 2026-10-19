"use client";

// Which vault secrets one custom code tool may receive.
//
// Names only. There is no value anywhere in this component, no input that
// accepts one, and no API call that could return one - a grant is a reference
// to something already in the vault (/secrets), resolved server-side at
// execution time and handed to the sandbox process, never to the model.
//
// Deliberately a toggle-list of EXISTING vault secrets rather than a free-text
// box. Typing a name invites typos that look identical to a revoked secret at
// runtime ("declared but never resolved"), and picking from the list makes the
// blast radius of a grant visible at the moment it is made.

import { useState } from "react";
import { KeyRound, Loader2 } from "lucide-react";
import { api, type VaultSecret } from "../lib/api";
import { useTranslations } from "../lib/i18n";
import { Button } from "./ui/button";
import { Badge } from "./ui/badge";
import { cn } from "../lib/utils";

interface ToolSecretGrantsProps {
  toolId: string;
  /** Names currently granted, from CustomCodeToolResponse.allowedSecretNames. */
  granted: string[];
  /** The workspace-scoped secrets available to grant. Personal ones never resolve here. */
  available: VaultSecret[];
  /** Granting is workspace-admin only, matching the server. */
  canEdit: boolean;
  onChanged: (names: string[]) => void;
}

export default function ToolSecretGrants({ toolId, granted, available, canEdit, onChanged }: ToolSecretGrantsProps) {
  const t = useTranslations("toolSecretGrants");
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Only TENANT-scoped secrets are offered: a custom code tool is authored by
  // one person and run by many, so resolving a personal secret here would let
  // the author harvest whoever-ran-it's credential. The server enforces this
  // too (it resolves with a null user id); the UI simply does not offer the
  // impossible choice.
  // ...and only those explicitly opted in to code-tool use. Without that
  // filter, granting here would be a read path for the whole vault: a program
  // can return its own credential transformed past any redaction, so a grant
  // IS a disclosure. The opt-in is the containment; this list just reflects it.
  const grantable = available.filter((s) => s.scope === "TENANT" && s.status === "ACTIVE" && s.codeToolAccess);

  async function toggle(name: string) {
    const next = granted.includes(name) ? granted.filter((n) => n !== name) : [...granted, name];
    setBusy(true);
    setError(null);
    try {
      await api.setCustomCodeToolSecrets(toolId, next);
      onChanged(next);
    } catch (err: any) {
      setError(err?.message || t("saveFailedError"));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="mt-3" data-testid={`tool-secrets-${toolId}`}>
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        className="flex items-center gap-1.5 border-0 bg-transparent p-0 text-xs text-muted-foreground shadow-none hover:text-foreground"
        aria-expanded={open}
      >
        <KeyRound aria-hidden="true" size={12} />
        {granted.length > 0 ? t("summaryGranted", { count: granted.length }) : t("summaryNone")}
      </button>

      {granted.length > 0 && !open && (
        <div className="mt-1.5 flex flex-wrap gap-1">
          {granted.map((name) => (
            <Badge key={name} variant="secondary" className="font-mono text-[0.6875rem]">
              {name}
            </Badge>
          ))}
        </div>
      )}

      {open && (
        <div className="mt-2 space-y-2">
          {!canEdit && <p className="text-xs text-muted-foreground">{t("adminOnlyNote")}</p>}
          {grantable.length === 0 ? (
            <p className="text-xs text-muted-foreground">{t("noneAvailable")}</p>
          ) : (
            <div className="flex flex-wrap gap-1.5">
              {grantable.map((secret) => {
                const on = granted.includes(secret.name);
                return (
                  <button
                    key={secret.id}
                    type="button"
                    disabled={!canEdit || busy}
                    aria-pressed={on}
                    onClick={() => toggle(secret.name)}
                    data-testid={`tool-secret-toggle-${secret.name}`}
                    className={cn(
                      "rounded-md px-2 py-1 font-mono text-[0.6875rem]",
                      on && "border-primary bg-primary/10 text-primary",
                      (!canEdit || busy) && "cursor-not-allowed opacity-50"
                    )}
                  >
                    {secret.name}
                  </button>
                );
              })}
            </div>
          )}
          {busy && (
            <p className="flex items-center gap-1.5 text-xs text-muted-foreground">
              <Loader2 aria-hidden="true" size={12} className="animate-spin" /> {t("savingLabel")}
            </p>
          )}
          {error && <p className="text-xs text-destructive">{error}</p>}
          <p className="text-xs text-muted-foreground">{t("howToUseHint")}</p>
          <Button type="button" variant="ghost" size="sm" onClick={() => setOpen(false)}>
            {t("doneButton")}
          </Button>
        </div>
      )}
    </div>
  );
}
