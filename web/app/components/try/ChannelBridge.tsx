"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { motion, AnimatePresence } from "motion/react";
import { ArrowRight, Check, CheckCheck, Copy, Info, Loader2, Send, Smartphone, Unplug } from "lucide-react";
import { Button } from "../ui/button";
import { QrCode } from "../ui/qr-code";
import { RegisterBanner } from "./conversion";
import { useTranslations } from "../../lib/i18n";
import {
  fetchChannels,
  pairTelegram,
  recordWhatsAppPreview,
  sendMessage,
  unlinkTelegram,
  type ChannelMessage,
  type ChannelsStatus,
  type DemoSession,
} from "./demoClient";

// ---------------------------------------------------------------------------
// "Try it from a real messenger" — the conversion hook.
//
// The point of this block is that a visitor stops looking at a web page and
// starts using the product where they actually live: their phone. It therefore
// has one rule above all others, and it is not a UI rule:
//
//   SAY EXACTLY WHAT IS REAL.
//
//   Telegram is a REAL connection. The visitor's own Telegram chat is bound to
//   their sandbox and answered by their own agent over the real Bot API. It is
//   labelled "Connected" because it is.
//
//   WhatsApp is a PREVIEW. The reply is genuinely their agent's — it is an
//   ordinary /api/demo/chat turn, same model, same workspace — but nothing is
//   delivered to WhatsApp, because this platform has no verified business
//   sender that could message a stranger, and asking an anonymous visitor to
//   scan their personal WhatsApp into an unregistered sandbox is a trust ask
//   we are not willing to make (see DemoChannelController's javadoc for the
//   full reasoning). Every surface of that card says so, unprompted, without
//   the visitor having to hover anything.
//
// The second rule is inherited from the rest of /try: no error surface, ever.
// The client resolves every failure to "channel unavailable", and an
// unavailable channel is simply not rendered.
// ---------------------------------------------------------------------------

type Props = {
  token: string | null;
  session: DemoSession | null;
  signupHref: string;
  /** Lets the parent keep its message counter honest — the preview spends the same sandbox allowance. */
  onTurnSpent?: (messagesUsed: number, messageLimit: number) => void;
  /** Guided-lab observer: fired on a REAL bridge action (Telegram connect
   *  clicked, or a WhatsApp-preview message sent) — never on mere render. */
  onBridgeUsed?: () => void;
};

type PreviewBubble = {
  id: string;
  role: "user" | "agent";
  text: string;
  simulated?: boolean;
};

const POLL_PENDING_MS = 3000;
const POLL_LINKED_MS = 5000;

let bubbleCounter = 0;
function newBubbleId(): string {
  bubbleCounter += 1;
  return `cb${Date.now()}-${bubbleCounter}`;
}

