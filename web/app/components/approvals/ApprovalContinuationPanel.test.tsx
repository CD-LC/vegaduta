import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import en from "../../../messages/en.json";

const streamChat = vi.fn();
vi.mock("../../lib/api", () => ({
  api: {
    get streamChat() {
      return streamChat;
    },
  },
}));
vi.mock("../../lib/api/assistant", () => ({ assistantApi: { getChatTranscript: vi.fn() } }));
vi.mock("../../lib/i18n", () => {
  const cache: Record<string, (k: string, v?: Record<string, unknown>) => string> = {};
  return {
    useTranslations: (ns: string) =>
      (cache[ns] ??= (key) => ((en as unknown as Record<string, Record<string, string>>)[ns] ?? {})[key] ?? key),
  };
});

import ApprovalContinuationPanel from "./ApprovalContinuationPanel";
import type { ApprovalRequestItem } from "./approvalsModel";

const MARKER = `<approval_result request="r1" tool="t" outcome="completed">\nx\n</approval_result>`;
const decided = (over: Partial<ApprovalRequestItem> = {}): ApprovalRequestItem => ({
  id: "r1",
  status: "APPROVED",
  actionType: "mcp_tool.get_invoice",
  summary: "Tool call: get_invoice",
  requestedBy: "agent:a1:session:s1",
  createdAt: new Date(Date.now() - 120_000).toISOString(),
  decidedAt: new Date(Date.now() - 60_000).toISOString(),
  ...over,
});

beforeEach(() => {
  streamChat.mockReset().mockResolvedValue("s1");
});
afterEach(() => cleanup());

describe("ApprovalContinuationPanel", () => {
  it("renders nothing for requests that are not decided tool calls", () => {
    const fetchTranscript = vi.fn(async () => ({ messages: [] }));
    const { container, rerender } = render(<ApprovalContinuationPanel item={decided({ status: "PENDING" })} fetchTranscript={fetchTranscript} />);
    expect(container.firstChild).toBeNull();
    rerender(<ApprovalContinuationPanel item={decided({ actionType: "channel.slack.send", requestedBy: "agent:a1" })} fetchTranscript={fetchTranscript} />);
    expect(container.firstChild).toBeNull();
    expect(fetchTranscript).not.toHaveBeenCalled();
  });

  it("reports a decision with no continuation message, past the start window, as not restarted - never as running", async () => {
    render(<ApprovalContinuationPanel item={decided()} fetchTranscript={async () => ({ messages: [{ role: "user", content: "hi" }] })} startWaitMs={1_000} />);
    expect(await screen.findByTestId("approval-continuation-notStarted")).toBeInTheDocument();
    expect(screen.queryByTestId("approval-continuing")).toBeNull();
  });

  it("retry sends a carry-on message to the same conversation and then reports it finished", async () => {
    let release: (v: string) => void = () => undefined;
    streamChat.mockReturnValue(new Promise<string>((resolve) => (release = resolve)));
    render(<ApprovalContinuationPanel item={decided()} fetchTranscript={async () => ({ messages: [] })} startWaitMs={1_000} />);
    fireEvent.click(await screen.findByTestId("approval-continuation-retry"));
    await waitFor(() => expect(streamChat).toHaveBeenCalledWith("a1", "Please carry on with the task.", "s1", expect.any(Function)));
    expect(await screen.findByTestId("approval-continuing")).toBeInTheDocument();
    release("s1");
    expect(await screen.findByTestId("approval-continuation-completed")).toBeInTheDocument();
  });

  it("shows the retry failure instead of pretending it worked", async () => {
    streamChat.mockImplementation(() => Promise.reject(new Error("offline")));
    render(<ApprovalContinuationPanel item={decided()} fetchTranscript={async () => ({ messages: [] })} startWaitMs={1_000} />);
    fireEvent.click(await screen.findByTestId("approval-continuation-retry"));
    expect(await screen.findByTestId("approval-continuation-failed")).toBeInTheDocument();
  });

  it("stays quiet about a decision more than a day old", async () => {
    const fetchTranscript = vi.fn(async () => ({ messages: [] }));
    const { container } = render(
      <ApprovalContinuationPanel item={decided({ decidedAt: new Date(Date.now() - 3 * 86_400_000).toISOString() })} fetchTranscript={fetchTranscript} />
    );
    await waitFor(() => expect(container.firstChild).toBeNull());
    expect(fetchTranscript).not.toHaveBeenCalled();
  });

  it("follows a running continuation to its reply", async () => {
    let call = 0;
    const fetchTranscript = async () => ({
      messages: call++ === 0 ? [{ role: "user", content: MARKER }] : [{ role: "user", content: MARKER }, { role: "assistant", content: "Done." }],
    });
    render(<ApprovalContinuationPanel item={decided({ decidedAt: new Date().toISOString() })} fetchTranscript={fetchTranscript} pollMs={10} />);
    expect(await screen.findByTestId("approval-continuing")).toBeInTheDocument();
    expect(await screen.findByTestId("approval-continuation-completed")).toBeInTheDocument();
  });
});
