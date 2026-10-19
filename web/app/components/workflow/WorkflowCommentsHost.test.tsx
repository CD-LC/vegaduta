import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import en from "../../../messages/en.json";

vi.mock("../../lib/i18n", () => ({
  useTranslations: (ns: string) => (key: string, values?: Record<string, unknown>) => {
    const raw = ((en as any)[ns] ?? {})[key];
    if (typeof raw !== "string") return `${ns}.${key}`;
    return raw.replace(/\{(\w+)\}/g, (m, k) => (values && k in values ? String(values[k]) : m));
  },
}));
vi.mock("@xyflow/react", () => ({
  useReactFlow: () => ({ getNode: (): null => null, flowToScreenPosition: (p: any) => p, screenToFlowPosition: (p: any) => p, setCenter: vi.fn() }),
  useViewport: () => ({ x: 0, y: 0, zoom: 1 }),
}));
vi.mock("../AuthProvider", () => ({ useAuth: () => ({ username: "ada" }) }));
let status = "ready";
vi.mock("./CommentLayer", () => ({
  default: () => <div data-testid="layer" />,
  useWorkflowComments: () => ({ threads: [] as unknown[], setThreads: vi.fn(), status, reload: vi.fn(), counts: {} }),
}));

import WorkflowCommentsHost from "./WorkflowCommentsHost";

const props = { workflowId: "w1", onClose: () => {}, nodeLabel: () => "", onFocusNode: () => {} };

describe("WorkflowCommentsHost", () => {
  it("renders nothing while closed", () => {
    const { container } = render(<WorkflowCommentsHost {...props} open={false} />);
    expect(container.firstChild).toBeNull();
  });

  it("mounts the layer with an add-comment control when open", () => {
    status = "ready";
    render(<WorkflowCommentsHost {...props} open />);
    expect(screen.getByTestId("layer")).toBeTruthy();
    expect(screen.getByRole("button", { name: "Add comment" })).toBeTruthy();
  });

  it("hides itself on a core without the comments route", () => {
    status = "unavailable";
    const { container } = render(<WorkflowCommentsHost {...props} open />);
    expect(container.firstChild).toBeNull();
  });
});
