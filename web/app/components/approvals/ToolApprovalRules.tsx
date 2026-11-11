"use client";

import { useId, useState } from "react";
import { Loader2 } from "lucide-react";
import { api } from "../../lib/api";
import { errorMessage } from "../../lib/errors";
import { useTranslations } from "../../lib/i18n";
import { cn } from "../../lib/utils";
import { Button } from "../ui/button";
import { ConfirmDialog } from "../ui/confirm-dialog";
import { Input } from "../ui/input";
import { Label } from "../ui/label";
import RiskBadge from "./RiskBadge";
import { toolRisk } from "./approvalsQueueModel";

/** Core builds a gated tool's action type as "mcp_tool." + name (GuardedToolCallback.ACTION_TYPE_PREFIX). */
const TOOL_PREFIX = "mcp_tool.";

/**
 * Per-tool approval rule: "always ask" or "never ask" for one tool, for every agent that follows the workspace
 * rules (Semi-autonomous). It writes the same per-action-type override as the channel policy form
 * (PUT /api/approvals/policy/{actionType}).
 *
 * What the server does NOT have, and this control therefore does not pretend to: per-agent tool rules, an
 * "ask once per session" mode, and a way to list the rules already set (the endpoint is write-only).
 */
export default function ToolApprovalRules({ canEdit }: { canEdit: boolean }) {
  const t = useTranslations("approvalsQueue");
  const uid = useId();
  const [tool, setTool] = useState("");
  const [mode, setMode] = useState<"ask" | "never">("ask");
  const [busy, setBusy] = useState(false);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [message, setMessage] = useState<{ kind: "ok" | "error"; text: string } | null>(null);

  const name = tool.trim().replace(new RegExp(`^${TOOL_PREFIX.replace(".", "\\.")}`), "");
  const nameError = tool.trim() === "" ? null : /\s/.test(name) ? t("rulesToolNameInvalid") : null;
  const risk = name ? toolRisk(name) : null;
  const ready = name !== "" && !nameError && canEdit;

  async function save() {
    setBusy(true);
    setMessage(null);
    try {
      await api.setApprovalPolicy(`${TOOL_PREFIX}${name}`, mode === "ask");
      setMessage({ kind: "ok", text: t(mode === "ask" ? "rulesSavedAsk" : "rulesSavedNever", { tool: name }) });
    } catch (err) {
      setMessage({ kind: "error", text: errorMessage(err, t("rulesSaveFailed")) });
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="grid max-w-xl gap-3" data-testid="tool-approval-rules">
      <div className="grid gap-1">
        <h3 className="text-sm font-semibold">{t("rulesTitle")}</h3>
        <p className="text-xs text-muted-foreground">{t("rulesIntro")}</p>
      </div>
      {!canEdit && <p className="text-xs text-muted-foreground">{t("rulesNeedsScope")}</p>}
      <div className="grid gap-1.5">
        <Label htmlFor={`${uid}-tool`}>{t("rulesToolLabel")}</Label>
        <Input
          id={`${uid}-tool`}
          value={tool}
          disabled={!canEdit || busy}
          maxLength={200}
          spellCheck={false}
          autoComplete="off"
          aria-invalid={nameError ? true : undefined}
          aria-describedby={`${uid}-tool-help`}
          placeholder={t("rulesToolPlaceholder")}
          onChange={(e) => {
            setTool(e.target.value);
            setMessage(null);
          }}
        />
        <p id={`${uid}-tool-help`} className="text-xs text-muted-foreground">
          {t("rulesToolHelp")}
        </p>
        {nameError && (
          <p className="text-xs text-destructive" role="alert" data-testid="rules-name-error">
            {nameError}
          </p>
        )}
      </div>
      <div className="grid gap-1.5" role="group" aria-label={t("rulesModeLabel")}>
        <div className="grid gap-2 sm:grid-cols-2">
          {(["ask", "never"] as const).map((m) => (
            <button
              key={m}
              type="button"
              aria-pressed={mode === m}
              disabled={!canEdit || busy}
              data-testid={`rules-mode-${m}`}
              onClick={() => {
                setMode(m);
                setMessage(null);
              }}
              className={cn(
                "rounded-lg border px-3 py-2 text-start transition-colors disabled:opacity-50",
                mode === m ? "border-primary bg-primary/10" : "border-border bg-card hover:bg-accent"
              )}
            >
              <span className="block text-sm font-medium">{t(m === "ask" ? "rulesAskName" : "rulesNeverName")}</span>
              <span className="mt-0.5 block text-xs text-muted-foreground">
                {t(m === "ask" ? "rulesAskHint" : "rulesNeverHint")}
              </span>
            </button>
          ))}
        </div>
        {risk && (
          <p className="flex items-center gap-2 text-xs text-muted-foreground" data-testid="rules-risk">
            <RiskBadge risk={risk} />
            {mode === "never" && risk !== "low" ? (
              <span className="font-medium text-destructive">{t("rulesNeverRiskyWarn")}</span>
            ) : null}
          </p>
        )}
      </div>
      <p className="text-xs text-muted-foreground">{t("rulesScopeNote")}</p>
      <div className="flex flex-wrap items-center gap-2">
        <Button
          type="button"
          size="sm"
          disabled={!ready || busy}
          data-testid="rules-save"
          onClick={() => (mode === "never" ? setConfirmOpen(true) : void save())}
        >
          {busy && <Loader2 size={14} className="animate-spin" aria-hidden="true" />}
          {t("rulesSave")}
        </Button>
      </div>
      {message && (
        <p
          className={cn("text-xs", message.kind === "error" ? "text-destructive" : "text-muted-foreground")}
          role={message.kind === "error" ? "alert" : "status"}
          data-testid={`rules-${message.kind}`}
        >
          {message.text}
        </p>
      )}
      <ConfirmDialog
        open={confirmOpen}
        onOpenChange={setConfirmOpen}
        destructive={risk !== "low"}
        title={t("rulesNeverConfirmTitle", { tool: name })}
        description={t(risk === "low" ? "rulesNeverConfirmBodyLow" : "rulesNeverConfirmBody", { tool: name })}
        confirmLabel={t("rulesNeverConfirm")}
        cancelLabel={t("bulkCancel")}
        onConfirm={save}
      />
    </div>
  );
}
