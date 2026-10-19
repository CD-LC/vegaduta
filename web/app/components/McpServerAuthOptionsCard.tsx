"use client";

import { useEffect, useState } from "react";
import { ShieldCheck } from "lucide-react";
import { api } from "../lib/api";
import type { McpServerAuthOptions } from "../lib/api/mcpToolPins";
import { Badge } from "./ui/badge";

/**
 * How callers can authenticate to this deployment's /mcp endpoint, read-only: grant keys (the simple option,
 * always on), the optional OAuth 2.1 mode and the Origin check. These are operator settings (environment
 * variables), so the card explains the current state and names the setting rather than offering a toggle.
 * Renders nothing if the endpoint is unavailable (the mcpserver profile is off).
 */
export default function McpServerAuthOptionsCard() {
  const [opts, setOpts] = useState<McpServerAuthOptions | null>(null);

  useEffect(() => {
    let alive = true;
    api
      .getMcpServerAuthOptions()
      .then((o) => alive && setOpts(o))
      .catch(() => alive && setOpts(null));
    return () => {
      alive = false;
    };
  }, []);

  if (!opts) return null;
  const { oauth, origin } = opts;

  return (
    <section className="panel mb-4" style={{ padding: "1rem 1.25rem" }} data-testid="mcp-auth-options">
      <div className="mb-2 flex items-center gap-2">
        <ShieldCheck className="h-4 w-4" />
        <h2 className="text-sm font-semibold">How callers authenticate to /mcp</h2>
      </div>
      <dl className="grid gap-3 text-sm md:grid-cols-3">
        <div>
          <dt className="font-medium">
            Grant keys <Badge variant="secondary">on</Badge>
          </dt>
          <dd className="text-muted-foreground">The simple option: create a key below and send it as a bearer token.</dd>
        </div>
        <div data-testid="mcp-auth-oauth">
          <dt className="font-medium">
            OAuth 2.1 <Badge variant={oauth.enabled ? "secondary" : "outline"}>{oauth.enabled ? "on" : "off"}</Badge>
          </dt>
          <dd className="text-muted-foreground">
            {oauth.enabled ? (
              <>
                Tokens issued by <code>{oauth.issuer}</code> for <code>{oauth.resource}</code>. Each token must carry the{" "}
                <code>{oauth.tenantClaim}</code> and <code>{oauth.grantClaim}</code> claims (the grant it acts as). Metadata:{" "}
                <code>{oauth.metadataUrl}</code>. Scopes: {oauth.scopes.map((s) => (<code key={s} className="mr-1">{s}</code>))}
              </>
            ) : (
              <>Off. The operator enables it with <code>app.mcpserver.oauth.enabled</code>; grant keys keep working either way.</>
            )}
          </dd>
        </div>
        <div data-testid="mcp-auth-origin">
          <dt className="font-medium">
            Browser origins <Badge variant="outline">{origin.mode}</Badge>
          </dt>
          <dd className="text-muted-foreground">
            {origin.mode === "off"
              ? "Origin is not checked."
              : origin.allowed.length > 0
                ? <>Browsers may call from: {origin.allowed.map((o) => (<code key={o} className="mr-1">{o}</code>))}</>
                : "No browser origin is allowed. Clients that send no Origin header (desktop apps, SDKs, curl) are unaffected."}
            {origin.mode === "report" && " Violations are logged, not blocked."}
          </dd>
        </div>
      </dl>
    </section>
  );
}
