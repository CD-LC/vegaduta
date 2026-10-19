import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import messages from "../../../messages/en.json";

const mode = vi.hoisted(() => ({ local: true }));

vi.mock("../../lib/i18n", () => ({
  useTranslations: (ns: string) => (key: string) => {
    const v = (messages as unknown as Record<string, Record<string, string>>)[ns]?.[key];
    return typeof v === "string" ? v : `${ns}.${key}`;
  },
}));
vi.mock("../../lib/authMode", () => ({ isLocalAuth: () => mode.local }));
vi.mock("../AuthProvider", () => ({ useAuth: () => ({ username: "ada@example.test", firstName: "Ada", logout: vi.fn() }) }));
vi.mock("../AppChromeProvider", () => ({ useAppChrome: () => ({ avatarBlobUrl: null as string | null }) }));
vi.mock("next/link", () => ({
  default: ({ children, href, ...rest }: { children: React.ReactNode; href: string }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

import RailFooter from "./RailFooter";

afterEach(cleanup);

const footer = (profileHref: string | null) => (
  <RailFooter profileHref={profileHref} hubMode={false} collapsibleRail={false} onOpenAvatar={() => {}} />
);

describe("RailFooter", () => {
  it("links local sign-in to the sign-in methods page, the only way to reach passkeys, PINs and recovery codes", () => {
    mode.local = true;
    render(footer(null));

    const link = screen.getByTestId("rail-account-security");
    expect(link).toHaveAttribute("href", "/login?security=1");
    expect(link).toHaveTextContent("Ada");
    expect(link).toHaveTextContent("Sign-in methods");
  });

  it("keeps the Keycloak account link when there is one, and a plain name otherwise", () => {
    mode.local = false;
    render(footer("https://id.example.test/account"));
    expect(screen.queryByTestId("rail-account-security")).toBeNull();
    expect(screen.getByRole("link", { name: /Manage profile/ })).toHaveAttribute("href", "https://id.example.test/account");

    cleanup();
    render(footer(null));
    expect(screen.queryByTestId("rail-account-security")).toBeNull();
    expect(screen.queryByRole("link")).toBeNull();
  });
});
