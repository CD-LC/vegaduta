import { describe, it, expect } from "vitest";
import { extraStatusComponents, humanizeKey } from "./statusComponents";

describe("extraStatusComponents", () => {
  it("renders unknown components generically and skips the named ones", () => {
    const out = extraStatusComponents({
      overallStatus: "NOMINAL",
      database: { status: "UP" },
      redis: { status: "UP" },
      temporal: { status: "UP" },
      codeIntel: { status: "DOWN" },
      minio: { status: "NOT_CONFIGURED" },
      langfuse: { status: "UP", detail: "v3" },
      jvm: { heapUsedMb: 10 },
      notAComponent: { foo: 1 },
      list: [{ status: "UP" }],
      scalar: "UP",
    });
    expect(out.map((c) => c.key)).toEqual(["codeIntel", "langfuse", "minio", "temporal"]);
    expect(out.find((c) => c.key === "minio")?.detail).toBe("not configured");
    expect(out.find((c) => c.key === "langfuse")?.detail).toBe("v3");
    expect(out.find((c) => c.key === "codeIntel")?.label).toBe("Code intel");
  });

  it("tolerates garbage", () => {
    expect(extraStatusComponents(null)).toEqual([]);
    expect(extraStatusComponents("x")).toEqual([]);
  });

  it("humanizeKey", () => {
    expect(humanizeKey("diskSpace")).toBe("Disk space");
    expect(humanizeKey("academy_api")).toBe("Academy api");
  });
});
