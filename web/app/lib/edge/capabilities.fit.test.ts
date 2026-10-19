import { describe, expect, it } from "vitest";
import { modelFitState, modelFits, type EdgeCapabilities, type ManifestModel } from "./capabilities";

const GB = 1024 ** 3;
function caps(over: Partial<EdgeCapabilities> = {}): EdgeCapabilities {
  return {
    version: 1,
    detectedAt: 1,
    webgpu: { available: true, maxBufferSize: 4 * GB, maxStorageBufferBindingSize: 4 * GB },
    promptApi: { shape: "none", availability: "unavailable" },
    deviceMemoryGB: null,
    hardwareConcurrency: 10,
    connection: { saveData: false, effectiveType: null },
    ...over,
  };
}
function model(minGB: number, over: Partial<ManifestModel> = {}): ManifestModel {
  return { id: "m", displayName: "M", sizeBytes: 1, minDeviceMemoryGB: minGB, minMaxBufferSize: 0, tier: 1, ...over };
}

describe("modelFitState", () => {
  it("Safari/Firefox (memory unreported) is unverified, not a failure", () => {
    expect(modelFitState(model(8), caps({ deviceMemoryGB: null }), null)).toBe("unverified");
    expect(modelFits(model(8), caps({ deviceMemoryGB: null }))).toBe(false);
  });
  it("Chrome's capped reading of 8 cannot confirm an 8 or 16 GB need", () => {
    expect(modelFitState(model(8), caps({ deviceMemoryGB: 8 }), null)).toBe("unverified");
    expect(modelFitState(model(16), caps({ deviceMemoryGB: 8 }), null)).toBe("unverified");
  });
  it("a real low reading is a confirmed no", () => {
    expect(modelFitState(model(8), caps({ deviceMemoryGB: 4 }), null)).toBe("no");
  });
  it("no WebGPU or a too-small buffer is a confirmed no", () => {
    expect(
      modelFitState(
        model(4),
        caps({ webgpu: { available: false, maxBufferSize: null, maxStorageBufferBindingSize: null } }),
        null
      )
    ).toBe("no");
    expect(modelFitState(model(0, { minMaxBufferSize: 8 * GB }), caps(), null)).toBe("no");
  });
  it("a declared 24 GB confirms every floor up to 24, even when the browser says unknown or 8", () => {
    expect(modelFitState(model(16), caps({ deviceMemoryGB: null }), 24)).toBe("fits");
    expect(modelFitState(model(16), caps({ deviceMemoryGB: 8 }), 24)).toBe("fits");
    expect(modelFitState(model(32), caps({ deviceMemoryGB: 8 }), 24)).toBe("no");
  });
  it("a declared value scales with a raised context window", () => {
    expect(modelFitState(model(8, { contextWindowSize: 8192 }), caps(), 12)).toBe("no");
    expect(modelFitState(model(8, { contextWindowSize: 8192 }), caps(), 16)).toBe("fits");
  });
  it("floor-less models always fit when WebGPU is fine", () => {
    expect(modelFitState(model(0), caps(), null)).toBe("fits");
  });
});
