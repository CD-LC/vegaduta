"use client";

import { api } from "../../../lib/api";
import CredentialHelp from "../../CredentialHelp";
import { X_APP_GUIDE } from "../../../lib/credentialGuides";
import { Button } from "../../ui/button";
import {
  ChannelHealthCard,
  ConnectedCard,
  InboundRoutingCard,
  InspectorHead,
  LabeledInput,
  TestSendCard,
} from "../ChannelInspectorCards";
import { errorMessage } from "../../../lib/errors";
import type { ChannelAdapter, ChannelCtx } from "./types";

export function XInspector({ ctx }: { ctx: ChannelCtx }) {
  const {
    t,
    xLoaded,
    x,
    confirmDisconnectKey,
    handleDisconnectClick,
    handleDisconnectX,
    busy,
    handleRegisterXApp,
    xClientId,
    setXClientId,
    xClientSecret,
    setXClientSecret,
    handleConnectX,
    setError,
    setSuccess,
    agents,
    xDefaultAgentId,
    cardsByKey,
    statusLoaded,
    setXDefaultAgentId,
    setBusy,
    refreshStatus,
  } = ctx;
  return (
    <>
      <>
        <InspectorHead channelKey="x" title={t("connectXTitle")} subtitle={t("connectXSubtitle")} />
        {xLoaded && x && x.status === "ACTIVE" && (
          <div className="flex items-center justify-between gap-2.5 rounded-lg border border-border bg-muted px-3 py-2.5">
            <div className="min-w-0">
              <div className="font-mono text-[10px] uppercase tracking-wide text-muted-foreground">{x.status}</div>
              <div className="truncate font-mono text-xs text-foreground">
                {x.xUsername ? `@${x.xUsername}` : x.clientId}
              </div>
            </div>
            <Button
              type="button"
              variant={confirmDisconnectKey === "x" ? "destructive" : "outline"}
              size="sm"
              onClick={() => handleDisconnectClick("x", handleDisconnectX)}
              disabled={busy}
            >
              {confirmDisconnectKey === "x" ? t("disconnectConfirmButton") : t("disconnectButton")}
            </Button>
          </div>
        )}
        {xLoaded && x && x.status !== "ACTIVE" && (
          <ConnectedCard label={x.status} value={x.xUsername ? `@${x.xUsername}` : x.clientId} />
        )}
        <form className="space-y-3" onSubmit={handleRegisterXApp}>
          <LabeledInput
            id="x-client-id"
            label={t("clientIdLabel")}
            help={<CredentialHelp {...X_APP_GUIDE} />}
            value={xClientId}
            onChange={(e) => setXClientId(e.target.value)}
          />
          <LabeledInput
            id="x-client-secret"
            type="password"
            label={t("clientSecretLabel")}
            value={xClientSecret}
            onChange={(e) => setXClientSecret(e.target.value)}
          />
          <Button type="submit" variant="outline" size="sm" disabled={busy}>
            {x ? t("replaceAppButton") : t("registerAppButton")}
          </Button>
        </form>
        {x && x.status !== "ACTIVE" && (
          <Button type="button" size="sm" disabled={busy} onClick={handleConnectX}>
            {t("connectXAccountButton")}
          </Button>
        )}
        {x?.status === "ACTIVE" && (
          <TestSendCard
            send={(to, body) => api.sendXMessage({ to, body })}
            busy={busy}
            setError={setError}
            setSuccess={setSuccess}
            t={t}
          />
        )}
        {x?.status === "ACTIVE" && <ChannelHealthCard channelKey="x" t={t} />}
        <InboundRoutingCard
          t={t}
          agents={agents}
          value={xDefaultAgentId}
          savedValue={cardsByKey.get("x")?.defaultAgentId}
          loaded={statusLoaded}
          onChange={setXDefaultAgentId}
          onSave={async (e) => {
            e.preventDefault();
            if (!xDefaultAgentId) return;
            setBusy(true);
            try {
              await api.setXDefaultAgent(xDefaultAgentId);
              setSuccess(t("xDefaultAgentUpdatedSuccess"));
              refreshStatus();
            } catch (err) {
              setError(errorMessage(err, t("setDefaultAgentError")));
            } finally {
              setBusy(false);
            }
          }}
          busy={busy}
        />
      </>
    </>
  );
}

export const adapter: ChannelAdapter = { keys: ["x"], Inspector: XInspector };
