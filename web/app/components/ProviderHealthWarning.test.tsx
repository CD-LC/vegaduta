// Prod re-verification 2026-10-05, R4b: a provider whose key the vendor rejects stayed selectable with no warning.
// The inline warnings the agent create form and the model editor show, and the default-model note.

import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";

vi.mock("../lib/i18n", () => ({ useTranslations: () => (key: string) => key }));
vi.mock("next/link", () => ({ default: ({ children, href }: { children: React.ReactNode; href: string }) => <a href={href}>{children}</a> }));

import { DefaultModelNotice, ProviderHealthWarning, ProviderRejectedBadge } from "./ProviderHealthWarning";

const PROVIDERS = [
  { providerName: "OPENROUTER", status: "ACTIVE", health: "ok" },
  { providerName: "GOOGLE_VERTEX", status: "ACTIVE", health: "rejected", recentFailures: 24 },
  { providerName: "ANTHROPIC", status: "ACTIVE" },
];

afterEach(cleanup);

describe("ProviderHealthWarning", () => {
  it("warns, with a way to fix the key, when the selected provider has rejected the credentials", () => {
    render(<ProviderHealthWarning providerName="GOOGLE_VERTEX" providers={PROVIDERS} />);
    const warning = screen.getByTestId("provider-health-warning");
    expect(warning.textContent).toContain("inlineWarning");
    expect(screen.getByRole("link", { name: "replaceKey" }).getAttribute("href")).toBe("/providers");
  });

  it("says nothing for a healthy provider, one without health info, or no selection", () => {
    for (const name of ["OPENROUTER", "ANTHROPIC", "UNKNOWN", "", null]) {
      const { container, unmount } = render(<ProviderHealthWarning providerName={name} providers={PROVIDERS} />);
      expect(container.textContent).toBe("");
      unmount();
    }
    const { container } = render(<ProviderHealthWarning providerName="GOOGLE_VERTEX" providers={undefined} />);
    expect(container.textContent).toBe("");
  });
});

describe("DefaultModelNotice", () => {
  it("shows the server message when the default needs attention", () => {
    render(<DefaultModelNotice notice={{ providerName: "GOOGLE_VERTEX", needsAttention: true, message: "Recent calls on Vertex failed." }} providerName="GOOGLE_VERTEX" />);
    expect(screen.getByTestId("default-model-notice").textContent).toContain("Recent calls on Vertex failed.");
  });

  it("falls back to a generic sentence when the server sent no message", () => {
    render(<DefaultModelNotice notice={{ needsAttention: true, message: null }} providerName="OPENROUTER" />);
    expect(screen.getByTestId("default-model-notice").textContent).toContain("defaultNeedsAttention");
  });

  it("is hidden for a healthy default, no notice, or once the person picked another provider", () => {
    for (const notice of [null, undefined, { needsAttention: false, message: "x" }, { providerName: "GOOGLE_VERTEX", needsAttention: true, message: "x" }]) {
      const { container, unmount } = render(<DefaultModelNotice notice={notice} providerName="OPENROUTER" />);
      expect(container.textContent).toBe("");
      unmount();
    }
  });
});

describe("ProviderRejectedBadge", () => {
  it("carries the rejected label and the last error as its tooltip", () => {
    render(<ProviderRejectedBadge lastError="401 invalid credentials" recentFailures={24} />);
    const badge = screen.getByTestId("provider-rejected-badge");
    expect(badge.textContent).toContain("rejectedBadge");
    expect(badge.getAttribute("title")).toBe("401 invalid credentials");
  });
});
