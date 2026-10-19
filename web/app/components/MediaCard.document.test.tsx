// Generated documents: a real document the turn's trace failed to record still renders (the
// server is the authority), a made-up id shows the never-generated notice after its fetch 404s,
// and a trace-backed document fetches exactly as before.

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";

const downloadMock = vi.hoisted(() => vi.fn());
vi.mock("../lib/api", () => ({
  api: { downloadGeneratedDocument: downloadMock },
  triggerBrowserDownload: vi.fn(),
}));

import MediaCard from "./MediaCard";

const ID = "0b75322d-8288-4a08-8f58-a633382a1437";
const URL_PATH = `/api/documentgen/documents/${ID}`;
const REPLY = `I have generated the PDF report.\n\nYou can download the PDF file here:\n${URL_PATH}`;

describe("MediaCard generated documents", () => {
  beforeEach(() => {
    downloadMock.mockReset();
    (URL as any).createObjectURL = vi.fn(() => "blob:test");
    (URL as any).revokeObjectURL = vi.fn();
  });
  afterEach(() => cleanup());

  it("renders a document the trace did not record when the server has it", async () => {
    downloadMock.mockResolvedValue({
      blob: new Blob(["%PDF"], { type: "application/pdf" }),
      filename: "abap-report.pdf",
    });
    render(<MediaCard text={REPLY} trace={[]} />);
    await waitFor(() => expect(screen.getByText("Download")).toBeTruthy());
    expect(screen.getByText("abap-report.pdf")).toBeTruthy();
    expect(screen.queryByTestId("media-unverified")).toBeNull();
    expect(downloadMock).toHaveBeenCalledWith(ID);
  });

  it("shows the never-generated notice only after the server says 404", async () => {
    const notFound: any = new Error("This document is no longer available.");
    notFound.status = 404;
    downloadMock.mockRejectedValue(notFound);
    render(<MediaCard text={REPLY} trace={[]} />);
    expect(await screen.findByTestId("media-unverified")).toBeTruthy();
    expect(screen.getByText(/never actually generated/)).toBeTruthy();
  });

  it("a non-404 failure on an unbacked ref is reported as a load error, not as fabrication", async () => {
    const boom: any = new Error("Something went wrong on our side.");
    boom.status = 500;
    downloadMock.mockRejectedValue(boom);
    render(<MediaCard text={REPLY} trace={[]} />);
    await waitFor(() => expect(screen.getByText("Something went wrong on our side.")).toBeTruthy());
    expect(screen.queryByTestId("media-unverified")).toBeNull();
  });

  it("a trace-backed document fetches once and renders the download", async () => {
    downloadMock.mockResolvedValue({ blob: new Blob(["x"], { type: "application/pdf" }), filename: "r.pdf" });
    render(<MediaCard text={REPLY} trace={[{ toolOutput: `{"status":"success","downloadUrl":"${URL_PATH}"}` }]} />);
    await waitFor(() => expect(screen.getByText("Download")).toBeTruthy());
    expect(downloadMock).toHaveBeenCalledTimes(1);
  });
});
