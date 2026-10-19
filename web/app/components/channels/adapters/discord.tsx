"use client";

import { api } from "../../../lib/api";
import CredentialHelp from "../../CredentialHelp";
import { DISCORD_BOT_GUIDE } from "../../../lib/credentialGuides";
import { LabeledInput, SimpleConnectPanel, TestSendCard } from "../ChannelInspectorCards";
import type { ChannelAdapter, ChannelCtx } from "./types";

export function DiscordInspector({ ctx }: { ctx: ChannelCtx }) {
  const {
    t,
    discord,
    busy,
    agents,
    cardsByKey,
    statusLoaded,
    setError,
    setSuccess,
    discordBotToken,
    setDiscordBotToken,
  } = ctx;
  return (
    <>
      <SimpleConnectPanel
        t={t}
        channelKey="discord"
        title={t("connectDiscordTitle")}
        subtitle={t("connectDiscordSubtitle")}
        credential={discord.credential}
        loaded={discord.loaded}
        connectedLabel={discord.credential?.botUsername}
        busy={busy}
        agents={agents}
        defaultAgentId={discord.defaultAgentId}
        savedDefaultAgentId={cardsByKey.get("discord")?.defaultAgentId}
        statusLoaded={statusLoaded}
        onDefaultAgentChange={discord.setDefaultAgentIdState}
        onSaveDefaultAgent={discord.handleSetDefault}
        testSend={
          discord.credential &&
          discord.credential.status === "ACTIVE" && (
            <TestSendCard
              send={(to, body) => api.sendDiscordMessage({ to, body })}
              busy={busy}
              setError={setError}
              setSuccess={setSuccess}
              t={t}
            />
          )
        }
        onDisconnect={discord.handleDisconnect}
        onSubmit={(e) => {
          e.preventDefault();
          if (!discordBotToken.trim()) {
            setError(t("botTokenRequiredError"));
            return;
          }
          discord
            .handleActivate({ botToken: discordBotToken })
            .then(() => setDiscordBotToken(""))
            .catch(() => {});
        }}
      >
        <LabeledInput
          id="discord-bot-token"
          type="password"
          label={t("botTokenLabel")}
          help={<CredentialHelp {...DISCORD_BOT_GUIDE} />}
          value={discordBotToken}
          onChange={(e) => setDiscordBotToken(e.target.value)}
        />
        <p className="text-xs text-muted-foreground">{t("discordReceivingHint")}</p>
      </SimpleConnectPanel>
    </>
  );
}

export const adapter: ChannelAdapter = { keys: ["discord"], Inspector: DiscordInspector };
