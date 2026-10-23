"use client";

import { useContext, useEffect, useMemo, useState } from "react";
import { AuthContext } from "../AuthProvider";
import { auth } from "../../lib/auth";
import { api } from "../../lib/api";
import { useTranslations } from "../../lib/i18n";
import { Button } from "../ui/button";
import { Textarea } from "../ui/textarea";
import GuardrailActivity from "./GuardrailActivity";
import ToolPolicyExplainer from "./ToolPolicyExplainer";
import {
  documentToText,
  EXAMPLE_DOCUMENT,
  isTextDirty,
  normalizeToolPolicies,
  parsePolicyText,
  policySummaries,
  type PolicyTextError,
  type ToolPoliciesView,
  updatedByLabel,
} from "./toolPolicies";
import BrandText from "../BrandText";

/**
 * "Tool argument policies" for one agent, mounted in the agent workbench under the run-budget field.
 *
 * Reads and writes core's /api/agents/{id}/tool-policies. A policy names a tool (or a name pattern) and rules on
 * its arguments; a call that breaks a rule is refused BEFORE the tool runs and the agent is told why. Policies can
 * only tighten what the agent may do.
 *
 * Self-hiding: an older core has no such route (404), and a deployment where the feature is off answers
 * enabled=false. Either way nothing renders - an absent optional control must not look like an error. The server
 * validates every key and pattern; its message is shown verbatim on a rejected save.
 */
