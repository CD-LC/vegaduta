// /playground composer: sandbox data files (xlsx always; csv/json/pdf/txt when
// the model reads no documents) go through the upload endpoint and ride the
// turn as an attachmentKey, never inline base64.

import { describe, it, expect, vi } from "vitest";
import {
  XLSX_MIME_TYPE,
  addFiles,
  addPickedFiles,
  effectiveMimeType,
  attachHint,
  buildAccept,
  describeModalities,
  isSandboxUploadFile,
  readModalities,
  splitForSend,
  type AgentModalities,
  type PendingAttachment,
} from "./ChatAttachments";

const base: AgentModalities = {
  image: false,
  audio: false,
  video: false,
  document: false,
  sandboxData: false,
  maxAttachmentBytes: 10 * 1024 * 1024,
  maxAttachments: 3,
};
const sandboxOnly: AgentModalities = { ...base, sandboxData: true };
const sandboxAndDocs: AgentModalities = { ...base, sandboxData: true, document: true };

function file(name: string, type: string, size = 4): File {
  return new File([new Uint8Array(size)], name, { type });
}

describe("readModalities with sandboxData", () => {
  it("is non-null when sandboxData is the only flag set", () => {
    const m = readModalities({ modalities: { image: false, audio: false, video: false, document: false, sandboxData: true } });
    expect(m).not.toBeNull();
    expect(m?.sandboxData).toBe(true);
  });

  it("stays null for an older core payload with every flag false and no sandboxData field", () => {
    expect(readModalities({ modalities: { image: false, audio: false, video: false, document: false } })).toBeNull();
    expect(readModalities({})).toBeNull();
  });

  it("defaults sandboxData to false when the field is absent", () => {
    expect(readModalities({ modalities: { image: true } })?.sandboxData).toBe(false);
  });
});

describe("buildAccept / describeModalities with sandboxData", () => {
  it("offers xlsx and the data document types when only the sandbox is on", () => {
    const accept = buildAccept(sandboxOnly).split(",");
    expect(accept).toContain(".xlsx");
    expect(accept).toContain(XLSX_MIME_TYPE);
    expect(accept).toContain("text/csv");
    expect(accept).toContain("application/pdf");
  });

  it("does not offer xlsx without the sandbox", () => {
    const accept = buildAccept({ ...base, document: true });
    expect(accept).not.toContain("xlsx");
    expect(accept).not.toContain(XLSX_MIME_TYPE);
  });

  it("does not list the document types twice when both are on", () => {
    const accept = buildAccept(sandboxAndDocs).split(",");
    expect(accept.filter((p) => p === "text/csv")).toHaveLength(1);
  });

  it("names spreadsheets in the tooltip", () => {
    expect(describeModalities(sandboxOnly)).toContain("XLSX");
    expect(describeModalities(sandboxAndDocs)).toContain("spreadsheets (XLSX)");
    expect(describeModalities({ ...base, document: true })).not.toContain("XLSX");
  });
});

describe("isSandboxUploadFile", () => {
  it("is always false without the sandbox", () => {
    expect(isSandboxUploadFile(file("a.xlsx", XLSX_MIME_TYPE), { ...base, document: true })).toBe(false);
    expect(isSandboxUploadFile(file("a.csv", "text/csv"), base)).toBe(false);
  });

  it("routes a workbook through upload whatever the model reads", () => {
    expect(isSandboxUploadFile(file("a.xlsx", XLSX_MIME_TYPE), sandboxOnly)).toBe(true);
    expect(isSandboxUploadFile(file("a.xlsx", XLSX_MIME_TYPE), sandboxAndDocs)).toBe(true);
  });

  it("recognises a workbook the OS reported without a type, by extension", () => {
    expect(isSandboxUploadFile(file("Sales Q3.XLSX", ""), sandboxOnly)).toBe(true);
    expect(isSandboxUploadFile(file("a.xlsx", "application/octet-stream"), sandboxOnly)).toBe(true);
    // A typed file is trusted over its name.
    expect(isSandboxUploadFile(file("a.xlsx", "image/png"), sandboxOnly)).toBe(false);
  });

  it("routes csv/json/pdf/txt through upload only when the model has no document input", () => {
    for (const type of ["text/csv", "application/json", "application/pdf", "text/plain"]) {
      expect(isSandboxUploadFile(file("d", type), sandboxOnly)).toBe(true);
      expect(isSandboxUploadFile(file("d", type), sandboxAndDocs)).toBe(false);
    }
  });

  it("never routes media through upload", () => {
    expect(isSandboxUploadFile(file("a.png", "image/png"), { ...sandboxOnly, image: true })).toBe(false);
  });
});

