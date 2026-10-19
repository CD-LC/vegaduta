"use client";

import { useEffect, useState, type FormEvent } from "react";
import { Check, Copy, MessageSquareText } from "lucide-react";
import { api } from "../../lib/api";
import { API_BASE } from "../../lib/api/core";
import { errorMessage } from "../../lib/errors";
import { toast } from "../../lib/toastBus";
import { useTranslations } from "../../lib/i18n";
import { Button } from "../ui/button";
import { InboundRoutingCard, StatusDot, type AgentOption } from "./ChannelInspectorCards";

// Plain SMS (ChannelType.SMS, docs/sms-channel.md) rides on the tenant's Twilio credential, so it has no
// connect form of its own: this inspector explains the three steps, shows the webhook URL to paste into
// Twilio and holds the "which agent answers" choice. Tile colours are theme tokens, not a brand mark.
export const SMS_META = {
  labelKey: "channelLabelSms",
  initials: "SMS",
  color: "var(--accent-600)",
  textColor: "var(--primary-foreground)",
};
export const SMS_ICON = (p: { className?: string }) => <MessageSquareText {...p} />;

function webhookUrl(): string {
  const base = API_BASE ? API_BASE.replace(/\/+$/, "") : typeof window !== "undefined" ? window.location.origin : "";
  return `${base}/webhook/twilio/sms`;
}

export function SmsInspector({
  agents,
  entry,
  loaded,
  refreshStatus,
  onConnectTwilio,
}: {
  agents?: AgentOption[];
  entry?: { state: string; detail?: string; defaultAgentId?: string };
  loaded: boolean;
  refreshStatus: () => void;
  /** Jump to the WhatsApp inspector, where the shared Twilio credential is connected. */
  onConnectTwilio: () => void;
}) {
  const t = useTranslations("channelsSms");
  const tp = useTranslations("channelsPanel");
  const [agentId, setAgentId] = useState("");
  const [busy, setBusy] = useState(false);
  const [copied, setCopied] = useState(false);
  const saved = entry?.defaultAgentId;
  const state = entry?.state || "NOT_CONNECTED";
  const url = webhookUrl();

  useEffect(() => {
    if (saved) setAgentId(saved);
  }, [saved]);

  async function save(e: FormEvent) {
    e.preventDefault();
    if (!agentId) return;
    setBusy(true);
    try {
      await api.setSmsDefaultAgent(agentId);
      toast.success(tp("smsDefaultAgentUpdatedSuccess"));
      refreshStatus();
    } catch (err) {
      toast.error(errorMessage(err, tp("setDefaultAgentError")));
    } finally {
      setBusy(false);
    }
  }

  function copyUrl() {
    navigator.clipboard?.writeText(url).then(
      () => {
        setCopied(true);
        setTimeout(() => setCopied(false), 1500);
      },
      () => toast.error(t("copyFailed"))
    );
  }

  return (
    <div className="space-y-4" data-testid="sms-inspector">
      <div className="flex items-center gap-3 border-b border-border pb-4">
        <span
          className="flex size-11 shrink-0 items-center justify-center rounded-xl shadow-sm ring-1 ring-black/5"
          style={{ background: SMS_META.color, color: SMS_META.textColor }}
        >
          <MessageSquareText className="size-5" aria-hidden="true" />
        </span>
        <div className="min-w-0 flex-1">
          <h3 className="text-base font-semibold tracking-tight text-foreground">{t("title")}</h3>
          <p className="text-xs text-muted-foreground">{t("subtitle")}</p>
        </div>
        <StatusDot state={state} />
      </div>

      {state === "CONNECTED" || state === "PENDING" ? (
        <p className="m-0 text-sm text-muted-foreground">{entry?.detail || t("twilioReady")}</p>
      ) : (
        <div className="space-y-2 rounded-xl border border-dashed border-border p-4">
          <p className="m-0 text-sm text-muted-foreground">{t("needsTwilio")}</p>
          <Button type="button" variant="outline" size="sm" onClick={onConnectTwilio}>
            {t("connectTwilio")}
          </Button>
        </div>
      )}

      <ol className="m-0 list-decimal space-y-1.5 pl-5 text-sm text-muted-foreground">
        <li>{t("step1")}</li>
        <li>{t("step2")}</li>
        <li>{t("step3")}</li>
      </ol>

      <div className="space-y-1.5">
        <div className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">{t("webhookLabel")}</div>
        <div className="flex items-start gap-2">
          <code className="block min-w-0 flex-1 break-all rounded-lg border border-border bg-muted/40 px-3 py-2 font-mono text-xs">
            {url}
          </code>
          <Button type="button" variant="outline" size="sm" onClick={copyUrl} aria-label={t("copyWebhook")}>
            {copied ? <Check size={13} aria-hidden="true" /> : <Copy size={13} aria-hidden="true" />}
          </Button>
        </div>
        <p className="m-0 text-xs text-muted-foreground">{t("webhookHint")}</p>
      </div>

      <InboundRoutingCard
        agents={agents || []}
        value={agentId}
        savedValue={saved}
        loaded={loaded}
        onChange={setAgentId}
        onSave={save}
        busy={busy}
        t={tp}
      />
    </div>
  );
}
