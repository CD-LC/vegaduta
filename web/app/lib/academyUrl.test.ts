import { afterEach, describe, expect, it } from "vitest";
import { academyHref, academyUrl, hasAcademy } from "./academyUrl";

describe("academyUrl", () => {
  const prev = process.env.NEXT_PUBLIC_ACADEMY_URL;
  afterEach(() => {
    if (prev === undefined) delete process.env.NEXT_PUBLIC_ACADEMY_URL;
    else process.env.NEXT_PUBLIC_ACADEMY_URL = prev;
  });

  it("has no default: unset means no Academy and never a localhost URL", () => {
    delete process.env.NEXT_PUBLIC_ACADEMY_URL;
    expect(academyUrl()).toBe("");
    expect(hasAcademy()).toBe(false);
    expect(academyHref()).toBe("https://academy.vegaduta.ai");
  });

  it("uses the configured origin without a trailing slash", () => {
    process.env.NEXT_PUBLIC_ACADEMY_URL = "https://academy.example.com/";
    expect(academyUrl()).toBe("https://academy.example.com");
    expect(hasAcademy()).toBe(true);
    expect(academyHref()).toBe("https://academy.example.com");
  });
});
