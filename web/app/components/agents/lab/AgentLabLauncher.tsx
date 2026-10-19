"use client";

import { FlaskConical } from "lucide-react";
import { useTranslations } from "../../../lib/i18n";
import { Button } from "../../ui/button";
import { Tooltip, TooltipContent, TooltipTrigger } from "../../ui/tooltip";

/** The "Lab" button in the playground header. Deliberately tiny and free of
 * any lab import beyond the namespace: it is part of the /agents bundle,
 * while the lab's panels are lazy chunks (see AgentLab.tsx). */
export default function AgentLabLauncher({ disabled, onOpen }: { disabled: boolean; onOpen: () => void }) {
  const t = useTranslations("agentLab");
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <Button type="button" variant="ghost" size="sm" disabled={disabled} onClick={onOpen}>
          <FlaskConical size={12} />
          {t("launcherLabel")}
        </Button>
      </TooltipTrigger>
      <TooltipContent>{t("launcherTooltip")}</TooltipContent>
    </Tooltip>
  );
}
