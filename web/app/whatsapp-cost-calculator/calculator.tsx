"use client";

import { useMemo, useState } from "react";
import Link from "next/link";
import { ArrowRight, Bot, Megaphone } from "lucide-react";
import { Button } from "../components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "../components/ui/card";
import { Input } from "../components/ui/input";
import { Label } from "../components/ui/label";
import { META_RATE_CARD_URL, PRICING_CHECKED_ON, parseRate } from "./pricing-facts";

// ---------------------------------------------------------------------------
// WhatsApp Business API cost calculator — the interactive half of
// /whatsapp-cost-calculator (SEO/growth free tool, roadmap §3 #1 "double
// down (a)"). The server page around this carries all the prose, sources and
// disclaimers; this component ONLY does arithmetic on the user's own inputs.
//
// Honesty posture (deliberate, don't "improve" it away):
//   - There are NO default rates. Meta's pricing page text publishes none (see
//     pricing-facts.ts), and the earlier third-party-reported India figures were
//     removed. The tool says "check Meta's current rates" until the user types
//     the rates from Meta's rate card, then does arithmetic on those.
//   - The agent-side "messages per conversation" default is LOWER than the
//     broadcast/human default, but it is just a default — the user controls
//     both sides, and the copy everywhere says the tool computes only what
//     those inputs imply. No canned "save 60%!" claims anywhere.
//   - The formula is printed verbatim under the results.
// ---------------------------------------------------------------------------

const inr = new Intl.NumberFormat("en-IN", {
  style: "currency",
  currency: "INR",
  maximumFractionDigits: 0,
});

