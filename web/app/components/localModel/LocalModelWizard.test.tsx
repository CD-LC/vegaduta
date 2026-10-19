import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import messages from "../../../messages/en.json";

// Real English strings with {placeholder} interpolation, so the tests read what a person would read.
vi.mock("../../lib/i18n", () => ({
  useTranslations: (ns: string) => (key: string, values?: Record<string, string | number>) => {
    const raw = (messages as unknown as Record<string, Record<string, string>>)[ns]?.[key] ?? `${ns}.${key}`;
    return raw.replace(/\{(\w+)\}/g, (_, k) => String(values?.[k] ?? `{${k}}`));
  },
}));
vi.mock("next/link", () => ({
  default: ({ children, href }: { children: React.ReactNode; href: string }) => <a href={href}>{children}</a>,
}));

import LocalModelWizard, { detectOs } from "./LocalModelWizard";
import LocalModelFirstRun from "./LocalModelFirstRun";
import { mockLocalModelApi, recommendationFixture } from "./fixtures";

afterEach(cleanup);

describe("LocalModelWizard", () => {
  it("shows hardware, the recommended model with honest numbers and its tool-calling grade", async () => {
    render(<LocalModelWizard api={mockLocalModelApi()} pollMs={1} />);
    expect(screen.getByTestId("lm-detecting")).toBeInTheDocument();
    await screen.findByTestId("lm-choices");
    expect(screen.getByText("16 GB memory")).toBeInTheDocument();
    expect(screen.getByText("Apple silicon (unified memory)")).toBeInTheDocument();
    const radios = screen.getAllByRole("radio");
    expect(radios).toHaveLength(5);
    expect(radios[0]).toBeChecked();
    expect(screen.getByText("Recommended")).toBeInTheDocument();
    expect(screen.getByText(/Download about 5\.2 GB - Needs about 8 GB of memory/)).toBeInTheDocument();
    expect(screen.getAllByText("Native tool calling").length).toBeGreaterThan(0);
    expect(screen.queryByTestId("lm-ram-warning")).toBeNull();
  });

  it("shows every catalog model in two groups, the ones that do not fit labelled with the memory gap", async () => {
    render(<LocalModelWizard api={mockLocalModelApi()} pollMs={1} />);
    await screen.findByTestId("lm-choices");
    const fits = screen.getByTestId("lm-group-fits");
    const more = screen.getByTestId("lm-group-needs-more");
    expect(fits.textContent).toContain("Fits your machine");
    expect(fits.querySelectorAll("input[type=radio]")).toHaveLength(3);
    expect(more.textContent).toContain("Needs more memory (from 24 GB, you have 16 GB)");
    expect(Array.from(more.querySelectorAll("input[type=radio]")).map((r) => (r as HTMLInputElement).value)).toEqual([
      "gpt-oss:20b",
      "llama3.3:70b",
    ]);
    expect(screen.getByTestId("lm-budget").textContent).toContain("About 12 GB available to a model");
  });

  it("shows a detail card for the selected model with size, memory versus detected, licence, thinking and best-for", async () => {
    render(<LocalModelWizard api={mockLocalModelApi()} pollMs={1} />);
    await screen.findByTestId("lm-choices");
    const detail = screen.getByTestId("lm-detail");
    expect(detail.getAttribute("data-tag")).toBe("qwen3:8b");
    expect(detail.textContent).toContain("8.2 billion parameters (Qwen)");
    expect(detail.textContent).toContain("needs about 8 GB; this machine has 16 GB, about 12 GB of it available to a model");
    expect(detail.textContent).toContain("40K tokens");
    expect(detail.textContent).toContain("Apache-2.0");
    expect(detail.textContent).toContain("yes: reasons before answering");
    expect(detail.textContent).toContain("coding help and tool-heavy agents");
    expect(detail.textContent).toContain("comfortable speed");
    fireEvent.click(screen.getByDisplayValue("llama3.3:70b"));
    const after = screen.getByTestId("lm-detail");
    expect(after.getAttribute("data-tag")).toBe("llama3.3:70b");
    expect(after.textContent).toContain("not enough");
    expect(after.textContent).toContain("very slow, or fails to load");
    expect(after.textContent).toContain("no: answers directly");
  });

  it("warns when the selected model needs more memory than the machine has, and only pulls it after an explicit acknowledgement", async () => {
    const api = mockLocalModelApi();
    const pull = vi.spyOn(api, "startLocalModelPull");
    render(<LocalModelWizard api={api} pollMs={1} />);
    await screen.findByTestId("lm-choices");
    fireEvent.click(screen.getByDisplayValue("llama3.3:70b"));
    expect(screen.getByTestId("lm-ram-warning").textContent).toContain("about 48 GB");
    expect(screen.getByTestId("lm-ram-warning").textContent).toContain("reports 16 GB");
    const start = screen.getByRole("button", { name: "Download anyway" });
    expect(start).toBeDisabled();
    fireEvent.click(screen.getByTestId("lm-ram-acknowledge"));
    expect(start).toBeEnabled();
    fireEvent.click(start);
    await screen.findByRole("progressbar", { name: "Download progress for llama3.3:70b" });
    expect(pull).toHaveBeenCalledWith("llama3.3:70b");
  });

  it("forgets the acknowledgement when a different model is picked", async () => {
    render(<LocalModelWizard api={mockLocalModelApi()} pollMs={1} />);
    await screen.findByTestId("lm-choices");
    fireEvent.click(screen.getByDisplayValue("llama3.3:70b"));
    fireEvent.click(screen.getByTestId("lm-ram-acknowledge"));
    fireEvent.click(screen.getByDisplayValue("gpt-oss:20b"));
    expect(screen.getByTestId("lm-ram-acknowledge")).not.toBeChecked();
    expect(screen.getByRole("button", { name: "Download anyway" })).toBeDisabled();
  });

  it("guides installation without downloading anything when Ollama is not reachable", async () => {
    const api = mockLocalModelApi({
      getLocalModelRecommendation: async () => ({
        ...recommendationFixture,
        ollama: { reachable: false, baseUrl: "http://localhost:11434", installedTags: [] },
      }),
    });
    render(<LocalModelWizard api={api} pollMs={1} />);
    const box = await screen.findByTestId("lm-ollama-missing");
    expect(box.textContent).toContain("http://localhost:11434");
    const links = Array.from(box.querySelectorAll("a")).map((a) => a.getAttribute("href"));
    expect(links).toEqual([
      "https://ollama.com/download/mac",
      "https://ollama.com/download/windows",
      "https://ollama.com/download/linux",
    ]);
    for (const a of box.querySelectorAll("a")) expect(a.getAttribute("rel")).toContain("noopener");
    expect(screen.getByRole("button", { name: "Download and set up" })).toBeDisabled();
  });

  it("re-checks for Ollama and enables setup once it appears", async () => {
    let reachable = false;
    const api = mockLocalModelApi({
      getLocalModelRecommendation: async () => ({
        ...recommendationFixture,
        ollama: { ...recommendationFixture.ollama, reachable },
      }),
    });
    render(<LocalModelWizard api={api} pollMs={1} />);
    await screen.findByTestId("lm-ollama-missing");
    reachable = true;
    fireEvent.click(screen.getByRole("button", { name: /Check again/ }));
    await waitFor(() => expect(screen.queryByTestId("lm-ollama-missing")).toBeNull());
    expect(screen.getByRole("button", { name: "Download and set up" })).toBeEnabled();
  });

  it("pulls with an accessible progress bar, probes, points the pool row at the model, then creates an agent on the pool", async () => {
    const api = mockLocalModelApi();
    const use = vi.spyOn(api, "useLocalModel");
    const create = vi.spyOn(api, "createAgent");
    render(<LocalModelWizard api={api} pollMs={1} />);
    await screen.findByTestId("lm-choices");
    fireEvent.click(screen.getByRole("button", { name: "Download and set up" }));
    const bar = await screen.findByRole("progressbar", { name: "Download progress for qwen3:8b" });
    expect(bar).toHaveAttribute("aria-valuemin", "0");
    expect(bar).toHaveAttribute("aria-valuemax", "100");
    expect(screen.getByRole("status")).toBeInTheDocument();

    await screen.findByTestId("lm-done");
    expect(use).toHaveBeenCalledTimes(1);
    expect(use).toHaveBeenCalledWith("qwen3:8b");
    expect(create).toHaveBeenCalledTimes(1);
    // The first agent runs on the pool like every other agent; no per-agent OLLAMA key is created.
    expect(create.mock.calls[0][0]).toMatchObject({ providerName: "PLATFORM_HOSTED", model: "platform-pool" });
    expect(use.mock.invocationCallOrder[0]).toBeLessThan(create.mock.invocationCallOrder[0]);
    expect(screen.getByTestId("lm-probe-summary").textContent).toContain("Native tool calling");
    expect(screen.getByRole("link", { name: "Open the agent" })).toHaveAttribute("href", "/agents?agentId=agent-1");
  });

  it("marks the model agents are serving and links to the runtime switch on Troubleshoot", async () => {
    const api = mockLocalModelApi({
      getLocalModelRecommendation: async () => ({
        ...recommendationFixture,
        servingModel: "qwen3:4b",
        ollama: { ...recommendationFixture.ollama, installedTags: ["qwen3:4b"] },
      }),
    });
    render(<LocalModelWizard api={api} pollMs={1} />);
    await screen.findByTestId("lm-choices");
    expect(screen.getByTestId("lm-serving").textContent).toContain("qwen3:4b");
    expect(screen.getByRole("link", { name: "Switch it on Troubleshoot" })).toHaveAttribute("href", "/troubleshoot");
    expect(screen.getByText("In use")).toBeInTheDocument();
  });

  it("skips the download for an installed model", async () => {
    const api = mockLocalModelApi({
      getLocalModelRecommendation: async () => ({
        ...recommendationFixture,
        ollama: { ...recommendationFixture.ollama, installedTags: ["qwen3:8b"] },
      }),
    });
    const pull = vi.spyOn(api, "startLocalModelPull");
    render(<LocalModelWizard api={api} pollMs={1} />);
    await screen.findByTestId("lm-choices");
    expect(screen.getByText("Already installed")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Set up with this model" }));
    await screen.findByTestId("lm-done");
    expect(pull).not.toHaveBeenCalled();
  });

  it("still creates the agent when the probe fails, and says the tool grade is unverified", async () => {
    const api = mockLocalModelApi({ probeLocalModel: async () => Promise.reject(new Error("probe 500")) });
    render(<LocalModelWizard api={api} pollMs={1} />);
    await screen.findByTestId("lm-choices");
    fireEvent.click(screen.getByRole("button", { name: "Download and set up" }));
    await screen.findByTestId("lm-done");
    expect(screen.getByTestId("lm-probe-unknown")).toBeInTheDocument();
  });

  it("reports a failed download with the backend message and retries", async () => {
    let fail = true;
    const api = mockLocalModelApi({
      getLocalModelPull: async () =>
        fail ? { status: "failed", percent: 12, message: "no space left on device" } : { status: "done", percent: 100, message: "" },
    });
    render(<LocalModelWizard api={api} pollMs={1} />);
    await screen.findByTestId("lm-choices");
    fireEvent.click(screen.getByRole("button", { name: "Download and set up" }));
    const err = await screen.findByTestId("lm-error");
    expect(err.textContent).toContain("The download failed");
    expect(err.textContent).toContain("no space left on device");
    fail = false;
    fireEvent.click(screen.getByRole("button", { name: /Try again/ }));
    await screen.findByTestId("lm-choices");
    fireEvent.click(screen.getByRole("button", { name: "Download and set up" }));
    await screen.findByTestId("lm-done");
  });

  it("shows an error state, not a blank panel, when detection fails", async () => {
    const api = mockLocalModelApi({ getLocalModelRecommendation: async () => Promise.reject(new Error("")) });
    render(<LocalModelWizard api={api} pollMs={1} />);
    const err = await screen.findByTestId("lm-error");
    expect(err.textContent).toContain("Could not check your hardware");
    expect(err.textContent).toContain("Something went wrong");
  });

  it("surfaces a failed pool-row switch as a create error and creates no agent", async () => {
    const api = mockLocalModelApi({ useLocalModel: async () => Promise.reject(new Error("The model server is not reachable.")) });
    const create = vi.spyOn(api, "createAgent");
    render(<LocalModelWizard api={api} pollMs={1} />);
    await screen.findByTestId("lm-choices");
    fireEvent.click(screen.getByRole("button", { name: "Download and set up" }));
    const err = await screen.findByTestId("lm-error");
    expect(err.textContent).toContain("Could not create the agent");
    expect(err.textContent).toContain("The model server is not reachable.");
    expect(create).not.toHaveBeenCalled();
  });

  it("still creates the agent on the pool when the default-model lookup is missing or fails", async () => {
    for (const getDefaultAgentModel of [undefined, async () => Promise.reject(new Error("boom"))]) {
      const api = mockLocalModelApi({ getDefaultAgentModel });
      const create = vi.spyOn(api, "createAgent");
      render(<LocalModelWizard api={api} pollMs={1} />);
      await screen.findByTestId("lm-choices");
      fireEvent.click(screen.getByRole("button", { name: "Download and set up" }));
      await screen.findByTestId("lm-done");
      expect(create.mock.calls[0][0]).toMatchObject({ providerName: "PLATFORM_HOSTED", model: "qwen3:8b" });
      cleanup();
    }
  });

  it("follows the workspace's own provider when it is not routed through the pool", async () => {
    const api = mockLocalModelApi({
      getDefaultAgentModel: async () => ({ providerName: "OPENAI", model: "gpt-5", source: "WORKING_PROVIDER" }),
    });
    const create = vi.spyOn(api, "createAgent");
    render(<LocalModelWizard api={api} pollMs={1} />);
    await screen.findByTestId("lm-choices");
    fireEvent.click(screen.getByRole("button", { name: "Download and set up" }));
    await screen.findByTestId("lm-done");
    expect(create.mock.calls[0][0]).toMatchObject({ providerName: "OPENAI", model: "gpt-5" });
  });

  it("exposes the steps as an ordered list with the current step marked", async () => {
    render(<LocalModelWizard api={mockLocalModelApi()} pollMs={1} />);
    await screen.findByTestId("lm-choices");
    const list = screen.getByRole("list", { name: "Setup progress" });
    expect(list.querySelector('[aria-current="step"]')?.textContent).toContain("Choose a model");
  });
});

describe("detectOs", () => {
  it("maps user agents to install links and returns null for the unknown", () => {
    expect(detectOs("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)")).toBe("mac");
    expect(detectOs("Mozilla/5.0 (Windows NT 10.0; Win64; x64)")).toBe("windows");
    expect(detectOs("Mozilla/5.0 (X11; Linux x86_64)")).toBe("linux");
    expect(detectOs("Mozilla/5.0 (Linux; Android 14)")).toBeNull();
    expect(detectOs("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X)")).toBeNull();
  });
});

describe("LocalModelFirstRun", () => {
  it("offers the wizard route", () => {
    render(<LocalModelFirstRun />);
    expect(screen.getByRole("link", { name: "Set up a local model" })).toHaveAttribute("href", "/providers/local-model");
  });
});
