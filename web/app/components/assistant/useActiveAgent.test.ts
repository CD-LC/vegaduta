import { beforeEach, describe, expect, it, vi } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";
import type { AgentResponse } from "../../lib/api/types";

vi.mock("../../lib/api/assistant", () => ({ assistantApi: { listAgents: vi.fn() } }));
import { assistantApi } from "../../lib/api/assistant";
import { useActiveAgent } from "./useActiveAgent";

const row = (id: string, name: string) => ({ id, name, providerName: "OLLAMA", model: "m" }) as unknown as AgentResponse;
const assistant = row("a-assist", "Assistant");
const other = row("a-other", "Researcher");

beforeEach(() => {
  window.localStorage.clear();
  window.history.replaceState(null, "", "/assistant");
  vi.mocked(assistantApi.listAgents).mockResolvedValue([assistant, other]);
});

describe("useActiveAgent", () => {
  it("starts on the Assistant and switches to another agent when one is picked", async () => {
    const { result } = renderHook(() => useActiveAgent(assistant, null));
    await waitFor(() => expect(result.current.agents).toHaveLength(2));
    expect(result.current.active).toMatchObject({ id: "a-assist", isAssistant: true });
    act(() => result.current.select("a-other"));
    expect(result.current.active).toMatchObject({ id: "a-other", isAssistant: false });
    expect(window.location.search).toBe("?agent=a-other");
  });

  it("a ?agent= link seeds the pick but never outvotes a later pick in the box", async () => {
    window.history.replaceState(null, "", "/assistant?agent=a-other");
    const { result } = renderHook(() => useActiveAgent(assistant, "a-other"));
    await waitFor(() => expect(result.current.active?.id).toBe("a-other"));
    // The URL still says a-other (the hook wrote it back), yet picking the Assistant must work.
    act(() => result.current.select("a-assist"));
    expect(result.current.active?.id).toBe("a-assist");
    expect(window.location.search).toBe("");
  });

  it("a new link to a different agent re-seeds the selection", async () => {
    const { result, rerender } = renderHook(({ q }) => useActiveAgent(assistant, q), { initialProps: { q: null as string | null } });
    await waitFor(() => expect(result.current.agents).toHaveLength(2));
    rerender({ q: "a-other" });
    await waitFor(() => expect(result.current.active?.id).toBe("a-other"));
  });

  it("falls back to the Assistant for an agent that no longer exists", async () => {
    const { result } = renderHook(() => useActiveAgent(assistant, "gone"));
    await waitFor(() => expect(result.current.agents).toHaveLength(2));
    expect(result.current.active?.id).toBe("a-assist");
  });
});
