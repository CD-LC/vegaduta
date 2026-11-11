import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import en from "../../../messages/en.json";

vi.mock("../../lib/i18n", () => {
  const dict = en as unknown as Record<string, Record<string, string>>;
  return {
    useTranslations: (ns: string) => (key: string, vars?: Record<string, unknown>) =>
      (dict[ns]?.[key] ?? `${ns}.${key}`).replace(/\{(\w+)\}/g, (_, k: string) => String(vars?.[k] ?? `{${k}}`)),
  };
});
vi.mock("../../lib/api/localModels", () => ({
  localModelsApi: { getLocalModelSettings: vi.fn(), saveLocalModelSettings: vi.fn() },
}));
import { localModelsApi } from "../../lib/api/localModels";
import LocalModelSettingsCard from "./LocalModelSettingsCard";

const base = {
  servingModel: "qwen3.5:4b",
  reasoningEffort: "none",
  keepAlive: "30m",
  contextLength: null as number | null,
  loadedContext: 16384,
  wantedContext: 16384,
} as const;

beforeEach(() => vi.clearAllMocks());

describe("LocalModelSettingsCard", () => {
  it("shows the current choices and saves a new reasoning setting", async () => {
    vi.mocked(localModelsApi.getLocalModelSettings).mockResolvedValue({ ...base });
    vi.mocked(localModelsApi.saveLocalModelSettings).mockResolvedValue({ ...base, reasoningEffort: "high" });
    render(<LocalModelSettingsCard />);
    await waitFor(() => expect(screen.getByTestId("local-model-settings")).toBeInTheDocument());
    expect(screen.getByTestId("effort-none")).toHaveAttribute("aria-checked", "true");
    expect(screen.getByTestId("keep-30m")).toHaveAttribute("aria-checked", "true");
    fireEvent.click(screen.getByTestId("effort-high"));
    await waitFor(() => expect(screen.getByTestId("effort-high")).toHaveAttribute("aria-checked", "true"));
    expect(localModelsApi.saveLocalModelSettings).toHaveBeenCalledWith({ reasoningEffort: "high" });
    expect(screen.getByText(/applies from your next message/i)).toBeInTheDocument();
  });

  it("saves a keep-alive choice", async () => {
    vi.mocked(localModelsApi.getLocalModelSettings).mockResolvedValue({ ...base });
    vi.mocked(localModelsApi.saveLocalModelSettings).mockResolvedValue({ ...base, keepAlive: "-1" });
    render(<LocalModelSettingsCard />);
    await waitFor(() => screen.getByTestId("keep--1"));
    fireEvent.click(screen.getByTestId("keep--1"));
    await waitFor(() => expect(localModelsApi.saveLocalModelSettings).toHaveBeenCalledWith({ keepAlive: "-1" }));
  });

  it("warns when the runner loaded fewer tokens than asked for, and a click makes a bigger window", async () => {
    vi.mocked(localModelsApi.getLocalModelSettings).mockResolvedValue({ ...base, loadedContext: 4096 });
    vi.mocked(localModelsApi.saveLocalModelSettings).mockResolvedValue({
      ...base,
      contextLength: 16384,
      loadedContext: 16384,
    });
    render(<LocalModelSettingsCard />);
    await waitFor(() => screen.getByTestId("context-warning"));
    expect(screen.getByTestId("context-warning")).toHaveTextContent("Choose a context window above");
    expect(screen.getByTestId("context-line")).toHaveTextContent("Loaded with 4096 tokens");
    expect(screen.getByTestId("context-0")).toHaveAttribute("aria-checked", "true");
    fireEvent.click(screen.getByTestId("context-16384"));
    await waitFor(() => expect(localModelsApi.saveLocalModelSettings).toHaveBeenCalledWith({ contextLength: 16384 }));
    await waitFor(() => expect(screen.getByTestId("context-16384")).toHaveAttribute("aria-checked", "true"));
    expect(screen.queryByTestId("context-warning")).toBeNull();
  });

  it("shows the batch/parallel/timeout/tool-deferral knobs and saves each one", async () => {
    vi.mocked(localModelsApi.getLocalModelSettings).mockResolvedValue({
      ...base,
      numBatch: 512,
      numParallel: null,
      responseTimeoutMs: 120000,
      toolDeferral: true,
    });
    vi.mocked(localModelsApi.saveLocalModelSettings).mockImplementation(async (update) => ({ ...base, ...update }));
    render(<LocalModelSettingsCard />);
    await waitFor(() => screen.getByTestId("batch-512"));
    expect(screen.getByTestId("batch-512")).toHaveAttribute("aria-checked", "true");
    expect(screen.getByTestId("parallel-default")).toHaveAttribute("aria-checked", "true");
    expect(screen.getByTestId("timeout-120000")).toHaveAttribute("aria-checked", "true");
    expect(screen.getByTestId("timeout-120000")).toHaveTextContent("2 min");
    expect(screen.getByTestId("tooldeferral-on")).toHaveAttribute("aria-checked", "true");

    fireEvent.click(screen.getByTestId("batch-1024"));
    await waitFor(() => expect(localModelsApi.saveLocalModelSettings).toHaveBeenCalledWith({ numBatch: 1024 }));
    await waitFor(() => expect(screen.getByTestId("parallel-4")).toBeEnabled());
    fireEvent.click(screen.getByTestId("parallel-4"));
    await waitFor(() => expect(localModelsApi.saveLocalModelSettings).toHaveBeenCalledWith({ numParallel: 4 }));
    await waitFor(() => expect(screen.getByTestId("timeout-default")).toBeEnabled());
    fireEvent.click(screen.getByTestId("timeout-default"));
    await waitFor(() =>
      expect(localModelsApi.saveLocalModelSettings).toHaveBeenCalledWith({ responseTimeoutMs: null })
    );
    await waitFor(() => expect(screen.getByTestId("tooldeferral-off")).toBeEnabled());
    fireEvent.click(screen.getByTestId("tooldeferral-off"));
    await waitFor(() => expect(localModelsApi.saveLocalModelSettings).toHaveBeenCalledWith({ toolDeferral: false }));
  });

  it("renders nothing when there is no local model, and says when a save fails", async () => {
    vi.mocked(localModelsApi.getLocalModelSettings).mockResolvedValue({ ...base, servingModel: null });
    const { container, unmount } = render(<LocalModelSettingsCard />);
    await waitFor(() => expect(localModelsApi.getLocalModelSettings).toHaveBeenCalled());
    await waitFor(() => expect(container.querySelector("[data-testid=local-model-settings]")).toBeNull());
    unmount();

    vi.mocked(localModelsApi.getLocalModelSettings).mockResolvedValue({ ...base });
    vi.mocked(localModelsApi.saveLocalModelSettings).mockRejectedValue(new Error("boom"));
    render(<LocalModelSettingsCard />);
    await waitFor(() => screen.getByTestId("effort-low"));
    fireEvent.click(screen.getByTestId("effort-low"));
    await waitFor(() => expect(screen.getByRole("alert")).toBeInTheDocument());
  });
});
