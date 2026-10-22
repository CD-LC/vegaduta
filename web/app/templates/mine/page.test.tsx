// /templates/mine is the template-submission corner of a shared gallery. A personal install has no gallery
// to submit to: the proxy answers 404 first, and the page gives the same answer if it is ever reached.
import { afterEach, describe, expect, it, vi } from "vitest";

const notFound = vi.hoisted(() =>
  vi.fn(() => {
    throw new Error("NEXT_NOT_FOUND");
  })
);
vi.mock("next/navigation", () => ({ notFound }));
vi.mock("next/link", () => ({ default: (): null => null }));
vi.mock("../../components/library/LibraryChrome", () => ({ default: (): null => null }));
vi.mock("../../components/templates/MySubmissions", () => ({ default: (): null => null }));

import MyTemplatesPage from "./page";

afterEach(() => {
  vi.unstubAllEnvs();
  vi.clearAllMocks();
});

describe("/templates/mine", () => {
  it("is a 404 on a personal install", async () => {
    vi.stubEnv("NEXT_PUBLIC_EDITION", "personal");
    await expect(MyTemplatesPage({ searchParams: Promise.resolve({}) })).rejects.toThrow("NEXT_NOT_FOUND");
    expect(notFound).toHaveBeenCalled();
  });

  it("renders on a shared install", async () => {
    vi.stubEnv("NEXT_PUBLIC_EDITION", "full");
    await expect(MyTemplatesPage({ searchParams: Promise.resolve({}) })).resolves.toBeTruthy();
    expect(notFound).not.toHaveBeenCalled();
  });
});