describe("addPickedFiles", () => {
  it("uploads a workbook and keeps the returned key instead of base64", async () => {
    const upload = vi.fn().mockResolvedValue({ attachmentKey: "chat-attachments/agent/uuid", mimeType: XLSX_MIME_TYPE });
    const picked = file("sales.xlsx", XLSX_MIME_TYPE, 12);
    const result = await addPickedFiles([], [picked], sandboxOnly, upload);
    expect(upload).toHaveBeenCalledTimes(1);
    expect(upload.mock.calls[0][0]).toBe(picked);
    expect("attachments" in result).toBe(true);
    if (!("attachments" in result)) return;
    expect(result.attachments).toHaveLength(1);
    expect(result.attachments[0]).toMatchObject({
      fileName: "sales.xlsx",
      mimeType: XLSX_MIME_TYPE,
      sizeBytes: 12,
      dataBase64: "",
      previewUrl: null,
      uploaded: { attachmentKey: "chat-attachments/agent/uuid", mimeType: XLSX_MIME_TYPE },
    });
  });

  it("re-types an untyped .xlsx before uploading so the server can categorise it", async () => {
    const upload = vi.fn().mockResolvedValue({ attachmentKey: "k", mimeType: XLSX_MIME_TYPE });
    await addPickedFiles([], [file("sales.xlsx", "")], sandboxOnly, upload);
    const sent: File = upload.mock.calls[0][0];
    expect(sent.type).toBe(XLSX_MIME_TYPE);
    expect(sent.name).toBe("sales.xlsx");
  });

  it("accepts a .csv or .xlsx that Windows reports as application/vnd.ms-excel, under its real type", async () => {
    expect(effectiveMimeType(file("q3.csv", "application/vnd.ms-excel"))).toBe("text/csv");
    expect(effectiveMimeType(file("q3.xlsx", "application/vnd.ms-excel"))).toBe(XLSX_MIME_TYPE);
    // A specific type from the browser always wins over the extension.
    expect(effectiveMimeType(file("notes.csv", "text/plain"))).toBe("text/plain");
    // A real legacy .xls is still not a supported type.
    expect(effectiveMimeType(file("old.xls", "application/vnd.ms-excel"))).toBe("application/vnd.ms-excel");

    const upload = vi.fn().mockResolvedValue({ attachmentKey: "k", mimeType: "text/csv" });
    const result = await addPickedFiles([], [file("q3.csv", "application/vnd.ms-excel")], sandboxOnly, upload);
    expect("attachments" in result).toBe(true);
    expect((upload.mock.calls[0][0] as File).type).toBe("text/csv");

    const legacy = await addPickedFiles([], [file("old.xls", "application/vnd.ms-excel")], sandboxOnly, vi.fn());
    expect("error" in legacy).toBe(true);
  });

  it("does not count a stored data file against the inline total-size cap", async () => {
    const existing: PendingAttachment[] = [
      {
        id: "att-1",
        fileName: "big.xlsx",
        mimeType: XLSX_MIME_TYPE,
        sizeBytes: 14,
        dataBase64: "",
        previewUrl: null,
        uploaded: { attachmentKey: "k", mimeType: XLSX_MIME_TYPE },
      },
    ];
    const result = await addFiles(existing, [file("photo.png", "image/png", 8)], {
      ...sandboxOnly,
      image: true,
      maxAttachmentBytes: 16,
    });
    expect("attachments" in result).toBe(true);
    if ("attachments" in result) expect(result.attachments).toHaveLength(2);
  });

  it("returns the server's rejection sentence and attaches nothing", async () => {
    const upload = vi.fn().mockRejectedValue(new Error("Spreadsheets are analysed in the code sandbox. Turn on Code execution."));
    const result = await addPickedFiles([], [file("a.xlsx", XLSX_MIME_TYPE)], sandboxOnly, upload);
    expect(result).toEqual({ error: "Spreadsheets are analysed in the code sandbox. Turn on Code execution." });
  });

  it("treats a response without a key as a failed upload", async () => {
    const result = await addPickedFiles([], [file("a.xlsx", XLSX_MIME_TYPE)], sandboxOnly, vi.fn().mockResolvedValue({}));
    expect("error" in result).toBe(true);
  });

  it("allows one stored data file per message", async () => {
    const upload = vi.fn();
    const two = await addPickedFiles(
      [],
      [file("a.xlsx", XLSX_MIME_TYPE), file("b.xlsx", XLSX_MIME_TYPE)],
      sandboxOnly,
      upload
    );
    expect("error" in two).toBe(true);

    const existing: PendingAttachment[] = [
      {
        id: "att-1",
        fileName: "a.xlsx",
        mimeType: XLSX_MIME_TYPE,
        sizeBytes: 4,
        dataBase64: "",
        previewUrl: null,
        uploaded: { attachmentKey: "k", mimeType: XLSX_MIME_TYPE },
      },
    ];
    const second = await addPickedFiles(existing, [file("b.xlsx", XLSX_MIME_TYPE)], sandboxOnly, upload);
    expect("error" in second).toBe(true);
    expect(upload).not.toHaveBeenCalled();
  });

  it("refuses an oversized data file before uploading", async () => {
    const upload = vi.fn();
    const result = await addPickedFiles(
      [],
      [file("big.xlsx", XLSX_MIME_TYPE, 32)],
      { ...sandboxOnly, maxAttachmentBytes: 16 },
      upload
    );
    expect("error" in result).toBe(true);
    expect(upload).not.toHaveBeenCalled();
  });

  it("refuses a pick that exceeds the per-message file count before uploading", async () => {
    const upload = vi.fn();
    const result = await addPickedFiles(
      [],
      [file("a.xlsx", XLSX_MIME_TYPE)],
      { ...sandboxOnly, maxAttachments: 0 },
      upload
    );
    expect("error" in result).toBe(true);
    expect(upload).not.toHaveBeenCalled();
  });

  it("never calls upload for an agent without the sandbox", async () => {
    const upload = vi.fn();
    const result = await addPickedFiles([], [file("a.xlsx", XLSX_MIME_TYPE)], { ...base, document: true }, upload);
    // Falls through to the inline validator, which rejects the type.
    expect("error" in result).toBe(true);
    expect(upload).not.toHaveBeenCalled();
  });
});

