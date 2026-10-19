"use client";

import { Code2, Pencil, RefreshCw, Trash2 } from "lucide-react";
import type { VaultSecret } from "../../lib/api";
import { useTranslations } from "../../lib/i18n";
import { cn } from "../../lib/utils";
import ToolSecretGrants from "../ToolSecretGrants";
import { Badge } from "../ui/badge";
import { Button } from "../ui/button";
import { Card } from "../ui/card";
import { Switch } from "../ui/switch";
import { statusHue, type CustomCodeTool, type CustomCodeToolStatus } from "./model";

export default function ToolCard({
  tool,
  readOnly,
  busy,
  canManageTools,
  vaultSecrets,
  onToggle,
  onEdit,
  onRevalidate,
  onDelete,
  onGrantsChanged,
}: {
  tool: CustomCodeTool;
  readOnly: boolean;
  busy: boolean;
  canManageTools: boolean;
  vaultSecrets: VaultSecret[];
  onToggle: (tool: CustomCodeTool) => void;
  onEdit: (tool: CustomCodeTool) => void;
  onRevalidate: (tool: CustomCodeTool) => void;
  onDelete: (tool: CustomCodeTool) => void;
  onGrantsChanged: (toolId: string, names: string[]) => void;
}) {
  const t = useTranslations("customCodeTools");
  const statusLabel = (status: CustomCodeToolStatus) =>
    status === "VALIDATED" ? t("statusValidated") : status === "REJECTED" ? t("statusRejected") : t("statusDraft");
  return (
    <Card key={tool.id} className={cn("gap-0 p-5 transition-colors hover:border-primary/40")}>
      <div className="flex items-start gap-3">
        <span
          className={cn(
            "flex size-9 shrink-0 items-center justify-center rounded-lg",
            tool.enabled ? "bg-primary/10 text-primary" : "bg-secondary text-muted-foreground opacity-60"
          )}
        >
          <Code2 aria-hidden="true" size={16} />
        </span>
        <div className="min-w-0 flex-1">
          <div className="truncate text-sm font-medium text-foreground">{tool.displayName}</div>
          <div className="mt-0.5 truncate font-mono text-xs text-muted-foreground">{tool.toolName}</div>
        </div>
        <Switch
          checked={tool.enabled}
          aria-label={t("toggleAriaLabel", { name: tool.displayName })}
          disabled={readOnly || busy}
          onCheckedChange={() => onToggle(tool)}
        />
      </div>

      <div className="mt-3 flex flex-wrap items-center gap-1.5">
        <Badge variant="outline" className="font-mono text-[0.6875rem] uppercase">
          {tool.language === "PYTHON" ? t("languagePython") : t("languageJava")}
        </Badge>
        <Badge variant="state" state={statusHue(tool.status)}>
          {statusLabel(tool.status)}
        </Badge>
        <span className="text-xs text-muted-foreground tabular-nums">
          {t("versionLabel", { version: tool.version })}
        </span>
      </div>

      <p className="mt-3 line-clamp-2 text-sm text-muted-foreground">{tool.description || t("noDescription")}</p>

      <ToolSecretGrants
        toolId={tool.id}
        granted={tool.allowedSecretNames ?? []}
        available={vaultSecrets}
        canEdit={!readOnly && canManageTools}
        onChanged={(names) => onGrantsChanged(tool.id, names)}
      />

      {!readOnly && (
        <div className="mt-4 flex gap-1">
          <Button
            type="button"
            variant="ghost"
            size="icon"
            className="size-8"
            disabled={busy}
            onClick={() => onEdit(tool)}
            aria-label={t("editAriaLabel", { name: tool.displayName })}
            title={t("editAriaLabel", { name: tool.displayName })}
          >
            <Pencil aria-hidden="true" size={14} />
          </Button>
          {tool.status !== "VALIDATED" && (
            <Button
              type="button"
              variant="ghost"
              size="icon"
              className="size-8"
              disabled={busy}
              onClick={() => onRevalidate(tool)}
              aria-label={t("revalidateAriaLabel", { name: tool.displayName })}
              title={t("revalidateAriaLabel", { name: tool.displayName })}
            >
              <RefreshCw aria-hidden="true" size={14} className={cn(busy && "animate-spin")} />
            </Button>
          )}
          <Button
            type="button"
            variant="ghost"
            size="icon"
            className="size-8 text-muted-foreground hover:text-destructive"
            disabled={busy}
            onClick={() => onDelete(tool)}
            aria-label={t("deleteAriaLabel", { name: tool.displayName })}
            title={t("deleteAriaLabel", { name: tool.displayName })}
          >
            <Trash2 aria-hidden="true" size={14} />
          </Button>
        </div>
      )}
    </Card>
  );
}
