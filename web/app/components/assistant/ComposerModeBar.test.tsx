import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import en from "../../../messages/en.json";

vi.mock("../../lib/i18n", () => {
  const cache: Record<string, (k: string, v?: Record<string, unknown>) => string> = {};
  return {
    useTranslations: (ns: string) =>
      (cache[ns] ??= (key, values) => {
        const raw = ((en as unknown as Record<string, Record<string, string>>)[ns] ?? {})[key] ?? key;
        return Object.entries(values ?? {}).reduce((s, [k, v]) => s.replace(`{${k}}`, String(v)), raw);
      }),
  };
});

import ComposerModeBar from "./ComposerModeBar";

describe("ComposerModeBar agent picker", () => {
  it("offers every agent and reports the one picked", () => {
    const onSelectAgent = vi.fn();
    render(
      <ComposerModeBar
        agents={[
          { id: "a", name: "Assistant" },
          { id: "b", name: "KUVEMPU" },
        ]}
        activeAgentId="a"
        assistantId="a"
        onSelectAgent={onSelectAgent}
        mode="chat"
        onMode={() => {}}
        modes={{ background: true, task: true }}
        disabled={false}
      />
    );
    fireEvent.click(screen.getByTestId("assistant-agent-picker"));
    fireEvent.click(screen.getByRole("option", { name: "KUVEMPU" }));
    expect(onSelectAgent).toHaveBeenCalledWith("b");
  });
});
