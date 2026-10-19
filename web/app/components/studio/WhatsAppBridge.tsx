"use client";

import { useCallback, useEffect, useState } from "react";
import { Loader2, MessageCircle, ExternalLink } from "lucide-react";
import { Button } from "../ui/button";
import { QrCode } from "../ui/qr-code";
import { fetchChannels, pairWhatsApp, unlinkWhatsApp, type ChannelsStatus, type DemoSession } from "../try/demoClient";
import { safeWaLink, whatsappView } from "./waPairing";

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
          <MessageCircle aria-hidden="true" className="size-4 text-primary" /> Chat with your agent on WhatsApp
        </h2>
        <p className="mt-0.5 text-xs text-muted-foreground">
          A shared demo number, your agent, until this sandbox expires. It replies to you - it cannot message you first.
        </p>
      </header>

      <div className="p-4">
        {view === "idle" && (
          <div className="space-y-3">
            <p className="text-sm text-muted-foreground">
              Get a one-time code, then scan the QR (or tap the link on your phone) and send the pre-filled message.
              Your live agent answers you in WhatsApp.
            </p>
            <Button size="sm" onClick={onGetCode} disabled={busy}>
              {busy ? <Loader2 aria-hidden="true" className="mr-2 h-3.5 w-3.5 animate-spin shrink-0" /> : null}
              Get code
            </Button>
          </div>
        )}

        {view === "waiting" && link && (
          <div className="flex flex-col gap-4 sm:flex-row sm:items-center">
            <QrCode
              value={link}
              px={168}
              label="Scan with your phone camera to open WhatsApp with your pairing code"
              className="shrink-0 rounded-md border border-border/60"
            />
            <div className="space-y-2 text-sm">
              <p>
                Your code: <span className="font-mono text-base font-semibold tracking-wider">{wa.pairingCode}</span>
              </p>
              <p className="text-xs text-muted-foreground">
                Scan the QR with your phone camera, or on this phone tap the button. WhatsApp opens with the code
                already typed - just press send.
                {wa.businessNumberMasked ? ` The demo number is ${wa.businessNumberMasked}.` : ""} The code works once.
              </p>
              <Button asChild size="sm" variant="outline">
                <a href={link} target="_blank" rel="noopener noreferrer">
                  Open WhatsApp <ExternalLink aria-hidden="true" className="ml-2 h-3.5 w-3.5 shrink-0" />
                </a>
              </Button>
              <p className="flex items-center gap-2 text-xs text-muted-foreground">
                <Loader2 aria-hidden="true" className="h-3 w-3 animate-spin shrink-0" /> Waiting for your first
                message...
              </p>
            </div>
          </div>
        )}

        {view === "linked" && (
          <div className="space-y-3">
            <p className="text-sm">
              Connected. Message the demo number{wa.businessNumberMasked ? ` (${wa.businessNumberMasked})` : ""} and
              your live agent answers.
              {wa.messageCount > 0 ? ` ${wa.messageCount} ${wa.messageCount === 1 ? "turn" : "turns"} so far.` : ""}
            </p>
            <p className="text-xs text-muted-foreground">
              Turns here count against this sandbox&apos;s message allowance, same as chatting on this page. It replies
              only when you write - it never messages you first.
            </p>
            <Button size="sm" variant="outline" onClick={onUnlink}>
              Disconnect
            </Button>
          </div>
        )}
      </div>
    </section>
  );
}