describe("splitForSend", () => {
  const inline: PendingAttachment = {
    id: "att-1",
    fileName: "photo.png",
    mimeType: "image/png",
    sizeBytes: 3,
    dataBase64: "QUJD",
    previewUrl: "data:image/png;base64,QUJD",
  };
  const stored: PendingAttachment = {
    id: "att-2",
    fileName: "sales.xlsx",
    mimeType: XLSX_MIME_TYPE,
    sizeBytes: 9,
    dataBase64: "",
    previewUrl: null,
    uploaded: { attachmentKey: "k", mimeType: XLSX_MIME_TYPE },
  };

  it("sends the stored file as the key and only real inline files as base64", () => {
    expect(splitForSend([inline, stored])).toEqual({
      stored: { attachmentKey: "k", mimeType: XLSX_MIME_TYPE },
      inline: [{ fileName: "photo.png", mimeType: "image/png", dataBase64: "QUJD" }],
    });
  });

  it("leaves a turn with no stored file exactly as before (null key, all inline)", () => {
    expect(splitForSend([inline])).toEqual({
      stored: null,
      inline: [{ fileName: "photo.png", mimeType: "image/png", dataBase64: "QUJD" }],
    });
    expect(splitForSend([])).toEqual({ stored: null, inline: [] });
  });
});

describe("sandboxDataUnavailableReason (D5)", () => {
  const reason = "Turn on Code execution for this agent (Capabilities) to analyse attached files.";

  it("is carried through readModalities when the server sends it", () => {
    const m = readModalities({ modalities: { image: true, sandboxData: false, sandboxDataUnavailableReason: reason } });
    expect(m?.sandboxDataUnavailableReason).toBe(reason);
  });

  it("adds no key when the server sends none, so a default payload normalizes as before", () => {
    const m = readModalities({ modalities: { image: true, sandboxData: false } });
    expect(m).not.toBeNull();
    expect(Object.prototype.hasOwnProperty.call(m, "sandboxDataUnavailableReason")).toBe(false);
  });

  it("does not make a text-only agent attachable just because a reason exists", () => {
    expect(readModalities({ modalities: { sandboxData: false, sandboxDataUnavailableReason: reason } })).toBeNull();
  });

  it("appends the reason to the paperclip tooltip", () => {
    const withReason: AgentModalities = { ...base, image: true, sandboxDataUnavailableReason: reason };
    expect(attachHint(withReason)).toBe(`${describeModalities(withReason)} ${reason}`);
  });

  it("leaves the tooltip alone without a reason, or when the sandbox is available", () => {
    const plain: AgentModalities = { ...base, image: true };
    expect(attachHint(plain)).toBe(describeModalities(plain));
    const available: AgentModalities = { ...sandboxOnly, sandboxDataUnavailableReason: reason };
    expect(attachHint(available)).toBe(describeModalities(available));
  });
});
