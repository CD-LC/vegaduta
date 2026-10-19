import { describe, expect, it } from "vitest";
import {
  buildClaudeDesktopConfig,
  buildCurlExample,
  buildCursorConfig,
  mcpEndpointUrl,
  normalizeMcpExposure,
} from "./mcpExposure";

describe("normalizeMcpExposure", () => {
  it("accepts the API shape", () => {
    expect(
      normalizeMcpExposure({ exposed: true, toolName: "agent_helper", featureEnabled: true, mcpPath: "/mcp" })
    ).toEqual({ exposed: true, toolName: "agent_helper", featureEnabled: true, mcpPath: "/mcp" });
  });

  it("treats a missing tool name as null and a missing flag as feature off", () => {
    expect(normalizeMcpExposure({ exposed: false, toolName: null })).toEqual({
      exposed: false,
      toolName: null,
      featureEnabled: false,
      mcpPath: "/mcp",
    });
  });

  it("rejects anything that is not the expected shape", () => {
    expect(normalizeMcpExposure(null)).toBeNull();
    expect(normalizeMcpExposure("x")).toBeNull();
    expect(normalizeMcpExposure({ toolName: "a" })).toBeNull();
    expect(normalizeMcpExposure({ exposed: "yes" })).toBeNull();
  });
});

describe("mcpEndpointUrl", () => {
  it("joins with exactly one slash", () => {
    expect(mcpEndpointUrl("https://api.example.com", "/mcp")).toBe("https://api.example.com/mcp");
    expect(mcpEndpointUrl("https://api.example.com/", "/mcp")).toBe("https://api.example.com/mcp");
    expect(mcpEndpointUrl("https://api.example.com", "mcp")).toBe("https://api.example.com/mcp");
  });
});

describe("connection snippets", () => {
  const url = "https://api.example.com/mcp";

  it("Cursor config is valid JSON with the url and a bearer header placeholder", () => {
    const parsed = JSON.parse(buildCursorConfig(url));
    expect(parsed.mcpServers.vegaduta.url).toBe(url);
    expect(parsed.mcpServers.vegaduta.headers.Authorization).toMatch(/^Bearer /);
  });

  it("Claude Desktop config goes through mcp-remote and keeps the key out of args", () => {
    const parsed = JSON.parse(buildClaudeDesktopConfig(url));
    const server = parsed.mcpServers.vegaduta;
    expect(server.command).toBe("npx");
    expect(server.args).toContain("mcp-remote");
    expect(server.args).toContain(url);
    expect(server.args.join(" ")).not.toContain("vmcp_");
    expect(server.env.AUTH_HEADER).toMatch(/^Bearer /);
  });

  it("curl example posts a tools/list call to the endpoint", () => {
    const curl = buildCurlExample(url);
    expect(curl).toContain(url);
    expect(curl).toContain("tools/list");
  });

  it("never embeds a real key", () => {
    for (const snippet of [buildCursorConfig(url), buildClaudeDesktopConfig(url), buildCurlExample(url)]) {
      expect(snippet).not.toMatch(/vmcp_[A-Za-z0-9]{8,}/);
    }
  });
});