export default function ChannelBridge({ token, session, signupHref, onTurnSpent, onBridgeUsed }: Props) {
  const t = useTranslations("try");
  const [channels, setChannels] = useState<ChannelsStatus | null>(null);
  const [tab, setTab] = useState<"telegram" | "whatsapp">("telegram");
  const [pairing, setPairing] = useState(false);

  // Newest mirrored-message timestamp already rendered. Held in a ref rather
  // than state so the poll interval does not need to be torn down and rebuilt
  // on every incoming message.
  const sinceRef = useRef<string | null>(null);
  const [mirrored, setMirrored] = useState<ChannelMessage[]>([]);

  const expired = !!session?.expired;
  const telegram = channels?.telegram;
  const telegramLinked = telegram?.state === "LINKED";
  const telegramPending = telegram?.state === "PENDING" && !!telegram?.pairingCode;
  // When the platform has a real WhatsApp number, the Deploy stage shows the live WhatsApp card (WhatsAppBridge) - so this
  // component drops its own "preview" WhatsApp tab rather than offering a pretend chat beside the real one.
  const whatsappLive = channels?.whatsapp?.mode === "LIVE" && !!channels?.whatsapp?.available;

  const absorb = useCallback((next: ChannelsStatus) => {
    setChannels(next);
    // Drop anything at or before the watermark. The polls already pass
    // `since`, but the pair/unlink calls do not, so this is what stops a
    // full-mirror response from double-rendering the conversation.
    const watermark = sinceRef.current;
    const incoming = (next.telegram?.messages || []).filter((m) => !watermark || m.at > watermark);
    if (incoming.length) {
      setMirrored((prev) => [...prev, ...incoming].slice(-40));
      sinceRef.current = incoming[incoming.length - 1].at;
    }
  }, []);

  // First load. Deliberately silent: if this fails the component renders
  // nothing at all, which is the correct degraded state for an optional
  // affordance on the one page that must never look broken.
  useEffect(() => {
    if (!token) return undefined;
    let cancelled = false;
    void (async () => {
      const next = await fetchChannels(token);
      if (!cancelled) absorb(next);
    })();
    return () => {
      cancelled = true;
    };
  }, [token, absorb]);

  // Poll only while there is something to wait for: an outstanding code, or a
  // live chat whose messages should appear here. An idle card polls nothing.
  useEffect(() => {
    if (!token || expired) return undefined;
    if (!telegramPending && !telegramLinked) return undefined;
    const period = telegramPending ? POLL_PENDING_MS : POLL_LINKED_MS;
    const poll = async () => {
      if (document.hidden) return; // a backgrounded tab has nobody watching it
      absorb(await fetchChannels(token, sinceRef.current));
    };
    const timer = setInterval(() => {
      void poll();
    }, period);
    return () => clearInterval(timer);
  }, [token, expired, telegramPending, telegramLinked, absorb]);

  const onConnect = useCallback(async () => {
    if (!token || pairing) return;
    onBridgeUsed?.();
    setPairing(true);
    absorb(await pairTelegram(token));
    setPairing(false);
  }, [token, pairing, absorb, onBridgeUsed]);

  const onDisconnect = useCallback(async () => {
    if (!token) return;
    setMirrored([]);
    sinceRef.current = null;
    absorb(await unlinkTelegram(token));
  }, [token, absorb]);

  const telegramAvailable = !!telegram?.available;

  // The buzz moment: the first time the agent's own reply arrives OUT over the
  // real Telegram Bot API — i.e. it just answered on the visitor's phone. That
  // is the single most convincing second of this whole page, and the one-tap
  // register banner fires exactly then (once; dismissal sticks per session).
  const agentRepliedOnPhone = mirrored.some((m) => m.direction === "OUT");

  // Nothing to offer at all (no demo bot configured on this deployment, and a
  // sandbox that has ended). Render nothing rather than an empty shell.
  if (!token || (!telegramAvailable && expired)) return null;

  return (
    <section className="rounded-xl border border-border/60 bg-card/40">
      <header className="flex flex-col gap-1 border-b border-border/60 px-4 py-3 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <h2 className="flex items-center gap-2 text-sm font-medium text-foreground">
            <Smartphone aria-hidden="true" className="size-4 text-primary" />
            {telegramAvailable ? t("channel_tryFromPhone") : t("channel_seeAsCustomer")}
          </h2>
          {/* The subtitle changes with what is genuinely on offer. Promising "a
              real messaging app" on a deployment with no demo bot configured
              would be exactly the kind of overclaim this whole block avoids. */}
          <p className="mt-0.5 text-xs text-muted-foreground">
            {telegramAvailable ? t("channel_subtitleReal") : t("channel_subtitlePreview")}
          </p>
        </div>
        {telegramAvailable && !whatsappLive && (
          <div className="flex shrink-0 gap-1 rounded-lg border border-border/70 bg-background/40 p-1">
            <TabButton active={tab === "telegram"} onClick={() => setTab("telegram")}>
              {t("channel_tabTelegram")}
            </TabButton>
            <TabButton active={tab === "whatsapp"} onClick={() => setTab("whatsapp")}>
              {t("channel_tabWhatsapp")}
            </TabButton>
          </div>
        )}
      </header>

      <div className="p-4">
        {telegramAvailable && (tab === "telegram" || whatsappLive) ? (
          <TelegramCard
            botUsername={telegram.botUsername}
            state={telegram.state}
            pairingCode={telegram.pairingCode}
            deepLink={telegram.deepLink}
            peerLabel={telegram.peerLabel}
            messageCount={telegram.messageCount}
            mirrored={mirrored}
            busy={pairing}
            expired={expired}
            signupHref={signupHref}
            onConnect={onConnect}
            onDisconnect={onDisconnect}
          />
        ) : (
          <WhatsAppPreviewCard
            token={token}
            session={session}
            signupHref={signupHref}
            telegramAvailable={telegramAvailable}
            onTurnSpent={onTurnSpent}
            onBridgeUsed={onBridgeUsed}
          />
        )}

        <RegisterBanner
          id="vd_try_banner_telegram"
          show={agentRepliedOnPhone}
          headline={t("channel_bannerPhoneReply")}
          token={token}
          signupHref={signupHref}
          className="mt-3"
        />
      </div>
    </section>
  );
}

