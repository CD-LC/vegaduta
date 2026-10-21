import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import en from "../../../messages/en.json";

vi.mock("../../lib/i18n", () => {
  const cache: Record<string, (k: string) => string> = {};
  return {
    useTranslations: (ns: string) =>
      (cache[ns] ??= (key) => ((en as unknown as Record<string, Record<string, string>>)[ns] ?? {})[key] ?? key),
  };
});
vi.mock("next/link", () => ({
  default: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

import LocalModelTip, { isLocalProvider, LOCAL_TIP_STORAGE_KEY } from "./LocalModelTip";

describe("LocalModelTip", () => {
  beforeEach(() => window.localStorage.clear());

  it("knows a local provider by name", () => {
    expect(isLocalProvider("OLLAMA")).toBe(true);
    expect(isLocalProvider(" ollama ")).toBe(true);
    expect(isLocalProvider("OPENAI")).toBe(false);
    expect(isLocalProvider(null)).toBe(false);
  });

  it("points at the agent's settings when the model is local", () => {
    render(<LocalModelTip agentId="a 1" providerName="OLLAMA" />);
    expect(screen.getByTestId("local-model-tip")).toBeTruthy();
    expect(screen.getByRole("link", { name: en.assistant.localModelTipLink }).getAttribute("href")).toBe("/agents?agent=a%201");
  });

  it("stays out of the way for a hosted model", () => {
    render(<LocalModelTip agentId="a1" providerName="OPENAI" />);
    expect(screen.queryByTestId("local-model-tip")).toBeNull();
  });

  it("is dismissed for good", () => {
    const { unmount } = render(<LocalModelTip agentId="a1" providerName="OLLAMA" />);
    fireEvent.click(screen.getByRole("button", { name: en.assistant.localModelTipDismiss }));
    expect(screen.queryByTestId("local-model-tip")).toBeNull();
    expect(window.localStorage.getItem(LOCAL_TIP_STORAGE_KEY)).toBe("1");
    unmount();
    render(<LocalModelTip agentId="a1" providerName="OLLAMA" />);
    expect(screen.queryByTestId("local-model-tip")).toBeNull();
  });
});
