// AgentTaskPanel — the states a person can land in before and around a run.
//
// The loop itself is covered in lib/agentkit/loop.test.ts against fake hosts.
// What is pinned here is the UI's half of the promises: the terminal limit is
// stated up front, a missing model or folder is explained with a next step
// (never a dead button with no reason), and a write is applied ONLY from the
// Apply click, after the browser granted write access inside that click.

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";

import type { LocalRepoHandle } from "../../lib/localcode/types";
import type { AgentModel } from "../../lib/agentkit/types";

const resolveEdgeAgentModel = vi.fn();
const ensureWritePermission = vi.fn();

vi.mock("../../lib/agentkit/edgeModel", () => ({ resolveEdgeAgentModel: () => resolveEdgeAgentModel() }));
vi.mock("../../lib/localcode/fsAccess", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../../lib/localcode/fsAccess")>()),
  ensureWritePermission: (...args: unknown[]) => ensureWritePermission(...args),
  // The host under the panel: one file, in memory.
  listCodeFiles: async (): Promise<unknown[]> => [],
  writeLocalFileResult: async () => ({ ok: true }),
}));
vi.mock("next/link", () => ({ default: ({ children, href }: { children: React.ReactNode; href: string }) => <a href={href}>{children}</a> }));

import AgentTaskPanel from "./AgentTaskPanel";

function domError(name: string): Error {
  const e = new Error(name);
  e.name = name;
  return e;
}

/** A folder holding exactly a.ts. */
function repo(): LocalRepoHandle {
  const text = "const a = 1;\n";
  const bytes = new TextEncoder().encode(text);
  const dir = {
    kind: "directory",
    name: "proj",
    async getDirectoryHandle() {
      throw domError("NotFoundError");
    },
    async getFileHandle(name: string) {
      if (name !== "a.ts") throw domError("NotFoundError");
      return {
        kind: "file",
        async getFile() {
          return { size: bytes.length, slice: () => ({ arrayBuffer: async () => bytes.buffer }), text: async () => text };
        },
      };
    },
    entries: () => ({ async *[Symbol.asyncIterator]() {} }),
  };
  return { id: "repo-ui", name: "proj", dir } as unknown as LocalRepoHandle;
}

function model(replies: string[]): AgentModel {
  let i = 0;
  return {
    id: "test-model",
    contextWindow: 4096,
    async complete() {
      const text = replies[Math.min(i, replies.length - 1)];
      i += 1;
      return { ok: true, text };
    },
  };
}

