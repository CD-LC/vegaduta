// Deep links into the approvals queue (notifications, agent pages): the page turns the query string into the
// panel's initial request, status and filters, and ignores values it does not know.
import type { ReactNode } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render } from "@testing-library/react";

let search = "";
const panel = vi.fn((_props: Record<string, unknown>) => <div data-testid="panel" />);
vi.mock("next/navigation", () => ({ useSearchParams: () => new URLSearchParams(search) }));
vi.mock("../../components/AppShell", () => ({ default: ({ children }: { children: ReactNode }) => <div>{children}</div> }));
vi.mock("../../components/ApprovalsPanel", () => ({ default: (props: Record<string, unknown>) => panel(props) }));
let allowed = true;
vi.mock("../../components/useRequireScope", () => ({ useRequireScope: () => allowed }));
vi.mock("../../lib/i18n", () => ({ useTranslations: () => (key: string) => key }));

import AdminApprovalsPage from "./page";

afterEach(() => {
  cleanup();
  panel.mockClear();
  allowed = true;
  search = "";
});

const props = () => panel.mock.calls.at(-1)![0];

describe("/admin/approvals deep links", () => {
  it("opens one request and applies status and filters from the link", () => {
    search = "id=req-9&status=expired&agent=a1&tool=send_email&age=older24h&rules=1";
    render(<AdminApprovalsPage />);
    expect(props()).toMatchObject({
      isAdmin: true,
      initialRequestId: "req-9",
      initialStatus: "EXPIRED",
      initialFilters: { agent: "a1", tool: "send_email", age: "older24h" },
      initialShowRules: true,
    });
  });

  it("maps status=all to the all-statuses list and drops an unknown age", () => {
    search = "status=all&age=yesterday";
    render(<AdminApprovalsPage />);
    expect(props()).toMatchObject({ initialStatus: "", initialFilters: { age: "" }, initialRequestId: null, initialShowRules: false });
  });

  it("leaves the default status alone when the link has none", () => {
    render(<AdminApprovalsPage />);
    expect(props().initialStatus).toBeNull();
  });

  it("renders nothing without the approvals scope", () => {
    allowed = false;
    render(<AdminApprovalsPage />);
    expect(panel).not.toHaveBeenCalled();
  });
});
