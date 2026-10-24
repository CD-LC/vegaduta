"use client";

import { Building2, Lock, Server, Sparkles, UserCog } from "lucide-react";
import type { SettingScope, SettingSource } from "../../lib/api/settings";
import { Badge } from "../ui/badge";
import { Tooltip, TooltipContent, TooltipTrigger } from "../ui/tooltip";
import { useSettingsText } from "./useSettingsText";

const ICONS = { default: Sparkles, platform: Server, tenant: Building2, agent: UserCog } as const;

/**
 * Where a setting's value comes from: shipped default, the platform operator's deployment config, the tenant's
 * override or this agent's override. The tooltip (hover AND keyboard focus) says what that means for editing; in
 * an agent view a tenant-sourced value reads "inherited from tenant" because the agent itself has no override.
 */
export default function SourceBadge({
  source,
  scope,
  locked = false,
}: {
  source: SettingSource;
  scope: SettingScope;
  locked?: boolean;
}) {
  const { ui } = useSettingsText();
  const variant = scope === "agent" && source === "tenant" ? "tenantInherited" : source;
  const label = ui(`source.${variant}`);
  const Icon = locked ? Lock : ICONS[source];
  const help = `${ui(`sourceHelp.${variant}`)}${locked ? ` ${ui("sourceHelp.lockedSuffix")}` : ""}`;
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <button
          type="button"
          data-testid="setting-source"
          data-source={source}
          className="inline-flex cursor-help rounded-full bg-transparent p-0 outline-none focus-visible:ring-[3px] focus-visible:ring-ring"
          aria-label={ui("source.aria", { source: label })}
        >
          <Badge variant={source === "default" ? "secondary" : source === "agent" || source === "tenant" ? "accent" : "outline"}>
            <Icon aria-hidden="true" />
            {label}
          </Badge>
        </button>
      </TooltipTrigger>
      <TooltipContent>{help}</TooltipContent>
    </Tooltip>
  );
}
