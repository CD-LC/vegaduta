import { act, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { I18nProvider, useTranslations } from "./i18n";

function Label() {
  const t = useTranslations("agentGlance");
  return <span data-testid="label">{t("title")}</span>;
}

const pruned = { tour: { a: "b" } };
const full = { tour: { a: "b" }, agentGlance: { title: "Agent at a glance" } };

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe("I18nProvider lazy completion", () => {
  it("keeps the completed catalog when the layout re-renders with pruned props", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue({ ok: true, json: async () => ({ messages: full, en: null as unknown }) });
    vi.stubGlobal("fetch", fetchMock);
    const ui = (msgs: any) => (
      <I18nProvider locale="en" messages={msgs} enMessages={msgs} complete={false} version="v-keep">
        <Label />
      </I18nProvider>
    );
    const { rerender } = render(ui(pruned));
    await act(async () => {
      await Promise.resolve();
      await Promise.resolve();
    });
    await vi.waitFor(() => expect(screen.getByTestId("label").textContent).toBe("Agent at a glance"));

    // router.refresh(): same locale, fresh pruned objects from the server.
    rerender(ui({ ...pruned }));
    await act(async () => {});
    expect(screen.getByTestId("label").textContent).toBe("Agent at a glance");
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("retries a failed catalog fetch on its own", async () => {
    vi.useFakeTimers();
    const fetchMock = vi
      .fn()
      .mockRejectedValueOnce(new Error("offline"))
      .mockResolvedValue({ ok: true, json: async () => ({ messages: full, en: null as unknown }) });
    vi.stubGlobal("fetch", fetchMock);
    render(
      <I18nProvider locale="en" messages={pruned} enMessages={pruned} complete={false} version="v-retry">
        <Label />
      </I18nProvider>
    );
    expect(screen.getByTestId("label").textContent).toBe("agentGlance.title");
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2500);
    });
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(screen.getByTestId("label").textContent).toBe("Agent at a glance");
  });
});