describe("AgentTaskPanel", () => {
  beforeEach(() => {
    localStorage.clear();
    resolveEdgeAgentModel.mockReset();
    ensureWritePermission.mockReset();
  });
  afterEach(() => cleanup());

  it("says up front that it cannot run commands, and asks for a folder before anything else", async () => {
    resolveEdgeAgentModel.mockResolvedValue({ ok: true, model: model(["done"]) });
    render(<AgentTaskPanel repo={null} />);
    expect(screen.getByText(/cannot run terminal commands/i)).toBeTruthy();
    expect(screen.getByText(/Open a folder above first/i)).toBeTruthy();
    expect((screen.getByRole("button", { name: /start/i }) as HTMLButtonElement).disabled).toBe(true);
  });

  it("explains a missing model with a way forward instead of a silent disabled button", async () => {
    resolveEdgeAgentModel.mockResolvedValue({ ok: false, reason: "no-model", detail: "No on-device model is downloaded yet." });
    render(<AgentTaskPanel repo={repo()} />);
    await waitFor(() => expect(screen.getByText(/No on-device model is downloaded yet/)).toBeTruthy());
    expect(screen.getByRole("link", { name: /Open VegaDūta Edge/ }).getAttribute("href")).toBe("/edge");
  });

  it("notices a model downloaded in another tab when the person comes back — no reload needed", async () => {
    resolveEdgeAgentModel
      .mockResolvedValueOnce({ ok: false, reason: "no-model", detail: "No on-device model is downloaded yet." })
      .mockResolvedValue({ ok: true, model: model(["done"]) });
    render(<AgentTaskPanel repo={repo()} />);
    await waitFor(() => expect(screen.getByText(/No on-device model is downloaded yet/)).toBeTruthy());
    fireEvent.change(screen.getByRole("textbox"), { target: { value: "do a thing" } });
    expect((screen.getByRole("button", { name: /start/i }) as HTMLButtonElement).disabled).toBe(true);

    // The person downloads a model on /edge in another tab and returns.
    fireEvent.focus(window);
    await waitFor(() => expect(screen.getByText(/On-device model ready/)).toBeTruthy());
    expect((screen.getByRole("button", { name: /start/i }) as HTMLButtonElement).disabled).toBe(false);

    // Once ready, focus changes stop probing.
    const calls = resolveEdgeAgentModel.mock.calls.length;
    fireEvent.focus(window);
    expect(resolveEdgeAgentModel.mock.calls.length).toBe(calls);
  });

  it('offers "Check again" next to a missing model, and it re-runs the probe', async () => {
    resolveEdgeAgentModel
      .mockResolvedValueOnce({ ok: false, reason: "probe-failed", detail: "This device could not be checked." })
      .mockResolvedValue({ ok: true, model: model(["done"]) });
    render(<AgentTaskPanel repo={repo()} />);
    await waitFor(() => expect(screen.getByText(/could not be checked/)).toBeTruthy());
    fireEvent.click(screen.getByRole("button", { name: "Check again" }));
    await waitFor(() => expect(screen.getByText(/On-device model ready/)).toBeTruthy());
  });

  it("does not offer a re-check when on-device AI is switched off for the deployment", async () => {
    resolveEdgeAgentModel.mockResolvedValue({ ok: false, reason: "edge-off", detail: "On-device AI is switched off for this deployment." });
    render(<AgentTaskPanel repo={repo()} />);
    await waitFor(() => expect(screen.getByText(/switched off/)).toBeTruthy());
    expect(screen.queryByRole("button", { name: "Check again" })).toBeNull();
    expect(screen.queryByRole("link", { name: /Open VegaDūta Edge/ })).toBeNull();
  });

  it("a double-click on Start does not land on Stop", async () => {
    resolveEdgeAgentModel.mockResolvedValue({ ok: true, model: model(["All done, nothing needed changing."]) });
    render(<AgentTaskPanel repo={repo()} />);
    await waitFor(() => expect(screen.getByText(/On-device model ready/)).toBeTruthy());
    fireEvent.change(screen.getByRole("textbox"), { target: { value: "look around" } });
    const startButton = screen.getByRole("button", { name: /start/i });
    fireEvent.click(startButton);
    // The second click of the double-click hits the SAME element, which is now
    // the disabled "Running…" button — Stop is a different button.
    expect((startButton as HTMLButtonElement).disabled).toBe(true);
    expect(startButton.isConnected).toBe(true);
    fireEvent.click(startButton);
    await waitFor(() => expect(screen.getByText(/All done, nothing needed changing/)).toBeTruthy());
    expect(screen.queryByText(/^Stopped/)).toBeNull();
  });

  it("survives a readiness probe that rejects", async () => {
    resolveEdgeAgentModel.mockRejectedValue(new Error("boom"));
    render(<AgentTaskPanel repo={repo()} />);
    await waitFor(() => expect(screen.getByText(/could not be checked/)).toBeTruthy());
  });

  it("shows the diff and writes only after Apply, with write access granted inside that click", async () => {
    const edit = '<agent_call>{"name":"edit_file","arguments":{"path":"a.ts","old_text":"const a = 1;","new_text":"const a = 2;"}}</agent_call>';
    resolveEdgeAgentModel.mockResolvedValue({ ok: true, model: model([edit, "Changed a to 2. Not verified — run your tests."]) });
    ensureWritePermission.mockResolvedValueOnce(false).mockResolvedValue(true);

    render(<AgentTaskPanel repo={repo()} />);
    await waitFor(() => expect(screen.getByText(/On-device model ready/)).toBeTruthy());
    fireEvent.change(screen.getByRole("textbox"), { target: { value: "set a to 2" } });
    fireEvent.click(screen.getByRole("button", { name: /start/i }));

    await waitFor(() => expect(screen.getByText("Waiting for your approval")).toBeTruthy());
    expect(screen.getByText("+const a = 2;")).toBeTruthy();
    expect(ensureWritePermission).not.toHaveBeenCalled();

    // First Apply: the browser refuses. The proposal must stay on screen.
    fireEvent.click(screen.getByRole("button", { name: "Apply to my file" }));
    await waitFor(() => expect(ensureWritePermission).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(screen.getByText(/Write permission wasn.t granted/)).toBeTruthy());
    expect(screen.getByText("Waiting for your approval")).toBeTruthy();

    // Second Apply: granted → the run carries on to its answer.
    fireEvent.click(screen.getByRole("button", { name: "Apply to my file" }));
    await waitFor(() => expect(screen.getByText(/Changed a to 2/)).toBeTruthy());
    expect(screen.queryByText("Waiting for your approval")).toBeNull();
    // The meter rendered the report of the prompt that was sent.
    expect(screen.getByText(/prompt tokens/)).toBeTruthy();
  });

  it("Stop works while an approval is pending, and nothing is written", async () => {
    const edit = '<agent_call>{"name":"edit_file","arguments":{"path":"a.ts","old_text":"const a = 1;","new_text":"const a = 2;"}}</agent_call>';
    resolveEdgeAgentModel.mockResolvedValue({ ok: true, model: model([edit, "done"]) });
    render(<AgentTaskPanel repo={repo()} />);
    await waitFor(() => expect(screen.getByText(/On-device model ready/)).toBeTruthy());
    fireEvent.change(screen.getByRole("textbox"), { target: { value: "set a to 2" } });
    fireEvent.click(screen.getByRole("button", { name: /start/i }));
    await waitFor(() => expect(screen.getByText("Waiting for your approval")).toBeTruthy());

    fireEvent.click(screen.getByRole("button", { name: /^stop$/i }));
    await waitFor(() => expect(screen.getByText(/Stopped at your request|Stopped\. Nothing further/)).toBeTruthy());
    expect(ensureWritePermission).not.toHaveBeenCalled();
    expect(screen.queryByText("Waiting for your approval")).toBeNull();
  });
});
