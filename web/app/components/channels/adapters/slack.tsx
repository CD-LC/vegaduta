"use client";

import { api } from "../../../lib/api";
import CredentialHelp from "../../CredentialHelp";
import { SLACK_APP_GUIDE } from "../../../lib/credentialGuides";
import { LabeledInput, SimpleConnectPanel, TestSendCard } from "../ChannelInspectorCards";
import type { ChannelAdapter, ChannelCtx } from "./types";

export function SlackInspector({ ctx }: { ctx: ChannelCtx }) {
  const {
    t,
    slack,
    busy,
    agents,
    cardsByKey,
    statusLoaded,
    setError,
    setSuccess,
    slackBotToken,
    slackSigningSecret,
    setSlackBotToken,
    setSlackSigningSecret,
  } = ctx;
  return (
    <>
      <SimpleConnectPanel
        t={t}
        channelKey="slack"
        title={t("connectSlackTitle")}
        subtitle={t("connectSlackSubtitle")}
        credential={slack.credential}
        loaded={slack.loaded}
        connectedLabel={slack.credential?.teamName}
        busy={busy}
        agents={agents}
        defaultAgentId={slack.defaultAgentId}
        savedDefaultAgentId={cardsByKey.get("slack")?.defaultAgentId}
        statusLoaded={statusLoaded}
        onDefaultAgentChange={slack.setDefaultAgentIdState}
        onSaveDefaultAgent={slack.handleSetDefault}
        testSend={
          slack.credential &&
          slack.credential.status === "ACTIVE" && (
            <TestSendCard
              send={(to, body) => api.sendSlackMessage({ to, body })}
              busy={busy}
              setError={setError}
              setSuccess={setSuccess}
              t={t}
            />
          )
        }
        onDisconnect={slack.handleDisconnect}
        onSubmit={(e) => {
          e.preventDefault();
          if (!slackBotToken.trim() || !slackSigningSecret.trim()) {
            setError(t("slackRequiredFieldsError"));
            return;
          }
          slack
            .handleActivate({ botToken: slackBotToken, signingSecret: slackSigningSecret })
            .then(() => {
              setSlackBotToken("");
              setSlackSigningSecret("");
            })
            .catch(() => {});
        }}
      >
        <LabeledInput
          id="slack-bot-token"
          type="password"
          label={t("botTokenLabel")}
          help={<CredentialHelp {...SLACK_APP_GUIDE} />}
          placeholder={t("slackBotTokenPlaceholder")}
          value={slackBotToken}
          onChange={(e) => setSlackBotToken(e.target.value)}
        />
        <LabeledInput
          id="slack-signing-secret"
          type="password"
          label={t("signingSecretLabel")}
          value={slackSigningSecret}
          onChange={(e) => setSlackSigningSecret(e.target.value)}
        />
      </SimpleConnectPanel>
    </>
  );
}

export const adapter: ChannelAdapter = { keys: ["slack"], Inspector: SlackInspector };
