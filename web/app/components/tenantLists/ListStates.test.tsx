import { describe, expect, it, vi } from "vitest";
import { fireEvent, render as rtlRender, screen } from "@testing-library/react";
import type { ReactNode } from "react";
import { I18nProvider } from "../../lib/i18n";
import messages from "../../../messages/en.json";

const render = (ui: ReactNode) =>
  rtlRender(
    <I18nProvider locale="en" messages={messages} enMessages={messages}>
      {ui}
    </I18nProvider>
  );
import { ListError, ListSkeleton } from "./ListStates";

describe("ListStates", () => {
  it("renders skeleton rows with an accessible loading label", () => {
    render(<ListSkeleton rows={3} label="Loading jobs" />);
    const status = screen.getByRole("status", { name: "Loading jobs" });
    expect(status.children).toHaveLength(3);
  });

  it("shows the failure message and calls onRetry", () => {
    const retry = vi.fn();
    render(<ListError message="Boom" onRetry={retry} />);
    expect(screen.getByText("Boom")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Retry" }));
    expect(retry).toHaveBeenCalledTimes(1);
  });
});
