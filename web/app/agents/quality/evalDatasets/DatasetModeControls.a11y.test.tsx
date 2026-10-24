// Accessibility (axe) of the eval dataset mode controls: text only, the full pipeline, and the auto-approve
// confirmation dialog.
import { beforeEach, describe, expect, it, vi } from "vitest";
import { useState } from "react";
import { fireEvent, render, screen } from "@testing-library/react";
import { axeViolations } from "../../../lib/axeTestKit";

const getTrajectoryEvalConfig = vi.hoisted(() => vi.fn());
vi.mock("../../../lib/api", () => ({ api: { getTrajectoryEvalConfig } }));
vi.mock("../../../lib/i18n", async () => {
  const en = (await import("../../../../messages/en.json")).default as unknown as Record<string, Record<string, string>>;
  return {
    useTranslations: (ns: string) => (key: string, values?: Record<string, unknown>) => {
      const raw = en[ns]?.[key] ?? `${ns}.${key}`;
      return raw.replace(/\{(\w+)\}/g, (_m, k) => String(values?.[k] ?? ""));
    },
  };
});

import DatasetModeControls from "./DatasetModeControls";
import type { DatasetModeValue } from "./datasetMode";

function Harness({ initial, refusal = null }: { initial: DatasetModeValue; refusal?: string | null }) {
  const [value, setValue] = useState(initial);
  return <DatasetModeControls agentId="agent-a11y" value={value} refusal={refusal} onChange={setValue} />;
}

beforeEach(() => {
  getTrajectoryEvalConfig.mockReset().mockResolvedValue({ enabled: false });
});

describe("eval dataset mode controls accessibility (axe)", () => {
  it("text only", async () => {
    render(<Harness initial={{ toolExecutionEnabled: false, autoApproveTools: false }} />);
    expect(await axeViolations()).toEqual([]);
  });

  it("full pipeline, and the auto-approve confirmation", async () => {
    render(<Harness initial={{ toolExecutionEnabled: true, autoApproveTools: false }} />);
    expect(await axeViolations()).toEqual([]);
    fireEvent.click(screen.getByRole("switch", { name: "Auto-approve tool calls in the eval sandbox" }));
    await screen.findByRole("alertdialog");
    expect(await axeViolations()).toEqual([]);
  });

  it("with a refusal message shown", async () => {
    render(<Harness initial={{ toolExecutionEnabled: true, autoApproveTools: true }} refusal="Not allowed in this workspace" />);
    expect(await axeViolations()).toEqual([]);
  });
});
