"use client";

import { HardDrive } from "lucide-react";
import { Button } from "../ui/button";
import { Badge } from "../ui/badge";
import { Card, CardContent } from "../ui/card";
import { useTranslations } from "../../lib/i18n";
import Link from "next/link";
import { initialsOf, agentsWithTool } from "./helpers";

/* eslint-disable @typescript-eslint/no-explicit-any */
// Inspector bodies for the non-MCP cards on the tools page: a built-in tool,
// the "connect Google Workspace" prompt, and a connected Google tool.

export function BuiltinInspector({ card }: { card: any }) {
  const t = useTranslations("mcpPanel");
  const Icon = card.data.icon;
  return (
    <div className="space-y-4">
      <div className="flex items-center gap-3">
        <span className="flex size-10 shrink-0 items-center justify-center rounded-full bg-primary/10 text-primary">
          <Icon size={18} />
        </span>
        <div className="min-w-0">
          <div className="truncate text-base font-semibold">{t(card.data.nameKey)}</div>
          <div className="text-xs text-muted-foreground">{t("builtinAlwaysAvailable")}</div>
        </div>
      </div>
      <Card>
        <CardContent className="space-y-2 pt-6">
          <div className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">{t("aboutLabel")}</div>
          <p className="text-sm text-muted-foreground">{t(card.data.descriptionKey)}</p>
          <div className="flex flex-wrap items-center justify-between gap-x-3 gap-y-1 text-sm">
            <span className="text-muted-foreground">{t("reachableFromLabel")}</span>
            <span className="min-w-0 break-words font-medium">{t(card.data.availableFromKey)}</span>
          </div>
        </CardContent>
      </Card>
      <Card>
        <CardContent className="space-y-2 pt-6">
          <div className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
            {t("availableToAgentsLabel")}
          </div>
          <p className="text-sm text-muted-foreground">{t("manageAgentsToolPickerHint")}</p>
          <Button variant="outline" size="sm" className="w-full" asChild>
            <Link href="/agents">{t("openAgentsLink")}</Link>
          </Button>
        </CardContent>
      </Card>
    </div>
  );
}

export function GoogleConnectInspector() {
  const t = useTranslations("mcpPanel");
  return (
    <div className="flex h-full flex-col gap-4">
      <div className="flex items-center gap-3">
        <span className="flex size-10 shrink-0 items-center justify-center rounded-full bg-[var(--ax-google-blue-bg)] text-[var(--ax-google-blue)]">
          <HardDrive size={18} aria-hidden="true" />
        </span>
        <div className="min-w-0">
          <div className="truncate text-base font-semibold">{t("googleWorkspace")}</div>
          <div className="text-xs text-muted-foreground">{t("notConnectedMeta")}</div>
        </div>
      </div>
      <Card>
        <CardContent className="pt-6">
          <p className="text-sm text-muted-foreground">{t("googleConnectInspectorBody")}</p>
        </CardContent>
      </Card>
      <Button variant="outline" size="sm" className="mt-auto w-full" asChild>
        <Link href="/admin/integrations">{t("goToIntegrations")}</Link>
      </Button>
    </div>
  );
}

export function GoogleToolInspector({
  card,
  agents,
  googleConnection,
  onDisconnectGoogle,
}: {
  card: any;
  agents: any;
  googleConnection: any;
  onDisconnectGoogle: any;
}) {
  const t = useTranslations("mcpPanel");
  const withAccess = agentsWithTool(agents, card.data.toolName);
  return (
    <div className="space-y-4">
      <div className="flex items-center gap-3">
        <span className="flex size-10 shrink-0 items-center justify-center rounded-full bg-[var(--ax-google-blue-bg)] text-[var(--ax-google-blue)]">
          <card.data.icon size={18} />
        </span>
        <div className="min-w-0">
          <div className="truncate text-base font-semibold">{t(card.data.nameKey)}</div>
          <div className="flex items-center gap-1.5 text-xs font-medium text-[var(--state-success)]">
            <span className="size-1.5 rounded-full bg-[var(--state-success)]" />
            {t("connectedLabel")}
            {googleConnection?.connectedEmail
              ? t("connectedEmailSuffix", { email: googleConnection.connectedEmail })
              : ""}
          </div>
        </div>
      </div>

      <Card>
        <CardContent className="space-y-1.5 pt-6">
          <div className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
            {t("toolsExposedCountLabel", { count: card.data.subActions.length })}
          </div>
          {card.data.subActions.map((a: { name: string; mode: string }) => (
            <div
              className="flex items-center justify-between border-b border-border/60 py-1.5 text-sm last:border-0"
              key={a.name}
            >
              <span className="font-mono text-xs text-foreground">{a.name}</span>
              <Badge
                variant="outline"
                className={
                  a.mode === "irreversible"
                    ? "border-[color-mix(in_srgb,var(--state-error)_32%,transparent)] bg-[color-mix(in_srgb,var(--state-error)_12%,transparent)] text-[var(--state-error)]"
                    : a.mode === "write"
                      ? "border-[color-mix(in_srgb,var(--state-warning)_32%,transparent)] bg-[color-mix(in_srgb,var(--state-warning)_12%,transparent)] text-[var(--state-warning)]"
                      : undefined
                }
              >
                {a.mode === "irreversible"
                  ? t("modeIrreversible")
                  : a.mode === "write"
                    ? t("modeWrite")
                    : t("modeRead")}
              </Badge>
            </div>
          ))}
          {card.data.subActions.some((a: { mode: string }) => a.mode === "irreversible") && (
            <p className="pt-1 text-xs text-muted-foreground">{t("irreversibleApprovalHint")}</p>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardContent className="space-y-2 pt-6">
          <div className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
            {t("availableToAgentsLabel")}
          </div>
          {withAccess.length === 0 ? (
            <p className="text-sm text-muted-foreground">{t("noAgentAccessHint")}</p>
          ) : (
            <div className="flex flex-wrap gap-1.5">
              {withAccess.slice(0, 6).map((a: { id: any; name: string }) => (
                <span
                  key={a.id}
                  className="inline-flex items-center gap-1.5 rounded-full border border-border bg-muted px-2 py-1 text-xs"
                >
                  <span className="flex size-5 items-center justify-center rounded-full bg-primary/15 text-xs font-semibold text-primary">
                    {initialsOf(a.name)}
                  </span>
                  {a.name}
                </span>
              ))}
              {withAccess.length > 6 && (
                <span className="inline-flex items-center rounded-full border border-border bg-muted px-2 py-1 text-xs">
                  {t("moreAgentsCount", { count: withAccess.length - 6 })}
                </span>
              )}
            </div>
          )}
        </CardContent>
      </Card>

      <div className="flex gap-2">
        <Button variant="outline" size="sm" className="flex-1" asChild>
          <Link href="/admin/integrations">{t("manageLink")}</Link>
        </Button>
        <Button variant="destructive" size="sm" className="flex-1" onClick={onDisconnectGoogle}>
          {t("disconnectButton")}
        </Button>
      </div>
    </div>
  );
}
