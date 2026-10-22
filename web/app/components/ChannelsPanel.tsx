"use client";

import InspectorErrorBoundary from "./channels/InspectorErrorBoundary";
import { forwardRef, useCallback, useEffect, useImperativeHandle, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { api } from "../lib/api";
import { Button } from "./ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "./ui/card";
import { Alert, AlertDescription } from "./ui/alert";
import { CHANNEL_META, type AgentOption } from "./channels/ChannelInspectorCards";
import { credentialGetter, useSimpleChannel } from "./channels/useSimpleChannel";
import { useDevices } from "./channels/useDevices";
import { adapterFor, type ChannelCtx } from "./channels/adapters";
import { useWhatsAppConnect } from "./channels/useWhatsAppConnect";
import { useXChannel } from "./channels/useXChannel";
import { useVoiceDtmf } from "./channels/useVoiceDtmf";
import { ChannelGrid } from "./channels/ChannelGrid";
import Link from "next/link";
import { useTranslations } from "../lib/i18n";
import SenderApprovalGuide from "./SenderApprovalGuide";
import { getMobileApkDownload } from "../lib/mobileApp";
import { errorMessage } from "../lib/errors";
import { channelOffered } from "../lib/edition";

// AXON Channels screen (#472) - rebuilt to pixel-match Channels.dc.html:
// a 3-col grid of every channel's real connection status (left) + a link
// card into /admin/inbox (bottom-left - the 10-row RECENT INBOUND log that
// used to sit here moved to that full conversations page on 2026-09-23),
// driven entirely by api.getChannelsStatus()'s single aggregate
// (ChannelStatusController), and
// a contextual "connect this channel" inspector (right) that swaps between
// nine per-channel forms depending on which grid card is selected -
// consolidating what used to be two separate panels (ChannelsPanel's
// WhatsApp 3-way + SocialChannelsPanel's Messenger/Instagram/Slack/Discord/X)
// plus two brand-new channels (Telegram, Email) into the mockup's single
// two-pane layout. Reuses the exact .ax-tl-body/.ax-tl-main/.ax-tl-inspector
// structure already built for /tools - same "page fetches, real aggregate
// drives the whole screen" shape.

// The Android APK download constant lives in lib/mobileApp.ts (shared with /try); bump the release there.

// ChannelStatusController's aggregate (GET /api/channels/status) - only the
// fields this panel reads.
interface ChannelStatusEntry {
  key: string;
  state: string;
  detail?: string;
  sessionCount?: number;
  defaultAgentId?: string;
}

interface ChannelsStatusResponse {
  channels: ChannelStatusEntry[];
  // ChannelStatusController's aggregate also returns a `recentInbound` log;
  // this panel stopped reading it when the 10-row RECENT INBOUND section
  // moved to the full /admin/inbox page (2026-09-23).
}

const ChannelsPanel = forwardRef(function ChannelsPanel({ agents }: { agents?: AgentOption[] }, ref) {
  const t = useTranslations("channelsPanel");
  const tLayout = useTranslations("channelsLayout");
  const apk = getMobileApkDownload();
  const router = useRouter();
  const searchParams = useSearchParams();
  const inspectorRef = useRef(null);
  // Channels whose adapters this install does not run (Slack, Discord, Teams, X on a personal install). Their
  // hooks below stay mounted, as hooks must, but get a settled "nothing connected" instead of the credential GET.
  const slackOffered = channelOffered("slack");
  const discordOffered = channelOffered("discord");
  const teamsOffered = channelOffered("teams");
  const xOffered = channelOffered("x");

  const [status, setStatus] = useState<ChannelsStatusResponse | null>(null);
  const [statusLoaded, setStatusLoaded] = useState(false);
  const [selected, setSelected] = useState("whatsapp");
  const [error, setError] = useState(null);
  const [success, setSuccess] = useState(null);
  const [busy, setBusy] = useState(false);

  // Plan entitlement for WhatsApp (upgrade-moment UX, pricing audit
  // 2026-07-31 §5.3/B5): when the tenant's plan does NOT include WhatsApp
  // (whatsappAllowed=false on GET /api/tenant/settings - the same field
  // /features reads), the WhatsApp inspector leads with a value pitch +
  // trial/upgrade CTA instead of letting the connect attempt fail cold
  // server-side. undefined = not yet known → show nothing extra.
  const [waAllowed, setWaAllowed] = useState<boolean | undefined>(undefined);
  const refreshEntitlements = useCallback(() => {
    api
      .getTenantSettings()
      .then((s: any) => setWaAllowed(s?.whatsappAllowed !== false))
      .catch(() => setWaAllowed(undefined));
  }, []);
  useEffect(() => {
    refreshEntitlements();
  }, [refreshEntitlements]);

  // "+ Connect a channel" in the page topbar - jumps to the first
  // not-yet-connected channel (falls back to WhatsApp) and scrolls the
  // sticky inspector rail into view, so the button does something real
  // rather than being purely decorative.
  useImperativeHandle(ref, () => ({
    openConnect() {
      const firstOpen = status?.channels.find((c) => c.state !== "CONNECTED" && channelOffered(c.key));
      setSelected(firstOpen ? firstOpen.key : "whatsapp");
      inspectorRef.current?.scrollIntoView({ behavior: "smooth", block: "start" });
    },
  }));

  const refreshStatus = useCallback(() => {
    api
      .getChannelsStatus()
      .then(setStatus)
      .catch(() => setStatus(null))
      .finally(() => setStatusLoaded(true));
  }, []);

  useEffect(() => {
    refreshStatus();
  }, [refreshStatus]);

  // Deep-link tab selection: /admin/channels?channel=mobile-sdk (or any
  // other valid channel key) pre-selects that channel's inspector tab. The
  // retired standalone /devices page now redirects here with this param, so
  // old bookmarks land on the Mobile SDK tab directly. Only acts when the
  // param is present and valid. The param stays in the URL and follows tile
  // clicks (selectChannel), so a reload or a shared link keeps the tab.
  useEffect(() => {
    const channelParam = searchParams.get("channel");
    if (!channelParam || (!CHANNEL_META[channelParam] && channelParam !== "sms")) return;
    if (!channelOffered(channelParam)) return;
    setSelected(channelParam);
  }, [searchParams]);
  const selectChannel = useCallback(
    (key: string) => {
      setSelected(key);
      const params = new URLSearchParams(searchParams.toString());
      params.set("channel", key);
      router.replace(`/admin/channels?${params.toString()}`, { scroll: false });
    },
    [router, searchParams]
  );

  const ui = { t, setError, setSuccess, setBusy, refreshStatus };
  const whatsapp = useWhatsAppConnect(ui);

  // WhatsApp and plain-SMS routing (default agent per channel) live in
  // TwilioRoutingCards, which owns their state and save handlers.

  const telegram = useSimpleChannel({
    ...ui,
    getCredential: api.getTelegramCredential,
    activate: api.activateTelegramCredential,
    setDefaultAgent: api.setTelegramDefaultAgent,
    disconnect: api.disconnectTelegramCredential,
  });
  const [telegramBotToken, setTelegramBotToken] = useState("");

  const email = useSimpleChannel({
    ...ui,
    getCredential: api.getEmailCredential,
    activate: api.activateEmailCredential,
    setDefaultAgent: api.setEmailDefaultAgent,
    disconnect: api.disconnectEmailCredential,
  });
  const [imapHost, setImapHost] = useState("");
  const [imapPort, setImapPort] = useState("993");
  const [imapUsername, setImapUsername] = useState("");
  const [imapPassword, setImapPassword] = useState("");

  const slack = useSimpleChannel({
    ...ui,
    getCredential: credentialGetter(slackOffered, api.getSlackCredential),
    activate: api.activateSlackCredential,
    setDefaultAgent: api.setSlackDefaultAgent,
    disconnect: api.disconnectSlackCredential,
  });
  const [slackBotToken, setSlackBotToken] = useState("");
  const [slackSigningSecret, setSlackSigningSecret] = useState("");

  const discord = useSimpleChannel({
    ...ui,
    getCredential: credentialGetter(discordOffered, api.getDiscordCredential),
    activate: api.activateDiscordCredential,
    setDefaultAgent: api.setDiscordDefaultAgent,
    disconnect: api.disconnectDiscordCredential,
  });
  const [discordBotToken, setDiscordBotToken] = useState("");

  // Microsoft Teams (backend landed 2026-09-23, migration V240) - an Azure
  // Bot registration (appId + client secret, optional Entra tenant id).
  const teams = useSimpleChannel({
    ...ui,
    getCredential: credentialGetter(teamsOffered, api.getTeamsCredential),
    activate: api.activateTeamsCredential,
    setDefaultAgent: api.setTeamsDefaultAgent,
    disconnect: api.disconnectTeamsCredential,
  });
  const [teamsAppId, setTeamsAppId] = useState("");
  const [teamsAppPassword, setTeamsAppPassword] = useState("");
  const [teamsAadTenantId, setTeamsAadTenantId] = useState("");

  // Messenger + Instagram share ONE Facebook Page credential (activate/
  // credential below), but each has its OWN default-agent routing on the
  // backend (ChannelType.MESSENGER vs ChannelType.INSTAGRAM) - so unlike
  // Telegram/Email/Slack/Discord, this can't reuse a single useSimpleChannel
  // default-agent slot. Whichever card is currently selected picks which
  // pair of state/handler below RouteBox actually gets used (see the
  // messenger/instagram JSX block).
  const metaSocial = useSimpleChannel({
    ...ui,
    getCredential: api.getMetaSocialCredential,
    activate: api.activateMetaSocialCredential,
    setDefaultAgent: api.setMessengerDefaultAgent, // unused for default-agent now, kept so useSimpleChannel's shape stays uniform
    disconnect: api.disconnectMetaSocialCredential,
  });
  const [pageId, setPageId] = useState("");
  const [pageAccessToken, setPageAccessToken] = useState("");
  const [metaSocialAppSecret, setMetaSocialAppSecret] = useState("");
  const [igBusinessAccountId, setIgBusinessAccountId] = useState("");
  const [messengerDefaultAgentId, setMessengerDefaultAgentId] = useState("");
  const [instagramDefaultAgentId, setInstagramDefaultAgentId] = useState("");
  async function handleSaveMessengerDefaultAgent(e: any) {
    e.preventDefault();
    if (!messengerDefaultAgentId) return;
    setBusy(true);
    try {
      await api.setMessengerDefaultAgent(messengerDefaultAgentId);
      setSuccess(t("messengerDefaultAgentUpdatedSuccess"));
      refreshStatus();
    } catch (err) {
      setError(errorMessage(err, t("setDefaultAgentError")));
    } finally {
      setBusy(false);
    }
  }
  async function handleSaveInstagramDefaultAgent(e: any) {
    e.preventDefault();
    if (!instagramDefaultAgentId) return;
    setBusy(true);
    try {
      await api.setInstagramDefaultAgent(instagramDefaultAgentId);
      setSuccess(t("instagramDefaultAgentUpdatedSuccess"));
      refreshStatus();
    } catch (err) {
      setError(errorMessage(err, t("setDefaultAgentError")));
    } finally {
      setBusy(false);
    }
  }

  const xChannel = useXChannel({ ...ui, offered: xOffered });
  const { setXDefaultAgentId } = xChannel;

  const voice = useVoiceDtmf(ui);
  const { setVoiceDefaultAgentId } = voice;

  // Seed every "ROUTE INBOUND TO" picker from the real saved value the
  // moment status loads. Without this, PUT .../default-agent was already
  // persisting correctly, but every RouteBox always rendered blank/
  // unselected on mount (no GET anywhere ever read it back) - which looked
  // exactly like "saving doesn't work" even though the backend had it right
  // the whole time. ChannelStatusController now includes defaultAgentId per
  // channel precisely to close this gap.
  useEffect(() => {
    if (!status) return;
    const byKey = Object.fromEntries(status.channels.map((c) => [c.key, c]));
    if (byKey.telegram?.defaultAgentId) telegram.setDefaultAgentIdState(byKey.telegram.defaultAgentId);
    if (byKey.email?.defaultAgentId) email.setDefaultAgentIdState(byKey.email.defaultAgentId);
    if (byKey.slack?.defaultAgentId) slack.setDefaultAgentIdState(byKey.slack.defaultAgentId);
    if (byKey.discord?.defaultAgentId) discord.setDefaultAgentIdState(byKey.discord.defaultAgentId);
    if (byKey.messenger?.defaultAgentId) setMessengerDefaultAgentId(byKey.messenger.defaultAgentId);
    if (byKey.instagram?.defaultAgentId) setInstagramDefaultAgentId(byKey.instagram.defaultAgentId);
    if (byKey.x?.defaultAgentId) setXDefaultAgentId(byKey.x.defaultAgentId);
    if (byKey.teams?.defaultAgentId) teams.setDefaultAgentIdState(byKey.teams.defaultAgentId);
    if (byKey.voice?.defaultAgentId) setVoiceDefaultAgentId(byKey.voice.defaultAgentId);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [status]);

  // Same click-again-to-confirm shape as device revoke above, for the
  // channel disconnect/sign-out actions below - these wipe auth state or
  // stop inbound routing for the tenant, so a single accidental click
  // should not be able to fire them.
  const [confirmDisconnectKey, setConfirmDisconnectKey] = useState<string | null>(null);
  function handleDisconnectClick(key: string, run: () => void | Promise<void>) {
    if (confirmDisconnectKey === key) {
      setConfirmDisconnectKey(null);
      void run();
    } else {
      setConfirmDisconnectKey(key);
    }
  }
  const {
    pairingCode,
    devices,
    devicesLoaded,
    expandedDeviceId,
    commandsByDevice,
    confirmRevokeDeviceId,
    setConfirmRevokeDeviceId,
    handleGeneratePairingCode,
    handleToggleDeviceHistory,
    handleRevokeDeviceClick,
    devicesPagination,
    deviceCommandsPagination,
  } = useDevices({ selected, t, setError, setSuccess, setBusy });

  const cardsByKey = new Map<string, any>((status?.channels || []).map((c) => [c.key, c]));
  const voiceStatusEntry = cardsByKey.get("voice");
  const voiceConnected = voiceStatusEntry?.state === "CONNECTED";

  const ctx: ChannelCtx = {
    t,
    agents,
    apk,
    selected,
    selectChannel,
    busy,
    setBusy,
    setError,
    setSuccess,
    refreshStatus,
    statusLoaded,
    cardsByKey,
    waAllowed,
    refreshEntitlements,
    ...whatsapp,
    confirmDisconnectKey,
    handleDisconnectClick,
    telegram,
    telegramBotToken,
    setTelegramBotToken,
    email,
    imapHost,
    setImapHost,
    imapPort,
    setImapPort,
    imapUsername,
    setImapUsername,
    imapPassword,
    setImapPassword,
    slack,
    slackBotToken,
    setSlackBotToken,
    slackSigningSecret,
    setSlackSigningSecret,
    discord,
    discordBotToken,
    setDiscordBotToken,
    teams,
    teamsAppId,
    setTeamsAppId,
    teamsAppPassword,
    setTeamsAppPassword,
    teamsAadTenantId,
    setTeamsAadTenantId,
    metaSocial,
    pageId,
    setPageId,
    pageAccessToken,
    setPageAccessToken,
    metaSocialAppSecret,
    setMetaSocialAppSecret,
    igBusinessAccountId,
    setIgBusinessAccountId,
    instagramDefaultAgentId,
    setInstagramDefaultAgentId,
    messengerDefaultAgentId,
    setMessengerDefaultAgentId,
    handleSaveInstagramDefaultAgent,
    handleSaveMessengerDefaultAgent,
    ...xChannel,
    voiceConnected,
    ...voice,
    pairingCode,
    handleGeneratePairingCode,
    devices,
    devicesLoaded,
    devicesPagination,
    deviceCommandsPagination,
    handleToggleDeviceHistory,
    expandedDeviceId,
    confirmRevokeDeviceId,
    handleRevokeDeviceClick,
    setConfirmRevokeDeviceId,
    commandsByDevice,
  };
  const Inspector = adapterFor(selected)?.Inspector;

  return (
    <div className="grid grid-cols-1 items-start gap-6 lg:grid-cols-[minmax(0,1fr)_400px]">
      <div className="min-w-0 space-y-6">
        {error && (
          <Alert variant="destructive" data-testid="channels-alert-error" role={undefined} aria-hidden="true">
            <AlertDescription>{error}</AlertDescription>
          </Alert>
        )}
        {success && (
          <Alert variant="success" data-testid="channels-alert-success" role={undefined} aria-hidden="true">
            <AlertDescription>{success}</AlertDescription>
          </Alert>
        )}
        <ChannelGrid
          cardsByKey={cardsByKey}
          selected={selected}
          selectChannel={selectChannel}
          statusLoaded={statusLoaded}
        />

        <SenderApprovalGuide />

        {/* RECENT INBOUND moved to its own page (/admin/inbox) - the
            10-row glance here duplicated a slice of that surface with
            fewer controls. This card keeps the section's discoverability
            and deep-links the full conversations view (search, filters,
            transcripts, pairing approvals, takeover/reply, reassign). */}
        <Card>
          <CardHeader className="flex-row items-center justify-between pb-0">
            <CardTitle className="font-mono text-[11px] font-semibold uppercase tracking-[0.14em] text-muted-foreground">
              {t("recentInboundSectionLabel")}
            </CardTitle>
          </CardHeader>
          <CardContent className="flex flex-wrap items-center justify-between gap-3">
            <p className="max-w-prose text-sm text-muted-foreground">{t("inboxMovedBlurb")}</p>
            <Button asChild variant="outline" size="sm">
              <Link href="/admin/inbox">{t("openInboxLink")}</Link>
            </Button>
          </CardContent>
        </Card>
      </div>

      <div className="lg:sticky lg:top-[calc(4rem+1.5rem)] scroll-mt-[calc(4rem+1.5rem)]" ref={inspectorRef}>
        {/* Bounded: the panel never grows past the viewport (the page stays the height of the channel list);
            a tall inspector scrolls inside the card instead. */}
        <Card className="overflow-hidden lg:max-h-[calc(100dvh-7rem)]">
          <CardContent
            className="space-y-4 py-6 lg:max-h-[calc(100dvh-7rem)] lg:overflow-y-auto"
            data-testid="channels-inspector-scroll"
          >
            {/* Duplicated from the left column's Alert above (same error/success
            state) - that copy is out of view once this sticky inspector is
            scrolled to, which is exactly where a connect/disconnect action
            just ran, so the result needs to show up here too. */}
            {error && (
              <Alert variant="destructive" data-testid="channels-inspector-alert-error">
                <AlertDescription>{error}</AlertDescription>
              </Alert>
            )}
            {success && (
              <Alert variant="success" data-testid="channels-inspector-alert-success">
                <AlertDescription>{success}</AlertDescription>
              </Alert>
            )}
            {Inspector && (
              <InspectorErrorBoundary
                resetKey={String(selected)}
                title={tLayout("inspectorCrashTitle")}
                retryLabel={tLayout("inspectorRetry")}
              >
                <Inspector ctx={ctx} />
              </InspectorErrorBoundary>
            )}
          </CardContent>
        </Card>
      </div>
    </div>
  );
});

export default ChannelsPanel;
