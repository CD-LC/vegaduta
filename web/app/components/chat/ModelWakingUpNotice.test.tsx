import { describe, expect, it, vi, afterEach } from "vitest";
import { act, render, screen } from "@testing-library/react";
import { I18nProvider } from "../../lib/i18n";
import en from "../../../messages/en.json";
import { ModelWakingUpNotice, WAKING_UP_AFTER_MS } from "./ModelWakingUpNotice";

function renderNotice(active: boolean, startedAt: number) {
  return render(
    <I18nProvider locale="en" messages={en} enMessages={en}>
      <ModelWakingUpNotice active={active} startedAt={startedAt} />
    </I18nProvider>
  );
}

afterEach(() => vi.useRealTimers());

describe("ModelWakingUpNotice", () => {
  it("says nothing for a reply that starts quickly", () => {
    vi.useFakeTimers();
    renderNotice(true, Date.now());
    act(() => {
      vi.advanceTimersByTime(WAKING_UP_AFTER_MS - 2000);
    });
    expect(screen.queryByRole("status")).toBeNull();
  });

  it("explains the wait once the first reply is slow", () => {
    vi.useFakeTimers();
    renderNotice(true, Date.now());
    act(() => {
      vi.advanceTimersByTime(WAKING_UP_AFTER_MS + 1000);
    });
    expect(screen.getByRole("status")).toHaveTextContent(/starting up/i);
  });

  it("is gone when the turn is not waiting", () => {
    renderNotice(false, Date.now() - 60_000);
    expect(screen.queryByRole("status")).toBeNull();
  });
});
