"use client";

// The two "this panel cannot work yet" states of EvalDatasetsSection, each a
// full Section so the page layout is the same as the live panel.

import { Alert, AlertDescription, AlertTitle } from "../../../components/ui/alert";
import { Section } from "../../../components/ax/page-kit";
import { LearnLink } from "../../../learn/LearnThisButton";

export function EvalDatasetsUnavailable({ reason }: { reason: "client-not-wired" | "plan-locked" }) {
  if (reason === "client-not-wired") {
    // The backend surface exists; the shared API client does not expose it
    // yet (api.ts is owned by a concurrent workstream — see
    // evalDatasetsApi.ts for the exact methods it needs). Say so honestly
    // instead of rendering a dead UI.
    return (
      <Section
        title="Eval datasets & regression runs"
        description="Golden test cases replayed against this agent and scored by the quality judges, run over run."
        actions={<LearnLink lessonId="l16-3" fromPath="/agents/quality" />}
      >
        <Alert>
          <AlertTitle>Almost there — API client wiring pending</AlertTitle>
          <AlertDescription>
            The backend for eval datasets is live, but the shared API client (app/lib/api.ts) doesn&apos;t expose its
            endpoints yet — that file is owned by concurrent work in flight. This panel activates automatically once the
            client methods land; see evalDatasetsApi.ts for the exact contract.
          </AlertDescription>
        </Alert>
      </Section>
    );
  }
  // Same inline entitlement posture as AgentQualityPanel's 403 branch.
  return (
    <Section
      title="Eval datasets & regression runs"
      actions={<LearnLink lessonId="l16-3" fromPath="/agents/quality" />}
    >
      <Alert>
        <AlertTitle>Premium feature</AlertTitle>
        <AlertDescription>
          Eval datasets and regression runs are available on Premium plans, alongside the full quality-evaluation
          suite above.
        </AlertDescription>
      </Alert>
    </Section>
  );
}
