"use client";

import { useEffect, useRef, useState } from "react";
import { TRIGGER_CHANNEL_LABELS, triggerChannelOffered } from "../../nodeDefs";
import { api, API_BASE } from "../../../../lib/api";
import Select from "../../../Select";
import { Button } from "../../../ui/button";
import { Label } from "../../../ui/label";
import { useTranslations } from "../../../../lib/i18n";
import { toast } from "../../../../lib/toastBus";
import { errorMessage } from "../../../../lib/errors";
import FormTriggerEditor from "../../FormTriggerEditor";
import WebhookSignatureConfig from "../../WebhookSignatureConfig";
import TriggerPollConfig from "../../TriggerPollConfig";
import { type WorkflowNodeConfig, type NodeWebhookState } from "../types";

/**
 * Trigger node config - multi-trigger graphs. A workflow can now hold more
 * than one Trigger node, each independently bound to its own inbound
 * channel (WhatsApp, Slack, Discord, its own webhook, etc.) - this replaces
 * the single workflow-wide trigger-channel toolbar strip that used to live
 * in WorkflowCanvas.tsx (see that file's own comments for the removal
 * rationale). `channel`/`webhookAuthMode` are written into this node's own
 * `config` via `setConfig` (same as every other node type in this drawer -
 * see the output node's channel select just above) so they round-trip with
 * the saved graph and WorkflowNode.tsx's badge can read them without a
 * separate fetch, BUT a channel binding is also an active server-side
 * routing registration (inbound messages need somewhere real to resolve to
 * the moment a channel is picked, not just after the next Save/Activate) -
 * so `applyChannel` below fires api.setNodeTriggerChannel immediately on
 * change too, same as the old workflow-wide applyTriggerChannel did.
 * webhookAuthMode/enabled/token are never part of the saved graph at all
 * (server-managed secrets, not workflow content) - hydrated on open via
 * api.getNodeTriggerChannel + api.getNodeWebhook, the per-node endpoints
 * that replaced the workflow-wide trigger-channel/webhook pair the backend
 * removed when multi-trigger graphs landed.
 */
