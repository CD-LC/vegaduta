// Accessibility (axe) of the advanced sampling controls: resting, with an inline error and with ignored-parameter warnings.
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { axeViolations } from "../../lib/axeTestKit";
import { i18nMock } from "./testI18n";

vi.mock("../../lib/i18n", () => i18nMock);
vi.mock("../../lib/api", () => ({ api: { setAgentGenerationParams: vi.fn() } }));

import AgentSamplingControls from "./AgentSamplingControls";

const AGENT = { id: "a1", maxTokens: 1024, temperature: 0.5, topP: 0.9, topK: 40, stopSequences: ["END"] };

afterEach(() => cleanup());

describe("sampling controls accessibility (axe)", () => {
  it("resting, with an inline range error, and with provider-ignores warnings", async () => {
    const { unmount } = render(<AgentSamplingControls agent={AGENT} />);
    expect(await axeViolations()).toEqual([]);
    const topK = screen.getByLabelText("Top-K") as HTMLInputElement;
    fireEvent.change(topK, { target: { value: "5000" } });
    fireEvent.blur(topK);
    expect(screen.getByRole("alert")).toBeInTheDocument();
    expect(await axeViolations()).toEqual([]);
    unmount();
    render(<AgentSamplingControls agent={AGENT} ignoredParams={["top_k", "stop"]} />);
    expect(await axeViolations()).toEqual([]);
  });
});
