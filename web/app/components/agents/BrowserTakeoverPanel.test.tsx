// Browser takeover panel: invisible until core switches the feature on (flag-off identity), then the request card,
// the "you are in control" banner with a countdown and a hand-back button, and the input relay (click mapped to
// frame pixels, keys, paste) with the takeover token. i18n is mocked to echo keys, so this asserts behaviour, not copy.

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";

vi.mock("../../lib/i18n", () => ({
  useTranslations: () => (key: string, v?: Record<string, string | number>) =>
    v ? `${key}:${Object.values(v).join(",")}` : key,
}));

const mockApi = vi.hoisted(() => ({
  getBrowserTakeoverCapabilities: vi.fn(),
  getBrowserTakeoverStatus: vi.fn(),
  pauseBrowserSession: vi.fn(),
  beginBrowserTakeover: vi.fn(),
  refreshBrowserTakeoverToken: vi.fn(),
  handBackBrowserTakeover: vi.fn(),
  sendBrowserTakeoverInput: vi.fn(),
  openBrowserTakeoverStream: vi.fn(),
}));

vi.mock("../../lib/api", () => ({ api: mockApi }));

import BrowserTakeoverPanel from "./BrowserTakeoverPanel";
import { resetBrowserTakeoverCapabilitiesCache } from "./useBrowserTakeoverCapabilities";

function view(over: Record<string, unknown> = {}) {
  return {
    takeoverId: "t-1",
    state: "PAUSED_FOR_USER",
    requestedBy: "agent",
    kind: "captcha",
    reason: "Solve the captcha",
    host: "shop.example",
    idleRemainingSeconds: 0,
    maxRemainingSeconds: 0,
    waitRemainingSeconds: 540,
    ...over,
  };
}

function begun(over: Record<string, unknown> = {}) {
  return {
    view: view({ state: "USER_CONTROL", idleRemainingSeconds: 300, maxRemainingSeconds: 900 }),
    token: "tok-1",
    tokenExpiresInSeconds: 120,
    fps: 2,
    maxDimension: 1024,
    maxTypeChars: 256,
    inputPerSecond: 20,
    ...over,
  };
}

/** A live stream that sends one 1024x640 frame, then stays open. */
function openStreamWithOneFrame() {
  mockApi.openBrowserTakeoverStream.mockImplementation((_id: string, _token: string, signal: AbortSignal) => {
    const encoder = new TextEncoder();
    const frame = JSON.stringify({
      seq: 1,
      mime: "image/jpeg",
      data: "AAAA",
      width: 1024,
      height: 640,
      state: "USER_CONTROL",
      idleRemainingSeconds: 300,
      maxRemainingSeconds: 900,
    });
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode(`event: frame\ndata: ${frame}\n\n`));
        signal.addEventListener("abort", () => {
          try {
            controller.close();
          } catch {
            /* already closed */
          }
        });
      },
    });
    return Promise.resolve(new Response(body, { status: 200, headers: { "content-type": "text/event-stream" } }));
  });
}

function props(over: Record<string, unknown> = {}) {
  return { agentId: "agent-1", sessionId: "chat-1", ...over };
}

