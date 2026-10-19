import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

const report = vi.hoisted(() => vi.fn());
vi.mock("./lib/clientLogger", () => ({ reportClientError: report }));
vi.mock("./components/Logo", () => ({ default: (): null => null }));
vi.mock("next/link", () => ({
  default: ({ children, href }: { children?: unknown; href?: string }) => <a href={href ?? "#"}>{children as never}</a>,
}));

import RouteError from "./error";

afterEach(() => {
  cleanup();
  report.mockReset();
});

describe("route error boundary", () => {
  it("shows the generic message plus the real error, and reports it server-side", () => {
    vi.spyOn(console, "error").mockImplementation(() => undefined);
    const err = Object.assign(new Error("Cannot read properties of undefined (reading 'map')"), { digest: "abc123" });
    render(<RouteError error={err} reset={() => undefined} />);
    expect(screen.getByText("Something went wrong on this screen.")).toBeTruthy();
    expect(screen.getByTestId("route-error-details").textContent).toContain("reading 'map'");
    expect(report).toHaveBeenCalledWith("client_error", expect.stringContaining("reading 'map'"), expect.objectContaining({ source: "route-error-boundary", digest: "abc123" }));
  });
});
