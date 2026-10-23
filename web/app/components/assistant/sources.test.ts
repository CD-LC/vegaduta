import { describe, expect, it } from "vitest";
import { replySources } from "./sources";

describe("replySources", () => {
  it("collects the pages a web tool opened or found, once each, in order", () => {
    const sources = replySources([
      { type: "tool_call", id: 1, tool: "web_search", args: '{"query":"monsoon 2026"}' },
      {
        type: "tool_result",
        id: 1,
        tool: "web_search",
        preview: "1. [IMD outlook](https://mausam.imd.gov.in/outlook) 2. https://www.example.com/news/monsoon, more",
      },
      { type: "tool_call", id: 2, tool: "fetch_web_page", args: '{"url":"https://www.example.com/news/monsoon"}' },
    ]);
    expect(sources).toEqual([
      { url: "https://mausam.imd.gov.in/outlook", site: "mausam.imd.gov.in", title: "IMD outlook" },
      { url: "https://www.example.com/news/monsoon", site: "example.com" },
    ]);
  });

  it("ignores links in tools that do not touch the web, and anything that is not http(s)", () => {
    expect(
      replySources([
        { type: "tool_result", id: 1, tool: "local_shell", preview: "see https://internal.example/x" },
        { type: "tool_result", id: 2, tool: "web_search", preview: "ftp://files.example/x javascript:alert(1)" },
        { type: "thinking", id: 3, tool: "web_search", message: "https://thought.example" },
      ])
    ).toEqual([]);
    expect(replySources(undefined)).toEqual([]);
  });

  it("caps the list", () => {
    const preview = Array.from({ length: 12 }, (_, i) => `https://s${i}.example.com/`).join(" ");
    expect(replySources([{ type: "tool_result", id: 1, tool: "web_search", preview }], 5)).toHaveLength(5);
  });
});
