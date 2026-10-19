// Cost-transparency badge (gap-radar P0, 2026-09-23): ModelInfoCard is the
// single component every model dropdown renders under (AgentWorkbench x4),
// so covering it here covers every picker without touching AgentWorkbench.
import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import { I18nProvider } from "../../lib/i18n";
import en from "../../../messages/en.json";
import ModelInfoCard, { modelOptionLabel } from "./ModelInfoCard";
import type { ProviderModelInfo } from "../../lib/api";

function Providers({ children }: { children: React.ReactNode }) {
  return (
    <I18nProvider locale="en" messages={en} enMessages={en}>
      {children}
    </I18nProvider>
  );
}

const BASE: ProviderModelInfo = { modelId: "some-model" };

describe("ModelInfoCard cost tier badge", () => {
  it("renders nothing when pricing was never fetched (undefined), even with no other metadata", () => {
    const { container } = render(
      <Providers>
        <ModelInfoCard model={BASE} />
      </Providers>
    );
    expect(container.textContent).toBe("");
  });

  it("shows a single $ for a cheap open-model-tier rate and the exact numbers in its tooltip", () => {
    render(
      <Providers>
        <ModelInfoCard model={{ ...BASE, inputCostPerMillionUsd: 0.05, outputCostPerMillionUsd: 0.08 }} />
      </Providers>
    );
    const badge = screen.getByTestId("model-cost-tier-badge");
    expect(badge.textContent).toBe("$");
    expect(badge.getAttribute("title")).toBe("Estimated $0.05 in / $0.08 out per 1M tokens");
  });

  it("shows $$$ for a frontier-reasoning-tier rate (claude-opus-4-1: $15/$75)", () => {
    render(
      <Providers>
        <ModelInfoCard model={{ ...BASE, inputCostPerMillionUsd: 15, outputCostPerMillionUsd: 75 }} />
      </Providers>
    );
    expect(screen.getByTestId("model-cost-tier-badge").textContent).toBe("$$$");
  });

  it("shows $$ for a mid-tier rate (claude-sonnet-5 standard: $3/$15)", () => {
    render(
      <Providers>
        <ModelInfoCard model={{ ...BASE, inputCostPerMillionUsd: 3, outputCostPerMillionUsd: 15 }} />
      </Providers>
    );
    expect(screen.getByTestId("model-cost-tier-badge").textContent).toBe("$$");
  });

  it("treats a genuinely free model (0/0) as its own $ tier, distinct from no-data", () => {
    render(
      <Providers>
        <ModelInfoCard model={{ ...BASE, inputCostPerMillionUsd: 0, outputCostPerMillionUsd: 0 }} />
      </Providers>
    );
    expect(screen.getByTestId("model-cost-tier-badge").textContent).toBe("$");
  });

  it("does not render the badge from a half-loaded value (only one of the two rates present)", () => {
    const { container } = render(
      <Providers>
        <ModelInfoCard model={{ ...BASE, inputCostPerMillionUsd: 3 }} />
      </Providers>
    );
    expect(container.querySelector('[data-testid="model-cost-tier-badge"]')).toBeNull();
  });
});

describe("modelOptionLabel", () => {
  it("is unaffected by the new pricing fields (still just displayName/modelId + deprecation)", () => {
    const label = modelOptionLabel(
      { modelId: "gpt-4o-mini", displayName: "GPT-4o mini", inputCostPerMillionUsd: 0.15, outputCostPerMillionUsd: 0.6 },
      "deprecated"
    );
    expect(label).toBe("GPT-4o mini (gpt-4o-mini)");
  });
});
