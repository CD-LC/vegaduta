// "Expose as MCP tool": core does not run the MCP server on a personal install, so the control is not
// mounted there and does not even look the exposure up. Elsewhere it renders the switch.
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

const api = vi.hoisted(() => ({ getMcpExposure: vi.fn(), setMcpExposure: vi.fn() }));

vi.mock("../lib/api", () => ({ api, API_BASE: "https://core.example.test" }));
vi.mock("../lib/i18n", () => ({ useTranslations: () => (key: string) => key }));
vi.mock("next/link", () => ({ default: ({ children, href }: { children?: unknown; href: string }) => <a href={href}>{children as never}</a> }));

import McpExposureField from "./McpExposureField";

const EXPOSURE = { exposed: false, featureEnabled: true, toolName: "support_bot", mcpPath: "/mcp" };

afterEach(() => {
  cleanup();
  vi.unstubAllEnvs();
  vi.clearAllMocks();
});

describe("McpExposureField", () => {
  it("renders nothing and makes no request on a personal install", () => {
    vi.stubEnv("NEXT_PUBLIC_EDITION", "personal");
    api.getMcpExposure.mockResolvedValue(EXPOSURE);
    const { container } = render(<McpExposureField kind="agents" resourceId="a1" />);
    expect(container).toBeEmptyDOMElement();
    expect(api.getMcpExposure).not.toHaveBeenCalled();
  });

  it("renders the switch on a shared install", async () => {
    vi.stubEnv("NEXT_PUBLIC_EDITION", "full");
    api.getMcpExposure.mockResolvedValue(EXPOSURE);
    render(<McpExposureField kind="agents" resourceId="a1" />);
    expect(await screen.findByTestId("mcp-exposure")).toBeTruthy();
    expect(api.getMcpExposure).toHaveBeenCalledWith("agents", "a1");
  });

  it("still self-hides when the exposure endpoint is missing", async () => {
    vi.stubEnv("NEXT_PUBLIC_EDITION", "lite");
    api.getMcpExposure.mockRejectedValue(Object.assign(new Error("nope"), { status: 404 }));
    const { container } = render(<McpExposureField kind="workflows" resourceId="w1" />);
    await vi.waitFor(() => expect(api.getMcpExposure).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
  });
});