export function TriggerNodeConfig({
  workflowId,
  nodeId,
  config,
  setConfig,
}: {
  workflowId: string;
  nodeId: string;
  config: WorkflowNodeConfig;
  setConfig: (patch: Partial<WorkflowNodeConfig>) => void;
}) {
    const tHC = useTranslations("hcComponentsWorkflowNodeConfigFormsTriggerForm");
  const t = useTranslations("nodeConfigDrawer");
  const tExtra = useTranslations("nodeConfigExtra");
  const [channelBusy, setChannelBusy] = useState(false);
  const [oidcTriggerPath, setOidcTriggerPath] = useState<string | null>(null);
  const [webhook, setWebhook] = useState<NodeWebhookState | null>(null);
  const [webhookBusy, setWebhookBusy] = useState(false);
  const [copied, setCopied] = useState(false);

  const channel = config.channel || "WEBHOOK";
  const authMode = config.webhookAuthMode || "SHARED_SECRET";
  // The hydrate below resolves after render: compare against the config as it is THEN, not as it was at mount.
  const configRef = useRef(config);
  configRef.current = config;

  // Hydrate this node's real server-side binding on open/switch - the saved
  // graph's config.channel may be stale (edited elsewhere), and the webhook
  // enabled/token/oidcTriggerPath fields are never part of the saved graph
  // to begin with.
  useEffect(() => {
    let cancelled = false;
    api
      .getNodeTriggerChannel(workflowId, nodeId)
      .then((result) => {
        if (cancelled || !result) return;
        // Selecting a node is a read: only write the server's binding into the graph when it really differs from
        // what the graph already says (a stale copy). Writing the same effective values - channel "WEBHOOK" where
        // the key was simply absent - marked an untouched graph dirty ("Unsaved changes", then an autosave) just
        // because the Trigger node was clicked (prod re-verification 2026-10-05, R8).
        const latest = configRef.current;
        const nextChannel = result.channel || "WEBHOOK";
        const nextAuthMode = result.webhookAuthMode || undefined;
        if ((latest.channel || "WEBHOOK") !== nextChannel || (latest.webhookAuthMode || undefined) !== nextAuthMode) {
          setConfig({ channel: nextChannel, webhookAuthMode: nextAuthMode });
        }
        setOidcTriggerPath(result.oidcTriggerPath || null);
      })
      .catch((err) => {
        // A failed hydrate renders the same "no binding yet" empty state as
        // a genuinely unconfigured node, which is acceptable - warn only
        // (no toast on mount), just so the failure isn't invisible.
        console.warn("[Workflow] Trigger-channel hydrate failed:", err);
      });
    api
      .getNodeWebhook(workflowId, nodeId)
      .then((w) => !cancelled && setWebhook(w))
      .catch((err) => {
        console.warn("[Workflow] Node webhook hydrate failed:", err);
        if (!cancelled) setWebhook(null);
      });
    return () => {
      cancelled = true;
    };
    // Deliberately re-runs only when the node identity changes, not on every
    // config edit - this is a one-time hydration from the server, not a
    // live subscription.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [workflowId, nodeId]);

  async function applyChannel(nextChannel: string, nextAuthMode: string) {
    setChannelBusy(true);
    try {
      const result = await api.setNodeTriggerChannel(workflowId, nodeId, nextChannel, nextAuthMode);
      setConfig({
        channel: result?.channel || nextChannel,
        webhookAuthMode: result?.webhookAuthMode || nextAuthMode || undefined,
        // A hosted form is a webhook-token feature: binding the node to another channel ends it.
        triggerType: nextChannel === "WEBHOOK" ? config.triggerType : undefined,
      });
      setOidcTriggerPath(result?.oidcTriggerPath || null);
      if (nextChannel !== "WEBHOOK") {
        toast.success(t("channelTriggerSuccess", { channel: TRIGGER_CHANNEL_LABELS[nextChannel] || nextChannel }));
      }
    } catch (err) {
      toast.error(errorMessage(err, t("channelTriggerError")));
    } finally {
      setChannelBusy(false);
    }
  }

  async function toggleWebhook() {
    setWebhookBusy(true);
    try {
      if (webhook?.enabled) {
        setWebhook(await api.disableNodeWebhook(workflowId, nodeId));
      } else {
        setWebhook(await api.enableNodeWebhook(workflowId, nodeId));
      }
    } catch (err) {
      toast.error(errorMessage(err, t("webhookUpdateError")));
    } finally {
      setWebhookBusy(false);
    }
  }

  function copyWebhookUrl() {
    const url = webhook?.url || (webhook?.token ? `${API_BASE}/webhook/workflows/${webhook.token}` : "");
    if (!url) return;
    void navigator.clipboard?.writeText(url);
    setCopied(true);
    setTimeout(() => setCopied(false), 1500);
  }

  return (
    <>
      <div className="field">
        <Label>{t("triggerChannelLabel")}</Label>
        <Select
          value={channel}
          onChange={(e) => applyChannel(e.target.value, "SHARED_SECRET")}
          disabled={channelBusy}
          options={[
            { value: "WEBHOOK", label: t("webhookDefaultOption") },
            { value: "WHATSAPP", label: t("inboundWhatsappOption") },
            { value: "SMS", label: t("inboundSmsOption") },
            { value: "CALL", label: t("phoneCallOption") },
            { value: "MESSENGER", label: t("inboundMessengerOption") },
            { value: "INSTAGRAM", label: t("inboundInstagramOption") },
            { value: "SLACK", label: t("inboundSlackOption") },
            { value: "DISCORD", label: t("inboundDiscordOption") },
            { value: "X", label: t("inboundXOption") },
            { value: "EMAIL", label: t("inboundEmailOption") },
            // An unoffered channel (Slack, Discord, X on a personal install) is not selectable, but a node that
            // already carries one keeps showing it rather than silently displaying another channel.
          ].filter((o) => triggerChannelOffered(o.value) || o.value === channel)}
        />
        {channel && channel !== "WEBHOOK" && channel !== "CALL" && (
          <p className="ax-wf-empty">
            {t("inboundChannelHint", { channel: TRIGGER_CHANNEL_LABELS[channel] || channel })}
          </p>
        )}
        {channel === "CALL" && <p className="ax-wf-empty">{t("inboundCallHint")}</p>}
      </div>

      {channel === "WEBHOOK" && (
        <div className="wf-trigger-box" style={{ flexDirection: "column", alignItems: "stretch" }}>
          <Label>{t("webhookLabel")}</Label>
          <Select
            value={authMode}
            onChange={(e) => applyChannel("WEBHOOK", e.target.value)}
            disabled={channelBusy}
            options={[
              { value: "SHARED_SECRET", label: t("secretTokenOption") },
              { value: "OAUTH2", label: t("oauthOption") },
            ]}
          />
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={toggleWebhook}
            disabled={webhookBusy}
            style={{ alignSelf: "flex-start" }}
          >
            {webhookBusy ? t("working") : webhook?.enabled ? t("disableWebhook") : t("enableWebhookTrigger")}
          </Button>
          {webhook?.enabled && webhook?.token && authMode !== "OAUTH2" && (
            <div className="row" style={{ gap: "0.4rem", alignItems: "center" }}>
              <code className="wf-trigger-url">{webhook.url || `${API_BASE}/webhook/workflows/${webhook.token}`}</code>
              <Button type="button" variant="outline" size="sm" onClick={copyWebhookUrl} title={t("copyUrlTooltip")}>
                {copied ? t("copied") : t("copy")}
              </Button>
            </div>
          )}
          {webhook?.enabled && authMode === "OAUTH2" && oidcTriggerPath && (
            <p className="ax-wf-empty">
              POST {`${API_BASE}${oidcTriggerPath}`}
              {t("oauthHintWithAn")}
              <code>{tHC("s1")}</code>
              {t("oauthHintHeaderPeriod")}
              <code>workflow-trigger</code>
              {t("oauthHintRealmRoleSuffix")}
            </p>
          )}
          {webhook?.enabled && authMode !== "OAUTH2" && (
            <WebhookSignatureConfig workflowId={workflowId} nodeId={nodeId} />
          )}
        </div>
      )}

      {channel === "WEBHOOK" && (
        <FormTriggerEditor
          config={config}
          setConfig={setConfig}
          webhookToken={webhook?.enabled && authMode !== "OAUTH2" ? webhook?.token || null : null}
          oauthMode={authMode === "OAUTH2"}
        />
      )}

      {/* HTTP polling is an alternative to the manual/webhook trigger, so it only applies to
          the WEBHOOK (default) channel; an inbound-channel trigger is push-based. Tucked behind
          a disclosure so it does not read as part of every trigger's setup. */}
      {channel === "WEBHOOK" && (
        <details className="wf-trigger-poll" style={{ marginTop: "0.5rem" }}>
          <summary style={{ cursor: "pointer", fontSize: "0.85rem" }}>{tExtra("pollTriggerSummary")}</summary>
          <TriggerPollConfig workflowId={workflowId} nodeId={nodeId} />
        </details>
      )}
    </>
  );
}
