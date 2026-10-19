import { describe, expect, it } from "vitest";
import { collectionNotEmptyDocumentCount, duplicateUploadFilename } from "./knowledgeErrors";

const err = (status: number, message: string) => Object.assign(new Error(message), { status });

describe("knowledgeErrors", () => {
  it("reads the document count from the collection-not-empty 409", () => {
    const e = err(
      409,
      "Cannot delete: 3 document(s) are still in it - delete each one first (DELETE /api/knowledge/documents/{documentId})"
    );
    expect(collectionNotEmptyDocumentCount(e)).toBe(3);
  });

  it("ignores other 409s and other statuses", () => {
    expect(collectionNotEmptyDocumentCount(err(409, "default collection must always exist"))).toBeNull();
    expect(collectionNotEmptyDocumentCount(err(500, "3 document(s)"))).toBeNull();
    expect(collectionNotEmptyDocumentCount(null)).toBeNull();
  });

  it("reads the existing file name from the duplicate-upload error", () => {
    const m = 'This file\'s content is already in this collection as "report.pdf" (document 1b2c, uploaded 2026).';
    expect(duplicateUploadFilename(err(400, m))).toBe("report.pdf");
    expect(duplicateUploadFilename(err(409, m))).toBe("report.pdf");
    expect(duplicateUploadFilename(err(500, m))).toBeNull();
    expect(duplicateUploadFilename(err(400, "unsupported type"))).toBeNull();
  });
});
