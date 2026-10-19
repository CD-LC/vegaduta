"use client";

import { useEffect, useState } from "react";
import { api } from "../../lib/api";
import { errorMessage } from "../../lib/errors";
import { InboundRoutingCard, type AgentOption } from "./ChannelInspectorCards";
import { WhatsAppBindingCard, WhatsAppQualityCard } from "./WhatsAppPolicyCards";
import { WhatsAppOptInCard } from "./WhatsAppOptInCard";

/**
 * The two "which agent answers" cards for a tenant's Twilio number: WhatsApp
 * (`PUT /api/channels/whatsapp/default-agent`, channel-level, covers both
 * connect methods; needs a declared task, see WhatsAppPolicyCards) and plain SMS (`PUT /api/channels/sms/default-agent`,
 * 2026-10-14). SMS sits under the WhatsApp section because the credential is
 * the same row; a feature phone with no data plan texts the number and the
 * agent chosen here answers. Point Twilio's "A message comes in" webhook for
 * the number at /webhook/twilio/sms.
 *
 * Owns the draft state so ChannelsPanel only passes the saved values the
 * status endpoint reports (ChannelStatusController includes defaultAgentId per
 * channel precisely so the select can show what is actually saved).
 */
export type RoutingSection = "all" | "routing" | "compliance";

export function TwilioRoutingCards({
  section = "all",
  agents,
  whatsappSaved,
  smsSaved,
  loaded,
  busy,
  setBusy,
  setError,
  setSuccess,
  refreshStatus,
  t,
}: {
  /** "routing" = which agent answers (WhatsApp + SMS); "compliance" = quality rating and opt-ins; "all" = both (default). */
  section?: RoutingSection;
  agents: AgentOption[];
  whatsappSaved?: string;
  smsSaved?: string;
  loaded: boolean;
  busy: boolean;
  setBusy: (busy: boolean) => void;
  setError: (message: string) => void;
  setSuccess: (message: string) => void;
  refreshStatus: () => void;
  t: (key: string) => string;
}) {
  const [smsDefaultAgentId, setSmsDefaultAgentId] = useState("");

  useEffect(() => {
    if (smsSaved) setSmsDefaultAgentId(smsSaved);
  }, [smsSaved]);

  async function save(
    e: React.FormEvent,
    agentId: string,
    call: (agentId: string) => Promise<unknown>,
    successKey: string,
  ) {
    e.preventDefault();
    if (!agentId) return;
    setBusy(true);
    try {
      await call(agentId);
      setSuccess(t(successKey));
      refreshStatus();
    } catch (err) {
      setError(errorMessage(err, t("setDefaultAgentError")));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      {section !== "routing" && <WhatsAppQualityCard t={t} />}
      {section !== "compliance" && (
      <WhatsAppBindingCard
        agents={agents}
        savedAgentId={whatsappSaved}
        loaded={loaded}
        busy={busy}
        setBusy={setBusy}
        setError={setError}
        setSuccess={setSuccess}
        refreshStatus={refreshStatus}
        t={t}
      />
      )}
      {section !== "routing" && <WhatsAppOptInCard />}
      {section !== "compliance" && (
      <div className="space-y-2" data-testid="sms-routing">
        <div className="text-sm font-medium">{t("smsRoutingTitle")}</div>
        <p className="text-xs text-muted-foreground">{t("smsRoutingHelp")}</p>
        <InboundRoutingCard
          agents={agents}
          value={smsDefaultAgentId}
          savedValue={smsSaved}
          loaded={loaded}
          onChange={setSmsDefaultAgentId}
          onSave={(e) => save(e, smsDefaultAgentId, api.setSmsDefaultAgent, "smsDefaultAgentUpdatedSuccess")}
          busy={busy}
          t={t}
        />
      </div>
      )}
    </>
  );
}
