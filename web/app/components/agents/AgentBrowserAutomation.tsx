"use client";

import { useTranslations } from "../../lib/i18n";
import BrowserProfilesCard from "./BrowserProfilesCard";
import BrowserRecipesCard from "./BrowserRecipesCard";
import { useBrowserCapabilities } from "./useBrowserCapabilities";
import BrandText from "../BrandText";

// Per-agent browser automation: saved logins and recorded tasks, mounted in the workbench next to the
// agent's other cards. Core ships both features OFF; until a deployment switches one on this renders
// NOTHING (not even while the capability probe is in flight), so a workbench without the feature is
// exactly the workbench it was.

export default function AgentBrowserAutomation({
  agentId,
  locked,
  editLockedTooltip,
}: {
  agentId: string;
  locked: boolean;
  editLockedTooltip?: string;
}) {
  const t = useTranslations("browserAutomation");
  const caps = useBrowserCapabilities();
  if (!caps || (!caps.profilesEnabled && !caps.recipesEnabled)) return null;

  return (
    <div className="mb-4" data-testid="agent-browser-automation">
      <div className="mb-1 text-xs text-muted-foreground"><BrandText text={t("title")} /></div>
      <p className="mb-2 text-xs text-muted-foreground"><BrandText text={t("subtitle")} /></p>
      {caps.profilesEnabled && <BrowserProfilesCard agentId={agentId} locked={locked} editLockedTooltip={editLockedTooltip} />}
      {caps.recipesEnabled && <BrowserRecipesCard agentId={agentId} locked={locked} editLockedTooltip={editLockedTooltip} />}
    </div>
  );
}
