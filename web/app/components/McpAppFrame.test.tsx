// McpAppFrame: flag-off no-op, collapsed-by-default / lazy fetch, the per-user disable switch, the fallback when the
// app is unavailable or invalid, and the sandbox attributes. The postMessage security rules themselves are covered
// in lib/mcpApps/appBridge.test.ts; here we only check the component wires them to the real iframe window.

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";

const getMcpApp = vi.fn();
const callMcpAppTool = vi.fn();

vi.mock("../lib/i18n", () => ({
  useTranslations: () => (key: string, values?: Record<string, unknown>) =>
    values ? `${key}:${JSON.stringify(values)}` : key,
}));
vi.mock("../lib/api", () => ({
  api: {
    getMcpApp: (...a: unknown[]) => getMcpApp(...a),
    callMcpAppTool: (...a: unknown[]) => callMcpAppTool(...a),
  },
}));

const pointer = { appId: "abcdefghijklmnopqrstuvwx", server: "weather", tool: "forecast", uri: "ui://weather/forecast" };
const view = {
  appId: pointer.appId,
  serverName: "weather",
  tool: "forecast",
  uri: pointer.uri,
  mimeType: "text/html;profile=mcp-app",
  html: "<p>hello</p>",
  csp: "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data: https:; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'",
  toolInput: '{"city":"Pune"}',
  toolResult: '{"temp":31}',
  toolResultTruncated: false,
};

/** jsdom loads a srcdoc iframe asynchronously and may swap its contentWindow; real browsers keep it stable.
 *  Let that settle before a test captures `iframe.contentWindow` as a message source. */
async function settleFrame(): Promise<void> {
  await act(async () => {
    await new Promise((r) => setTimeout(r, 50));
  });
}

async function load(flag: "1" | "0") {
  vi.resetModules();
  if (flag === "1") vi.stubEnv("NEXT_PUBLIC_MCP_APPS", "1");
  else vi.unstubAllEnvs();
  return (await import("./McpAppFrame")).default;
}

beforeEach(() => {
  getMcpApp.mockReset();
  callMcpAppTool.mockReset();
  try {
    window.localStorage.clear();
  } catch {
    /* ignore */
  }
});
afterEach(() => {
  cleanup();
  vi.unstubAllEnvs();
});

