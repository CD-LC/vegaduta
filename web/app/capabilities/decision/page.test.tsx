// The /capabilities/decision console is data-driven off GET
// /api/capabilities: every provider the catalog lists for the "decision"
// capability must render its own credential row, and activation must name
// THAT row's providerKey - nothing in the page may assume the first (or any
// specific) provider. Two catalog providers are mocked to prove it.

import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { ReactNode } from "react";

const api = vi.hoisted(() => ({
  listCapabilities: vi.fn(),
  getDecisionStatus: vi.fn(),
  getCapabilityPolicy: vi.fn(),
  updateCapabilityPolicy: vi.fn(),
  activateCapabilityCredential: vi.fn(),
  deactivateCapabilityCredential: vi.fn(),
  runDecisionPlayground: vi.fn(),
}));

vi.mock("../../lib/api", () => ({ api }));

vi.mock("../../components/AppShell", () => ({
  default: ({ children }: { children?: ReactNode }) => <div>{children}</div>,
}));

vi.mock("../../components/RouteTransition", () => ({
  usePageReady: (): void => undefined,
}));

vi.mock("../../lib/i18n", () => ({
  useTranslations: () => (key: string) => key,
}));

let mockCanManage = true;
vi.mock("../../components/AuthProvider", () => ({
  useAuth: () => ({ hasPermission: () => mockCanManage }),
}));

import DecisionCapabilityPage from "./page";
import type { CapabilityDescriptor } from "../../lib/api";

const CATALOG: CapabilityDescriptor[] = [
  {
    key: "decision",
    providers: [
      { providerKey: "TYPESAFE", displayName: "TypeSafe Jev", credential: null },
      { providerKey: "SECOND", displayName: "Another Decision Co", credential: null },
    ],
  },
];

function mockLoadedPage(): void {
  api.listCapabilities.mockResolvedValue(CATALOG);
  api.getDecisionStatus.mockResolvedValue({
    enabled: true,
    configured: false,
    providerKey: null,
    source: null,
    tenantCredential: null,
  });
  api.getCapabilityPolicy.mockResolvedValue({
    capability: "decision",
    overrides: {},
    updatedAt: null,
  });
}

describe("DecisionCapabilityPage", () => {
  afterEach(() => {
    cleanup();
    mockCanManage = true;
  });

  // The playground spends the tenant's decision key, so the server requires
  // MANAGE_INTEGRATIONS for it: without that permission the run button is
  // disabled and the page says why, rather than surfacing a 403.
  it("disables the playground and explains why without MANAGE_INTEGRATIONS", async () => {
    mockCanManage = false;
    mockLoadedPage();
    render(<DecisionCapabilityPage />);
    expect(await screen.findByTestId("decision-playground-needs-permission")).toHaveTextContent(
      "playgroundNeedsPermission",
    );
    expect(screen.getByRole("button", { name: /^run$/ })).toBeDisabled();
  });

  it("keeps the playground note hidden for a member who may run it", async () => {
    mockLoadedPage();
    render(<DecisionCapabilityPage />);
    expect(await screen.findByText("TypeSafe Jev")).toBeInTheDocument();
    expect(screen.queryByTestId("decision-playground-needs-permission")).toBeNull();
  });

  it("renders every catalog provider and activates the second by its own key", async () => {
    api.listCapabilities.mockResolvedValue(CATALOG);
    api.getDecisionStatus.mockResolvedValue({
      enabled: true,
      configured: false,
      providerKey: null,
      source: null,
      tenantCredential: null,
    });
    api.getCapabilityPolicy.mockResolvedValue({
      capability: "decision",
      overrides: {},
      updatedAt: null,
    });
    api.activateCapabilityCredential.mockResolvedValue({});

    const { container } = render(<DecisionCapabilityPage />);

    // Both catalog rows render from data - no hardcoded provider.
    expect(await screen.findByText("TypeSafe Jev")).toBeInTheDocument();
    expect(screen.getByText("Another Decision Co")).toBeInTheDocument();

    const secondInput = container.querySelector<HTMLInputElement>("#decision-key-SECOND");
    expect(secondInput).not.toBeNull();
    fireEvent.change(secondInput!, { target: { value: "second-key" } });
    fireEvent.submit(secondInput!.closest("form")!);

    await waitFor(() =>
      expect(api.activateCapabilityCredential).toHaveBeenCalledWith(
        "decision",
        "SECOND",
        "second-key",
      ),
    );
  });
});
