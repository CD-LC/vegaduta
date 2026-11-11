"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { History, Search } from "lucide-react";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { Label } from "../ui/label";
import { Badge } from "../ui/badge";
import { Alert, AlertDescription, AlertTitle } from "../ui/alert";
import { Skeleton } from "../ui/skeleton";
import { EmptyState } from "../ax/page-kit";
import { useLocale, useTranslations } from "../../lib/i18n";
import { listSessionTurns, type TurnSummary } from "../../lib/api/turns";
import { errorMessage } from "../../lib/errors";
import { formatStamp } from "./timelineModel";
import CopyTurnId from "./CopyTurnId";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

type Load =
  | { kind: "idle" }
  | { kind: "loading" }
  | { kind: "ready"; turns: TurnSummary[] }
  | { kind: "notFound" }
  | { kind: "forbidden" }
  | { kind: "error"; message: string };

function statusVariant(status: string): "default" | "secondary" | "outline" | "destructive" {
  if (status === "completed") return "default";
  if (status === "open") return "outline";
  if (status.startsWith("error") || status === "failed") return "destructive";
  return "secondary";
}

/**
 * Finds the journaled turns of one chat session (newest first) so a turn can be opened without knowing its id.
 * The chat message's "view turn" link lands here with the agent and session already filled in.
 */
export default function SessionTurns({
  initialAgentId = "",
  initialSessionId = "",
  selectedTurnId = "",
  onSelect,
}: {
  initialAgentId?: string;
  initialSessionId?: string;
  selectedTurnId?: string;
  onSelect: (agentId: string, turnId: string) => void;
}) {
  const t = useTranslations("turnInspector");
  const locale = useLocale();
  const [agentId, setAgentId] = useState(initialAgentId);
  const [sessionId, setSessionId] = useState(initialSessionId);
  const [load, setLoad] = useState<Load>({ kind: "idle" });
  const [invalid, setInvalid] = useState(false);
  const loaded = useRef<{ agentId: string; sessionId: string } | null>(null);
  const requestSeq = useRef(0);

  const find = useCallback(
    async (a: string, s: string, autoOpen: boolean) => {
      const aid = a.trim();
      const sid = s.trim();
      if (!UUID.test(aid) || !sid) {
        setInvalid(true);
        return;
      }
      setInvalid(false);
      setLoad({ kind: "loading" });
      const mine = ++requestSeq.current;
      try {
        const view = await listSessionTurns(aid, sid);
        if (mine !== requestSeq.current) return;
        loaded.current = { agentId: aid, sessionId: sid };
        const turns = view.turns ?? [];
        setLoad({ kind: "ready", turns });
        if (autoOpen && turns.length > 0) onSelect(aid, turns[0].turnId);
      } catch (e) {
        if (mine !== requestSeq.current) return;
        const status = (e as { status?: number }).status;
        if (status === 404 || status === 405) setLoad({ kind: "notFound" });
        else if (status === 403) setLoad({ kind: "forbidden" });
        else setLoad({ kind: "error", message: errorMessage(e) });
      }
    },
    [onSelect]
  );

  useEffect(() => {
    if (UUID.test(initialAgentId) && initialSessionId) void find(initialAgentId, initialSessionId, !selectedTurnId);
    // Deep link only: run once on mount.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <section aria-label={t("sessTitle")} className="space-y-4 rounded-lg border border-border bg-card p-4">
      <div>
        <h2 className="text-base font-semibold text-foreground">{t("sessTitle")}</h2>
        <p className="text-sm text-muted-foreground">{t("sessSubtitle")}</p>
      </div>
      <form
        className="grid gap-3 sm:grid-cols-[1fr_1fr_auto] sm:items-end"
        onSubmit={(ev) => {
          ev.preventDefault();
          void find(agentId, sessionId, true);
        }}
      >
        <div className="space-y-1.5">
          <Label htmlFor="sess-agent-id">{t("agentIdLabel")}</Label>
          <Input
            id="sess-agent-id"
            value={agentId}
            onChange={(e) => setAgentId(e.target.value)}
            placeholder={t("agentIdPlaceholder")}
            spellCheck={false}
            autoComplete="off"
          />
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="sess-session-id">{t("sessionIdLabel")}</Label>
          <Input
            id="sess-session-id"
            value={sessionId}
            onChange={(e) => setSessionId(e.target.value)}
            placeholder={t("sessionIdPlaceholder")}
            spellCheck={false}
            autoComplete="off"
          />
        </div>
        <Button type="submit" variant="outline" disabled={load.kind === "loading"}>
          <Search size={14} aria-hidden="true" /> {t("sessFind")}
        </Button>
      </form>

      {invalid && (
        <Alert variant="destructive" role="alert">
          <AlertDescription>{t("sessInvalid")}</AlertDescription>
        </Alert>
      )}

      {load.kind === "loading" && (
        <div className="space-y-2" aria-busy="true" aria-label={t("sessLoading")}>
          <Skeleton className="h-12 w-full" />
          <Skeleton className="h-12 w-full" />
        </div>
      )}

      {load.kind === "notFound" && (
        <EmptyState icon={History} title={t("sessUnavailableTitle")} description={t("sessUnavailableBody")} />
      )}

      {load.kind === "forbidden" && (
        <Alert variant="destructive" role="alert">
          <AlertTitle>{t("forbiddenTitle")}</AlertTitle>
          <AlertDescription>{t("forbiddenBody")}</AlertDescription>
        </Alert>
      )}

      {load.kind === "error" && (
        <Alert variant="destructive" role="alert">
          <AlertTitle>{t("sessErrorTitle")}</AlertTitle>
          <AlertDescription className="flex flex-wrap items-center justify-between gap-2">
            <span>{load.message}</span>
            <Button type="button" size="sm" variant="outline" onClick={() => void find(agentId, sessionId, false)}>
              {t("retry")}
            </Button>
          </AlertDescription>
        </Alert>
      )}

      {load.kind === "ready" && load.turns.length === 0 && (
        <div className="space-y-3">
          <EmptyState icon={History} title={t("sessEmptyTitle")} description={t("sessEmptyBody")} />
          <Alert>
            <AlertTitle>{t("enableTitle")}</AlertTitle>
            <AlertDescription>{t("enableBody")}</AlertDescription>
          </Alert>
        </div>
      )}

      {load.kind === "ready" && load.turns.length > 0 && (
        <ul className="space-y-2" aria-label={t("sessListLabel", { count: load.turns.length })}>
          {load.turns.map((turn) => {
            const selected = turn.turnId === selectedTurnId;
            return (
              <li
                key={turn.turnId}
                data-testid="session-turn"
                className={`flex flex-wrap items-center gap-3 rounded-md border p-3 ${selected ? "border-primary" : "border-border"}`}
              >
                <Badge variant={statusVariant(turn.status)}>
                  {turn.status === "open" ? t("sessStatusOpen") : turn.status}
                </Badge>
                <span className="text-xs text-muted-foreground">{formatStamp(turn.startedAt, locale)}</span>
                <span className="text-xs text-muted-foreground">{t("sessEvents", { count: turn.events })}</span>
                <CopyTurnId turnId={turn.turnId} />
                <Button
                  type="button"
                  size="sm"
                  className="ms-auto"
                  variant={selected ? "default" : "outline"}
                  aria-pressed={selected}
                  onClick={() => onSelect(loaded.current?.agentId ?? agentId.trim(), turn.turnId)}
                >
                  {selected ? t("sessOpened") : t("sessOpen")}
                </Button>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}