describe("McpAppFrame", () => {
  it("renders nothing and fetches nothing with the flag off", async () => {
    const McpAppFrame = await load("0");
    const { container } = render(<McpAppFrame pointer={pointer} agentId="agent-1" />);
    expect(container).toBeEmptyDOMElement();
    expect(getMcpApp).not.toHaveBeenCalled();
  });

  it("is collapsed and labelled with the server, and loads nothing until opened", async () => {
    const McpAppFrame = await load("1");
    render(<McpAppFrame pointer={pointer} agentId="agent-1" />);
    expect(screen.getByTestId("mcp-app-card")).toBeInTheDocument();
    expect(screen.getByText(`label:${JSON.stringify({ server: "weather" })}`)).toBeInTheDocument();
    expect(document.querySelector("iframe")).toBeNull();
    expect(getMcpApp).not.toHaveBeenCalled();
  });

  it("opens into a sandboxed, origin-less iframe under the core CSP", async () => {
    getMcpApp.mockResolvedValue(view);
    const McpAppFrame = await load("1");
    render(<McpAppFrame pointer={pointer} agentId="agent-1" />);
    fireEvent.click(screen.getByText("show"));
    await waitFor(() => expect(document.querySelector("iframe")).not.toBeNull());
    await settleFrame();
    const iframe = document.querySelector("iframe") as HTMLIFrameElement;
    expect(getMcpApp).toHaveBeenCalledWith(pointer.appId);
    expect(iframe.getAttribute("sandbox")).toBe("allow-scripts");
    expect(iframe.getAttribute("referrerpolicy")).toBe("no-referrer");
    expect(iframe.getAttribute("srcdoc")).toContain("Content-Security-Policy");
    expect(iframe.getAttribute("srcdoc")).toContain("connect-src 'none'");
    expect(iframe.getAttribute("srcdoc")).toContain("<p>hello</p>");
    expect(iframe.getAttribute("src")).toBeNull();
  });

  it("falls back to a note when the app is unavailable", async () => {
    getMcpApp.mockResolvedValue(null);
    const McpAppFrame = await load("1");
    render(<McpAppFrame pointer={pointer} agentId="agent-1" />);
    fireEvent.click(screen.getByText("show"));
    await waitFor(() => expect(screen.getByTestId("mcp-app-failed")).toBeInTheDocument());
    expect(document.querySelector("iframe")).toBeNull();
  });

  it("falls back when the payload is invalid: wrong mime, non-ui uri, oversize", async () => {
    const McpAppFrame = await load("1");
    for (const bad of [
      { ...view, mimeType: "text/html" },
      { ...view, uri: "https://evil.example/x" },
      { ...view, uri: "ui://other/view" },
      { ...view, html: "x".repeat(600 * 1024) },
    ]) {
      getMcpApp.mockResolvedValue(bad);
      const { unmount } = render(<McpAppFrame pointer={pointer} agentId="agent-1" />);
      fireEvent.click(screen.getByText("show"));
      await waitFor(() => expect(screen.getByTestId("mcp-app-failed")).toBeInTheDocument());
      expect(document.querySelector("iframe")).toBeNull();
      unmount();
    }
  });

  it("'Disable apps' hides the app, persists in localStorage, and can be undone", async () => {
    getMcpApp.mockResolvedValue(view);
    const McpAppFrame = await load("1");
    render(<McpAppFrame pointer={pointer} agentId="agent-1" />);
    fireEvent.click(screen.getByText("disable"));
    expect(window.localStorage.getItem("vd.mcpApps.disabled")).toBe("1");
    expect(screen.getByTestId("mcp-app-disabled")).toBeInTheDocument();
    expect(screen.queryByTestId("mcp-app-card")).toBeNull();
    fireEvent.click(screen.getByText("enable"));
    expect(window.localStorage.getItem("vd.mcpApps.disabled")).toBeNull();
    expect(screen.getByTestId("mcp-app-card")).toBeInTheDocument();
  });

  it("starts disabled when the stored preference says so, without fetching", async () => {
    window.localStorage.setItem("vd.mcpApps.disabled", "1");
    const McpAppFrame = await load("1");
    render(<McpAppFrame pointer={pointer} agentId="agent-1" />);
    expect(screen.getByTestId("mcp-app-disabled")).toBeInTheDocument();
    expect(getMcpApp).not.toHaveBeenCalled();
  });

  it("only listens to the frame's own window, and a user-confirmed ui/message goes to the composer", async () => {
    getMcpApp.mockResolvedValue(view);
    const onAddMessage = vi.fn();
    const McpAppFrame = await load("1");
    render(<McpAppFrame pointer={pointer} agentId="agent-1" onAddMessage={onAddMessage} />);
    fireEvent.click(screen.getByText("show"));
    await waitFor(() => expect(document.querySelector("iframe")).not.toBeNull());
    await settleFrame();
    const iframe = document.querySelector("iframe") as HTMLIFrameElement;
    const msg = { jsonrpc: "2.0", id: 1, method: "ui/message", params: { text: "hello there" } };

    // From the parent window itself (not the frame): ignored, nothing to confirm.
    act(() => {
      window.dispatchEvent(new MessageEvent("message", { data: msg, origin: "null", source: window }));
    });
    expect(screen.queryByTestId("mcp-app-confirm")).toBeNull();

    // From the frame but with a real origin: ignored.
    act(() => {
      window.dispatchEvent(new MessageEvent("message", { data: msg, origin: "https://evil.example", source: iframe.contentWindow }));
    });
    expect(screen.queryByTestId("mcp-app-confirm")).toBeNull();

    // From the frame, opaque origin: asks the person, and only adds the text on Allow.
    act(() => {
      window.dispatchEvent(new MessageEvent("message", { data: msg, origin: "null", source: iframe.contentWindow }));
    });
    await waitFor(() => expect(screen.getByTestId("mcp-app-confirm")).toBeInTheDocument());
    expect(onAddMessage).not.toHaveBeenCalled();
    fireEvent.click(screen.getByText("allow"));
    await waitFor(() => expect(onAddMessage).toHaveBeenCalledWith("hello there"));
  });

  it("collapsing tears the frame down and a pending confirmation counts as 'no'", async () => {
    getMcpApp.mockResolvedValue(view);
    const onAddMessage = vi.fn();
    const McpAppFrame = await load("1");
    render(<McpAppFrame pointer={pointer} agentId="agent-1" onAddMessage={onAddMessage} />);
    fireEvent.click(screen.getByText("show"));
    await waitFor(() => expect(document.querySelector("iframe")).not.toBeNull());
    await settleFrame();
    const iframe = document.querySelector("iframe") as HTMLIFrameElement;
    act(() => {
      window.dispatchEvent(
        new MessageEvent("message", {
          data: { jsonrpc: "2.0", id: 1, method: "ui/message", params: { text: "late" } },
          origin: "null",
          source: iframe.contentWindow,
        })
      );
    });
    await waitFor(() => expect(screen.getByTestId("mcp-app-confirm")).toBeInTheDocument());
    fireEvent.click(screen.getByText("hide"));
    await waitFor(() => expect(document.querySelector("iframe")).toBeNull());
    expect(screen.queryByTestId("mcp-app-confirm")).toBeNull();
    expect(onAddMessage).not.toHaveBeenCalled();
  });
});
