import { describe, expect, it, vi } from "vitest";
import {
  ExportFailedError,
  asciiPunctuation,
  buildDocumentRequest,
  generateAndFetch,
  pdfWouldDropCharacters,
  plainInline,
  reportText,
  sanitizeFilename,
  splitSections,
  toDocumentContent,
  toMarkdown,
} from "./exportReport";
import type { DocumentGenerationJob, ResearchResult } from "../../lib/api/types";

const RESULT: ResearchResult = {
  title: "Tea and health",
  reportMarkdown: "## Findings\n\nTea is **widely** drunk [S1].\n\n- one\n- two\n\nAfter the list.",
  sources: [
    { id: "S1", url: "https://example.org/tea", title: "Tea facts", quotes: [{ quote: "most consumed", verdict: "SUPPORTED" }] },
    { id: "S2", url: "https://example.com/b" },
  ],
  unanswered: ["How much is too much?"],
  partial: true,
};

describe("sanitizeFilename", () => {
  it("removes characters a file system refuses and joins words with hyphens", () => {
    expect(sanitizeFilename('Tea: is it "good" / bad?')).toBe("Tea-is-it-good-bad");
    expect(sanitizeFilename("a\\b|c<d>e*f")).toBe("a-b-c-d-e-f");
  });

  it("drops leading dots and trailing dots or spaces", () => {
    expect(sanitizeFilename("..hidden report. ")).toBe("hidden-report");
  });

  it("keeps non-Latin letters", () => {
    expect(sanitizeFilename("चाय और स्वास्थ्य")).toBe("चाय-और-स्वास्थ्य");
  });

  it("bounds the length and falls back when nothing is left", () => {
    expect(sanitizeFilename("x".repeat(300)).length).toBe(80);
    expect(sanitizeFilename("")).toBe("research-report");
    expect(sanitizeFilename("???")).toBe("research-report");
    expect(sanitizeFilename(null, "fallback")).toBe("fallback");
  });
});

describe("toMarkdown", () => {
  it("writes title, partial note, body, unanswered and numbered sources with quotes", () => {
    expect(toMarkdown(RESULT)).toBe(
      [
        "# Tea and health",
        "",
        "> Partial report: some sources could not be read or a limit was reached.",
        "",
        "## Findings",
        "",
        "Tea is **widely** drunk [S1].",
        "",
        "- one",
        "- two",
        "",
        "After the list.",
        "",
        "## Not answered",
        "",
        "- How much is too much?",
        "",
        "## Sources",
        "",
        "1. [S1] Tea facts - https://example.org/tea",
        '   - "most consumed" (SUPPORTED)',
        "2. [S2] https://example.com/b",
        "",
      ].join("\n")
    );
  });

  it("omits the optional parts when they are absent", () => {
    const md = toMarkdown({ title: "", reportMarkdown: "Body", sources: [] });
    expect(md).toBe("# Research report\n\nBody\n");
  });
});

describe("plainInline", () => {
  it("flattens emphasis and code and spells links out", () => {
    expect(plainInline("**bold** and *it* and `code`")).toBe("bold and it and code");
    expect(plainInline("see [the docs](https://d.example/x) [S2]")).toBe("see the docs (https://d.example/x) [S2]");
    expect(plainInline("[https://a.example](https://a.example)")).toBe("https://a.example");
  });
});

describe("splitSections", () => {
  it("splits on headings and keeps text after a list below that list", () => {
    expect(splitSections(RESULT.reportMarkdown)).toEqual([
      { heading: "Findings", paragraphs: ["Tea is widely drunk [S1]."], bulletPoints: ["one", "two"] },
      { heading: null, paragraphs: ["After the list."], bulletPoints: [] },
    ]);
  });

  it("joins wrapped lines into one paragraph and handles numbered lists", () => {
    expect(splitSections("line one\nline two\n\n1. first\n2) second")).toEqual([
      { heading: null, paragraphs: ["line one line two"], bulletPoints: ["first", "second"] },
    ]);
  });

  it("keeps code lines and drops fences, rules and table separators", () => {
    expect(splitSections("# H\n```js\nconst a = 1;\n```\n---\n| a | b |\n| --- | --- |\n| 1 | 2 |")).toEqual([
      { heading: "H", paragraphs: ["const a = 1;", "| a | b |", "| 1 | 2 |"], bulletPoints: [] },
    ]);
  });

  it("returns no sections for empty text", () => {
    expect(splitSections("")).toEqual([]);
  });
});

