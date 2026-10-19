import { render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import ContactPage from "./contact/page";
import RefundPolicyPage, { REFUND_SECTIONS } from "./refund-policy/page";
import ShippingPolicyPage from "./shipping-policy/page";
import { PUBLIC_ROUTES } from "./lib/publicRoutes";
import { SUPPORT_EMAIL } from "./components/legal/LegalPage";
import { LEGAL_ENTITY } from "./lib/legalEntity";

const POLICY_LINKS = ["/pricing", "/terms", "/privacy", "/refund-policy", "/shipping-policy", "/contact"];

function expectPolicyNav() {
  const nav = screen.getByRole("navigation", { name: "Policies" });
  const hrefs = within(nav)
    .getAllByRole("link")
    .map((a) => a.getAttribute("href"));
  expect(hrefs).toEqual(POLICY_LINKS);
}

describe("public policy pages (payment-gateway verification)", () => {
  // The price list and the refund/shipping policies are billing surfaces: they
  // only exist when the operator turns billing on (lib/billing-flag.ts).
  beforeEach(() => {
    process.env.NEXT_PUBLIC_BILLING_ENABLED = "1";
  });
  afterEach(() => {
    delete process.env.NEXT_PUBLIC_BILLING_ENABLED;
  });

  it("refund policy states the cancellation rule, the 7-day refund and how to ask", () => {
    render(<RefundPolicyPage />);
    expect(screen.getByRole("heading", { level: 1, name: "Refund & Cancellation Policy" })).toBeTruthy();
    const text = document.body.textContent ?? "";
    expect(text).toContain("within 7 days of the first successful charge");
    expect(text).toContain("end of the billing period");
    expect(text).toContain(SUPPORT_EMAIL);
    expect(document.body.innerHTML).toContain("<!--email_off-->");
    expect(text).toContain("within 2 business days");
    expectPolicyNav();
  });

  it("refund policy has every section a reviewer looks for", () => {
    const headings = REFUND_SECTIONS.map((s) => s.h);
    for (const wanted of ["Cancelling", "refund", "request", "Taxes"]) {
      expect(headings.some((h) => h.toLowerCase().includes(wanted.toLowerCase()))).toBe(true);
    }
  });

  it("shipping policy says it is a digital service with no shipping", () => {
    render(<ShippingPolicyPage />);
    expect(document.body.textContent).toContain("nothing is shipped");
    expect(document.body.textContent).toContain("within a few minutes");
    expectPolicyNav();
  });

  it("contact page shows the operator and support email, and never an empty CIN, address, phone or company-email row", () => {
    render(<ContactPage />);
    // Deployment-configured operator (lib/legalEntity.ts); unset here, so the defaults.
    expect(document.body.textContent).toContain(LEGAL_ENTITY);
    expect(LEGAL_ENTITY).toBe("VegaDūta contributors");
    expect(screen.getByRole("link", { name: SUPPORT_EMAIL }).getAttribute("href")).toBe(`mailto:${SUPPORT_EMAIL}`);
    expect(document.body.innerHTML).toContain("<!--email_off-->");
    expect(screen.queryByText("CIN")).toBeNull();
    expect(screen.queryByText("Registered office")).toBeNull();
    expect(screen.queryByText("Phone")).toBeNull();
    expect(screen.queryByText("Company email")).toBeNull();
    expect(screen.getByText("Billing and refunds")).toBeTruthy();
    expectPolicyNav();
  });

  it("with billing off, the policy nav drops the billing-only pages", () => {
    delete process.env.NEXT_PUBLIC_BILLING_ENABLED;
    render(<ContactPage />);
    const nav = screen.getByRole("navigation", { name: "Policies" });
    const hrefs = within(nav)
      .getAllByRole("link")
      .map((a) => a.getAttribute("href"));
    expect(hrefs).toEqual(["/terms", "/privacy", "/contact"]);
    expect(screen.queryByText("Billing and refunds")).toBeNull();
  });

  it("all four pages are public (reachable without signing in)", () => {
    for (const route of ["/pricing", "/refund-policy", "/shipping-policy", "/contact"]) {
      expect(PUBLIC_ROUTES).toContain(route);
    }
  });
});
