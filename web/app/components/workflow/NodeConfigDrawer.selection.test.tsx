// Prod re-verification 2026-10-05, R8: selecting the Trigger node of an untouched workflow flipped the toolbar to
// "Unsaved changes" and then autosaved. Cause: the Trigger node's panel hydrates its channel from the server on open
// and wrote the result into the graph even when it was the same effective value. Selecting is a read.

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, render, screen } from "@testing-library/react";

const getNodeTriggerChannel = vi.fn();
const getNodeWebhook = vi.fn();

vi.mock("../../lib/api", () => ({
  api: {
    get getNodeTriggerChannel() {
      return getNodeTriggerChannel;
    },
    get getNodeWebhook() {
      return getNodeWebhook;
    },
  },
  API_BASE: "https://api.example.test",
}));
vi.mock("../../lib/i18n", () => ({ useTranslations: () => (key: string) => key }));
vi.mock("./useNodeDefTranslations", () => ({ useNodeDefTranslations: () => (key: string) => key }));
vi.mock("../AuthProvider", () => ({ useAuth: () => ({ hasPermission: () => true }) }));
vi.mock("../Select", () => ({
  default: ({ value }: { value: string }) => <span data-testid="select">{value}</span>,
}));
vi.mock("../../lib/toastBus", () => ({ toast: { success: vi.fn(), error: vi.fn(), info: vi.fn() } }));
vi.mock("./StepTestSlot", () => ({ default: (): null => null }));
vi.mock("./PinBanner", () => ({ default: (): null => null }));
vi.mock("./DataPicker", () => ({ default: (): null => null }));
vi.mock("./FormTriggerEditor", () => ({ default: (): null => null }));
vi.mock("./TriggerPollConfig", () => ({ default: (): null => null }));
vi.mock("./NodeErrorStrategyEditor", () => ({ default: (): null => null }));
vi.mock("./ExpressionField", () => ({
  ScopedExpressionField: (): null => null,
  ExpressionScopeContext: { Provider: ({ children }: { children: React.ReactNode }) => <>{children}</> },
}));
vi.mock("./ExpressionFocus", () => ({ ExpressionFocusProvider: ({ children }: { children: React.ReactNode }) => <>{children}</> }));
vi.mock("../setup/SetupLinks", () => ({ SetupHint: (): null => null }));

import NodeConfigDrawer from "./NodeConfigDrawer";

function mount(config: Record<string, unknown>) {
  const onChange = vi.fn();
  const node = { id: "n1", type: "trigger", data: { label: "Trigger", config } };
  render(
    <NodeConfigDrawer
      node={node as never}
      workflowId="w1"
      agents={[]}
      collections={[]}
      devices={[]}
      triggerCount={1}
      onChange={onChange}
      onDelete={() => {}}
      onClose={() => {}}
    />,
  );
  return onChange;
}
const settle = () =>
  act(async () => {
    await Promise.resolve();
    await Promise.resolve();
  });

describe("NodeConfigDrawer - selecting a Trigger node does not edit the graph", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    getNodeWebhook.mockResolvedValue({ enabled: false });
  });
  afterEach(cleanup);

  it("leaves the graph alone when the server binding is the same webhook the graph already means", async () => {
    getNodeTriggerChannel.mockResolvedValue({ channel: "WEBHOOK", webhookAuthMode: undefined });
    const onChange = mount({}); // no channel key at all: the default is WEBHOOK
    await settle();
    expect(getNodeTriggerChannel).toHaveBeenCalledTimes(1);
    expect(onChange).not.toHaveBeenCalled();
    expect(screen.getAllByTestId("select")[0].textContent).toBe("WEBHOOK");
  });

  it("also leaves it alone when the graph already carries the same channel and auth mode", async () => {
    getNodeTriggerChannel.mockResolvedValue({ channel: "SLACK", webhookAuthMode: "OAUTH2" });
    const onChange = mount({ channel: "SLACK", webhookAuthMode: "OAUTH2" });
    await settle();
    expect(onChange).not.toHaveBeenCalled();
  });

  it("still brings a genuinely stale graph copy in line with the server binding", async () => {
    getNodeTriggerChannel.mockResolvedValue({ channel: "SLACK", webhookAuthMode: undefined });
    const onChange = mount({ channel: "WEBHOOK" });
    await settle();
    expect(onChange).toHaveBeenCalledTimes(1);
    expect(onChange.mock.calls[0][1]).toMatchObject({ config: { channel: "SLACK" } });
  });
});