describe("toDocumentContent / buildDocumentRequest", () => {
  it("appends unanswered and sources sections and fills the nested content shape", () => {
    const content = toDocumentContent(RESULT);
    expect(content.title).toBe("Tea and health");
    expect(content.sections[0]).toEqual({
      heading: null,
      paragraphs: ["Partial report: some sources could not be read or a limit was reached."],
      bulletPoints: [],
    });
    expect(content.sections[content.sections.length - 2]).toEqual({ heading: "Not answered", paragraphs: [], bulletPoints: ["How much is too much?"] });
    expect(content.sections[content.sections.length - 1]).toEqual({
      heading: "Sources",
      paragraphs: [],
      bulletPoints: ["[S1] Tea facts - https://example.org/tea", 'S1: "most consumed" (SUPPORTED)', "[S2] https://example.com/b"],
    });
    expect(content.table).toBeNull();
    expect(content.rawText).toBeNull();
  });

  it("builds the backend request: type, extension-less filename, nested content", () => {
    const req = buildDocumentRequest("DOCX", RESULT);
    expect(Object.keys(req).sort()).toEqual(["content", "filename", "type"]);
    expect(req.type).toBe("DOCX");
    expect(req.filename).toBe("Tea-and-health");
    expect(req.content.sections.length).toBeGreaterThan(0);
  });

  it("swaps typographic punctuation for PDF only", () => {
    const fancy: ResearchResult = { title: "“Quoted” – title", reportMarkdown: "It’s fine… really", sources: [] };
    const pdf = buildDocumentRequest("PDF", fancy);
    expect(pdf.content.title).toBe('"Quoted" - title');
    expect(pdf.content.sections[0].paragraphs).toEqual(["It's fine... really"]);
    const docx = buildDocumentRequest("DOCX", fancy);
    expect(docx.content.title).toBe("“Quoted” – title");
  });
});

describe("pdfWouldDropCharacters", () => {
  it("is false for Latin text, even with typographic punctuation and accents", () => {
    expect(pdfWouldDropCharacters("Café “quoted” – fine…")).toBe(false);
    expect(asciiPunctuation("a b")).toBe("a b");
  });

  it("is true for scripts the PDF generator cannot draw", () => {
    expect(pdfWouldDropCharacters("चाय")).toBe(true);
    expect(pdfWouldDropCharacters("茶")).toBe(true);
    expect(pdfWouldDropCharacters(reportText({ ...RESULT, unanswered: ["கேள்வி"] }))).toBe(true);
    expect(pdfWouldDropCharacters(reportText(RESULT))).toBe(false);
  });
});

