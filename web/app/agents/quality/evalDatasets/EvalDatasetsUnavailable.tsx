"use client";

// The two "this panel cannot work yet" states of EvalDatasetsSection, each a
// full Section so the page layout is the same as the live panel.

import { Alert, AlertDescription, AlertTitle } from "../../../components/ui/alert";
import { Section } from "../../../components/ax/page-kit";
import { useTranslations } from "../../../lib/i18n";

export function EvalDatasetsUnavailable({ reason }: { reason: "client-not-wired" | "plan-locked" }) {
    const tHC = useTranslations("hcAgentsQualityEvalDatasetsEvalDatasetsUnavailable");
  if (reason === "client-not-wired") {
    // The backend surface exists; the shared API client does not expose it
    // yet (api.ts is owned by a concurrent workstream — see
    // evalDatasetsApi.ts for the exact methods it needs). Say so honestly
    // instead of rendering a dead UI.
    return (
      <Section
        title={tHC("hcAgentsQualityEvalDatasetsEvalDatasetsUnavailable.s3")}
        description="Golden test cases replayed against this agent and scored by the quality judges, run over run."
      >
        <Alert>
          <AlertTitle>{tHC("hcAgentsQualityEvalDatasetsEvalDatasetsUnavailable.s5")}</AlertTitle>
          <AlertDescription>
            {tHC("hcAgentsQualityEvalDatasetsEvalDatasetsUnavailable.s4")}
          </AlertDescription>
        </Alert>
      </Section>
    );
  }
  // Same inline entitlement posture as AgentQualityPanel's 403 branch.
  return (
    <Section
      title={tHC("hcAgentsQualityEvalDatasetsEvalDatasetsUnavailable.s3")}
    >
      <Alert>
        <AlertTitle>{tHC("hcAgentsQualityEvalDatasetsEvalDatasetsUnavailable.s2")}</AlertTitle>
        <AlertDescription>
          {tHC("hcAgentsQualityEvalDatasetsEvalDatasetsUnavailable.s1")}
        </AlertDescription>
      </Alert>
    </Section>
  );
}
