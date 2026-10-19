"use client";

// One-time raw API key reveal for the MCP server access console, with the
// plain-REST quickstart for that key.

import { useRef, useState } from "react";
import type { CSSProperties } from "react";
import { KeyRound, Copy, Check, X } from "lucide-react";
import { Button } from "../../components/ui/button";
import { API_BASE } from "../../lib/api";
import { toast } from "../../lib/toastBus";
import { useDialogFocus } from "../../lib/hooks/useDialogFocus";
import type { Translator } from "./mcpGrants";

// One-time raw-API-key reveal. McpServerAccessGrantCreatedResponse's
// rawApiKey is returned ONLY from create/rotate and can never be retrieved
// again afterward - the backend only ever stores its sha256 hash (see that
// DTO's javadoc). Uses the same .wf-modal-backdrop/.wf-modal shell
// WorkflowCanvas's validation/run-input dialogs already use (globals.css) -
// a real, token-driven (--hud-panel/--hud-line, flips with data-mode)
// generic dialog shell already established elsewhere in this app, not the
// .hud-tour-* walkthrough-tooltip component this page reused before (wrong
// semantic fit for a security-sensitive credential dialog) and not
// window.alert/window.confirm chrome.
export function KeyRevealDialog({
  title,
  rawApiKey,
  onClose,
  t,
}: {
  title: string;
  rawApiKey: string;
  onClose: () => void;
  t: Translator;
}) {
  const [copied, setCopied] = useState(false);
  const closeBtnRef = useRef<HTMLButtonElement>(null);
  const dialogRef = useRef<HTMLDivElement>(null);

  // Initial focus, Tab trap and focus-restore-on-close (Escape included) -
  // safe here because KeyRevealDialog is only ever mounted while `reveal` is
  // set (see the call site below), so this hook's mount-keyed effect lines
  // up with the dialog's own open/close lifecycle.
  useDialogFocus(dialogRef, closeBtnRef, onClose);

  function handleCopy() {
    if (!navigator?.clipboard?.writeText) {
      toast.error(t("copyFailedToast"));
      return;
    }
    navigator.clipboard.writeText(rawApiKey).then(
      () => {
        setCopied(true);
        setTimeout(() => setCopied(false), 1500);
      },
      () => toast.error(t("copyFailedToast"))
    );
  }

  return (
    <div
      className="wf-modal-backdrop"
      role="presentation"
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <div ref={dialogRef} className="wf-modal" role="dialog" aria-modal="true" aria-label={title}>
        <div className="wf-modal-header">
          <h2 style={{ display: "flex", alignItems: "center", gap: 8 }}>
            <KeyRound size={16} aria-hidden="true" />
            {title}
          </h2>
          <Button
            ref={closeBtnRef}
            variant="ghost"
            size="icon"
            className="wf-modal-close"
            onClick={onClose}
            aria-label={t("revealCloseAriaLabel")}
          >
            <X size={16} aria-hidden="true" />
          </Button>
        </div>

        <div className="error-box">{t("revealWarning")}</div>

        <div
          className="hint"
          style={{ fontFamily: "var(--font-mono), monospace", letterSpacing: "0.06em", marginBottom: 5 }}
        >
          {t("revealKeyLabel")}
        </div>
        <div className="row" style={{ flexWrap: "nowrap" }}>
          <code
            style={{
              flex: 1,
              display: "block",
              padding: "0.6rem 0.75rem",
              borderRadius: 8,
              border: "1px solid var(--hud-line)",
              background: "var(--ax-track)",
              fontSize: "0.78rem",
              wordBreak: "break-all",
            }}
          >
            {rawApiKey}
          </code>
          <Button type="button" variant="outline" size="sm" onClick={handleCopy}>
            {copied ? <Check size={13} aria-hidden="true" /> : <Copy size={13} aria-hidden="true" />}
            {copied ? t("copiedButton") : t("copyButton")}
          </Button>
        </div>

        <DeveloperApiQuickstart rawApiKey={rawApiKey} t={t} />

        <div className="row" style={{ justifyContent: "flex-end", marginTop: "1.1rem" }}>
          <Button type="button" size="sm" style={{ marginBottom: 0 }} onClick={onClose}>
            {t("revealDoneButton")}
          </Button>
        </div>
      </div>
    </div>
  );
}

// "Developer options... API keys + docs" - shown right here, not on a
// separate docs page, because this is the ONE moment the raw key is ever
// visible (create/rotate only - see this file's own KeyRevealDialog
// comment). Hits core/mcpserver's DeveloperApiController
// (/api/dev/v1/agents/{id}/chat, /api/dev/v1/workflows/{id}/run) - the
// plain-REST calling surface for this exact key, scoped to whichever
// agents/workflows this grant exposes (set above in the create form / each
// row's "Edit agents" panel).
function DeveloperApiQuickstart({ rawApiKey, t }: { rawApiKey: string; t: Translator }) {
  const codeStyle: CSSProperties = {
    display: "block",
    whiteSpace: "pre-wrap",
    wordBreak: "break-all",
    padding: "0.65rem 0.8rem",
    borderRadius: 8,
    border: "1px solid var(--hud-line)",
    background: "var(--ax-track)",
    fontSize: "0.72rem",
    lineHeight: 1.5,
    fontFamily: "var(--font-mono), monospace",
  };
  const chatExample = `curl -X POST ${API_BASE}/api/dev/v1/agents/<agent-id>/chat \\\n  -H "Authorization: Bearer ${rawApiKey}" \\\n  -H "Content-Type: application/json" \\\n  -d '{"message": "Hello!"}'`;
  const runExample = `curl -X POST ${API_BASE}/api/dev/v1/workflows/<workflow-id>/run \\\n  -H "Authorization: Bearer ${rawApiKey}" \\\n  -H "Content-Type: application/json" \\\n  -d '{"input": "..."}'`;
  return (
    <div style={{ marginTop: "1.1rem" }}>
      <div
        className="hint"
        style={{ fontFamily: "var(--font-mono), monospace", letterSpacing: "0.06em", marginBottom: 6 }}
      >
        {t("devApiQuickstartLabel")}
      </div>
      <p className="hint" style={{ fontSize: "0.75rem", marginBottom: 8 }}>
        {t("devApiQuickstartHint")}
      </p>
      <p className="hint" style={{ fontSize: "0.72rem", marginBottom: 4 }}>
        {t("devApiChatLabel")}
      </p>
      <code style={codeStyle}>{chatExample}</code>
      <p className="hint" style={{ fontSize: "0.72rem", margin: "10px 0 4px" }}>
        {t("devApiRunLabel")}
      </p>
      <code style={codeStyle}>{runExample}</code>
    </div>
  );
}