describe("generateAndFetch", () => {
  const request = buildDocumentRequest("PDF", RESULT);
  const file = { blob: new Blob(["x"]), filename: "Tea-and-health.pdf" };

  function jobRow(status: string, over: Partial<DocumentGenerationJob> = {}): DocumentGenerationJob {
    return { id: "d1", status, type: "PDF", filename: "Tea-and-health.pdf", downloadUrl: "", createdAt: "", ...over };
  }

  function page(rows: DocumentGenerationJob[]) {
    return { content: rows, totalElements: rows.length, totalPages: 1, number: 0, size: 20 };
  }

  it("polls the list until the job completes, then downloads it", async () => {
    const list = vi
      .fn()
      .mockResolvedValueOnce(page([jobRow("PROCESSING")]))
      .mockResolvedValueOnce(page([jobRow("COMPLETE", { id: "other" }), jobRow("COMPLETE")]));
    const download = vi.fn().mockResolvedValue(file);
    const sleep = vi.fn().mockResolvedValue(undefined);
    const out = await generateAndFetch(
      { generate: vi.fn().mockResolvedValue(jobRow("PENDING")), list, download, sleep },
      request,
      { pollMs: 5 }
    );
    expect(out).toBe(file);
    expect(list).toHaveBeenCalledTimes(2);
    expect(sleep).toHaveBeenCalledWith(5);
    expect(download).toHaveBeenCalledWith("d1");
  });

  it("downloads straight away when the job is already complete", async () => {
    const list = vi.fn();
    const download = vi.fn().mockResolvedValue(file);
    await generateAndFetch(
      { generate: vi.fn().mockResolvedValue(jobRow("COMPLETE")), list, download, sleep: vi.fn() },
      request
    );
    expect(list).not.toHaveBeenCalled();
    expect(download).toHaveBeenCalledWith("d1");
  });

  it("reports a failed generation without downloading", async () => {
    const download = vi.fn();
    const attempt = generateAndFetch(
      {
        generate: vi.fn().mockResolvedValue(jobRow("PENDING")),
        list: vi.fn().mockResolvedValue(page([jobRow("FAILED", { errorMessage: "boom" })])),
        download,
        sleep: vi.fn().mockResolvedValue(undefined),
      },
      request
    );
    const err = await attempt.catch((e) => e);
    expect(err).toBeInstanceOf(ExportFailedError);
    expect(err.reason).toBe("failed");
    expect(err.message).toBe("boom");
    expect(download).not.toHaveBeenCalled();
  });

  it("gives up after the poll budget", async () => {
    const list = vi.fn().mockResolvedValue(page([jobRow("PROCESSING")]));
    const attempt = generateAndFetch(
      { generate: vi.fn().mockResolvedValue(jobRow("PENDING")), list, download: vi.fn(), sleep: vi.fn().mockResolvedValue(undefined) },
      request,
      { maxPolls: 3 }
    );
    await expect(attempt).rejects.toBeInstanceOf(ExportFailedError);
    expect(list).toHaveBeenCalledTimes(3);
  });

  it("reports a refused generate request as refused, with its status", async () => {
    for (const status of [403, 404, 429, 500, 503]) {
      const refused = Object.assign(new Error("nope"), { status });
      const list = vi.fn();
      const err = await generateAndFetch(
        { generate: vi.fn().mockRejectedValue(refused), list, download: vi.fn(), sleep: vi.fn() },
        request
      ).catch((e) => e);
      expect(err).toBeInstanceOf(ExportFailedError);
      expect(err.reason).toBe("refused");
      expect(err.status).toBe(status);
      expect(err.message).toBe("nope");
      expect(list).not.toHaveBeenCalled();
    }
  });

  it("does not call a network failure on the generate request a refusal", async () => {
    const offline = new Error("Failed to fetch");
    await expect(
      generateAndFetch(
        { generate: vi.fn().mockRejectedValue(offline), list: vi.fn(), download: vi.fn(), sleep: vi.fn() },
        request
      )
    ).rejects.toBe(offline);
  });

  it("does not call a failed list poll or a failed download a refusal", async () => {
    const listDown = Object.assign(new Error("list down"), { status: 503 });
    await expect(
      generateAndFetch(
        {
          generate: vi.fn().mockResolvedValue(jobRow("PENDING")),
          list: vi.fn().mockRejectedValue(listDown),
          download: vi.fn(),
          sleep: vi.fn().mockResolvedValue(undefined),
        },
        request
      )
    ).rejects.toBe(listDown);

    const gone = Object.assign(new Error("gone"), { status: 404 });
    await expect(
      generateAndFetch(
        { generate: vi.fn().mockResolvedValue(jobRow("COMPLETE")), list: vi.fn(), download: vi.fn().mockRejectedValue(gone), sleep: vi.fn() },
        request
      )
    ).rejects.toBe(gone);
  });
});
