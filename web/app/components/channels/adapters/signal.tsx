"use client";

import { useEffect, useState } from "react";
import { api } from "../../../lib/api";
import { errorMessage } from "../../../lib/errors";
import Select from "../../Select";
import { Button } from "../../ui/button";
import { Label } from "../../ui/label";
import { LabeledInput, SimpleConnectPanel, TestSendCard } from "../ChannelInspectorCards";
import { useSimpleChannel } from "../useSimpleChannel";
import type { ChannelAdapter, ChannelCtx } from "./types";

type UnknownSenders = "PAIR" | "IGNORE";
type GroupMode = "IGNORE" | "MENTION" | "ALL";

/**
 * Signal runs through the operator's own signal-cli REST API: a base URL, the registered number and an
 * optional proxy token. This adapter owns its state (it does not widen the panel's shared context), so
 * adding the channel is this file plus its registry line.
 */
function SignalInspector({ ctx }: { ctx: ChannelCtx }) {
  const { t, busy, agents, cardsByKey, statusLoaded, setError, setSuccess, setBusy, refreshStatus } = ctx;
  const signal = useSimpleChannel({
    getCredential: api.getSignalCredential,
    activate: api.activateSignalCredential,
    setDefaultAgent: api.setSignalDefaultAgent,
    disconnect: api.disconnectSignalCredential,
    t,
    setError,
    setSuccess,
    setBusy,
    refreshStatus,
  });
  const [baseUrl, setBaseUrl] = useState("");
  const [number, setNumber] = useState("");
  const [authToken, setAuthToken] = useState("");
  const [unknownSenders, setUnknownSenders] = useState<UnknownSenders>("PAIR");
  const [groupMode, setGroupMode] = useState<GroupMode>("IGNORE");

  // Seed the routing picker and the form from what is saved. Keyed on the saved values, not the
  // status map (a new Map every render), so a user's edit is not overwritten while they type.
  const savedAgentId: string | undefined = cardsByKey.get("signal")?.defaultAgentId;
  const { setDefaultAgentIdState } = signal;
  useEffect(() => {
    if (savedAgentId) setDefaultAgentIdState(savedAgentId);
  }, [savedAgentId, setDefaultAgentIdState]);
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const credential = signal.credential as any;
  useEffect(() => {
    if (!credential) return;
    if (credential.baseUrl) setBaseUrl(credential.baseUrl);
    if (credential.number) setNumber(credential.number);
    if (credential.unknownSenders) setUnknownSenders(credential.unknownSenders);
    if (credential.groupMode) setGroupMode(credential.groupMode);
  }, [credential]);

  const active = credential && credential.status === "ACTIVE";

  async function savePolicy(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setSuccess(null);
    setBusy(true);
    try {
      await api.updateSignalPolicy({ unknownSenders, groupMode });
      setSuccess(t("signalPolicySavedSuccess"));
      refreshStatus();
    } catch (err) {
      setError(errorMessage(err, t("signalPolicySaveError")));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <SimpleConnectPanel
        t={t}
        channelKey="signal"
        title={t("connectSignalTitle")}
        subtitle={t("connectSignalSubtitle")}
        credential={signal.credential}
        loaded={signal.loaded}
        connectedLabel={credential?.number ?? ""}
        busy={busy}
        agents={agents}
        defaultAgentId={signal.defaultAgentId}
        savedDefaultAgentId={savedAgentId}
        statusLoaded={statusLoaded}
        onDefaultAgentChange={signal.setDefaultAgentIdState}
        onSaveDefaultAgent={signal.handleSetDefault}
        testSend={
          active && (
            <TestSendCard
              send={(to, body) => api.sendSignalMessage({ to, body })}
              busy={busy}
              setError={setError}
              setSuccess={setSuccess}
              t={t}
              toPlaceholder={t("signalToPlaceholder")}
            />
          )
        }
        onDisconnect={signal.handleDisconnect}
        onSubmit={(e) => {
          e.preventDefault();
          if (!baseUrl.trim() || !number.trim()) {
            setError(t("signalConnectRequiredError"));
            return;
          }
          // Reconnecting keeps the saved who-is-answered settings; the token is only sent when typed.
          signal
            .handleActivate({
              baseUrl: baseUrl.trim(),
              number: number.trim(),
              authToken: authToken.trim() || undefined,
              unknownSenders,
              groupMode,
            })
            .then(() => setAuthToken(""))
            .catch(() => {});
        }}
      >
        <p className="text-xs text-muted-foreground">{t("signalSetupHint")}</p>
        <LabeledInput
          id="signal-base-url"
          type="url"
          label={t("signalBaseUrlLabel")}
          placeholder={t("signalBaseUrlPlaceholder")}
          value={baseUrl}
          onChange={(e) => setBaseUrl(e.target.value)}
        />
        <LabeledInput
          id="signal-number"
          label={t("signalNumberLabel")}
          placeholder={t("signalNumberPlaceholder")}
          value={number}
          onChange={(e) => setNumber(e.target.value)}
        />
        <LabeledInput
          id="signal-auth-token"
          type="password"
          autoComplete="off"
          label={t("signalAuthTokenLabel")}
          value={authToken}
          onChange={(e) => setAuthToken(e.target.value)}
        />
      </SimpleConnectPanel>
      {active && (
        <form
          className="w-full space-y-3 rounded-xl border border-border bg-muted/40 p-4 text-start"
          onSubmit={savePolicy}
        >
          <div className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
            {t("signalPolicyTitle")}
          </div>
          <p className="text-xs text-muted-foreground">{t("signalPolicyHelp")}</p>
          <div className="space-y-1.5">
            <Label htmlFor="signal-unknown-senders" className="text-xs text-muted-foreground">
              {t("signalUnknownSendersLabel")}
            </Label>
            <Select
              id="signal-unknown-senders"
              value={unknownSenders}
              onChange={(e) => setUnknownSenders(e.target.value as UnknownSenders)}
              options={[
                { value: "PAIR", label: t("signalUnknownSendersPair") },
                { value: "IGNORE", label: t("signalUnknownSendersIgnore") },
              ]}
            />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="signal-group-mode" className="text-xs text-muted-foreground">
              {t("signalGroupModeLabel")}
            </Label>
            <Select
              id="signal-group-mode"
              value={groupMode}
              onChange={(e) => setGroupMode(e.target.value as GroupMode)}
              options={[
                { value: "IGNORE", label: t("signalGroupModeIgnore") },
                { value: "MENTION", label: t("signalGroupModeMention") },
                { value: "ALL", label: t("signalGroupModeAll") },
              ]}
            />
          </div>
          <Button type="submit" size="sm" disabled={busy}>
            {t("signalPolicySaveButton")}
          </Button>
        </form>
      )}
    </>
  );
}

export const adapter: ChannelAdapter = { keys: ["signal"], Inspector: SignalInspector };