function TabButton({ active, onClick, children }: { active: boolean; onClick: () => void; children: React.ReactNode }) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={active}
      className={
        "rounded-md px-3 py-1 text-xs font-medium transition-colors " +
        (active ? "bg-primary/15 text-primary" : "text-muted-foreground hover:text-foreground")
      }
    >
      {children}
    </button>
  );
}

// ---------------------------------------------------------------------------
// Telegram — the real one
// ---------------------------------------------------------------------------

function TelegramCard({
  botUsername,
  state,
  pairingCode,
  deepLink,
  peerLabel,
  messageCount,
  mirrored,
  busy,
  expired,
  signupHref,
  onConnect,
  onDisconnect,
}: {
  botUsername: string | null;
  state: string;
  pairingCode: string | null;
  deepLink: string | null;
  peerLabel: string | null;
  messageCount: number;
  mirrored: ChannelMessage[];
  busy: boolean;
  expired: boolean;
  signupHref: string;
  onConnect: () => void;
  onDisconnect: () => void;
}) {
  const t = useTranslations("try");
  if (expired) {
    return (
      <p className="text-xs text-muted-foreground">
        {t("channel_endedPrefix")}{" "}
        <Link href={signupHref} className="text-primary underline-offset-2 hover:underline">
          {t("try_createFreeAccount")}
        </Link>{" "}
        {t("channel_endedSuffix")}
      </p>
    );
  }

  if (state === "LINKED") {
    return (
      <div className="space-y-3">
        <div className="flex flex-wrap items-center gap-2">
          <span className="inline-flex items-center gap-1.5 rounded-full border border-emerald-500/40 bg-emerald-500/10 px-2.5 py-1 text-[11px] font-medium text-emerald-400">
            <Check aria-hidden="true" className="size-3" />
            {t("channel_connectedLabel")}
            {peerLabel ? ` — ${peerLabel}` : ""}
          </span>
          <span className="text-xs text-muted-foreground">
            {messageCount > 0
              ? t("channel_messagesOverTelegramPlural", { count: messageCount, plural: messageCount === 1 ? "" : "s" })
              : t("channel_sendBotMessage")}
          </span>
          <Button size="sm" variant="ghost" className="ms-auto text-xs text-muted-foreground" onClick={onDisconnect}>
            <Unplug aria-hidden="true" className="size-3.5" />
            {t("channel_disconnect")}
          </Button>
        </div>

        <p className="text-xs text-muted-foreground">
          {t("channel_realExplanation", {
            who: botUsername ? `@${botUsername}` : t("channel_theBot"),
          })}
        </p>

        <MirrorFeed messages={mirrored} />
      </div>
    );
  }

  if (state === "PENDING" && pairingCode) {
    return (
      <div className="space-y-4">
        <div className="flex flex-col gap-3 sm:flex-row sm:items-center">
          <PairingCode code={pairingCode} />
          <div className="flex-1 space-y-2">
            <ol className="space-y-1 text-xs text-muted-foreground">
              <li>
                <span className="text-foreground">1.</span>{" "}
                {t("channel_step1", { username: botUsername ? ` (@${botUsername})` : "" })}
              </li>
              <li>
                <span className="text-foreground">2.</span> {t("channel_step2Pre")}{" "}
                <span className="text-foreground">{t("channel_startWord")}</span> {t("channel_step2Post")}
              </li>
              <li>
                <span className="text-foreground">3.</span> {t("channel_step3")}
              </li>
            </ol>
            {deepLink && (
              <div className="flex items-center gap-3 rounded-xl border border-border/60 p-2">
                <QrCode
                  value={deepLink}
                  px={104}
                  label="QR code that opens the Telegram bot with your pairing code"
                  className="shrink-0 rounded-md"
                />
                <p className="text-xs text-muted-foreground">
                  On a computer? Scan this with your phone camera and Telegram opens with your code filled in.
                </p>
              </div>
            )}
            {deepLink && (
              <Button asChild size="sm">
                {/* rel=noopener on an external target, and the link carries only
                    the single-use pairing code — never the demo token. */}
                <a href={deepLink} target="_blank" rel="noopener noreferrer">
                  {t("channel_openTelegram")}
                  <ArrowRight aria-hidden="true" className="size-4" />
                </a>
              </Button>
            )}
          </div>
        </div>
        <p className="flex items-center gap-2 text-xs text-muted-foreground">
          <Loader2 aria-hidden="true" className="size-3.5 animate-spin" />
          {t("channel_waitingForStart")}
        </p>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
      <p className="text-xs text-muted-foreground">{t("channel_getCodeInstructions")}</p>
      <Button size="sm" onClick={onConnect} disabled={busy} className="shrink-0">
        {busy ? (
          <Loader2 aria-hidden="true" className="size-4 animate-spin" />
        ) : (
          <Send aria-hidden="true" className="size-4" />
        )}
        {t("channel_connectTelegram")}
      </Button>
    </div>
  );
}

function PairingCode({ code }: { code: string }) {
  const t = useTranslations("try");
  const [copied, setCopied] = useState(false);
  const copy = useCallback(async () => {
    try {
      await navigator.clipboard.writeText(code);
      setCopied(true);
      setTimeout(() => setCopied(false), 1800);
    } catch {
      // Clipboard blocked (insecure context, permission denied). The code is
      // right there on screen to be read — nothing to report.
    }
  }, [code]);

  return (
    <button
      type="button"
      onClick={copy}
      title={t("channel_copyCodeTitle")}
      className="group flex items-center gap-2 self-start rounded-lg border border-primary/40 bg-primary/10 px-4 py-3 font-mono text-lg tracking-[0.2em] text-primary transition-colors hover:border-primary/70"
    >
      {code}
      {copied ? (
        <Check aria-hidden="true" className="size-4 shrink-0" />
      ) : (
        <Copy aria-hidden="true" className="size-4 shrink-0 opacity-70 group-hover:opacity-100" />
      )}
    </button>
  );
}

/** What was actually said over Telegram, so a laptop tab reflects a conversation happening on a phone. */
function MirrorFeed({ messages }: { messages: ChannelMessage[] }) {
  const t = useTranslations("try");
  const endRef = useRef<HTMLDivElement | null>(null);
  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: "smooth", block: "nearest" });
  }, [messages]);

  if (!messages.length) {
    return (
      <div className="rounded-lg border border-dashed border-border/70 px-4 py-6 text-center text-xs text-muted-foreground">
        {t("channel_nothingYet")}
      </div>
    );
  }
  return (
    <div className="max-h-56 space-y-2 overflow-y-auto rounded-lg border border-border/60 bg-background/30 p-3">
      <AnimatePresence initial={false}>
        {messages.map((m, i) => (
          <motion.div
            key={`${m.at}-${i}`}
            initial={{ opacity: 0, y: 4 }}
            animate={{ opacity: 1, y: 0 }}
            className={m.direction === "IN" ? "flex justify-end" : "flex justify-start"}
          >
            <div
              className={
                m.direction === "IN"
                  ? "max-w-[85%] rounded-xl rounded-ee-sm bg-primary/20 px-3 py-1.5 text-xs text-foreground"
                  : "max-w-[85%] rounded-xl rounded-es-sm border border-border/60 bg-card/70 px-3 py-1.5 text-xs"
              }
            >
              <p className="whitespace-pre-wrap leading-relaxed">{m.text}</p>
            </div>
          </motion.div>
        ))}
      </AnimatePresence>
      <div ref={endRef} />
    </div>
  );
}

