import { describe, expect, it } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { useAgentDraftState } from "./useAgentDraftState";

describe("useAgentDraftState", () => {
  it("keeps the setter names and stable identities the workbench relies on", () => {
    const { result, rerender } = renderHook(() => useAgentDraftState("OPENAI"));
    const first = result.current.setName;
    act(() => result.current.setName("Bot"));
    act(() => result.current.setName((c) => `${c}!`));
    rerender();
    expect(result.current.name).toBe("Bot!");
    expect(result.current.setName).toBe(first);
    expect(result.current.draftStatus).toBe("dirty");
  });

  it("maps busy/error/success onto the status and resetDraft clears the form", () => {
    const { result } = renderHook(() => useAgentDraftState());
    act(() => result.current.setName("x"));
    act(() => result.current.setBusy(true));
    expect(result.current.busy).toBe(true);
    expect(result.current.draftStatus).toBe("saving");
    act(() => result.current.setBusy(false));
    act(() => result.current.setError("failed"));
    expect(result.current.draftStatus).toBe("error");
    act(() => result.current.resetDraft("GEMINI"));
    expect(result.current.name).toBe("");
    expect(result.current.providerName).toBe("GEMINI");
    expect(result.current.error).toBeNull();
    expect(result.current.draftStatus).toBe("clean");
  });
});
