"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { Search, Plug, Unplug, Loader2, Globe, Lock, CheckCircle2, ExternalLink } from "lucide-react";
import { Button } from "../ui/button";
import { Badge } from "../ui/badge";
import { Input } from "../ui/input";
import { Alert, AlertDescription, AlertTitle } from "../ui/alert";
import { Tooltip, TooltipContent, TooltipTrigger } from "../ui/tooltip";
import { studio, type RegistryHit, type StudioAgent, type StudioConnector } from "./studioClient";
import { connectorToolLabel } from "./studioContent";
import { useTranslations } from "../../lib/i18n";

// Search the public MCP registry and plug a hosted server into ONE agent.
// Searching is always live; connecting is a separate switch on the backend, and
// when it is off the Connect buttons say so instead of failing after a click.
// Only hosted servers that need no credential are listed - the sandbox never
// asks a stranger for an API key and never launches a local process.

type Props = {
  token: string;
  agent: StudioAgent;
  readOnly: boolean;
  connectorsEnabled: boolean;
  /** Starting search - usually derived from the agent's topic. */
  suggestions: string[];
  onChanged: () => void;
};

const QUICK = ["weather", "github", "maps", "search", "notion", "finance", "docs", "calendar"];

export default function AgentConnectors({ token, agent, readOnly, connectorsEnabled, suggestions, onChanged }: Props) {
    const tHC = useTranslations("hcComponentsStudioAgentConnectors");
  const [q, setQ] = useState("");
  const [hits, setHits] = useState<RegistryHit[] | null>(null);
  const [unavailable, setUnavailable] = useState(false);
  const [searching, setSearching] = useState(false);
  const [next, setNext] = useState<string | null>(null);
  const [connected, setConnected] = useState<StudioConnector[] | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const seq = useRef(0);

  const loadConnected = useCallback(async () => {
    const res = await studio.listConnectors(token, agent.id);
    setConnected(res.ok ? res.value : []);
  }, [token, agent.id]);

  useEffect(() => {
    setConnected(null);
    setError(null);
    void loadConnected();
  }, [loadConnected]);

  const seed = suggestions[0] ?? "";
  const search = useCallback(
    async (query: string, cursor?: string | null): Promise<void> => {
      const mine = ++seq.current;
      setSearching(true);
      const res = await studio.searchMcp(token, query, cursor);
      if (mine !== seq.current) return;
      setSearching(false);
      if (!res.ok) {
        setUnavailable(true);
        setHits([]);
        return;
      }
      setUnavailable(res.value.unavailable);
      setHits((cur) => (cursor ? [...(cur ?? []), ...res.value.results] : res.value.results));
      setNext(res.value.nextCursor);
    },
    [token]
  );

  // Debounced live search; the first load shows what the registry has for the agent's own topic.
  useEffect(() => {
    const id = window.setTimeout(
      () => {
        void search(q.trim() || seed);
      },
      q ? 350 : 0
    );
    return () => window.clearTimeout(id);
  }, [q, search, seed]);

  const connectedNames = new Set((connected ?? []).map((c) => c.title ?? ""));

  const connect = async (hit: RegistryHit) => {
    setBusy(hit.name);
    setError(null);
    const res = await studio.connect(token, agent.id, hit.name);
    setBusy(null);
    if (!res.ok) {
      setError(res.error.message);
      return;
    }
    setConnected((c) => [...(c ?? []).filter((x) => x.server !== res.value.server), res.value]);
    onChanged();
  };

  const disconnect = async (c: StudioConnector) => {
    setBusy(c.server);
    const before = connected;
    setConnected((list) => (list ?? []).filter((x) => x.server !== c.server));
    const res = await studio.disconnect(token, agent.id, c.server);
    setBusy(null);
    if (!res.ok) {
      setConnected(before);
      setError(res.error.message);
      return;
    }
    onChanged();
  };

  return (
    <div className="space-y-4">
      <div>
        <div className="text-sm font-medium">{tHC("hcComponentsStudioAgentConnectors.s12")}</div>
        <p className="text-xs text-muted-foreground">
          {tHC("hcComponentsStudioAgentConnectors.s11")}
        </p>
      </div>

      {!connectorsEnabled && (
        <Alert>
          <Lock aria-hidden="true" />
          <AlertTitle>{tHC("hcComponentsStudioAgentConnectors.s10")}</AlertTitle>
          <AlertDescription>
            {tHC("hcComponentsStudioAgentConnectors.s9")}
          </AlertDescription>
        </Alert>
      )}

      {(connected?.length ?? 0) > 0 && (
        <div className="space-y-2">
          <div className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
            {tHC("hcComponentsStudioAgentConnectors.s8")} {agent.name}
          </div>
          {connected!.map((c) => (
            <div
              key={c.server}
              className="flex items-start gap-3 rounded-xl border border-violet-500/40 bg-violet-500/10 p-3 text-sm"
            >
              <CheckCircle2 aria-hidden="true" className="mt-0.5 h-4 w-4 shrink-0 text-primary" />
              <div className="min-w-0 flex-1">
                <div className="truncate font-medium">{c.title ?? c.server}</div>
                <div className="mt-1 flex flex-wrap gap-1">
                  {c.tools.slice(0, 8).map((t) => (
                    <Badge key={t} variant="secondary" className="font-mono text-[10px]">
                      {connectorToolLabel(t)}
                    </Badge>
                  ))}
                  {c.tools.length > 8 && <Badge variant="outline">+{c.tools.length - 8}</Badge>}
                </div>
              </div>
              <Button
                size="sm"
                variant="ghost"
                disabled={readOnly || busy === c.server}
                onClick={() => void disconnect(c)}
              >
                {busy === c.server ? (
                  <Loader2 aria-hidden="true" className="animate-spin" />
                ) : (
                  <Unplug aria-hidden="true" />
                )}{" "}
                Remove
              </Button>
            </div>
          ))}
        </div>
      )}

      <div className="relative" data-tour="mcp-search">
        <Search
          aria-hidden="true"
          className="pointer-events-none absolute start-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground shrink-0"
        />
        <Input
          value={q}
          onChange={(e) => setQ(e.target.value)}
          placeholder={tHC("hcComponentsStudioAgentConnectors.s7")}
          className="ps-9"
          aria-label={tHC("hcComponentsStudioAgentConnectors.s6")}
        />
        {searching && (
          <Loader2
            aria-hidden="true"
            className="absolute end-3 top-1/2 h-4 w-4 -translate-y-1/2 animate-spin text-muted-foreground shrink-0"
          />
        )}
      </div>
      <div className="flex flex-wrap gap-1.5">
        {[...suggestions.slice(0, 2), ...QUICK]
          .filter((v, i, a) => a.indexOf(v) === i)
          .slice(0, 8)
          .map((s) => (
            <button
              key={s}
              type="button"
              onClick={() => setQ(s)}
              className="rounded-full border border-border/60 px-2.5 py-1 text-xs transition-colors hover:border-primary/60"
            >
              {s}
            </button>
          ))}
      </div>

      {error && <div className="rounded-lg border border-destructive/40 bg-destructive/5 p-2.5 text-sm">{error}</div>}

      {unavailable && (
        <div className="rounded-lg bg-muted/50 p-3 text-sm text-muted-foreground">
          {tHC("hcComponentsStudioAgentConnectors.s5")}
        </div>
      )}

      <ul className="space-y-2">
        {hits === null && [0, 1, 2].map((i) => <li key={i} className="h-16 animate-pulse rounded-xl bg-muted/50" />)}
        {(hits ?? []).map((h) => {
          const isOn = connectedNames.has(h.title);
          const button = (
            <Button
              size="sm"
              variant={isOn ? "secondary" : "outline"}
              disabled={readOnly || !connectorsEnabled || isOn || busy === h.name}
              onClick={() => void connect(h)}
            >
              {busy === h.name ? <Loader2 aria-hidden="true" className="animate-spin" /> : <Plug aria-hidden="true" />}{" "}
              {isOn ? "Connected" : busy === h.name ? "Connecting…" : "Connect"}
            </Button>
          );
          return (
            <li key={h.name} className="flex items-start gap-3 rounded-xl border border-border/60 p-3 text-sm">
              <Globe aria-hidden="true" className="mt-0.5 h-4 w-4 shrink-0 text-muted-foreground" />
              <div className="min-w-0 flex-1">
                <div className="truncate font-medium">{h.title}</div>
                <div className="line-clamp-2 text-xs text-muted-foreground">{h.description ?? h.name}</div>
                <div className="mt-1 flex items-center gap-1 text-[11px] text-muted-foreground">
                  <ExternalLink aria-hidden="true" className="h-3 w-3 shrink-0" /> {h.host ?? "hosted"} {tHC("hcComponentsStudioAgentConnectors.s4")}
                </div>
              </div>
              {connectorsEnabled ? (
                button
              ) : (
                <Tooltip>
                  <TooltipTrigger asChild>
                    <span>{button}</span>
                  </TooltipTrigger>
                  <TooltipContent>{tHC("hcComponentsStudioAgentConnectors.s3")}</TooltipContent>
                </Tooltip>
              )}
            </li>
          );
        })}
        {hits !== null && hits.length === 0 && !unavailable && !searching && (
          <li className="rounded-xl border border-dashed border-border/60 p-4 text-center text-xs text-muted-foreground">
            {tHC("hcComponentsStudioAgentConnectors.s2")}
          </li>
        )}
      </ul>
      {next && (
        <Button size="sm" variant="ghost" disabled={searching} onClick={() => void search(q.trim() || seed, next)}>
          {tHC("hcComponentsStudioAgentConnectors.s1")}
        </Button>
      )}
    </div>
  );
}
