"use client";

import { useEffect, useState } from "react";
import { Infinity as InfinityIcon, Timer, Fingerprint, Gauge, PauseCircle, Route } from "lucide-react";
import { Badge } from "../ui/badge";
import { Tooltip, TooltipContent, TooltipTrigger } from "../ui/tooltip";
import type { StudioStatus } from "./studioClient";
import { useTranslations } from "../../lib/i18n";

// The gateways, made visible. Unlimited is only believable when the limits that
// DO exist are on the page: how fast, how long, how many at once - and the
// address the visitor is being held to.

export default function FairUseBar({ status }: { status: StudioStatus | null }) {
    const tHC = useTranslations("hcComponentsStudioFairUseBar");
  const [cool, setCool] = useState(0);
  const coolFromServer = status?.fairUse?.coolingSeconds ?? 0;

  useEffect(() => {
    setCool(coolFromServer);
    if (coolFromServer <= 0) return undefined;
    const id = window.setInterval(() => setCool((s) => Math.max(0, s - 1)), 1000);
    return () => window.clearInterval(id);
  }, [coolFromServer]);

  const f = status?.fairUse;
  return (
    <div className="flex flex-wrap items-center gap-1.5 text-xs">
      <Chip icon={InfinityIcon} tip="No message allowance. What limits you is speed, not a lifetime count.">
        {tHC("s4")}
      </Chip>
      {f && (
        <Chip icon={Gauge} tip="A burst limit per sandbox, per network and per day keeps the studio open for everyone.">
          up to {f.missionsPerMinute}/min · {f.maxConcurrentRuns} at once
        </Chip>
      )}
      {status?.missionTimeoutSeconds ? (
        <Chip icon={Timer} tip="Every agent turn is stopped at this wall-clock limit, tools included.">
          {status.missionTimeoutSeconds}{tHC("s3")}
        </Chip>
      ) : null}
      {status?.workflowTimeoutMinutes ? (
        <Chip icon={Route} tip="A whole workflow, including the time you take to approve, is stopped at this limit.">
          {status.workflowTimeoutMinutes} {tHC("s2")}
        </Chip>
      ) : null}
      {status?.clientIp ? (
        <Chip
          icon={Fingerprint}
          tip="Your network address is recorded with this sandbox and logged against every run. Please refrain from illegal or abusive activity."
        >
          <span className="font-mono">{status.clientIp}</span> recorded
        </Chip>
      ) : null}
      {cool > 0 && (
        <Badge variant="destructive" className="gap-1" role="status">
          <PauseCircle aria-hidden="true" /> {tHC("s1")} {cool >= 60 ? `${Math.ceil(cool / 60)} min` : `${cool}s`} left
        </Badge>
      )}
    </div>
  );
}

function Chip({ icon: Icon, tip, children }: { icon: typeof Timer; tip: string; children: React.ReactNode }) {
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <span className="inline-flex cursor-help items-center gap-1 rounded-full border border-border/60 bg-background/70 px-2.5 py-1 text-muted-foreground">
          <Icon className="h-3 w-3" /> {children}
        </span>
      </TooltipTrigger>
      <TooltipContent className="max-w-xs">{tip}</TooltipContent>
    </Tooltip>
  );
}
