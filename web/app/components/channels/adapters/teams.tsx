"use client";

import { api } from "../../../lib/api";
import CredentialHelp from "../../CredentialHelp";
import { TEAMS_APP_GUIDE } from "../../../lib/credentialGuides";
import { LabeledInput, SimpleConnectPanel, TestSendCard } from "../ChannelInspectorCards";
import type { ChannelAdapter, ChannelCtx } from "./types";

export function TeamsInspector({ ctx }: { ctx: ChannelCtx }) {
  const {
    t,
    teams,
    busy,
    agents,
    cardsByKey,
    statusLoaded,
    setError,
    setSuccess,
    teamsAppId,
    teamsAppPassword,
    teamsAadTenantId,
    setTeamsAppId,
    setTeamsAppPassword,
    setTeamsAadTenantId,
  } = ctx;
  return (
    <>
      <SimpleConnectPanel
        t={t}
        channelKey="teams"
        title={t("connectTeamsTitle")}
        subtitle={t("connectTeamsSubtitle")}
        credential={teams.credential}
        loaded={teams.loaded}
        connectedLabel={teams.credential ? `app ${teams.credential.appId}` : ""}
        busy={busy}
        agents={agents}
        defaultAgentId={teams.defaultAgentId}
        savedDefaultAgentId={cardsByKey.get("teams")?.defaultAgentId}
        statusLoaded={statusLoaded}
        onDefaultAgentChange={teams.setDefaultAgentIdState}
        onSaveDefaultAgent={teams.handleSetDefault}
        testSend={
          teams.credential &&
          teams.credential.status === "ACTIVE" && (
            <TestSendCard
              send={(to, body) => api.sendTeamsMessage({ to, body })}
              busy={busy}
              setError={setError}
              setSuccess={setSuccess}
              t={t}
            />
          )
        }
        onDisconnect={teams.handleDisconnect}
        onSubmit={(e) => {
          e.preventDefault();
          if (!teamsAppId.trim() || !teamsAppPassword.trim()) {
            setError(t("teamsRequiredFieldsError"));
            return;
          }
          teams
            .handleActivate({
              appId: teamsAppId.trim(),
              appPassword: teamsAppPassword,
              aadTenantId: teamsAadTenantId.trim() || null,
            })
            .then(() => {
              setTeamsAppId("");
              setTeamsAppPassword("");
              setTeamsAadTenantId("");
            })
            .catch(() => {});
        }}
      >
        <LabeledInput
          id="teams-app-id"
          label={t("teamsAppIdLabel")}
          help={<CredentialHelp {...TEAMS_APP_GUIDE} />}
          value={teamsAppId}
          onChange={(e) => setTeamsAppId(e.target.value)}
        />
        <LabeledInput
          id="teams-app-password"
          type="password"
          label={t("teamsAppPasswordLabel")}
          value={teamsAppPassword}
          onChange={(e) => setTeamsAppPassword(e.target.value)}
        />
        <LabeledInput
          id="teams-tenant-id"
          label={t("teamsTenantIdLabel")}
          placeholder={t("teamsTenantIdPlaceholder")}
          value={teamsAadTenantId}
          onChange={(e) => setTeamsAadTenantId(e.target.value)}
        />
        <p className="text-xs text-muted-foreground">{t("teamsMessagingEndpointHint")}</p>
      </SimpleConnectPanel>
    </>
  );
}

export const adapter: ChannelAdapter = { keys: ["teams"], Inspector: TeamsInspector };