beforeEach(() => {
  vi.clearAllMocks();
  resetBrowserTakeoverCapabilitiesCache();
  mockApi.getBrowserTakeoverCapabilities.mockResolvedValue({ enabled: true, fps: 2, idleTimeoutSeconds: 300 });
  mockApi.getBrowserTakeoverStatus.mockResolvedValue({ takeover: null });
  mockApi.beginBrowserTakeover.mockResolvedValue(begun());
  mockApi.sendBrowserTakeoverInput.mockResolvedValue(undefined);
  mockApi.handBackBrowserTakeover.mockResolvedValue(view({ state: "AGENT_CONTROL" }));
  openStreamWithOneFrame();
  vi.spyOn(HTMLCanvasElement.prototype, "getBoundingClientRect").mockReturnValue({
    left: 0,
    top: 0,
    width: 512,
    height: 320,
    right: 512,
    bottom: 320,
    x: 0,
    y: 0,
    toJSON: () => ({}),
  });
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

async function takeControl() {
  mockApi.getBrowserTakeoverStatus.mockResolvedValue({ takeover: view() });
  const onHandedBack = vi.fn();
  const utils = render(<BrowserTakeoverPanel {...props()} onHandedBack={onHandedBack} />);
  fireEvent.click(await screen.findByText("takeControl"));
  const canvas = await screen.findByTestId("browser-takeover-canvas");
  // wait for the first frame so the frame size is known
  await waitFor(() => expect(mockApi.openBrowserTakeoverStream).toHaveBeenCalled());
  await act(async () => {
    await new Promise((r) => setTimeout(r, 20));
  });
  return { ...utils, canvas, onHandedBack };
}

describe("flag-off identity", () => {
  it("renders nothing and polls nothing while takeover is off", async () => {
    mockApi.getBrowserTakeoverCapabilities.mockRejectedValue(Object.assign(new Error("404"), { status: 404 }));
    const { container } = render(<BrowserTakeoverPanel {...props()} usedBrowser />);
    await waitFor(() => expect(mockApi.getBrowserTakeoverCapabilities).toHaveBeenCalled());
    await act(async () => {
      await Promise.resolve();
    });
    expect(container).toBeEmptyDOMElement();
    expect(mockApi.getBrowserTakeoverStatus).not.toHaveBeenCalled();
  });

  it("renders nothing when core answers enabled:false or there is no chat session yet", async () => {
    mockApi.getBrowserTakeoverCapabilities.mockResolvedValue({ enabled: false, fps: 2, idleTimeoutSeconds: 300 });
    const first = render(<BrowserTakeoverPanel {...props()} usedBrowser />);
    await waitFor(() => expect(mockApi.getBrowserTakeoverCapabilities).toHaveBeenCalled());
    expect(first.container).toBeEmptyDOMElement();
    first.unmount();

    resetBrowserTakeoverCapabilitiesCache();
    mockApi.getBrowserTakeoverCapabilities.mockResolvedValue({ enabled: true, fps: 2, idleTimeoutSeconds: 300 });
    const second = render(<BrowserTakeoverPanel {...props({ sessionId: null })} usedBrowser />);
    await waitFor(() => expect(mockApi.getBrowserTakeoverCapabilities).toHaveBeenCalledTimes(2));
    expect(second.container).toBeEmptyDOMElement();
    expect(mockApi.getBrowserTakeoverStatus).not.toHaveBeenCalled();
  });

  it("shows nothing while on, nothing is pending and the browser has not been used", async () => {
    const { container } = render(<BrowserTakeoverPanel {...props()} />);
    await waitFor(() => expect(mockApi.getBrowserTakeoverStatus).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
  });
});

describe("the agent asks for help", () => {
  it("shows what is needed, where, and offers to take control or hand back", async () => {
    mockApi.getBrowserTakeoverStatus.mockResolvedValue({ takeover: view() });
    render(<BrowserTakeoverPanel {...props()} />);
    expect(await screen.findByText("Solve the captcha")).toBeInTheDocument();
    expect(screen.getByText("siteLine:shop.example")).toBeInTheDocument();
    expect(screen.getByText(/kindCaptcha/)).toBeInTheDocument();
    expect(screen.getByText("takeControl")).toBeInTheDocument();
    expect(screen.getByText("notNow")).toBeInTheDocument();
    expect(mockApi.beginBrowserTakeover).not.toHaveBeenCalled();
  });

  it("renders the agent's reason as text, never as markup", async () => {
    mockApi.getBrowserTakeoverStatus.mockResolvedValue({ takeover: view({ reason: "<img src=x onerror=alert(1)>" }) });
    const { container } = render(<BrowserTakeoverPanel {...props()} />);
    expect(await screen.findByText("<img src=x onerror=alert(1)>")).toBeInTheDocument();
    expect(container.querySelector("img")).toBeNull();
  });

  it("declining hands back without ever taking control", async () => {
    mockApi.getBrowserTakeoverStatus.mockResolvedValue({ takeover: view() });
    const onHandedBack = vi.fn();
    render(<BrowserTakeoverPanel {...props()} onHandedBack={onHandedBack} />);
    fireEvent.click(await screen.findByText("notNow"));
    await waitFor(() => expect(mockApi.handBackBrowserTakeover).toHaveBeenCalledWith("t-1"));
    expect(mockApi.beginBrowserTakeover).not.toHaveBeenCalled();
    expect(onHandedBack).toHaveBeenCalledWith("continueMessage");
  });
});

describe("the person is in control", () => {
  it("says plainly that the agent is paused, counts down, and opens the live view with the token", async () => {
    const { canvas } = await takeControl();
    expect(screen.getByText("youAreInControl")).toBeInTheDocument();
    expect(screen.getByText(/^returnsIn:[45]:\d\d$/)).toBeInTheDocument();
    expect(screen.getByText("handBack")).toBeInTheDocument();
    expect(canvas).toBeInTheDocument();
    expect(mockApi.beginBrowserTakeover).toHaveBeenCalledWith("t-1");
    expect(mockApi.openBrowserTakeoverStream).toHaveBeenCalledWith("t-1", "tok-1", expect.any(AbortSignal));
  });

  it("maps a click to frame pixels and relays it with the token", async () => {
    const { canvas } = await takeControl();
    // 512x320 on screen, 1024x640 frame: the middle of the box is the middle of the frame
    fireEvent(canvas, new MouseEvent("pointerdown", { bubbles: true, clientX: 256, clientY: 160 }));
    await waitFor(() =>
      expect(mockApi.sendBrowserTakeoverInput).toHaveBeenCalledWith("t-1", "tok-1", {
        type: "click",
        x: 512,
        y: 320,
        frameWidth: 1024,
        frameHeight: 640,
      }),
    );
  });

  it("relays keys in order, ignores shortcuts, and relays a paste as one bounded text event", async () => {
    const { canvas } = await takeControl();
    fireEvent.keyDown(canvas, { key: "h" });
    fireEvent.keyDown(canvas, { key: "i" });
    fireEvent.keyDown(canvas, { key: "c", ctrlKey: true });
    fireEvent.keyDown(canvas, { key: "Shift" });
    fireEvent.keyDown(canvas, { key: "Enter" });
    fireEvent.paste(canvas, { clipboardData: { getData: () => "123456" } });
    await waitFor(() => expect(mockApi.sendBrowserTakeoverInput).toHaveBeenCalledTimes(4));
    expect(mockApi.sendBrowserTakeoverInput.mock.calls.map((c) => c[2])).toEqual([
      { type: "key", key: "h" },
      { type: "key", key: "i" },
      { type: "key", key: "Enter" },
      { type: "type", text: "123456" },
    ]);
  });

  it("never puts what was typed anywhere in the page", async () => {
    const { canvas, container } = await takeControl();
    fireEvent.paste(canvas, { clipboardData: { getData: () => "hunter2-Pw" } });
    await waitFor(() => expect(mockApi.sendBrowserTakeoverInput).toHaveBeenCalled());
    expect(container.textContent).not.toContain("hunter2-Pw");
    expect((canvas as HTMLCanvasElement).getAttribute("aria-label")).not.toContain("hunter2-Pw");
  });

  it("renews the token once and retries when the server says it expired", async () => {
    const { canvas } = await takeControl();
    mockApi.sendBrowserTakeoverInput.mockRejectedValueOnce(Object.assign(new Error("403"), { status: 403 }));
    mockApi.refreshBrowserTakeoverToken.mockResolvedValue({ token: "tok-2", tokenExpiresInSeconds: 120 });
    fireEvent.keyDown(canvas, { key: "x" });
    await waitFor(() => expect(mockApi.sendBrowserTakeoverInput).toHaveBeenCalledTimes(2));
    expect(mockApi.refreshBrowserTakeoverToken).toHaveBeenCalledWith("t-1");
    expect(mockApi.sendBrowserTakeoverInput.mock.calls[1][1]).toBe("tok-2");
  });

  it("drops an event the server throttled instead of piling up retries", async () => {
    const { canvas } = await takeControl();
    mockApi.sendBrowserTakeoverInput.mockRejectedValueOnce(Object.assign(new Error("429"), { status: 429 }));
    fireEvent.keyDown(canvas, { key: "x" });
    await waitFor(() => expect(mockApi.sendBrowserTakeoverInput).toHaveBeenCalledTimes(1));
    expect(mockApi.refreshBrowserTakeoverToken).not.toHaveBeenCalled();
  });

  it("hands back: calls core, offers the continue message to the chat, and confirms", async () => {
    const { onHandedBack } = await takeControl();
    fireEvent.click(screen.getByText("handBack"));
    await waitFor(() => expect(mockApi.handBackBrowserTakeover).toHaveBeenCalledWith("t-1"));
    expect(onHandedBack).toHaveBeenCalledWith("continueMessage");
    expect(await screen.findByText("handedBack")).toBeInTheDocument();
    expect(screen.queryByTestId("browser-takeover-canvas")).toBeNull();
  });

  it("stops the live view when the server says control is no longer the person's", async () => {
    await takeControl();
    mockApi.getBrowserTakeoverStatus.mockResolvedValue({ takeover: null });
    await act(async () => {
      await new Promise((r) => setTimeout(r, 3_100));
    });
    await waitFor(() => expect(screen.queryByTestId("browser-takeover-canvas")).toBeNull());
  }, 10_000);
});

describe("the person pauses the agent themselves", () => {
  it("offers 'take over' once the browser has been used, then pauses and takes control in one go", async () => {
    mockApi.pauseBrowserSession.mockResolvedValue(view({ requestedBy: "user", reason: null }));
    render(<BrowserTakeoverPanel {...props()} usedBrowser />);
    fireEvent.click(await screen.findByText("pauseButton"));
    await waitFor(() => expect(mockApi.pauseBrowserSession).toHaveBeenCalledWith("agent-1", "chat-1"));
    await waitFor(() => expect(mockApi.beginBrowserTakeover).toHaveBeenCalledWith("t-1"));
    expect(await screen.findByText("youAreInControl")).toBeInTheDocument();
  });

  it("says so when pausing fails", async () => {
    mockApi.pauseBrowserSession.mockRejectedValue(Object.assign(new Error("403"), { status: 403 }));
    render(<BrowserTakeoverPanel {...props()} usedBrowser />);
    fireEvent.click(await screen.findByText("pauseButton"));
    expect(await screen.findByText("failed")).toBeInTheDocument();
  });
});
