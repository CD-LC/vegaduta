"use client";

import { useEffect, useState } from "react";
import nextDynamic from "next/dynamic";
import Link from "next/link";
import { Radio, ArrowRight, Loader2, CheckCircle2 } from "lucide-react";
import { Button } from "../ui/button";
import { Badge } from "../ui/badge";
import { fetchChannels, type DemoSession } from "../try/demoClient";
import { studio, type StudioAgent } from "./studioClient";

const ChannelBridge = nextDynamic(() => import("../try/ChannelBridge"));
const PocketPanel = nextDynamic(() => import("./PocketPanel"));
// Renders nothing unless the deployment has a real platform WhatsApp number.
const WhatsAppBridge = nextDynamic(() => import("./WhatsAppBridge"));

// Stage 4: put an agent where people are. Everything here is the existing,
// real demo plumbing - a public /a/{shareId} chat link and the Telegram
// pairing bridge - pointed at whichever agent the visitor marks live. The
// signup link carries the demo token, so on registration every agent this
// session built is copied into the new workspace (DemoConversionAgentCopier).

type Props = {
  token: string;
  session: DemoSession;
  agents: StudioAgent[];
  readOnly: boolean;
  signupHref: string;
  onAgentsChanged: (agents: StudioAgent[]) => void;
  onTurnSpent: (used: number, limit: number) => void;
  /** The real share link, once minted - lifted so the sticky phone can show its QR. */
  onShareUrl?: (url: string) => void;
};

export default function DeployPanel({
  token,
  session,
  agents,
  readOnly,
  signupHref,
  onAgentsChanged,
  onTurnSpent,
  onShareUrl,
}: Props) {
  const [switching, setSwitching] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  // The messenger bridge is only shown when the deployment has a REAL
  // Telegram bot to pair with. Without one, ChannelBridge falls back to a
  // WhatsApp "preview" thread - a chat box with pretend delivery, which is
  // exactly the kind of simulation this page no longer contains.
  const [telegramAvailable, setTelegramAvailable] = useState(false);
  useEffect(() => {
    let cancelled = false;
    void fetchChannels(token).then((status) => {
      if (!cancelled) setTelegramAvailable(!!status?.telegram?.available);
    });
    return () => {
      cancelled = true;
    };
  }, [token]);
  const live = agents.find((a) => a.live) ?? null;

  const makeLive = async (id: string) => {
    setSwitching(id);
    setError(null);
    const res = await studio.makeLive(token, id);
    setSwitching(null);
    if (!res.ok) {
      setError(res.error.message);
      return;
    }
    onAgentsChanged(agents.map((a) => ({ ...a, live: a.id === id })));
  };

  const mine = agents.filter((a) => a.mine);

  return (
    <div className="space-y-6">
      <section>
        <div className="mb-1 flex items-center gap-2 text-sm font-medium">
          <Radio aria-hidden="true" className="h-4 w-4 shrink-0" /> Which agent answers the outside world
        </div>
        <p className="mb-3 text-xs text-muted-foreground">
          The QR code, the link and the Telegram bridge talk to one agent at a time. Pick it.
        </p>
        <div className="grid gap-2 sm:grid-cols-2">
          {agents.map((a) => (
            <div
              key={a.id}
              className={`flex items-center gap-3 rounded-xl border p-3 text-sm ${a.live ? "border-sky-500 bg-sky-500/10 ring-1 ring-sky-500/30" : "border-border/60"}`}
            >
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-2 font-medium">
                  <span className="truncate">{a.name}</span>
                  {a.live && (
                    <Badge className="text-[10px]">
                      <CheckCircle2 aria-hidden="true" className="me-1 h-3 w-3 shrink-0" /> live
                    </Badge>
                  )}
                </div>
                <div className="text-xs text-muted-foreground">
                  {a.toolsOff ? "no tools" : `${a.tools.length} tool${a.tools.length === 1 ? "" : "s"}`} ·{" "}
                  {a.reasoningDepth.toLowerCase()}
                </div>
              </div>
              {!a.live && (
                <Button
                  size="sm"
                  variant="outline"
                  disabled={readOnly || switching != null}
                  onClick={() => makeLive(a.id)}
                >
                  {switching === a.id ? (
                    <Loader2 aria-hidden="true" className="h-3.5 w-3.5 animate-spin shrink-0" />
                  ) : (
                    "Make live"
                  )}
                </Button>
              )}
            </div>
          ))}
        </div>
        {error && <p className="mt-2 text-sm text-destructive">{error}</p>}
      </section>

      <PocketPanel
        token={token}
        agentName={live?.name ?? null}
        readOnly={readOnly || !live}
        signupHref={signupHref}
        onShareUrl={(u) => onShareUrl?.(u)}
      />

      {telegramAvailable && (
        <section>
          <ChannelBridge token={token} session={session} signupHref={signupHref} onTurnSpent={onTurnSpent} />
        </section>
      )}

      <WhatsAppBridge token={token} session={session} />

      <section className="rounded-2xl border border-primary/30 bg-primary/5 p-5">
        <div className="mb-1 text-base font-semibold">Keep all of this</div>
        <p className="mb-3 text-sm text-muted-foreground">
          Create a free account and {mine.length === 1 ? "the agent" : `all ${mine.length} agents`} you designed here{" "}
          {mine.length === 1 ? "is" : "are"} copied into your own workspace on the spot - same missions, same tools,
          same rules. Then connect WhatsApp, schedules, email and your documents.
        </p>
        <Button asChild>
          <Link href={signupHref}>
            Create my workspace <ArrowRight aria-hidden="true" className="ms-2 h-4 w-4 shrink-0" />
          </Link>
        </Button>
      </section>
    </div>
  );
}
