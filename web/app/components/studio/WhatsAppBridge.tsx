"use client";

import { useCallback, useEffect, useState } from "react";
import { Loader2, MessageCircle, ExternalLink } from "lucide-react";
import { Button } from "../ui/button";
import { QrCode } from "../ui/qr-code";
import { fetchChannels, pairWhatsApp, unlinkWhatsApp, type ChannelsStatus, type DemoSession } from "../try/demoClient";
import { safeWaLink, whatsappView } from "./waPairing";
import { useTranslations } from "../../lib/i18n";

// Real WhatsApp pairing for the Studio Deploy stage: a wa.me click-to-chat
// link (QR for a laptop, tap for a phone) prefilled with a single-use code,
// opening a chat with the PLATFORM-owned demo number. Renders NOTHING unless
// the deployment has that number configured (status.whatsapp.mode === "LIVE").
// No simulated chat exists here - if nothing is switched on, nothing shows.

type Props = {
  token: string;
  session: DemoSession | null;
  onBridgeUsed?: () => void;
};

const POLL_PENDING_MS = 3000;
const POLL_LINKED_MS = 8000;

export default function WhatsAppBridge({ token, session, onBridgeUsed }: Props) {
  const tHC = useTranslations("hcComponentsStudioWhatsAppBridge");
  const [channels, setChannels] = useState<ChannelsStatus | null>(null);
  const [busy, setBusy] = useState(false);
  const expired = !!session?.expired;

  useEffect(() => {
    if (!token) return undefined;
    let cancelled = false;
    void fetchChannels(token).then((next) => {
      if (!cancelled) setChannels(next);
    });
    return () => {
      cancelled = true;
    };
  }, [token]);

  const wa = channels?.whatsapp;
  const view = whatsappView(wa, expired);

  // Poll only while there is something to wait for.
  useEffect(() => {
    if (!token || expired) return undefined;
    if (view !== "waiting" && view !== "linked") return undefined;
    const timer = setInterval(
      () => {
        if (document.hidden) return;
        void fetchChannels(token).then(setChannels);
      },
      view === "waiting" ? POLL_PENDING_MS : POLL_LINKED_MS
    );
    return () => clearInterval(timer);
  }, [token, expired, view]);

  const onGetCode = useCallback(async () => {
    if (!token || busy) return;
    onBridgeUsed?.();
    setBusy(true);
    setChannels(await pairWhatsApp(token));
    setBusy(false);
  }, [token, busy, onBridgeUsed]);

  const onUnlink = useCallback(async () => {
    if (!token) return;
    setChannels(await unlinkWhatsApp(token));
  }, [token]);

  if (view === "hidden" || !wa) return null;

  const link = safeWaLink(wa.deepLink);

  return (
    <section className="rounded-xl border border-border/60 bg-card/40">
      <header className="border-b border-border/60 px-4 py-3">
        <h2 className="flex items-center gap-2 text-sm font-medium text-foreground">
          <MessageCircle aria-hidden="true" className="size-4 text-primary" /> {tHC("s13")}
        </h2>
        <p className="mt-0.5 text-xs text-muted-foreground">{tHC("s12")}</p>
      </header>

      <div className="p-4">
        {view === "idle" && (
          <div className="space-y-3">
            <p className="text-sm text-muted-foreground">{tHC("s11")}</p>
            <Button size="sm" onClick={onGetCode} disabled={busy}>
              {busy ? <Loader2 aria-hidden="true" className="me-2 h-3.5 w-3.5 animate-spin shrink-0" /> : null}
              {tHC("s10")}
            </Button>
          </div>
        )}

        {view === "waiting" && link && (
          <div className="flex flex-col gap-4 sm:flex-row sm:items-center">
            <QrCode value={link} px={168} label={tHC("s9")} className="shrink-0 rounded-md border border-border/60" />
            <div className="space-y-2 text-sm">
              <p>
                {tHC("s8")} <span className="font-mono text-base font-semibold tracking-wider">{wa.pairingCode}</span>
              </p>
              <p className="text-xs text-muted-foreground">
                {tHC("s7")}
                {wa.businessNumberMasked ? ` The demo number is ${wa.businessNumberMasked}.` : ""} {tHC("s6")}
              </p>
              <Button asChild size="sm" variant="outline">
                <a href={link} target="_blank" rel="noopener noreferrer">
                  {tHC("s5")} <ExternalLink aria-hidden="true" className="ms-2 h-3.5 w-3.5 shrink-0" />
                </a>
              </Button>
              <p className="flex items-center gap-2 text-xs text-muted-foreground">
                <Loader2 aria-hidden="true" className="h-3 w-3 animate-spin shrink-0" /> {tHC("s4")}
              </p>
            </div>
          </div>
        )}

        {view === "linked" && (
          <div className="space-y-3">
            <p className="text-sm">
              {tHC("s3")}
              {wa.businessNumberMasked ? ` (${wa.businessNumberMasked})` : ""} {tHC("s2")}
              {wa.messageCount > 0 ? ` ${wa.messageCount} ${wa.messageCount === 1 ? "turn" : "turns"} so far.` : ""}
            </p>
            <p className="text-xs text-muted-foreground">{tHC("s1")}</p>
            <Button size="sm" variant="outline" onClick={onUnlink}>
              Disconnect
            </Button>
          </div>
        )}
      </div>
    </section>
  );
}
