"use client";

import { useEffect, useState } from "react";
import { ShieldCheck } from "lucide-react";
import { api } from "../lib/api";
import type { McpServerAuthOptions } from "../lib/api/mcpToolPins";
import { Badge } from "./ui/badge";
import { useTranslations } from "../lib/i18n";

/**
 * How callers can authenticate to this deployment's /mcp endpoint, read-only: grant keys (the simple option,
 * always on), the optional OAuth 2.1 mode and the Origin check. These are operator settings (environment
 * variables), so the card explains the current state and names the setting rather than offering a toggle.
 * Renders nothing if the endpoint is unavailable (the mcpserver profile is off).
 */
export default function McpServerAuthOptionsCard() {
    const tHC = useTranslations("hcComponentsMcpServerAuthOptionsCard");
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
        <h2 className="text-sm font-semibold">{tHC("hcComponentsMcpServerAuthOptionsCard.s12")}</h2>
      </div>
      <dl className="grid gap-3 text-sm md:grid-cols-3">
        <div>
          <dt className="font-medium">
            {tHC("hcComponentsMcpServerAuthOptionsCard.s11")} <Badge variant="secondary">on</Badge>
          </dt>
          <dd className="text-muted-foreground">
            {tHC("hcComponentsMcpServerAuthOptionsCard.s10")}
          </dd>
        </div>
        <div data-testid="mcp-auth-oauth">
          <dt className="font-medium">
            {tHC("hcComponentsMcpServerAuthOptionsCard.s9")} <Badge variant={oauth.enabled ? "secondary" : "outline"}>{oauth.enabled ? "on" : "off"}</Badge>
          </dt>
          <dd className="text-muted-foreground">
            {oauth.enabled ? (
              <>
                {tHC("hcComponentsMcpServerAuthOptionsCard.s8")} <code>{oauth.issuer}</code> for <code>{oauth.resource}</code>{tHC("hcComponentsMcpServerAuthOptionsCard.s7")} <code>{oauth.tenantClaim}</code> and <code>{oauth.grantClaim}</code> {tHC("hcComponentsMcpServerAuthOptionsCard.s6")} <code>{oauth.metadataUrl}</code>{tHC("hcComponentsMcpServerAuthOptionsCard.s5")}{" "}
                {oauth.scopes.map((s) => (
                  <code key={s} className="me-1">
                    {s}
                  </code>
                ))}
              </>
            ) : (
              <>
                {tHC("hcComponentsMcpServerAuthOptionsCard.s4")} <code>app.mcpserver.oauth.enabled</code>{tHC("hcComponentsMcpServerAuthOptionsCard.s3")}
              </>
            )}
          </dd>
        </div>
        <div data-testid="mcp-auth-origin">
          <dt className="font-medium">
            {tHC("hcComponentsMcpServerAuthOptionsCard.s2")} <Badge variant="outline">{origin.mode}</Badge>
          </dt>
          <dd className="text-muted-foreground">
            {origin.mode === "off" ? (
              "Origin is not checked."
            ) : origin.allowed.length > 0 ? (
              <>
                {tHC("hcComponentsMcpServerAuthOptionsCard.s1")}{" "}
                {origin.allowed.map((o) => (
                  <code key={o} className="me-1">
                    {o}
                  </code>
                ))}
              </>
            ) : (
              "No browser origin is allowed. Clients that send no Origin header (desktop apps, SDKs, curl) are unaffected."
            )}
            {origin.mode === "report" && " Violations are logged, not blocked."}
          </dd>
        </div>
      </dl>
    </section>
  );
}
