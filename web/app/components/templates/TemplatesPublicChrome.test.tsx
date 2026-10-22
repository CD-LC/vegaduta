// The public template chrome links to /directory, /templates/mine and /signup; a personal install serves
// none of them (the proxy answers 404), so the chrome must not advertise them.
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

const auth = vi.hoisted(() => ({ init: vi.fn(), login: vi.fn() }));
vi.mock("../../lib/auth", () => ({ auth }));

import TemplatesPublicChrome from "./TemplatesPublicChrome";

const hrefs = (container: HTMLElement) => Array.from(container.querySelectorAll("a")).map((a) => a.getAttribute("href"));

afterEach(() => {
  cleanup();
  vi.unstubAllEnvs();
  vi.clearAllMocks();
});

describe("TemplatesPublicChrome", () => {
  it("signed out on a personal install: no directory and no sign-up link", () => {
    vi.stubEnv("NEXT_PUBLIC_EDITION", "personal");
    auth.init.mockResolvedValue(null);
    const { container } = render(<TemplatesPublicChrome>x</TemplatesPublicChrome>);
    const links = hrefs(container);
    expect(links).toContain("/plugins");
    expect(links).not.toContain("/directory");
    expect(links).not.toContain("/signup");
    expect(links).not.toContain("/templates/mine");
  });

  it("signed in on a personal install: no directory and no My templates link", async () => {
    vi.stubEnv("NEXT_PUBLIC_EDITION", "personal");
    auth.init.mockResolvedValue({});
    const { container } = render(<TemplatesPublicChrome>x</TemplatesPublicChrome>);
    await waitFor(() => expect(screen.getByText(/Back to console/)).toBeTruthy());
    const links = hrefs(container);
    expect(links).not.toContain("/directory");
    expect(links).not.toContain("/templates/mine");
  });

  it("keeps all of them on a shared install", async () => {
    vi.stubEnv("NEXT_PUBLIC_EDITION", "full");
    auth.init.mockResolvedValue(null);
    const signedOut = render(<TemplatesPublicChrome>x</TemplatesPublicChrome>);
    expect(hrefs(signedOut.container)).toEqual(expect.arrayContaining(["/directory", "/signup"]));
    cleanup();

    auth.init.mockResolvedValue({});
    const signedIn = render(<TemplatesPublicChrome>x</TemplatesPublicChrome>);
    await waitFor(() => expect(hrefs(signedIn.container)).toContain("/templates/mine"));
    expect(hrefs(signedIn.container)).toContain("/directory");
  });
});
