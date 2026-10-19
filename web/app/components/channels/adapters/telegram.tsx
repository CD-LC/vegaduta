"use client";

import { api } from "../../../lib/api";
import CredentialHelp from "../../CredentialHelp";
import { TELEGRAM_BOT_GUIDE } from "../../../lib/credentialGuides";
import { LabeledInput, SimpleConnectPanel, TestSendCard } from "../ChannelInspectorCards";
import type { ChannelAdapter, ChannelCtx } from "./types";

export function TelegramInspector({ ctx }: { ctx: ChannelCtx }) {
  const {
    t,
    telegram,
    busy,
    agents,
    cardsByKey,
    statusLoaded,
    setError,
    setSuccess,
    telegramBotToken,
    setTelegramBotToken,
  } = ctx;
  return (
    <>
      <SimpleConnectPanel
        t={t}
        channelKey="telegram"
        title={t("connectTelegramTitle")}
        subtitle={t("connectTelegramSubtitle")}
        credential={telegram.credential}
        loaded={telegram.loaded}
        connectedLabel={
          telegram.credential ? `@${telegram.credential.botUsername || telegram.credential.botUserId}` : ""
        }
        busy={busy}
        agents={agents}
        defaultAgentId={telegram.defaultAgentId}
        savedDefaultAgentId={cardsByKey.get("telegram")?.defaultAgentId}
        statusLoaded={statusLoaded}
        onDefaultAgentChange={telegram.setDefaultAgentIdState}
        onSaveDefaultAgent={telegram.handleSetDefault}
        testSend={
          telegram.credential &&
          telegram.credential.status === "ACTIVE" && (
            <TestSendCard
              send={(to, body) => api.sendTelegramMessage({ to, body })}
              busy={busy}
              setError={setError}
              setSuccess={setSuccess}
              t={t}
            />
          )
        }
        onDisconnect={telegram.handleDisconnect}
        onSubmit={(e) => {
          e.preventDefault();
          if (!telegramBotToken.trim()) {
            setError(t("botTokenRequiredError"));
            return;
          }
          telegram
            .handleActivate({ botToken: telegramBotToken })
            .then(() => setTelegramBotToken(""))
            .catch(() => {});
        }}
      >
        <LabeledInput
          id="telegram-bot-token"
          type="password"
          label={t("botTokenLabel")}
          help={<CredentialHelp {...TELEGRAM_BOT_GUIDE} />}
          value={telegramBotToken}
          onChange={(e) => setTelegramBotToken(e.target.value)}
        />
      </SimpleConnectPanel>
    </>
  );
}

export const adapter: ChannelAdapter = { keys: ["telegram"], Inspector: TelegramInspector };