export default function AgentToolPoliciesCard({
  agentId,
  locked = false,
  lockedTooltip,
}: {
  agentId: string | null | undefined;
  /** The agent is edit-locked (free-tier lock); the editor is read-only. */
  locked?: boolean;
  lockedTooltip?: string;
}) {
  const t = useTranslations("toolPolicies");
  const q = useTranslations("approvalsQueue");
  // Keyed by the agent it was fetched for, so switching agents can never show (or save against) the previous
  // agent's policies while the new fetch is in flight.
  const [loaded, setLoaded] = useState<{ agentId: string; view: ToolPoliciesView } | null>(null);
  const [text, setText] = useState("");
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ kind: "error" | "saved"; text: string } | null>(null);

  // Who the stored "last changed by" id is: the viewer, or - for someone allowed to list members - a teammate.
  // Anyone else sees "a teammate", never the raw id.
  // useContext, not useAuth(): this card must still render where no provider is mounted.
  const authCtx = useContext(AuthContext) as { username?: string | null; firstName?: string | null; isAdmin?: boolean } | null;
  const username = authCtx?.username ?? null;
  const firstName = authCtx?.firstName ?? null;
  const isAdmin = !!authCtx?.isAdmin;
  const [memberNames, setMemberNames] = useState<ReadonlyMap<string, string>>(() => new Map());
  const updatedByRaw = loaded && loaded.agentId === agentId ? loaded.view.updatedBy : null;
  const needsLookup = isAdmin && !!updatedByRaw && updatedByRaw !== auth.getUserId();
  useEffect(() => {
    if (!needsLookup) return undefined;
    let live = true;
    Promise.resolve()
      .then(() => api.listAdminUsers())
      .then((list: Array<{ id: string; username?: string; firstName?: string | null; lastName?: string | null }>) => {
        if (!live || !Array.isArray(list)) return;
        const map = new Map<string, string>();
        for (const u of list) {
          const full = [u.firstName, u.lastName].filter(Boolean).join(" ").trim();
          if (u.id && (full || u.username)) map.set(u.id, full || (u.username as string));
        }
        setMemberNames(map);
      })
      .catch(() => {});
    return () => {
      live = false;
    };
  }, [needsLookup]);
  const updatedBy = useMemo(
    () => updatedByLabel(updatedByRaw, { selfId: auth.getUserId(), selfName: firstName || username, names: memberNames }),
    [updatedByRaw, firstName, username, memberNames],
  );

  useEffect(() => {
    setMessage(null);
    if (!agentId) {
      setLoaded(null);
      return undefined;
    }
    let cancelled = false;
    const requestedFor: string = agentId;
    api
      .getAgentToolPolicies(requestedFor)
      .then((raw: unknown) => {
        if (cancelled) return;
        const view = normalizeToolPolicies(raw);
        setLoaded(view ? { agentId: requestedFor, view } : null);
        setText(view ? documentToText(view.document) : "");
      })
      .catch(() => {
        // 404 (older core / agent not visible) or a transient failure: hide.
        if (!cancelled) setLoaded(null);
      });
    return () => {
      cancelled = true;
    };
  }, [agentId]);

  const view = loaded && loaded.agentId === agentId ? loaded.view : null;
  if (!agentId || !view || !view.enabled) return null;

  const disabled = busy || locked;
  const stored = view.document;
  const dirty = isTextDirty(text, stored);
  const check = parsePolicyText(text, view.limits);
  // An empty editor is not an error to shout about - Save is simply unavailable.
  const inlineError = text.trim() === "" || check.error == null ? null : precheckMessage(check.error, check.policy, check.limit);
  const summaries = policySummaries(stored);

  function precheckMessage(error: PolicyTextError, policy: number, limit: number): string {
    switch (error) {
      case "notJson":
        return t("errorNotJson");
      case "shape":
        return t("errorShape");
      case "tooLarge":
        return t("errorTooLarge", { max: String(limit) });
      case "tooManyPolicies":
        return t("errorTooManyPolicies", { max: String(limit) });
      case "tooManyRules":
        return t("errorTooManyRules", { n: String(policy), max: String(limit) });
      case "policyShape":
        return t("errorPolicyShape", { n: String(policy) });
      default:
        return t("errorEmpty");
    }
  }

  async function submit(document: unknown, successKey: "saved" | "cleared") {
    if (!agentId || disabled) return;
    const requestedFor: string = agentId;
    setBusy(true);
    setMessage(null);
    try {
      const next = normalizeToolPolicies(await api.setAgentToolPolicies(requestedFor, document));
      if (next) {
        setLoaded({ agentId: requestedFor, view: next });
        setText(documentToText(next.document));
      }
      setMessage({ kind: "saved", text: t(successKey) });
    } catch (err: any) {
      // 400 carries the server's precise reason; 409/423/404 their own plain-language messages.
      setMessage({ kind: "error", text: err?.message || t("saveFailed") });
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="mb-4" data-testid="agent-tool-policies">
      <label htmlFor="agent-tool-policies-input" className="mb-1 block text-xs text-muted-foreground">
        <BrandText text={t("title")} />
      </label>
      <p className="mb-2 text-xs text-muted-foreground">{t("intro")}</p>
      <ToolPolicyExplainer className="mb-2" />
      <p className="mb-2 text-xs text-muted-foreground" data-testid="agent-tool-policies-approval-note">
        {q("policyThenApprovalNote")}
      </p>
      <GuardrailActivity kind="toolPolicy" agentId={agentId} />

      <div className="mb-2 rounded-md border border-border p-2" data-testid="agent-tool-policies-summary">
        <p className="text-xs font-medium text-foreground">{t("stored")}</p>
        {summaries.length === 0 ? (
          <p className="mt-1 text-xs text-muted-foreground">{t("none")}</p>
        ) : (
          <ul className="mt-1 space-y-1">
            {summaries.map((p, i) => (
              <li key={`${p.tool}-${i}`} className="text-xs text-muted-foreground">
                <code className="font-mono text-foreground">{p.tool}</code>
                {p.denyUnlistedArgs ? <span> - {t("denyUnlisted")}</span> : null}
                {p.rules.map((r, j) => (
                  <div key={`${r.path}-${j}`} className="ml-3">
                    <code className="font-mono">{r.path}</code>
                    {r.checks.length > 0 ? <span>: {r.checks.join(" · ")}</span> : null}
                  </div>
                ))}
              </li>
            ))}
          </ul>
        )}
        {updatedBy ? (
          <p className="mt-1 text-xs text-muted-foreground">
            {updatedBy.kind === "you"
              ? t("updatedByYou")
              : updatedBy.kind === "unknown"
                ? t("updatedByUnknown")
                : t("updatedBy", { name: updatedBy.name })}
          </p>
        ) : null}
      </div>

      <Textarea
        id="agent-tool-policies-input"
        data-testid="agent-tool-policies-input"
        className="min-h-40 font-mono text-xs"
        rows={10}
        spellCheck={false}
        autoComplete="off"
        value={text}
        disabled={disabled}
        title={locked ? lockedTooltip : undefined}
        aria-invalid={inlineError ? true : undefined}
        aria-describedby="agent-tool-policies-help"
        onChange={(e) => {
          setText(e.target.value);
          setMessage(null);
        }}
      />
      <p id="agent-tool-policies-help" className="mt-1 text-xs text-muted-foreground">
        {t("editorHelp")}
      </p>
      {inlineError && (
        <p className="mt-1 text-xs text-destructive" role="alert" data-testid="agent-tool-policies-invalid">
          {inlineError}
        </p>
      )}

      <div className="mt-2 flex flex-wrap items-center gap-2">
        <Button
          type="button"
          size="sm"
          data-testid="agent-tool-policies-save"
          disabled={disabled || !dirty || check.error != null || check.document == null}
          title={locked ? lockedTooltip : undefined}
          onClick={() => {
            if (check.document) void submit(check.document, "saved");
          }}
        >
          {t("save")}
        </Button>
        <Button
          type="button"
          size="sm"
          variant="outline"
          data-testid="agent-tool-policies-example"
          disabled={disabled}
          title={locked ? lockedTooltip : undefined}
          onClick={() => {
            setText(JSON.stringify(EXAMPLE_DOCUMENT, null, 2));
            setMessage(null);
          }}
        >
          {t("example")}
        </Button>
        {summaries.length > 0 && (
          <Button
            type="button"
            size="sm"
            variant="outline"
            data-testid="agent-tool-policies-clear"
            disabled={disabled}
            title={locked ? lockedTooltip : undefined}
            onClick={() => void submit({ policies: [] }, "cleared")}
          >
            {t("clear")}
          </Button>
        )}
        {dirty && !busy && <span className="text-xs text-muted-foreground">{t("unsaved")}</span>}
      </div>

      {message && (
        <p
          className={message.kind === "error" ? "mt-1 text-xs text-destructive" : "mt-1 text-xs text-muted-foreground"}
          role={message.kind === "error" ? "alert" : "status"}
          data-testid={`agent-tool-policies-${message.kind}`}
        >
          {message.text}
        </p>
      )}
      <p className="mt-2 text-xs text-muted-foreground">{t("refusalNote")}</p>
    </div>
  );
}
