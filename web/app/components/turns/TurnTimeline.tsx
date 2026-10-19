"use client";

import { Badge } from "../ui/badge";
import { useTranslations } from "../../lib/i18n";
import type { TurnJournalEvent } from "../../lib/api/turns";
import { epochMillis, gapFromPrevious, toolMillis } from "./journalChain";

const TYPE_VARIANT: Record<string, "default" | "secondary" | "outline" | "accent" | "destructive"> = {
  INTAKE: "secondary",
  GUARD: "outline",
  MODEL_CALL: "accent",
  TOOL_CALL: "accent",
  APPROVAL: "outline",
  VERIFY: "outline",
  COMMIT: "default",
};

function shortHash(h: string): string {
  return h ? `${h.slice(0, 10)}...${h.slice(-6)}` : "";
}

function stamp(createdAt: string | number): string {
  const ms = epochMillis(createdAt);
  return Number.isFinite(ms) ? new Date(ms).toISOString().replace("T", " ").replace("Z", " UTC") : String(createdAt);
}

export default function TurnTimeline({ events, brokenAtSeq }: { events: TurnJournalEvent[]; brokenAtSeq: number | null }) {
  const t = useTranslations("turnInspector");
  return (
    <ol className="space-y-3" aria-label={t("timelineTitle")}>
      {events.map((e, i) => {
        const gap = gapFromPrevious(events, i);
        const ms = e.type === "TOOL_CALL" ? toolMillis(e) : null;
        const broken = brokenAtSeq !== null && brokenAtSeq >= 0 && e.seq >= brokenAtSeq;
        return (
          <li
            key={`${e.seq}-${e.hash}`}
            aria-label={t("eventLabel", { seq: e.seq, type: String(e.type) })}
            className={`rounded-lg border bg-card p-3 ${broken ? "border-destructive" : "border-border"}`}
          >
            <div className="flex flex-wrap items-center gap-2">
              <span className="text-xs tabular-nums text-muted-foreground">#{e.seq}</span>
              <Badge variant={TYPE_VARIANT[String(e.type)] ?? "outline"}>{String(e.type)}</Badge>
              <span className="text-xs text-muted-foreground">{stamp(e.createdAt)}</span>
              {gap !== null && <span className="text-xs text-muted-foreground">{t("gap", { ms: gap })}</span>}
              {ms !== null && <span className="text-xs text-muted-foreground">{t("toolMs", { ms })}</span>}
            </div>
            <p className="mt-2 break-words font-mono text-sm text-foreground">{e.summary || "-"}</p>
            <dl className="mt-2 grid gap-x-4 gap-y-1 text-xs text-muted-foreground sm:grid-cols-3">
              <div>
                <dt className="inline">{t("payloadHash")}: </dt>
                <dd className="inline font-mono" title={e.payloadHash}>
                  {shortHash(e.payloadHash)}
                </dd>
              </div>
              <div>
                <dt className="inline">{t("prevHash")}: </dt>
                <dd className="inline font-mono" title={e.prevHash}>
                  {shortHash(e.prevHash)}
                </dd>
              </div>
              <div>
                <dt className="inline">{t("eventHash")}: </dt>
                <dd className="inline font-mono" title={e.hash}>
                  {shortHash(e.hash)}
                </dd>
              </div>
            </dl>
          </li>
        );
      })}
    </ol>
  );
}
