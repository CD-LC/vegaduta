"use client";

import { useState } from "react";
import { useTranslations } from "../../../lib/i18n";
import { api } from "../../../lib/api";
import CredentialHelp from "../../CredentialHelp";
import {
  TWILIO_AUTH_TOKEN_GUIDE,
  TWILIO_WHATSAPP_NUMBER_GUIDE,
  META_WHATSAPP_GUIDE,
} from "../../../lib/credentialGuides";
import { Loader2, Sparkles } from "lucide-react";
import Link from "next/link";
import TrialOfferButton from "../../TrialOfferButton";
import { isBillingEnabled } from "../../../lib/billing-flag";
import { Button } from "../../ui/button";
import { Tabs, TabsList, TabsTrigger, TabsContent } from "../../ui/tabs";
import { ChannelHealthCard, ConnectedCard, InspectorHead, LabeledInput, TestSendCard } from "../ChannelInspectorCards";
import { TwilioRoutingCards } from "../TwilioRoutingCards";
import type { ChannelAdapter, ChannelCtx } from "./types";

export function WhatsAppInspector({ ctx }: { ctx: ChannelCtx }) {
  const [section, setSection] = useState("connect");
  const tl = useTranslations("channelsLayout");
  const {
    t,
    waAllowed,
    refreshEntitlements,
    waMethod,
    setWaMethod,
    handleActivateMeta,
    metaLoaded,
    metaCredential,
    confirmDisconnectKey,
    handleDisconnectClick,
    handleDisconnectMeta,
    busy,
    metaPhoneNumberId,
    setMetaPhoneNumberId,
    metaWabaId,
    setMetaWabaId,
    metaAccessToken,
    setMetaAccessToken,
    metaAppSecret,
    setMetaAppSecret,
    handleActivateTwilio,
    twilioLoaded,
    twilioCredential,
    handleDisconnectTwilio,
    accountSid,
    setAccountSid,
    authToken,
    setAuthToken,
    waFromNumber,
    setWaFromNumber,
    cardsByKey,
    setError,
    setSuccess,
    agents,
    statusLoaded,
    setBusy,
    refreshStatus,
  } = ctx;
  return (
    <>
      <>
        <InspectorHead
          channelKey="whatsapp"
          title={t("connectWhatsappTitle")}
          subtitle={t("connectWhatsappSubtitle")}
        />

        {/* Upgrade-moment sell (pricing audit §5.3/B5): when this plan
                doesn't include WhatsApp, lead with the value pitch + the
                event-triggered 7-day trial offer (shown only when
                /api/tenant/trial/status says eligible) instead of letting
                the connect attempt fail cold. The connect forms stay
                visible below - the server remains the real gate. */}
        {waAllowed === false && (
          <div className="rounded-xl border border-primary/30 bg-primary/[0.06] p-4">
            <div className="flex items-start gap-2.5">
              <Sparkles size={16} className="mt-0.5 shrink-0 text-primary" aria-hidden="true" />
              <div className="space-y-1">
                <div className="text-sm font-semibold text-foreground">{t("whatsappGateTitle")}</div>
                <p className="m-0 text-xs leading-relaxed text-muted-foreground">{t("whatsappGatePitch")}</p>
              </div>
            </div>
            <div className="mt-3 flex flex-wrap items-center gap-2">
              <TrialOfferButton trigger="whatsapp-gate" onGranted={refreshEntitlements} />
              {isBillingEnabled() && (
                <Button asChild variant="outline" size="sm">
                  <Link href="/features">{t("whatsappGateUpgradeLink")}</Link>
                </Button>
              )}
            </div>
          </div>
        )}

        <Tabs value={section} onValueChange={setSection} data-testid="whatsapp-sections">
          <TabsList className="h-auto w-full flex-wrap">
            <TabsTrigger value="connect" className="flex-none">{tl("tabConnect")}</TabsTrigger>
            <TabsTrigger value="routing" className="flex-none">{tl("tabRouting")}</TabsTrigger>
            <TabsTrigger value="compliance" className="flex-none">{tl("tabCompliance")}</TabsTrigger>
            {cardsByKey.get("whatsapp")?.state === "CONNECTED" && (
              <TabsTrigger value="test" className="flex-none">{tl("tabTest")}</TabsTrigger>
            )}
          </TabsList>

          <TabsContent value="connect" className="space-y-3">
        <Tabs value={waMethod} onValueChange={setWaMethod}>
          <TabsList className="h-auto flex-wrap">
            <TabsTrigger value="meta" className="flex-none">
              {t("metaCloudApiTab")}
            </TabsTrigger>
            <TabsTrigger value="twilio" className="flex-none">
              {t("twilioTab")}
            </TabsTrigger>
          </TabsList>

          <TabsContent value="meta">
            <form className="space-y-3" onSubmit={handleActivateMeta}>
              {metaLoaded && metaCredential && metaCredential.status === "ACTIVE" && (
                <div className="flex items-center justify-between gap-2.5 rounded-lg border border-border bg-muted px-3 py-2.5">
                  <div className="min-w-0">
                    <div className="font-mono text-[10px] uppercase tracking-wide text-muted-foreground">
                      {t("connectedLabel")}
                    </div>
                    <div className="truncate font-mono text-xs text-foreground">
                      {metaCredential.displayPhoneNumber || metaCredential.phoneNumberId}
                    </div>
                  </div>
                  <Button
                    type="button"
                    variant={confirmDisconnectKey === "meta" ? "destructive" : "outline"}
                    size="sm"
                    onClick={() => handleDisconnectClick("meta", handleDisconnectMeta)}
                    disabled={busy}
                  >
                    {confirmDisconnectKey === "meta" ? t("disconnectConfirmButton") : t("disconnectButton")}
                  </Button>
                </div>
              )}
              {metaLoaded && metaCredential && metaCredential.status === "DISCONNECTED" && (
                <ConnectedCard
                  label={t("disconnectedLabel")}
                  value={`${metaCredential.displayPhoneNumber || metaCredential.phoneNumberId}${t("disconnectedReconnectHintShort")}`}
                />
              )}
              <LabeledInput
                id="wa-meta-phone"
                label={t("phoneNumberIdLabel")}
                help={<CredentialHelp {...META_WHATSAPP_GUIDE} />}
                value={metaPhoneNumberId}
                onChange={(e) => setMetaPhoneNumberId(e.target.value)}
              />
              <LabeledInput
                id="wa-meta-waba"
                label={t("wabaIdLabel")}
                value={metaWabaId}
                onChange={(e) => setMetaWabaId(e.target.value)}
              />
              <LabeledInput
                id="wa-meta-token"
                type="password"
                label={t("accessTokenLabel")}
                value={metaAccessToken}
                onChange={(e) => setMetaAccessToken(e.target.value)}
              />
              <LabeledInput
                id="wa-meta-secret"
                type="password"
                label={t("appSecretLabel")}
                value={metaAppSecret}
                onChange={(e) => setMetaAppSecret(e.target.value)}
              />
              <Button type="submit" size="sm" disabled={busy} aria-busy={busy}>
                {busy && <Loader2 size={14} className="animate-spin" aria-hidden="true" />}
                {metaCredential ? t("replaceButton") : t("connectButton")}
              </Button>
            </form>
          </TabsContent>

          <TabsContent value="twilio">
            <form className="space-y-3" onSubmit={handleActivateTwilio}>
              {twilioLoaded && twilioCredential && twilioCredential.status === "ACTIVE" && (
                <div className="flex items-center justify-between gap-2.5 rounded-lg border border-border bg-muted px-3 py-2.5">
                  <div className="min-w-0">
                    <div className="font-mono text-[10px] uppercase tracking-wide text-muted-foreground">
                      {t("connectedLabel")}
                    </div>
                    <div className="truncate font-mono text-xs text-foreground">{twilioCredential.accountSid}</div>
                  </div>
                  <Button
                    type="button"
                    variant={confirmDisconnectKey === "twilio" ? "destructive" : "outline"}
                    size="sm"
                    onClick={() => handleDisconnectClick("twilio", handleDisconnectTwilio)}
                    disabled={busy}
                  >
                    {confirmDisconnectKey === "twilio" ? t("disconnectConfirmButton") : t("disconnectButton")}
                  </Button>
                </div>
              )}
              {twilioLoaded && twilioCredential && twilioCredential.status === "DISCONNECTED" && (
                <ConnectedCard
                  label={t("disconnectedLabel")}
                  value={`${twilioCredential.accountSid}${t("disconnectedReconnectHint")}`}
                />
              )}
              <LabeledInput
                id="wa-twilio-sid"
                label={t("accountSidLabel")}
                value={accountSid}
                onChange={(e) => setAccountSid(e.target.value)}
              />
              <LabeledInput
                id="wa-twilio-token"
                type="password"
                label={t("authTokenLabel")}
                help={<CredentialHelp {...TWILIO_AUTH_TOKEN_GUIDE} />}
                value={authToken}
                onChange={(e) => setAuthToken(e.target.value)}
              />
              <LabeledInput
                id="wa-twilio-from"
                label={t("whatsappFromNumberLabel")}
                help={<CredentialHelp {...TWILIO_WHATSAPP_NUMBER_GUIDE} />}
                placeholder={t("whatsappFromNumberPlaceholder")}
                value={waFromNumber}
                onChange={(e) => setWaFromNumber(e.target.value)}
              />
              <Button type="submit" size="sm" disabled={busy}>
                {twilioCredential ? t("replaceButton") : t("connectButton")}
              </Button>
            </form>
          </TabsContent>
        </Tabs>
          </TabsContent>

          <TabsContent value="routing" className="space-y-3">
        <TwilioRoutingCards
          section="routing"
          agents={agents}
          whatsappSaved={cardsByKey.get("whatsapp")?.defaultAgentId}
          smsSaved={cardsByKey.get("sms")?.defaultAgentId}
          loaded={statusLoaded}
          busy={busy}
          setBusy={setBusy}
          setError={setError}
          setSuccess={setSuccess}
          refreshStatus={refreshStatus}
          t={t}
        />
          </TabsContent>

          <TabsContent value="compliance" className="space-y-3">
        <TwilioRoutingCards
          section="compliance"
          agents={agents}
          whatsappSaved={cardsByKey.get("whatsapp")?.defaultAgentId}
          smsSaved={cardsByKey.get("sms")?.defaultAgentId}
          loaded={statusLoaded}
          busy={busy}
          setBusy={setBusy}
          setError={setError}
          setSuccess={setSuccess}
          refreshStatus={refreshStatus}
          t={t}
        />
          </TabsContent>

          {cardsByKey.get("whatsapp")?.state === "CONNECTED" && (
            <TabsContent value="test" className="space-y-3">
        {cardsByKey.get("whatsapp")?.state === "CONNECTED" && <ChannelHealthCard channelKey="whatsapp" t={t} />}
        {cardsByKey.get("whatsapp")?.state === "CONNECTED" && (
          <TestSendCard
            send={(to, body) => api.sendWhatsApp(to, body)}
            busy={busy}
            setError={setError}
            setSuccess={setSuccess}
            t={t}
            toPlaceholder={t("testSendToPhonePlaceholder")}
          />
        )}

            </TabsContent>
          )}
        </Tabs>
      </>
    </>
  );
}

export const adapter: ChannelAdapter = { keys: ["whatsapp"], Inspector: WhatsAppInspector };
