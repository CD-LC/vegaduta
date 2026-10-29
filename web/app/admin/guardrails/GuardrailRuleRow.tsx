"use client";

// One guardrail rule row: action/default/off badges, the pattern, and (for the
// workspace's own rules) turn on/off, edit and two-step delete.

import { Ban, EyeOff, Lock, Pencil, Trash2 } from "lucide-react";
import { Badge } from "../../components/ui/badge";
import { Button } from "../../components/ui/button";
import type { GuardrailRule } from "./guardrailRules";
import { useTranslations } from "../../lib/i18n";

export function GuardrailRuleRow({
  rule,
  editable,
  busy,
  canAuthor,
  upgradeLocked,
  isConfirming,
  onToggle,
  onEdit,
  onRequestDelete,
  onDelete,
  onCancelDelete,
}: {
  rule: GuardrailRule;
  editable: boolean;
  busy: boolean;
  canAuthor: boolean;
  upgradeLocked: boolean;
  isConfirming: boolean;
  onToggle: () => void;
  onEdit: () => void;
  onRequestDelete: () => void;
  onDelete: () => void;
  onCancelDelete: () => void;
}) {
    const tHC = useTranslations("hcAdminGuardrailsGuardrailRuleRow");
  return (
    <div
      data-testid={`guardrail-rule-${rule.id}`}
      className="flex flex-col gap-3 rounded-lg border border-border bg-card p-4 sm:flex-row sm:items-start sm:justify-between"
    >
      <div className="flex min-w-0 flex-col gap-1.5">
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-sm font-medium">{rule.name}</span>
          <Badge variant={rule.action === "BLOCK" ? "destructive" : "secondary"}>
            {rule.action === "BLOCK" ? (
              <>
                <Ban aria-hidden="true" /> Block
              </>
            ) : (
              <>
                <EyeOff aria-hidden="true" /> Redact
              </>
            )}
          </Badge>
          {!editable && (
            <Badge variant="outline">
              <Lock aria-hidden="true" /> {tHC("hcAdminGuardrailsGuardrailRuleRow.s2")}
            </Badge>
          )}
          {!rule.enabled && <Badge variant="outline">Off</Badge>}
        </div>
        {rule.description && <p className="text-xs text-muted-foreground">{rule.description}</p>}
        <code className="max-w-full overflow-x-auto rounded bg-muted px-2 py-1 font-mono text-xs text-muted-foreground">
          {rule.pattern}
        </code>
      </div>

      {editable && (
        <div className="flex shrink-0 flex-wrap items-center gap-2">
          <Button
            type="button"
            size="sm"
            variant="outline"
            disabled={busy || !canAuthor}
            upgradeLocked={upgradeLocked}
            upgradeReason="Authoring your own guardrail rules is included from the Premium plan."
            onClick={onToggle}
          >
            {rule.enabled ? "Turn off" : "Turn on"}
          </Button>
          <Button
            type="button"
            size="sm"
            variant="outline"
            disabled={busy || !canAuthor}
            upgradeLocked={upgradeLocked}
            upgradeReason="Authoring your own guardrail rules is included from the Premium plan."
            onClick={onEdit}
          >
            <Pencil className="size-3.5" aria-hidden="true" /> Edit
          </Button>
          {isConfirming ? (
            <>
              <Button type="button" size="sm" variant="destructive" disabled={busy} onClick={onDelete}>
                {tHC("hcAdminGuardrailsGuardrailRuleRow.s1")}
              </Button>
              <Button type="button" size="sm" variant="outline" disabled={busy} onClick={onCancelDelete}>
                Cancel
              </Button>
            </>
          ) : (
            <Button
              type="button"
              size="sm"
              variant="outline"
              disabled={busy || !canAuthor}
              upgradeLocked={upgradeLocked}
              upgradeReason="Authoring your own guardrail rules is included from the Premium plan."
              onClick={onRequestDelete}
            >
              <Trash2 className="size-3.5" aria-hidden="true" /> Delete
            </Button>
          )}
        </div>
      )}
    </div>
  );
}
