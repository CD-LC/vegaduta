"use client";

import { Check, Copy, Pencil, RotateCcw, ShieldAlert, Trash2 } from "lucide-react";
import type { VaultSecret } from "../../lib/api";
import { useTranslations } from "../../lib/i18n";
import { Button } from "../ui/button";
import { Badge } from "../ui/badge";
import { Card } from "../ui/card";
import { Alert, AlertDescription } from "../ui/alert";

/**
 * One vault secret on SecretsPanel's grid: name, masked preview, scope/kind/
 * status badges, the allowed-hosts list (with its inline editor while
 * `editingHosts`), and the row actions. Copy copies the REFERENCE, never a
 * value (see SecretsPanel's header).
 */
export default function SecretCard({
  secret,
  copied,
  editingHosts,
  hostsDraft,
  setHostsDraft,
  hostsBusy,
  hostsError,
  onSaveHosts,
  onCancelHosts,
  onCopy,
  onEditHosts,
  onRotate,
  onRevoke,
  onPurge,
}: {
  secret: VaultSecret;
  copied: boolean;
  editingHosts: boolean;
  hostsDraft: string;
  setHostsDraft: (draft: string) => void;
  hostsBusy: boolean;
  hostsError: string | null;
  onSaveHosts: () => void;
  onCancelHosts: () => void;
  onCopy: () => void;
  onEditHosts: () => void;
  onRotate: () => void;
  onRevoke: () => void;
  onPurge: () => void;
}) {
  const t = useTranslations("secretsPanel");
  return (
    <Card className="p-4" data-testid={`secret-card-${secret.name}`}>
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <p className="truncate font-mono text-sm font-semibold">{secret.name}</p>
          <p className="mt-0.5 text-xs text-muted-foreground">
            {secret.preview ? t("previewLabel", { preview: secret.preview }) : t("previewHiddenLabel")}
          </p>
        </div>
        <Badge variant={secret.scope === "TENANT" ? "default" : "secondary"}>
          {secret.scope === "TENANT" ? t("scopeTenant") : t("scopeUser")}
        </Badge>
      </div>

      {secret.description && (
        <p className="mt-2 line-clamp-2 text-xs text-muted-foreground">{secret.description}</p>
      )}

      <div className="mt-3 flex flex-wrap gap-1.5 text-xs">
        <Badge variant="outline">{t(`kind${secret.kind}`)}</Badge>
        {secret.codeToolAccess && (
          <Badge variant="outline" className="border-primary/40 text-primary">
            {t("codeToolAccessBadge")}
          </Badge>
        )}
        {secret.status === "REVOKED" && <Badge variant="destructive">{t("statusRevoked")}</Badge>}
        {secret.expired && secret.status !== "REVOKED" && (
          <Badge variant="destructive">{t("statusExpired")}</Badge>
        )}
      </div>

      {secret.status === "ACTIVE" && (secret.allowedHosts ?? []).length === 0 && (
        <Alert className="mt-3" data-testid={`secret-nohosts-${secret.name}`}>
          <AlertDescription className="text-xs">{t("noHostsWarning")}</AlertDescription>
        </Alert>
      )}
      {(secret.allowedHosts ?? []).length > 0 && (
        <div className="mt-3 flex flex-wrap gap-1.5" data-testid={`secret-hosts-${secret.name}`}>
          {(secret.allowedHosts ?? []).map((h) => (
            <Badge key={h} variant="secondary" className="font-mono text-[11px]">
              {h}
            </Badge>
          ))}
        </div>
      )}
      {editingHosts && (
        <div className="mt-3 space-y-2" data-testid={`secret-hosts-editor-${secret.name}`}>
          <textarea
            aria-label={t("allowedHostsLabel")}
            value={hostsDraft}
            onChange={(e) => setHostsDraft(e.target.value)}
            rows={3}
            spellCheck={false}
            className="w-full rounded-md border bg-transparent px-3 py-2 font-mono text-xs"
            data-testid={`secret-hosts-input-${secret.name}`}
          />
          <p className="text-xs text-muted-foreground">{t("allowedHostsHint")}</p>
          {hostsError && (
            <p className="text-xs text-destructive" role="alert">
              {hostsError}
            </p>
          )}
          <div className="flex gap-2">
            <Button
              type="button"
              size="sm"
              disabled={hostsBusy}
              onClick={onSaveHosts}
              data-testid={`secret-hosts-save-${secret.name}`}
            >
              {hostsBusy ? t("savingButton") : t("hostsSaveButton")}
            </Button>
            <Button
              type="button"
              size="sm"
              variant="outline"
              disabled={hostsBusy}
              onClick={onCancelHosts}
            >
              {t("cancelButton")}
            </Button>
          </div>
        </div>
      )}

      <p className="mt-3 text-xs text-muted-foreground">
        {secret.lastUsedAt
          ? t("lastUsedLabel", { date: new Date(secret.lastUsedAt).toLocaleDateString() })
          : t("neverUsedLabel")}
      </p>

      <div className="mt-3 flex flex-wrap items-center gap-2">
        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={onCopy}
          data-testid={`secret-copy-${secret.name}`}
        >
          {copied ? (
            <Check aria-hidden="true" size={14} />
          ) : (
            <Copy aria-hidden="true" size={14} />
          )}
          {copied ? t("copiedButton") : t("copyReferenceButton")}
        </Button>
        {secret.status === "ACTIVE" && (
          <Button
            type="button"
            variant="ghost"
            size="sm"
            onClick={onEditHosts}
            data-testid={`secret-edit-hosts-${secret.name}`}
          >
            <Pencil size={14} /> {t("editHostsButton")}
          </Button>
        )}
        <Button type="button" variant="ghost" size="sm" onClick={onRotate}>
          <RotateCcw aria-hidden="true" size={14} /> {t("rotateButton")}
        </Button>
        {secret.status === "ACTIVE" ? (
          <Button
            type="button"
            variant="ghost"
            size="sm"
            onClick={onRevoke}
            data-testid={`secret-revoke-${secret.name}`}
          >
            <ShieldAlert aria-hidden="true" size={14} /> {t("revokeButton")}
          </Button>
        ) : (
          <Button
            type="button"
            variant="ghost"
            size="sm"
            onClick={onPurge}
            data-testid={`secret-purge-${secret.name}`}
          >
            <Trash2 aria-hidden="true" size={14} /> {t("purgeButton")}
          </Button>
        )}
      </div>
    </Card>
  );
}
