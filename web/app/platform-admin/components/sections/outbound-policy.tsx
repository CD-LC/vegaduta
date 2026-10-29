"use client";

import { useCallback, useEffect, useState } from "react";
import { Globe2, Network, RefreshCw } from "lucide-react";
import { apiFetch, toJson } from "../../lib/api";
import { Badge } from "../ui/badge";
import { Button } from "../ui/button";
import { Alert, AlertDescription } from "../ui/alert";
import { Skeleton } from "../ui/skeleton";
import { SectionCard } from "./common";

// Read-only view of the operator-set outbound limits, backed by
// OutboundPolicyController (GET /api/platform/security/outbound-policy). These
// are environment settings (docs/configuration.md), deliberately not editable
// here: a stolen admin session must not be able to widen egress.

export interface OutboundPolicy {
  egress: { restricted: boolean; allowList: string[]; privateHostExceptions: string[] };
  a2aClient: { enabled: boolean; allowedHosts: string[]; requireSignedCard: boolean; maxCallsPerTurn: number };
  a2aServer: { enabled: boolean; bindings: string[]; jsonRpcPath: string; restPath: string };
}

function HostList({ hosts, empty }: { hosts: string[]; empty: string }) {
  if (!hosts.length) return <p className="text-xs text-muted-foreground">{empty}</p>;
  return (
    <ul className="flex flex-wrap gap-1.5" aria-label="Hosts">
      {hosts.map((h) => (
        <li key={h}>
          <Badge variant="outline" className="font-mono text-xs">
            {h}
          </Badge>
        </li>
      ))}
    </ul>
  );
}

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex flex-wrap items-start justify-between gap-2 border-b border-border/50 py-2 last:border-0">
      <span className="text-sm text-muted-foreground">{label}</span>
      <div className="min-w-0 text-end text-sm">{children}</div>
    </div>
  );
}

export function OutboundPolicyPanel({ delay }: { delay?: number }) {
  const [data, setData] = useState<OutboundPolicy | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setData(await toJson(await apiFetch("/api/platform/security/outbound-policy"), true));
      setError(null);
    } catch (err) {
      setError((err as Error)?.message || "Could not load the outbound policy.");
    } finally {
      setLoading(false);
    }
  }, []);
  useEffect(() => {
    void load();
  }, [load]);

  const refresh = (
    <Button size="sm" variant="outline" onClick={() => void load()} aria-label="Refresh outbound policy">
      <RefreshCw size={14} />
    </Button>
  );

  if (loading && !data) {
    return (
      <SectionCard title="Outbound and agent-to-agent policy" icon={Globe2} delay={delay}>
        <Skeleton className="h-16 w-full" />
      </SectionCard>
    );
  }
  if (error || !data) {
    return (
      <SectionCard title="Outbound and agent-to-agent policy" icon={Globe2} delay={delay} actions={refresh}>
        <Alert variant="destructive">
          <AlertDescription>{error || "No data."}</AlertDescription>
        </Alert>
      </SectionCard>
    );
  }

  const { egress, a2aClient, a2aServer } = data;
  return (
    <>
      <SectionCard
        title="Outbound allow-list"
        subtitle="Which hosts tenant-configured URLs (BYOK models, OpenAPI tools, web fetch, MCP servers, webhooks) may reach. Set APP_SECURITY_OUTBOUND_EGRESS_ALLOW_LIST."
        icon={Globe2}
        delay={delay}
        actions={refresh}
      >
        <Row label="Mode">
          {egress.restricted ? (
            <Badge>Restricted to the list below</Badge>
          ) : (
            <Badge variant="secondary">Any public host (no list set)</Badge>
          )}
        </Row>
        <Row label="Allowed hosts">
          <HostList
            hosts={egress.allowList}
            empty="None set. Private and metadata addresses stay blocked either way."
          />
        </Row>
        <Row label="Private-host exceptions">
          <HostList hosts={egress.privateHostExceptions} empty="None." />
        </Row>
        <p className="pt-1 text-xs text-muted-foreground">
          Platform-wide: the list applies to every agent. A per-agent list is not available yet.
        </p>
      </SectionCard>

      <SectionCard
        title="Agent-to-agent (A2A)"
        subtitle="Inbound endpoints other agents call, and the outbound client your agents use to call them."
        icon={Network}
        delay={delay}
      >
        <Row label="Inbound server">
          <Badge variant={a2aServer.enabled ? "default" : "secondary"}>{a2aServer.enabled ? "On" : "Off"}</Badge>
        </Row>
        {a2aServer.enabled && (
          <Row label="Bindings">
            <div className="space-y-1 font-mono text-xs">
              <div>JSON-RPC {a2aServer.jsonRpcPath}</div>
              <div>HTTP+JSON {a2aServer.restPath}</div>
            </div>
          </Row>
        )}
        <Row label="Outbound client">
          <Badge variant={a2aClient.enabled ? "default" : "secondary"}>{a2aClient.enabled ? "On" : "Off"}</Badge>
        </Row>
        <Row label="Allowed remote hosts">
          <HostList hosts={a2aClient.allowedHosts} empty="None: no remote agent is reachable." />
        </Row>
        <Row label="Signed cards required">{a2aClient.requireSignedCard ? "Yes" : "No"}</Row>
        <Row label="Remote calls per turn">{a2aClient.maxCallsPerTurn > 0 ? a2aClient.maxCallsPerTurn : "No cap"}</Row>
      </SectionCard>
    </>
  );
}
