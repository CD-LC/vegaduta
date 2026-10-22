import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render } from "@testing-library/react";

const redirect = vi.hoisted(() =>
  vi.fn((to: string) => {
    throw new Error(`NEXT_REDIRECT:${to}`);
  })
);
const shellMounted = vi.hoisted(() => vi.fn());

vi.mock("next/navigation", () => ({ redirect, usePathname: () => "/platform-admin" }));
vi.mock("./components/dashboard/dashboardStyles", () => ({}));
// The overview itself is out of scope here: only whether it mounts.
vi.mock("./components/AdminShell", () => ({
  default: () => {
    shellMounted();
    return <div>overview shell</div>;
  },
}));
vi.mock("./lib/api", () => ({ api: new Proxy({}, { get: () => async () => ({}) }) }));
vi.mock("./lib/adminApi/spend", () => ({ getSpendSummary: async () => ({}) }));

import OverviewRoute from "./page";

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
  vi.unstubAllEnvs();
  delete window.__ENV;
});

describe("/platform-admin overview", () => {
  it("redirects to the LLM pools on a personal install without mounting the overview", () => {
    vi.stubEnv("NEXT_PUBLIC_EDITION", "personal");
    expect(() => render(<OverviewRoute />)).toThrow("NEXT_REDIRECT:/platform-admin/llm-providers");
    expect(redirect).toHaveBeenCalledWith("/platform-admin/llm-providers");
    expect(shellMounted).not.toHaveBeenCalled();
  });

  it("renders the overview on the full tier", () => {
    vi.stubEnv("NEXT_PUBLIC_EDITION", "full");
    render(<OverviewRoute />);
    expect(redirect).not.toHaveBeenCalled();
    expect(shellMounted).toHaveBeenCalled();
  });
});