// ---------------------------------------------------------------------------
// WhatsApp — a preview, and it says so everywhere
// ---------------------------------------------------------------------------

function WhatsAppPreviewCard({
  token,
  session,
  signupHref,
  telegramAvailable,
  onTurnSpent,
  onBridgeUsed,
}: {
  token: string;
  session: DemoSession | null;
  signupHref: string;
  telegramAvailable: boolean;
  onTurnSpent?: (messagesUsed: number, messageLimit: number) => void;
  onBridgeUsed?: () => void;
}) {
  const t = useTranslations("try");
  const [bubbles, setBubbles] = useState<PreviewBubble[]>([]);
  const [input, setInput] = useState("");
  const [sending, setSending] = useState(false);
  const chatSessionRef = useRef<string | null>(null);
  const scrollRef = useRef<HTMLDivElement | null>(null);

  const expired = !!session?.expired;
  const outOfMessages = !!session && session.messageLimit > 0 && session.messagesUsed >= session.messageLimit;
  const blocked = expired || outOfMessages;

  useEffect(() => {
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: "smooth" });
  }, [bubbles, sending]);

  const send = useCallback(
    async (text: string) => {
      const trimmed = text.trim();
      if (!trimmed || sending || blocked) return;
      onBridgeUsed?.();
      setInput("");
      setSending(true);
      setBubbles((prev) => [...prev, { id: newBubbleId(), role: "user", text: trimmed }]);

      // The REAL turn. Same endpoint, same model, same sandbox, same allowance
      // as the chat above — the only thing this card changes is the frame it
      // is drawn in.
      const result = await sendMessage(token, trimmed, chatSessionRef.current);
      if (result.sessionId) chatSessionRef.current = result.sessionId;
      setBubbles((prev) => [
        ...prev,
        { id: newBubbleId(), role: "agent", text: result.reply, simulated: result.simulated },
      ]);
      if (result.messageLimit > 0) onTurnSpent?.(result.messagesUsed, result.messageLimit);
      // Fire-and-forget analytics beacon: records only that the WhatsApp
      // affordance was used, so the funnel can compare it against Telegram.
      // No phone number is collected and no message is delivered anywhere.
      void recordWhatsAppPreview(token);
      setSending(false);
    },
    [token, sending, blocked, onTurnSpent, onBridgeUsed]
  );

  return (
    <div className="space-y-3">
      {/* The honesty banner. Above the mockup, not below it, and not a tooltip:
          a visitor must know what they are looking at before they look at it. */}
      <div className="flex items-start gap-2 rounded-lg border border-amber-500/30 bg-amber-500/5 px-3 py-2">
        <Info aria-hidden="true" className="mt-0.5 size-3.5 shrink-0 text-amber-500" />
        <p className="text-xs text-muted-foreground">
          <span className="font-medium text-foreground">{t("channel_previewBannerLabel")}</span>{" "}
          {t("channel_previewBannerBody")}{" "}
          <Link href={signupHref} className="text-primary underline-offset-2 hover:underline">
            {t("channel_registerFree")}
          </Link>{" "}
          {t("channel_connectRealNumber")}
          {telegramAvailable ? t("channel_switchToTelegramSuffix") : "."}
        </p>
      </div>

      <div className="overflow-hidden rounded-xl border border-border/60">
        <div className="flex items-center gap-2 bg-emerald-900/40 px-3 py-2">
          <div className="flex size-7 items-center justify-center rounded-full bg-emerald-600 text-[11px] font-semibold text-white">
            {(session?.agentName || "A").slice(0, 1).toUpperCase()}
          </div>
          <div className="min-w-0">
            <p className="truncate text-xs font-medium text-foreground">{session?.agentName || t("try_yourAgent")}</p>
            <p className="text-[10px] text-muted-foreground">{t("channel_previewLabel")}</p>
          </div>
        </div>

        <div ref={scrollRef} className="max-h-56 min-h-[8rem] space-y-2 overflow-y-auto bg-background/40 p-3">
          {bubbles.length === 0 && (
            <p className="py-6 text-center text-xs text-muted-foreground">{t("channel_typeCustomerMessagePrompt")}</p>
          )}
          {bubbles.map((b) => (
            <div key={b.id} className={b.role === "user" ? "flex justify-end" : "flex justify-start"}>
              <div
                className={
                  b.role === "user"
                    ? "max-w-[85%] rounded-xl rounded-ee-sm bg-emerald-700/50 px-3 py-1.5 text-xs text-foreground"
                    : "max-w-[85%] rounded-xl rounded-es-sm border border-border/60 bg-card/80 px-3 py-1.5 text-xs"
                }
              >
                {b.simulated && (
                  <span className="mb-1 inline-block rounded-full border border-border/70 bg-background/50 px-1.5 py-0.5 text-[9px] uppercase tracking-wide text-muted-foreground">
                    {t("try_sampleResponse")}
                  </span>
                )}
                <p className="whitespace-pre-wrap leading-relaxed">{b.text}</p>
                {b.role === "user" && (
                  <CheckCheck aria-hidden="true" className="mt-0.5 ms-auto block size-3 text-emerald-400" />
                )}
              </div>
            </div>
          ))}
          {sending && (
            <div className="flex justify-start">
              <div className="rounded-xl rounded-es-sm border border-border/60 bg-card/80 px-3 py-2">
                <Loader2 aria-hidden="true" className="size-3 animate-spin text-muted-foreground" />
              </div>
            </div>
          )}
        </div>

        {blocked ? (
          <div className="flex items-center justify-between gap-2 border-t border-border/60 bg-card/40 px-3 py-2">
            <p className="text-xs text-muted-foreground">
              {expired ? t("try_inventoryEnded") : t("channel_usedAllMessages")}
            </p>
            <Button asChild size="sm" variant="outline">
              <Link href={signupHref}>{t("channel_registerFree")}</Link>
            </Button>
          </div>
        ) : (
          <div className="flex items-center gap-2 border-t border-border/60 bg-card/40 px-2 py-2">
            <input
              value={input}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Enter") {
                  e.preventDefault();
                  void send(input);
                }
              }}
              maxLength={2000}
              placeholder={t("channel_typeMessagePlaceholder")}
              aria-label={t("channel_messageAgentPreviewAria")}
              className="flex-1 bg-transparent px-2 py-1.5 text-xs outline-none placeholder:text-muted-foreground"
            />
            <Button
              size="icon"
              className="size-8 shrink-0"
              onClick={() => send(input)}
              disabled={sending || !input.trim()}
              aria-label={t("channel_sendPreviewAria")}
            >
              <Send aria-hidden="true" className="size-3.5" />
            </Button>
          </div>
        )}
      </div>
    </div>
  );
}
