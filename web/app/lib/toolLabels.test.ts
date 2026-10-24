import { describe, expect, it } from "vitest";
import en from "../../messages/en.json";
import { KNOWN_TOOL_LABELS, parseToolName, toolLabel } from "./toolLabels";

const catalog = (en as unknown as Record<string, Record<string, string>>).toolLabels;
const t = (key: string, values?: Record<string, string | number>) =>
  Object.entries(values ?? {}).reduce((s, [k, v]) => s.replace(`{${k}}`, String(v)), catalog[key] ?? key);

describe("parseToolName", () => {
  it("drops the action prefix, core's trailing annotation and splits MCP names", () => {
    expect(parseToolName("mcp_tool.web_search")).toEqual({ raw: "mcp_tool.web_search", tool: "web_search", server: null });
    expect(parseToolName("web_search [queued behind: web_search]").tool).toBe("web_search");
    expect(parseToolName("mcp__github__create_issue")).toMatchObject({ tool: "create_issue", server: "github" });
    expect(parseToolName("mcp_tool.mcp__github__create_issue")).toMatchObject({ tool: "create_issue", server: "github" });
    expect(parseToolName(null)).toEqual({ raw: "", tool: "", server: null });
  });
});

describe("toolLabel", () => {
  it("gives built-in tools a readable label and a what-it-does line, keeping the raw name", () => {
    expect(toolLabel("mcp_tool.web_search", t)).toEqual({
      label: "Search the web",
      description: expect.stringContaining("web"),
      raw: "mcp_tool.web_search",
    });
    expect(toolLabel("fetch_web_page", t).label).toBe("Open a web page");
  });

  it("names an MCP tool after its server and leaves unknown tools as they are", () => {
    expect(toolLabel("mcp__github__create_issue", t)).toEqual({
      label: "create_issue (github)",
      description: null,
      raw: "mcp__github__create_issue",
    });
    expect(toolLabel("mcp_tool.send_email", t)).toEqual({ label: "send_email", description: null, raw: "mcp_tool.send_email" });
  });

  it("has an English label and description for every known tool", () => {
    for (const name of KNOWN_TOOL_LABELS) {
      expect(catalog[`label_${name}`], name).toBeTruthy();
      expect(catalog[`does_${name}`], name).toBeTruthy();
    }
    expect(catalog.mcpTool).toContain("{server}");
  });
});
