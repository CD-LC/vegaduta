// The PATCH behind this card needs MANAGE_GUARDRAILS (an agent's content
// guardrails are a guardrail decision, like its procedures). Without the
// permission the card must be read-only and say why, not offer checkboxes
// that can only fail with a 403 the card would misreport as a plan lock.

import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

const api = vi.hoisted(() => ({
  getAgentGuardrails: vi.fn(),
  setAgentGuardrails: vi.fn(),
}));

vi.mock("../../lib/api", () => ({ api }));

let mockCanManage = true;
vi.mock("../AuthProvider", () => ({
  useAuth: () => ({ hasPermission: (name: string) => name === "MANAGE_GUARDRAILS" && mockCanManage }),
}));

import AgentGuardrailsCard from "./AgentGuardrailsCard";

const STATE = {
  enabledGuardrails: ["profanity"],
  availableGuardrails: ["profanity", "jailbreak"],
  lockedGuardrails: [] as string[],
  guardrailDescriptions: {},
};

describe("AgentGuardrailsCard", () => {
  afterEach(() => {
    cleanup();
    mockCanManage = true;
    vi.clearAllMocks();
  });

  it("is read-only and says why without MANAGE_GUARDRAILS", async () => {
    mockCanManage = false;
    api.getAgentGuardrails.mockResolvedValue(STATE);
    render(<AgentGuardrailsCard agentId="a1" locked={false} />);

    expect(await screen.findByTestId("agent-guardrails-read-only")).toBeInTheDocument();
    const jailbreak = screen.getByTestId("agent-guardrail-jailbreak").querySelector("input")!;
    expect(jailbreak).toBeDisabled();
    fireEvent.click(jailbreak);
    expect(api.setAgentGuardrails).not.toHaveBeenCalled();
  });

  it("lets a holder of MANAGE_GUARDRAILS change the set", async () => {
    api.getAgentGuardrails.mockResolvedValue(STATE);
    api.setAgentGuardrails.mockResolvedValue({ ...STATE, enabledGuardrails: ["profanity", "jailbreak"] });
    render(<AgentGuardrailsCard agentId="a1" locked={false} />);

    const row = await screen.findByTestId("agent-guardrail-jailbreak");
    expect(screen.queryByTestId("agent-guardrails-read-only")).toBeNull();
    fireEvent.click(row.querySelector("input")!);
    expect(api.setAgentGuardrails).toHaveBeenCalledWith("a1", ["profanity", "jailbreak"]);
  });
});
