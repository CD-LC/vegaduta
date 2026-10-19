"use client";

import { api } from "../../../lib/api";
import CredentialHelp from "../../CredentialHelp";
import { META_SOCIAL_GUIDE } from "../../../lib/credentialGuides";
import { LabeledInput, SimpleConnectPanel, TestSendCard } from "../ChannelInspectorCards";
import type { ChannelAdapter, ChannelCtx } from "./types";

export function MetaSocialInspector({ ctx }: { ctx: ChannelCtx }) {
  const {
    t,
    selected,
    metaSocial,
    busy,
    agents,
    instagramDefaultAgentId,
    messengerDefaultAgentId,
    cardsByKey,
    statusLoaded,
    setInstagramDefaultAgentId,
    setMessengerDefaultAgentId,
    handleSaveInstagramDefaultAgent,
    handleSaveMessengerDefaultAgent,
    setError,
    setSuccess,
    pageId,
    pageAccessToken,
    metaSocialAppSecret,
    igBusinessAccountId,
    setPageId,
    setPageAccessToken,
    setMetaSocialAppSecret,
    setIgBusinessAccountId,
  } = ctx;
  return (
    <>
      <SimpleConnectPanel
        t={t}
        channelKey={selected}
        title={t("connectMessengerInstagramTitle")}
        subtitle={t("connectMessengerInstagramSubtitle")}
        credential={metaSocial.credential}
        loaded={metaSocial.loaded}
        connectedLabel={metaSocial.credential?.pageName}
        busy={busy}
        agents={agents}
        defaultAgentId={selected === "instagram" ? instagramDefaultAgentId : messengerDefaultAgentId}
        savedDefaultAgentId={cardsByKey.get(selected)?.defaultAgentId}
        statusLoaded={statusLoaded}
        onDefaultAgentChange={selected === "instagram" ? setInstagramDefaultAgentId : setMessengerDefaultAgentId}
        onSaveDefaultAgent={
          selected === "instagram" ? handleSaveInstagramDefaultAgent : handleSaveMessengerDefaultAgent
        }
        onDisconnect={metaSocial.handleDisconnect}
        testSend={
          metaSocial.credential &&
          metaSocial.credential.status === "ACTIVE" &&
          (selected === "messenger" || metaSocial.credential.instagramConnected) && (
            <TestSendCard
              send={(to, body) =>
                selected === "instagram"
                  ? api.sendInstagramMessage({ to, body })
                  : api.sendMessengerMessage({ to, body })
              }
              busy={busy}
              setError={setError}
              setSuccess={setSuccess}
              t={t}
            />
          )
        }
        onSubmit={(e) => {
          e.preventDefault();
          if (!pageId.trim() || !pageAccessToken.trim() || !metaSocialAppSecret.trim()) {
            setError(t("metaSocialRequiredFieldsError"));
            return;
          }
          metaSocial
            .handleActivate({
              pageId,
              pageAccessToken,
              appSecret: metaSocialAppSecret,
              ...(igBusinessAccountId.trim() ? { instagramBusinessAccountId: igBusinessAccountId } : {}),
            })
            .then(() => {
              setPageId("");
              setPageAccessToken("");
              setMetaSocialAppSecret("");
              setIgBusinessAccountId("");
            })
            .catch(() => {});
        }}
      >
        <LabeledInput
          id="meta-social-page-id"
          label={t("pageIdLabel")}
          help={<CredentialHelp {...META_SOCIAL_GUIDE} />}
          value={pageId}
          onChange={(e) => setPageId(e.target.value)}
        />
        <LabeledInput
          id="meta-social-page-token"
          type="password"
          label={t("pageAccessTokenLabel")}
          value={pageAccessToken}
          onChange={(e) => setPageAccessToken(e.target.value)}
        />
        <LabeledInput
          id="meta-social-secret"
          type="password"
          label={t("appSecretLabel")}
          value={metaSocialAppSecret}
          onChange={(e) => setMetaSocialAppSecret(e.target.value)}
        />
        <LabeledInput
          id="meta-social-ig-id"
          label={t("igBusinessAccountIdLabel")}
          value={igBusinessAccountId}
          onChange={(e) => setIgBusinessAccountId(e.target.value)}
        />
      </SimpleConnectPanel>
    </>
  );
}

export const adapter: ChannelAdapter = { keys: ["instagram","messenger"], Inspector: MetaSocialInspector };