const inrPrecise = new Intl.NumberFormat("en-IN", {
  style: "currency",
  currency: "INR",
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

function clamp(value: number, min: number, max: number): number {
  if (Number.isNaN(value)) return min;
  return Math.min(max, Math.max(min, value));
}

// One slider+number pair. The number input mirrors the slider so keyboard
// users and slider users both get a first-class control.
function SliderField({
  id,
  label,
  hint,
  value,
  onChange,
  min,
  max,
  step,
  unit,
}: {
  id: string;
  label: string;
  hint?: string;
  value: number;
  onChange: (v: number) => void;
  min: number;
  max: number;
  step: number;
  unit?: string;
}) {
  return (
    <div className="space-y-2">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <Label htmlFor={id}>{label}</Label>
        <span className="text-xs text-muted-foreground tabular-nums">
          {value.toLocaleString("en-IN")}
          {unit ? ` ${unit}` : ""}
        </span>
      </div>
      <div className="flex items-center gap-3">
        <input
          type="range"
          aria-label={`${label} (slider)`}
          className="h-1.5 w-full cursor-pointer appearance-none rounded-full bg-secondary accent-primary"
          min={min}
          max={max}
          step={step}
          value={value}
          onChange={(e) => onChange(clamp(Number(e.target.value), min, max))}
        />
        <Input
          id={id}
          type="number"
          inputMode="numeric"
          className="h-9 w-24 shrink-0 text-right tabular-nums"
          min={min}
          max={max}
          step={step}
          value={value}
          onChange={(e) => onChange(clamp(Number(e.target.value), min, max))}
        />
      </div>
      {hint ? <p className="text-xs leading-relaxed text-muted-foreground">{hint}</p> : null}
    </div>
  );
}

function ResultRow({ label, value, precise }: { label: string; value: number; precise?: boolean }) {
  return (
    <div className="flex items-baseline justify-between gap-3 border-b border-border/60 py-2 last:border-b-0">
      <span className="text-sm text-muted-foreground">{label}</span>
      <span className="text-sm font-medium text-foreground tabular-nums">
        {precise ? inrPrecise.format(value) : inr.format(value)}
      </span>
    </div>
  );
}

export default function WhatsAppCostCalculator() {
  // Volume + behaviour inputs. Every value here is a starting point, not a
  // claim — the user owns both sides of the comparison.
  const [conversations, setConversations] = useState(1000);
  const [msgsBroadcast, setMsgsBroadcast] = useState(10);
  const [msgsAgent, setMsgsAgent] = useState(4);
  const [marketingPct, setMarketingPct] = useState(30);

  // Empty until the user copies the rates from Meta's rate card.
  const [marketingRateText, setMarketingRateText] = useState("");
  const [utilityRateText, setUtilityRateText] = useState("");
  const marketingRate = parseRate(marketingRateText);
  const utilityRate = parseRate(utilityRateText);

  const r = useMemo(() => {
    if (marketingRate === null || utilityRate === null) return null;
    const mktShare = marketingPct / 100;
    const blendedRate = mktShare * marketingRate + (1 - mktShare) * utilityRate;
    const costBroadcast = conversations * msgsBroadcast * blendedRate;
    const costAgent = conversations * msgsAgent * blendedRate;
    return {
      blendedRate,
      costBroadcast,
      costAgent,
      delta: costBroadcast - costAgent,
      perConvBroadcast: conversations > 0 ? costBroadcast / conversations : 0,
      perConvAgent: conversations > 0 ? costAgent / conversations : 0,
    };
  }, [conversations, msgsBroadcast, msgsAgent, marketingPct, marketingRate, utilityRate]);

  return (
    <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
      {/* ------------------------------------------------------------ Inputs */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">Your numbers</CardTitle>
          <CardDescription>
            Every field is yours to set — the results below are computed from these inputs and
            nothing else.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-6">
          <SliderField
            id="wa-conversations"
            label="Customer conversations per month"
            value={conversations}
            onChange={setConversations}
            min={0}
            max={50000}
            step={100}
          />
          <SliderField
            id="wa-msgs-broadcast"
            label="Messages per conversation — broadcast / human workflow"
            hint="Business-sent messages it typically takes to resolve one customer today: the blast, the follow-ups, the back-and-forth."
            value={msgsBroadcast}
            onChange={setMsgsBroadcast}
            min={1}
            max={40}
            step={1}
            unit="msgs"
          />
          <SliderField
            id="wa-msgs-agent"
            label="Messages per conversation — AI agent"
            hint="What you expect an agent that answers precisely to need. The default is only a starting point — set it to whatever you believe."
            value={msgsAgent}
            onChange={setMsgsAgent}
            min={1}
            max={40}
            step={1}
            unit="msgs"
          />
          <SliderField
            id="wa-marketing-pct"
            label="Marketing share of messages"
            hint="Marketing templates bill at the high rate; the remainder is priced at the utility/authentication rate."
            value={marketingPct}
            onChange={setMarketingPct}
            min={0}
            max={100}
            step={5}
            unit="%"
          />

          <div className="space-y-3 rounded-lg border border-border bg-muted/30 p-4">
            <p className="text-xs font-semibold uppercase tracking-[0.14em] text-muted-foreground">
              Per-message rates (₹) — copy from Meta&apos;s rate card
            </p>
            <div className="grid grid-cols-2 gap-3">
              <div className="space-y-1.5">
                <Label htmlFor="wa-rate-marketing" className="text-xs">
                  Marketing
                </Label>
                <Input
                  id="wa-rate-marketing"
                  type="number"
                  inputMode="decimal"
                  step={0.0001}
                  min={0}
                  placeholder="from rate card"
                  className="h-9 text-right tabular-nums"
                  value={marketingRateText}
                  onChange={(e) => setMarketingRateText(e.target.value)}
                />
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="wa-rate-utility" className="text-xs">
                  Utility / authentication
                </Label>
                <Input
                  id="wa-rate-utility"
                  type="number"
                  inputMode="decimal"
                  step={0.001}
                  min={0}
                  placeholder="from rate card"
                  className="h-9 text-right tabular-nums"
                  value={utilityRateText}
                  onChange={(e) => setUtilityRateText(e.target.value)}
                />
              </div>
            </div>
            <p className="text-xs leading-relaxed text-muted-foreground" data-testid="wa-rates-note">
              Pricing model checked against Meta&apos;s documentation on {PRICING_CHECKED_ON}: per-message,
              by category (marketing, utility, authentication). Meta&apos;s documentation text publishes no
              rates, so none are pre-filled. Read yours from{" "}
              <a
                href={META_RATE_CARD_URL}
                target="_blank"
                rel="noopener noreferrer"
                className="font-medium text-primary underline-offset-4 hover:underline"
              >
                Meta&apos;s rate card
              </a>{" "}
              (pick your market and currency) and type them here.
            </p>
          </div>
        </CardContent>
      </Card>

      {/* ----------------------------------------------------------- Results */}
      <div className="space-y-6">
        {r === null ? (
          <Card>
            <CardContent className="space-y-2" data-testid="wa-no-rates">
              <p className="text-sm font-medium text-foreground">Check Meta&apos;s current rates</p>
              <p className="text-xs leading-relaxed text-muted-foreground">
                Enter the marketing and utility rates from Meta&apos;s rate card on the left and the
                estimate appears here. Rates last checked: not published in Meta&apos;s documentation
                text as of {PRICING_CHECKED_ON}.
              </p>
            </CardContent>
          </Card>
        ) : (
          <>
        <div className="grid gap-4 sm:grid-cols-2">
          <Card className="gap-4">
            <CardHeader className="gap-2">
              <div className="flex items-center gap-2 text-muted-foreground">
                <Megaphone className="size-4" aria-hidden="true" />
                <CardTitle className="text-sm font-medium text-muted-foreground">
                  Broadcast / human workflow
                </CardTitle>
              </div>
              <p className="text-2xl font-semibold tracking-tight text-foreground tabular-nums sm:text-3xl">
                {inr.format(r.costBroadcast)}
                <span className="ml-1 text-sm font-normal text-muted-foreground">/ month</span>
              </p>
            </CardHeader>
            <CardContent>
              <ResultRow label="Cost per resolved conversation" value={r.perConvBroadcast} precise />
            </CardContent>
          </Card>

          <Card className="gap-4 border-primary/40">
            <CardHeader className="gap-2">
              <div className="flex items-center gap-2 text-primary">
                <Bot className="size-4" aria-hidden="true" />
                <CardTitle className="text-sm font-medium text-primary">
                  AI agent that answers precisely
                </CardTitle>
              </div>
              <p className="text-2xl font-semibold tracking-tight text-foreground tabular-nums sm:text-3xl">
                {inr.format(r.costAgent)}
                <span className="ml-1 text-sm font-normal text-muted-foreground">/ month</span>
              </p>
            </CardHeader>
            <CardContent>
              <ResultRow label="Cost per resolved conversation" value={r.perConvAgent} precise />
            </CardContent>
          </Card>
        </div>

        <Card>
          <CardContent className="space-y-3">
            <div className="flex flex-wrap items-baseline justify-between gap-2">
              <span className="text-sm font-medium text-foreground">
                Monthly difference implied by your inputs
              </span>
              <span
                className={`text-xl font-semibold tabular-nums ${
                  r.delta >= 0 ? "text-primary" : "text-destructive"
                }`}
                data-testid="wa-calc-delta"
              >
                {r.delta >= 0 ? "−" : "+"}
                {inr.format(Math.abs(r.delta))}
              </span>
            </div>
            <p className="text-xs leading-relaxed text-muted-foreground">
              {r.delta >= 0
                ? "That is what the messaging bill shrinks by if — and only if — an agent really resolves conversations in the fewer messages you entered above."
                : "With these inputs the agent side sends more messages, so it costs more. The calculator only reflects what you entered."}
            </p>

            {/* The formula, verbatim — no hidden math. */}
            <div className="rounded-md border border-border bg-muted/30 p-3">
              <p className="text-xs font-semibold uppercase tracking-[0.14em] text-muted-foreground">
                The whole formula
              </p>
              <p className="mt-2 font-mono text-xs leading-relaxed text-muted-foreground">
                blended rate = {marketingPct}% × ₹{marketingRate} + {100 - marketingPct}% × ₹
                {utilityRate} = ₹{r.blendedRate.toFixed(4)} per message
                <br />
                side A = {conversations.toLocaleString("en-IN")} conversations ×{" "}
                {msgsBroadcast} msgs × blended rate = {inrPrecise.format(r.costBroadcast)}
                <br />
                side B = {conversations.toLocaleString("en-IN")} conversations × {msgsAgent} msgs ×
                blended rate = {inrPrecise.format(r.costAgent)}
                <br />
                per resolved conversation = side ÷ conversations
              </p>
            </div>
          </CardContent>
        </Card>

          </>
        )}

        <Card className="border-primary/40 bg-primary/5">
          <CardContent className="flex flex-wrap items-center justify-between gap-4">
            <div className="space-y-1">
              <p className="text-sm font-medium text-foreground">
                See an agent answer for your business
              </p>
              <p className="text-xs text-muted-foreground">
                Free, no signup — a live sandbox, not a video.
              </p>
            </div>
            <Button asChild>
              <Link href="/try">
                Try it now <ArrowRight className="size-4" aria-hidden="true" />
              </Link>
            </Button>
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
