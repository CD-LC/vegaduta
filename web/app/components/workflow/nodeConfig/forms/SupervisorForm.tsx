"use client";

import { Trash2 } from "lucide-react";
import Select from "../../../Select";
import { Button } from "../../../ui/button";
import { Input } from "../../../ui/input";
import { Label } from "../../../ui/label";
import { useTranslations } from "../../../../lib/i18n";
import { TEXTAREA_CLASS } from "../shared";
import { type WorkflowNodeConfig, type AgentSummary, type SupervisorRoute } from "../types";

/**
 * Supervisor Routing node config - Master Roadmap 2026 item #12 (§E). The
 * `routes` list is the one piece of config that has no equivalent anywhere
 * else in this drawer: each entry is a `{handle, label}` pair that becomes
 * one of this node's output ports on the canvas (see WorkflowNode.js's
 * dynamic per-route Handle rendering) - editing a route's `handle` text here
 * live-updates which port an already-drawn edge from that route resolves to,
 * since the edge's own `sourceHandle` was set to that exact string when it
 * was drawn. Deliberately no minimum-2-routes UI guard (unlike Condition's
 * fixed true/false pair) - OrchestratorService only requires "at least one
 * route", since a single-route Supervisor is a legitimate (if unusual)
 * "always ask the model to confirm this path" pattern.
 */
export function SupervisorNodeConfig({
  agents,
  config,
  setConfig,
}: {
  agents: AgentSummary[] | null;
  config: WorkflowNodeConfig;
  setConfig: (patch: Partial<WorkflowNodeConfig>) => void;
}) {
  const t = useTranslations("nodeConfigDrawer");
  const routes: SupervisorRoute[] = Array.isArray(config.routes) ? config.routes : [];

  function updateRoute(index: number, patch: Partial<SupervisorRoute>) {
    const next = routes.map((r, i) => (i === index ? { ...r, ...patch } : r));
    setConfig({ routes: next });
  }

  function addRoute() {
    const n = routes.length + 1;
    setConfig({ routes: [...routes, { handle: `route-${n}`, label: `Route ${n}` }] });
  }

  function removeRoute(index: number) {
    setConfig({ routes: routes.filter((_, i) => i !== index) });
  }

  return (
    <>
      <div className="field">
        <Label>{t("supervisorNodeAgentLabel")}</Label>
        <Select
          value={config.agentId || ""}
          onChange={(e) => setConfig({ agentId: e.target.value })}
          placeholder={t("selectAgentPlaceholder")}
          options={(agents || []).map((a: AgentSummary) => ({ value: a.id, label: a.name }))}
        />
      </div>

      <div className="field">
        <Label>{t("routesLabel")}</Label>
        {routes.map((route, i) => (
          <div key={i} className="row" style={{ gap: "0.4rem", marginBottom: "0.4rem" }}>
            <Input
              type="text"
              value={route.handle || ""}
              onChange={(e) => updateRoute(i, { handle: e.target.value })}
              placeholder={t("routeHandlePlaceholder")}
              style={{ flex: "0 0 40%" }}
            />
            <Input
              type="text"
              value={route.label || ""}
              onChange={(e) => updateRoute(i, { label: e.target.value })}
              placeholder={t("routeLabelPlaceholder")}
              style={{ flex: 1 }}
            />
            <Button
              type="button"
              variant="outline"
              size="icon"
              onClick={() => removeRoute(i)}
              title={t("removeRouteButton")}
              aria-label={t("removeRouteButton")}
            >
              <Trash2 size={14} aria-hidden="true" />
            </Button>
          </div>
        ))}
        <Button type="button" variant="outline" size="sm" onClick={addRoute}>
          {t("addRouteButton")}
        </Button>
        {routes.length === 0 && <p className="ax-wf-empty">{t("noRoutesHint")}</p>}
      </div>

      <div className="field">
        <Label>{t("supervisorInstructionsLabel")}</Label>
        <textarea
          rows={3}
          className={TEXTAREA_CLASS}
          placeholder={t("supervisorInstructionsPlaceholder")}
          value={config.instructions || ""}
          onChange={(e) => setConfig({ instructions: e.target.value })}
        />
      </div>
      <p className="ax-wf-empty">{t("supervisorHint")}</p>
    </>
  );
}

