"use client";

import { api } from "../../../lib/api";
import CredentialHelp from "../../CredentialHelp";
import { EMAIL_IMAP_GUIDE } from "../../../lib/credentialGuides";
import { LabeledInput, SimpleConnectPanel, TestSendCard } from "../ChannelInspectorCards";
import type { ChannelAdapter, ChannelCtx } from "./types";

export function EmailInspector({ ctx }: { ctx: ChannelCtx }) {
  const {
    t,
    email,
    busy,
    agents,
    cardsByKey,
    statusLoaded,
    setError,
    setSuccess,
    imapHost,
    imapUsername,
    imapPassword,
    imapPort,
    setImapPassword,
    setImapHost,
    setImapPort,
    setImapUsername,
  } = ctx;
  return (
    <>
      <SimpleConnectPanel
        t={t}
        channelKey="email"
        title={t("connectEmailTitle")}
        subtitle={t("connectEmailSubtitle")}
        credential={email.credential}
        loaded={email.loaded}
        connectedLabel={email.credential?.imapUsername}
        busy={busy}
        agents={agents}
        defaultAgentId={email.defaultAgentId}
        savedDefaultAgentId={cardsByKey.get("email")?.defaultAgentId}
        statusLoaded={statusLoaded}
        onDefaultAgentChange={email.setDefaultAgentIdState}
        onSaveDefaultAgent={email.handleSetDefault}
        testSend={
          email.credential &&
          email.credential.status === "ACTIVE" && (
            <TestSendCard
              send={(to, body, subject) => api.sendEmailMessage({ to, subject, body })}
              busy={busy}
              setError={setError}
              setSuccess={setSuccess}
              t={t}
              toPlaceholder={t("testSendToEmailPlaceholder")}
              withSubject
            />
          )
        }
        onDisconnect={email.handleDisconnect}
        onSubmit={(e) => {
          e.preventDefault();
          if (!imapHost.trim() || !imapUsername.trim() || !imapPassword.trim()) {
            setError(t("emailRequiredFieldsError"));
            return;
          }
          email
            .handleActivate({
              imapHost,
              imapPort: Number(imapPort) || 993,
              imapUsername,
              imapPassword,
              useSsl: true,
            })
            .then(() => setImapPassword(""))
            .catch(() => {});
        }}
      >
        <LabeledInput
          id="email-imap-host"
          label={t("imapHostLabel")}
          help={<CredentialHelp {...EMAIL_IMAP_GUIDE} />}
          placeholder={t("imapHostPlaceholder")}
          value={imapHost}
          onChange={(e) => setImapHost(e.target.value)}
        />
        <LabeledInput
          id="email-imap-port"
          label={t("portLabel")}
          value={imapPort}
          onChange={(e) => setImapPort(e.target.value)}
        />
        <LabeledInput
          id="email-imap-username"
          label={t("usernameLabel")}
          value={imapUsername}
          onChange={(e) => setImapUsername(e.target.value)}
        />
        <LabeledInput
          id="email-imap-password"
          type="password"
          label={t("passwordHintLabel")}
          value={imapPassword}
          onChange={(e) => setImapPassword(e.target.value)}
        />
      </SimpleConnectPanel>
    </>
  );
}

export const adapter: ChannelAdapter = { keys: ["email"], Inspector: EmailInspector };
