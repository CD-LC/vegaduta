"use client";

import Link from "next/link";
import { KeyRound, Plug } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { Alert, AlertDescription } from "../../components/ui/alert";
import { Button } from "../../components/ui/button";
import McpServerAuthOptionsCard from "../../components/McpServerAuthOptionsCard";

/**
 * Header of /admin/mcp-servers: says which kind of key this page manages (MCP grant keys, for clients that
 * connect INTO this workspace's /mcp endpoint) and points to the REST API keys page for everything else, then
 * shows how callers can authenticate.
 */
export default function McpAccessHint() {
  const t = useTranslations("channelsUsageMcp");
  return (
    <>
      <div
        className="mb-4 flex flex-col gap-2 rounded-lg border border-border bg-muted/40 p-3 text-sm sm:flex-row sm:items-start"
        data-testid="mcp-keys-hint"
      >
        <Plug size={16} className="mt-0.5 shrink-0 text-muted-foreground" aria-hidden="true" />
        <div className="min-w-0 space-y-1">
          <p className="m-0 font-medium text-foreground">{t("keysHintTitle")}</p>
          <p className="m-0 text-muted-foreground">{t("keysHintMcp")}</p>
          <p className="m-0 text-muted-foreground">
            <KeyRound size={12} className="mr-1 inline" aria-hidden="true" />
            {t("keysHintRest")}{" "}
            <Link href="/admin/api-keys" className="font-medium text-primary underline-offset-4 hover:underline">
              {t("keysHintRestLink")}
            </Link>
          </p>
        </div>
      </div>
      <McpServerAuthOptionsCard />
    </>
  );
}

/** Load-failure banner with a Retry action (a failed load must not read as "no grants yet"). */
export function LoadErrorRetry({ message, onRetry }: { message: string; onRetry: () => void }) {
  const t = useTranslations("channelsUsageMcp");
  return (
    <Alert variant="destructive" className="mb-4" data-testid="mcp-grants-load-error">
      <AlertDescription className="flex flex-wrap items-center gap-3">
        <span>{message}</span>
        <Button type="button" size="sm" variant="outline" onClick={onRetry}>
          {t("retry")}
        </Button>
      </AlertDescription>
    </Alert>
  );
}
