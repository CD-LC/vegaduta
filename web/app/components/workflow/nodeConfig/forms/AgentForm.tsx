"use client";

import { useTranslations } from "../../../../lib/i18n";
import type { AgentSummary } from "../types";
import type { NodeFormProps } from "../registryTypes";
import Select from "../../../Select";
import { Label } from "../../../ui/label";
import { SetupHint } from "../../../setup/SetupLinks";
import { AgentRlToggle } from "./AgentRlToggle";
import { AgentStructuredInputConfig } from "./AgentStructuredInput";

export default function AgentForm({ config, setConfig, agents, collections }: NodeFormProps) {
  const t = useTranslations("nodeConfigDrawer");
  return (
    <>
            {(
              <div className="field">
                <Label>{t("agentLabel")}</Label>
                <Select
                  value={config.agentId || ""}
                  onChange={(e) => setConfig({ agentId: e.target.value })}
                  placeholder={t("selectAgentPlaceholder")}
                  options={(agents || []).map((a: AgentSummary) => ({ value: a.id, label: a.name }))}
                />
              </div>
            )}
            {(
              <SetupHint
                targets={["agents", "providers", "edge"]}
                lead="Configure the agent, its provider key and on-device setup (workflow runs always use the server model):"
                newTab
              />
            )}
            {config.agentId && <AgentRlToggle agentId={config.agentId} />}

            {config.agentId && (
              <AgentStructuredInputConfig
                agent={(agents || []).find((a: AgentSummary) => a.id === config.agentId)}
                collections={collections}
                config={config}
                setConfig={setConfig}
              />
            )}
    </>
  );
}
