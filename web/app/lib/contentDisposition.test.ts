import { describe, expect, it } from "vitest";
import { decodeEncodedWords, filenameFromContentDisposition } from "./contentDisposition";
import { filenameFromDisposition } from "./api/workflowPortability";

// Prod signed-in pass 2026-10-05, bug 3: a workflow export was saved as
// "=?UTF-8?Q?e2e-workflow.vegaduta-workflow.json?=".

describe("filenameFromContentDisposition", () => {
  it("reads the header core sent on prod without keeping the MIME encoded-word", () => {
    const header =
      "attachment; filename=\"=?UTF-8?Q?e2e-workflow.vegaduta-workflow.json?=\"; " +
      "filename*=UTF-8''e2e-workflow.vegaduta-workflow.json";
    expect(filenameFromContentDisposition(header, "x")).toBe("e2e-workflow.vegaduta-workflow.json");
    expect(filenameFromDisposition(header, "x")).toBe("e2e-workflow.vegaduta-workflow.json");
  });

  it("prefers filename* over filename whichever comes first", () => {
    expect(filenameFromContentDisposition("attachment; filename=\"caf_.txt\"; filename*=UTF-8''caf%C3%A9.txt", "x")).toBe("café.txt");
    expect(filenameFromContentDisposition("attachment; filename*=UTF-8''caf%C3%A9.txt; filename=\"caf_.txt\"", "x")).toBe("café.txt");
    expect(filenameFromContentDisposition("attachment; filename*=UTF-8'en'my%20flow.json", "x")).toBe("my flow.json");
  });

  it("decodes an encoded-word that arrives with no filename* beside it", () => {
    expect(filenameFromContentDisposition('attachment; filename="=?UTF-8?Q?caf=C3=A9_menu.txt?="', "x")).toBe("café menu.txt");
    expect(filenameFromContentDisposition('attachment; filename="=?UTF-8?B?Y2Fmw6kudHh0?="', "x")).toBe("café.txt");
    // A charset this client does not decode is left as it came rather than mangled.
    expect(decodeEncodedWords("=?KOI8-R?Q?abc?=")).toBe("=?KOI8-R?Q?abc?=");
  });

  it("reads plain quoted and bare names, and falls back when there is none", () => {
    expect(filenameFromContentDisposition('attachment; filename="report 1.csv"', "x")).toBe("report 1.csv");
    expect(filenameFromContentDisposition("attachment; filename=flow.json", "x")).toBe("flow.json");
    expect(filenameFromContentDisposition('attachment; filename="a\\"b.txt"', "x")).toBe("ab.txt");
    expect(filenameFromContentDisposition("attachment", "fallback.json")).toBe("fallback.json");
    expect(filenameFromContentDisposition(null, "fallback.json")).toBe("fallback.json");
    expect(filenameFromContentDisposition("attachment; filename*=UTF-8''100%.json", "x")).toBe("100%.json");
  });

  it("never returns a path or control characters", () => {
    expect(filenameFromContentDisposition('attachment; filename="../../etc/passwd"', "x")).toBe("passwd");
    expect(filenameFromContentDisposition("attachment; filename*=UTF-8''..%2F..%2Fevil.sh", "x")).toBe("evil.sh");
    expect(filenameFromContentDisposition("attachment; filename*=UTF-8''a%0Ab.txt", "x")).toBe("ab.txt");
  });
});
