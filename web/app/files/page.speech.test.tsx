// The Files page lists generated audio from two cloud-speech controllers (ElevenLabs, Sarvam). On a personal
// install core has neither (CLOUD_SPEECH is off), so the page must not call those routes: a 404 there would
// reject its Promise.allSettled entry and show the "some files could not be loaded" banner for nothing.

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";

vi.mock("../lib/i18n", () => ({ useTranslations: () => (key: string) => key }));
vi.mock("next/link", () => ({ default: ({ children, href }: { children: React.ReactNode; href: string }) => <a href={href}>{children}</a> }));
vi.mock("../components/AppShell", () => ({ default: ({ children }: { children: React.ReactNode }) => <div>{children}</div> }));
vi.mock("../components/RouteTransition", () => ({ usePageReady: () => {} }));

const calls = vi.hoisted(() => ({
  listCollections: vi.fn(async () => []),
  listGeneratedImages: vi.fn(async () => []),
  listElevenLabsAudio: vi.fn(async () => {
    throw new Error("404");
  }),
  listSarvamAudio: vi.fn(async () => {
    throw new Error("404");
  }),
  listVideoGenerationJobs: vi.fn(async () => []),
  listGeneratedDocuments: vi.fn(async () => ({ content: [], totalElements: 0 })),
}));
vi.mock("../lib/api", () => ({ api: calls }));

import FilesPage from "./page";

beforeEach(() => {
  for (const fn of Object.values(calls)) fn.mockClear();
});

afterEach(() => {
  cleanup();
  vi.unstubAllEnvs();
  delete window.__ENV;
});

describe("FilesPage - generated audio listings", () => {
  it("does not call the speech routes and shows no partial-failure banner on a personal install", async () => {
    vi.stubEnv("NEXT_PUBLIC_EDITION", "personal");
    render(<FilesPage />);
    await waitFor(() => expect(calls.listGeneratedDocuments).toHaveBeenCalled());
    await waitFor(() => expect(calls.listVideoGenerationJobs).toHaveBeenCalled());
    expect(calls.listGeneratedImages).toHaveBeenCalled();
    expect(calls.listElevenLabsAudio).not.toHaveBeenCalled();
    expect(calls.listSarvamAudio).not.toHaveBeenCalled();
    expect(screen.queryByText("artifactsPartialTitle")).toBeNull();
    expect(screen.queryByText("artifactsFailedTitle")).toBeNull();
  });

  it.each(["lite", "full"])("still lists both on the %s edition (and a rejected one is the partial banner)", async (tier) => {
    vi.stubEnv("NEXT_PUBLIC_EDITION", tier);
    render(<FilesPage />);
    await waitFor(() => expect(calls.listSarvamAudio).toHaveBeenCalled());
    expect(calls.listElevenLabsAudio).toHaveBeenCalled();
    expect(await screen.findByText("artifactsPartialTitle")).toBeInTheDocument();
  });
});
