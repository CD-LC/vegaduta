"use client";

import { ShieldAlert } from "lucide-react";
import { useTranslations } from "../../../lib/i18n";
import { Badge } from "../../ui/badge";
import { MCP_SERVER_FACTS, mcpNeedsNoCredential, type McpServerKey } from "../templatePrereqs";
import { type groupSuggestions, serverState, type CatalogEntry, type WorkspaceSnapshot } from "./workspaceSnapshot";

/**
 * The MCP step's server list: each suggestion block (required first, interchangeable servers grouped
 * as either/or) with its live registration state, credential needs and where to find its card.
 */
export default function McpSuggestionList({
  mcpBlocks,
  snapshot,
  catalogEntry,
}: {
  mcpBlocks: ReturnType<typeof groupSuggestions>;
  snapshot: WorkspaceSnapshot | null;
  catalogEntry: (server: McpServerKey) => CatalogEntry | null;
}) {
  const t = useTranslations("templateSetupGuide");
  return (
  <ul className="mt-3 space-y-2">
    {mcpBlocks.map((block) => {
      const required = block.items.some((i) => i.necessity === "required");
      return (
        <li key={block.id} className="rounded-lg border border-border bg-muted/40 p-3">
          <div className="flex flex-wrap items-center gap-2">
            <Badge variant={required ? "accent" : "secondary"}>
              {required ? t("mcpRequired") : t("mcpOptional")}
            </Badge>
            {block.alternatives && block.items.length > 1 && (
              <span className="text-[11px] text-muted-foreground">{t("mcpEitherOr")}</span>
            )}
          </div>
          <div className="mt-2 space-y-2.5">
            {block.items.map((suggestion) => {
              const entry = catalogEntry(suggestion.server);
              const state = serverState(snapshot, suggestion.server);
              const facts = MCP_SERVER_FACTS[suggestion.server];
              const envVars = entry?.requiredEnvVars?.length ? entry.requiredEnvVars : facts.requiredEnvVars;
              const needsOAuth = entry?.requiresOAuth ?? facts.requiresOAuth;
              return (
                <div key={suggestion.server}>
                  <div className="flex flex-wrap items-center gap-2">
                    <code className="rounded bg-background px-1.5 py-0.5 font-mono text-xs text-foreground">
                      {entry?.name ?? suggestion.server}
                    </code>
                    <span
                      className={
                        state === "on"
                          ? "text-[11px] font-medium text-primary"
                          : state === "error"
                            ? "text-[11px] font-medium text-destructive"
                            : "text-[11px] font-medium text-muted-foreground"
                      }
                    >
                      {state === "on"
                        ? t("mcpStateOn")
                        : state === "error"
                          ? t("mcpStateError")
                          : state === "off"
                            ? t("mcpStateOff")
                            : state === "missing"
                              ? t("mcpStateMissing")
                              : t("statusUnknown")}
                    </span>
                  </div>
                  <p className="mt-1 text-xs leading-relaxed text-muted-foreground">{t(suggestion.reasonKey)}</p>
                  <p className="mt-1 text-xs leading-relaxed text-muted-foreground">
                    {mcpNeedsNoCredential(suggestion.server)
                      ? t("mcpNoCredential")
                      : needsOAuth
                        ? t("mcpOAuth")
                        : facts.credential === "serverPath"
                          ? t("mcpServerPath", { vars: envVars.join(", ") })
                          : facts.credential === "deployment"
                            ? t("mcpDeployment", { vars: envVars.join(", ") })
                            : t("mcpNeedsCredential", { vars: envVars.join(", ") })}
                  </p>
                  <p className="mt-1 text-xs text-muted-foreground">
                    {t("mcpFindHint", { name: entry?.name ?? suggestion.server })}
                  </p>
                  {suggestion.cautionKey && (
                    <p className="mt-1 flex items-start gap-1.5 text-xs leading-relaxed text-foreground">
                      <ShieldAlert size={13} className="mt-0.5 shrink-0 text-destructive" aria-hidden="true" />
                      {t(suggestion.cautionKey)}
                    </p>
                  )}
                </div>
              );
            })}
          </div>
        </li>
      );
    })}
    <li className="text-xs leading-relaxed text-muted-foreground">{t("mcpSecretNote")}</li>
  </ul>
  );
}
