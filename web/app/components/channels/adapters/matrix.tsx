"use client";

import { useEffect, useState, type FormEvent } from "react";
import { api } from "../../../lib/api";
import { errorMessage } from "../../../lib/errors";
import { MATRIX_BOT_GUIDE } from "../../../lib/credentialGuides";
import { useTranslations } from "../../../lib/i18n";
import CredentialHelp from "../../CredentialHelp";
import Select from "../../Select";
import { Button } from "../../ui/button";
import { Label } from "../../ui/label";
import { Textarea } from "../../ui/textarea";
import { LabeledInput, SimpleConnectPanel, TestSendCard } from "../ChannelInspectorCards";
import { useSimpleChannel } from "../useSimpleChannel";
import type { ChannelAdapter, ChannelCtx } from "./types";

// Matrix bot: a homeserver URL plus the access token of a bot user. Receiving is a /sync loop (no webhook),
// so the response tells us whether this install runs it (`inboundActive`). Who may talk to the bot is
// default-closed: any sender goes through admin pairing, and the bot joins a room on invite only when
// "join on invite" is on AND the inviter is on the allow-list. End-to-end encrypted rooms are not supported.
export function MatrixInspector({ ctx }: { ctx: ChannelCtx }) {
  const { t, busy, agents, cardsByKey, statusLoaded, setError, setSuccess, setBusy, refreshStatus } = ctx;
  const tm = useTranslations("channelsMatrix");
  const matrix = useSimpleChannel({
    getCredential: api.getMatrixCredential,
    activate: api.activateMatrixCredential,
    setDefaultAgent: api.setMatrixDefaultAgent,
    disconnect: api.disconnectMatrixCredential,
    t,
    setError,
    setSuccess,
    setBusy,
    refreshStatus,
  });
  const credential = matrix.credential as {
    id: string;
    botUserId: string;
    homeserverUrl: string;
    status: string;
    joinPolicy: string;
    senderMode: string;
    allowedSenders: string;
    inboundActive: boolean;
  } | null;

  const [homeserverUrl, setHomeserverUrl] = useState("");
  const [accessToken, setAccessToken] = useState("");
  const [joinPolicy, setJoinPolicy] = useState("OFF");
  const [senderMode, setSenderMode] = useState("PAIRING");
  const [allowedSenders, setAllowedSenders] = useState("");

  // Start from what is stored, so the policy form edits the live settings.
  useEffect(() => {
    if (!credential) return;
    setHomeserverUrl(credential.homeserverUrl || "");
    setJoinPolicy(credential.joinPolicy || "OFF");
    setSenderMode(credential.senderMode || "PAIRING");
    setAllowedSenders(credential.allowedSenders || "");
  }, [credential?.id]); // eslint-disable-line react-hooks/exhaustive-deps

  const isActive = !!credential && credential.status === "ACTIVE";

  const policyBody = { joinPolicy, senderMode, allowedSenders };

  async function savePolicy(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setSuccess(null);
    setBusy(true);
    try {
      await api.updateMatrixPolicy(policyBody);
      setSuccess(tm("policySaved"));
    } catch (err) {
      setError(errorMessage(err, tm("policySaveError")));
    } finally {
      setBusy(false);
    }
  }

  const policyOptionsJoin = [
    { value: "OFF", label: tm("joinOff") },
    { value: "ALLOWLIST", label: tm("joinAllowlist") },
  ];
  const policyOptionsSender = [
    { value: "PAIRING", label: tm("senderPairing") },
    { value: "ALLOWLIST", label: tm("senderAllowlist") },
  ];

  const policyFields = (
    <>
      <div className="space-y-1.5">
        <Label htmlFor="matrix-sender-mode" className="text-xs text-muted-foreground">
          {tm("senderModeLabel")}
        </Label>
        <Select
          id="matrix-sender-mode"
          value={senderMode}
          onChange={(e) => setSenderMode(e.target.value)}
          options={policyOptionsSender}
        />
      </div>
      <div className="space-y-1.5">
        <Label htmlFor="matrix-join-policy" className="text-xs text-muted-foreground">
          {tm("joinPolicyLabel")}
        </Label>
        <Select
          id="matrix-join-policy"
          value={joinPolicy}
          onChange={(e) => setJoinPolicy(e.target.value)}
          options={policyOptionsJoin}
        />
      </div>
      <div className="space-y-1.5">
        <Label htmlFor="matrix-allowed-senders" className="text-xs text-muted-foreground">
          {tm("allowedSendersLabel")}
        </Label>
        <Textarea
          id="matrix-allowed-senders"
          rows={3}
          placeholder="@alice:example.org&#10;@*:example.org"
          value={allowedSenders}
          onChange={(e) => setAllowedSenders(e.target.value)}
        />
        <p className="m-0 text-xs text-muted-foreground">{tm("allowedSendersHint")}</p>
      </div>
    </>
  );

  return (
    <SimpleConnectPanel
      t={t}
      channelKey="matrix"
      title={tm("title")}
      subtitle={tm("subtitle")}
      credential={credential}
      loaded={matrix.loaded}
      connectedLabel={credential ? credential.botUserId : ""}
      busy={busy}
      agents={agents}
      defaultAgentId={matrix.defaultAgentId}
      savedDefaultAgentId={cardsByKey.get("matrix")?.defaultAgentId}
      statusLoaded={statusLoaded}
      onDefaultAgentChange={matrix.setDefaultAgentIdState}
      onSaveDefaultAgent={matrix.handleSetDefault}
      testSend={
        <>
          {isActive && credential && !credential.inboundActive && (
            <p className="m-0 rounded-lg border border-border bg-muted px-3 py-2 text-xs text-muted-foreground">
              {tm("inboundOff")}
            </p>
          )}
          {isActive && (
            <form className="w-full space-y-3 rounded-xl border border-border bg-muted/40 p-4" onSubmit={savePolicy}>
              <div className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                {tm("policyTitle")}
              </div>
              {policyFields}
              <Button type="submit" size="sm" variant="outline" disabled={busy}>
                {tm("savePolicy")}
              </Button>
            </form>
          )}
          {isActive && (
            <TestSendCard
              send={(to, body) => api.sendMatrixMessage({ to, body })}
              busy={busy}
              setError={setError}
              setSuccess={setSuccess}
              t={t}
              toPlaceholder={tm("roomIdPlaceholder")}
            />
          )}
          <p className="m-0 text-xs text-muted-foreground">{tm("encryptionNote")}</p>
        </>
      }
      onDisconnect={matrix.handleDisconnect}
      onSubmit={(e) => {
        e.preventDefault();
        if (!homeserverUrl.trim() || !accessToken.trim()) {
          setError(tm("requiredError"));
          return;
        }
        matrix
          .handleActivate({ homeserverUrl: homeserverUrl.trim(), accessToken: accessToken.trim(), ...policyBody })
          .then(() => setAccessToken(""))
          .catch(() => {});
      }}
    >
      <LabeledInput
        id="matrix-homeserver"
        type="url"
        label={tm("homeserverLabel")}
        placeholder="https://matrix.example.org"
        value={homeserverUrl}
        onChange={(e) => setHomeserverUrl(e.target.value)}
      />
      <LabeledInput
        id="matrix-access-token"
        type="password"
        autoComplete="off"
        label={tm("accessTokenLabel")}
        help={<CredentialHelp {...MATRIX_BOT_GUIDE} />}
        value={accessToken}
        onChange={(e) => setAccessToken(e.target.value)}
      />
      {!isActive && policyFields}
    </SimpleConnectPanel>
  );
}

export const adapter: ChannelAdapter = { keys: ["matrix"], Inspector: MatrixInspector };
