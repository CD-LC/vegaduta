// run_code hands the model "/api/sandbox/artifacts/<uuid>" for each chart/xlsx/script it saved;
// the signed-in chat only renders them if MediaCard recognises that path.

import { describe, it, expect } from "vitest";
import { extractMediaRefs } from "./MediaCard";

const A = "0f8fad5b-d9cb-469f-a165-70867728950e";
const B = "1a8fad5b-d9cb-469f-a165-70867728950f";

describe("MediaCard sandbox artifact refs", () => {
  it("recognises bare sandbox artifact URLs, one per line", () => {
    const refs = extractMediaRefs(`Chart:\n/api/sandbox/artifacts/${A}\nWorkbook:\n/api/sandbox/artifacts/${B}`);
    expect(refs.map((r) => r.kind)).toEqual(["sandbox-artifact", "sandbox-artifact"]);
    expect(refs.map((r) => r.id)).toEqual([A, B]);
  });

  it("recognises a markdown-linked artifact", () => {
    const refs = extractMediaRefs(`[Download the workbook](/api/sandbox/artifacts/${A})`);
    expect(refs.map((r) => r.kind)).toEqual(["sandbox-artifact"]);
  });
});
