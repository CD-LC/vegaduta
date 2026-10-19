import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";

vi.mock("../../lib/i18n", () => ({
  useTranslations: () => (key: string, values?: Record<string, unknown>) =>
    values ? `${key}:${JSON.stringify(values)}` : key,
}));

const listProviderModels = vi.fn();
const probeLocalOpenAi = vi.fn();
vi.mock("../../lib/api", () => ({
  api: {
    listProviderModels: (...args: unknown[]) => listProviderModels(...args),
    probeLocalOpenAi: (...args: unknown[]) => probeLocalOpenAi(...args),
  },
}));

import LocalServerPresets from "./LocalServerPresets";
import LocalServerProbe from "./LocalServerProbe";

describe("LocalServerPresets", () => {
  it("fills the clicked server's address, switching to host.docker.internal when asked", () => {
    const onPick = vi.fn();
    render(<LocalServerPresets baseUrl="" onPick={onPick} />);

    fireEvent.click(screen.getByRole("button", { name: "vLLM" }));
    expect(onPick).toHaveBeenLastCalledWith("http://localhost:8000/v1");

    fireEvent.click(screen.getByRole("checkbox"));
    fireEvent.click(screen.getByRole("button", { name: "LM Studio" }));
    expect(onPick).toHaveBeenLastCalledWith("http://host.docker.internal:1234/v1");
  });

  it("tells the user which allow-list entry the operator needs once a URL is typed", () => {
    const { rerender } = render(<LocalServerPresets baseUrl="" onPick={vi.fn()} />);
    expect(screen.queryByText(/localOpenaiAllowListNote/)).toBeNull();

    rerender(<LocalServerPresets baseUrl="http://localhost:8080/v1" onPick={vi.fn()} />);
    expect(screen.getByText(/localOpenaiAllowListNote.*localhost:8080/)).toBeInTheDocument();
  });
});

describe("LocalServerProbe", () => {
  beforeEach(() => {
    listProviderModels.mockReset();
    probeLocalOpenAi.mockReset();
  });

  it("probes the first model the server lists and shows the advice", async () => {
    listProviderModels.mockResolvedValue([{ modelId: "qwen3-8b" }, { modelId: "llama-3.2-3b" }]);
    probeLocalOpenAi.mockResolvedValue({ model: "qwen3-8b", toolCallGrade: "STRUCTURED", advice: "Native tool calling works." });
    render(<LocalServerProbe />);

    await waitFor(() => expect(screen.getByRole("combobox")).toHaveTextContent("qwen3-8b"));
    fireEvent.click(screen.getByRole("button", { name: "localOpenaiProbeButton" }));

    expect(await screen.findByRole("status")).toHaveTextContent("Native tool calling works.");
    expect(probeLocalOpenAi).toHaveBeenCalledWith("qwen3-8b");
  });

  it("falls back to a free-text model field when the server lists nothing", async () => {
    listProviderModels.mockResolvedValue([]);
    probeLocalOpenAi.mockResolvedValue({ model: "my-model", toolCallGrade: null, advice: "could not be measured" });
    render(<LocalServerProbe />);

    const button = screen.getByRole("button", { name: "localOpenaiProbeButton" });
    expect(button).toBeDisabled();
    fireEvent.change(screen.getByLabelText("localOpenaiProbeModelLabel"), { target: { value: " my-model " } });
    fireEvent.click(button);

    await waitFor(() => expect(probeLocalOpenAi).toHaveBeenCalledWith("my-model"));
  });

  it("shows a plain error when the probe call fails", async () => {
    listProviderModels.mockRejectedValue(new Error("down"));
    probeLocalOpenAi.mockRejectedValue(new Error("boom"));
    render(<LocalServerProbe />);

    fireEvent.change(screen.getByLabelText("localOpenaiProbeModelLabel"), { target: { value: "m" } });
    fireEvent.click(screen.getByRole("button", { name: "localOpenaiProbeButton" }));

    expect(await screen.findByRole("alert")).toBeInTheDocument();
  });
});
