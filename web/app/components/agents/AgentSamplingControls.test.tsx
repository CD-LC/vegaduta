// Advanced sampling controls, rendered for real with the English catalog: labels and help come from i18n, a bad
// value gets an inline error naming its bounds, and a setting the provider ignores carries a warning.

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { i18nMock } from "./testI18n";

const net = vi.hoisted(() => ({ setAgentGenerationParams: vi.fn() }));
vi.mock("../../lib/i18n", () => i18nMock);
vi.mock("../../lib/api", () => ({ api: { setAgentGenerationParams: net.setAgentGenerationParams } }));

import AgentSamplingControls from "./AgentSamplingControls";

const AGENT = { id: "a1", maxTokens: 1024, temperature: 0.5, topP: 0.9, topK: 40, stopSequences: ["END"] };

beforeEach(() => {
  net.setAgentGenerationParams.mockReset().mockImplementation(async (_id: string, body: object) => ({ id: "a1", ...body }));
});
afterEach(() => cleanup());

describe("AgentSamplingControls", () => {
  it("shows plain-language labels and help from the catalog", () => {
    render(<AgentSamplingControls agent={AGENT} />);
    expect(screen.getByRole("region", { name: "Advanced sampling" })).toBeTruthy();
    expect(screen.getByLabelText("Top-K")).toBeTruthy();
    expect(screen.getByText(/Only consider the K most likely next words/)).toBeTruthy();
    expect(screen.getByLabelText("Stop sequences")).toBeTruthy();
    expect(screen.getByText("Up to 8", { exact: false })).toBeTruthy();
  });

  it("refuses an out-of-range value inline, names the bounds, and puts the old value back", () => {
    render(<AgentSamplingControls agent={AGENT} />);
    const topK = screen.getByLabelText("Top-K") as HTMLInputElement;
    fireEvent.change(topK, { target: { value: "5000" } });
    fireEvent.blur(topK);
    expect(screen.getByRole("alert").textContent).toBe("Top-K must be a whole number from 1 to 1000, or empty to turn it off.");
    expect(topK.value).toBe("40");
    expect(net.setAgentGenerationParams).not.toHaveBeenCalled();
  });

  it("saves a valid value through the generation-params call, keeping the other settings", async () => {
    const onSaved = vi.fn();
    render(<AgentSamplingControls agent={AGENT} onSaved={onSaved} />);
    const topK = screen.getByLabelText("Top-K") as HTMLInputElement;
    fireEvent.change(topK, { target: { value: "50" } });
    fireEvent.blur(topK);
    await waitFor(() => expect(onSaved).toHaveBeenCalled());
    expect(net.setAgentGenerationParams).toHaveBeenCalledWith(
      "a1",
      expect.objectContaining({ topK: 50, maxTokens: 1024, temperature: 0.5, topP: 0.9, stopSequences: ["END"] })
    );
    expect(screen.getAllByText("Saved").length).toBeGreaterThan(0);
  });

  it("reports a failed save with the server's reason", async () => {
    net.setAgentGenerationParams.mockRejectedValue(new Error("Plan does not allow this."));
    render(<AgentSamplingControls agent={AGENT} />);
    const topK = screen.getByLabelText("Top-K") as HTMLInputElement;
    fireEvent.change(topK, { target: { value: "50" } });
    fireEvent.blur(topK);
    expect((await screen.findByRole("alert")).textContent).toBe("Plan does not allow this.");
    expect(screen.getByText("Not saved")).toBeTruthy();
  });

  it("warns on exactly the fields the provider ignores", () => {
    render(<AgentSamplingControls agent={AGENT} ignoredParams={["top_k", "stop"]} />);
    expect(screen.getByTestId("sampling-ignored-topK").textContent).toContain("provider ignores this setting");
    expect(screen.getByTestId("sampling-ignored-stopSequences")).toBeTruthy();
    expect(screen.queryByTestId("sampling-ignored-seed")).toBeNull();
  });

  it("shows no warning without a list of ignored settings", () => {
    render(<AgentSamplingControls agent={AGENT} />);
    expect(screen.queryByTestId("sampling-ignored-topK")).toBeNull();
  });

  it("adds a stop sequence", async () => {
    render(<AgentSamplingControls agent={AGENT} />);
    const box = screen.getByLabelText("Stop sequences") as HTMLInputElement;
    fireEvent.change(box, { target: { value: "STOP" } });
    fireEvent.click(screen.getByRole("button", { name: "Add" }));
    await waitFor(() =>
      expect(net.setAgentGenerationParams).toHaveBeenCalledWith("a1", expect.objectContaining({ stopSequences: ["END", "STOP"] }))
    );
    expect(screen.getByRole("button", { name: "Remove stop sequence STOP" })).toBeTruthy();
  });

  it("refuses a stop sequence that is too long, with the limit in the message", () => {
    render(<AgentSamplingControls agent={AGENT} />);
    fireEvent.change(screen.getByLabelText("Stop sequences"), { target: { value: "x".repeat(65) } });
    fireEvent.click(screen.getByRole("button", { name: "Add" }));
    expect(screen.getByRole("alert").textContent).toBe("A stop sequence can be at most 64 characters.");
    expect(net.setAgentGenerationParams).not.toHaveBeenCalled();
  });

  it("disables everything and shows the reason when locked", () => {
    render(<AgentSamplingControls agent={AGENT} disabled disabledReason="This agent is locked for 24 hours." />);
    expect((screen.getByLabelText("Top-K") as HTMLInputElement).disabled).toBe(true);
    expect(screen.getByText("This agent is locked for 24 hours.")).toBeTruthy();
  });
});
