// The entry-point probe: one request per page life for a definitive answer,
// asked again after a passing failure.

import { beforeEach, describe, expect, it, vi } from "vitest";

const apiMock = vi.hoisted(() => ({ probeTutorStatus: vi.fn() }));
vi.mock("../../lib/api", () => ({ api: apiMock }));

import { resetTutorAvailability, tutorAvailable } from "./tutorAvailability";

function failure(status: number) {
  return Object.assign(new Error("failed"), { status });
}

describe("tutorAvailable", () => {
  beforeEach(() => {
    apiMock.probeTutorStatus.mockReset();
    resetTutorAvailability();
  });

  it("remembers that the tutor is on", async () => {
    apiMock.probeTutorStatus.mockResolvedValue({ enabled: true });
    expect(await tutorAvailable()).toBe(true);
    expect(await tutorAvailable()).toBe(true);
    expect(apiMock.probeTutorStatus).toHaveBeenCalledTimes(1);
  });

  it("remembers a 404 (feature off) without asking again", async () => {
    apiMock.probeTutorStatus.mockRejectedValue(failure(404));
    expect(await tutorAvailable()).toBe(false);
    expect(await tutorAvailable()).toBe(false);
    expect(apiMock.probeTutorStatus).toHaveBeenCalledTimes(1);
  });

  it("treats enabled:false as off", async () => {
    apiMock.probeTutorStatus.mockResolvedValue({ enabled: false });
    expect(await tutorAvailable()).toBe(false);
  });

  it("asks again after a passing failure", async () => {
    apiMock.probeTutorStatus.mockRejectedValueOnce(failure(503));
    expect(await tutorAvailable()).toBe(false);
    apiMock.probeTutorStatus.mockResolvedValue({ enabled: true });
    expect(await tutorAvailable()).toBe(true);
    expect(apiMock.probeTutorStatus).toHaveBeenCalledTimes(2);
  });
});
