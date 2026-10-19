// Wire-shape coverage for the personal-tutor client: paths, methods, bodies,
// path encoding (a review card id contains a colon), and which statuses stay
// quiet so the page can render them inline. apiFetch is mocked at the seam.

import { beforeEach, describe, expect, it, vi } from "vitest";

const apiFetchMock = vi.hoisted(() => vi.fn());
const quietCalls = vi.hoisted(() => [] as number[][]);
vi.mock("./core", () => ({
  apiFetch: apiFetchMock,
  toJson: async (res: any) => res.json(),
  toJsonQuietOn: (statuses: number[]) => {
    quietCalls.push(statuses);
    return async (res: any) => {
      if (!res.ok) {
        const err: any = new Error("quiet");
        err.status = res.status;
        throw err;
      }
      return res.json();
    };
  },
  jsonBody: (body: any) => ({
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  }),
}));

import { tutorApi } from "./tutor";

function okJson(body: any) {
  return Promise.resolve({ ok: true, status: 200, json: async () => body } as any);
}

function lastCall(): [string, any] {
  const call = apiFetchMock.mock.calls[apiFetchMock.mock.calls.length - 1];
  return [call[0], call[1]];
}

describe("tutorApi", () => {
  beforeEach(() => {
    apiFetchMock.mockReset();
    quietCalls.length = 0;
  });

  it("probes status quietly on 404 and surfaces the status to the caller", async () => {
    apiFetchMock.mockReturnValue(Promise.resolve({ ok: false, status: 404, json: async () => ({}) }));
    await expect(tutorApi.getTutorStatus()).rejects.toMatchObject({ status: 404 });
    expect(lastCall()[0]).toBe("/api/tutor/status");
    expect(quietCalls).toEqual([[404]]);
  });

  it("generates a plan with the topic, level and language", async () => {
    apiFetchMock.mockReturnValue(okJson({ id: "p1" }));
    const plan = await tutorApi.generateTutorPlan({ topic: "Photosynthesis", level: "beginner", language: "hi" });
    expect(plan.id).toBe("p1");
    const [url, init] = lastCall();
    expect(url).toBe("/api/tutor/plans/generate");
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body)).toEqual({ topic: "Photosynthesis", level: "beginner", language: "hi" });
    // Refusals the page renders inline must not also raise a global toast.
    expect(quietCalls[0]).toEqual(expect.arrayContaining([400, 409, 422, 429, 502]));
  });

  it("lists, reads and deletes plans on the plan routes", async () => {
    apiFetchMock.mockReturnValue(okJson([]));
    await tutorApi.listTutorPlans();
    expect(lastCall()[0]).toBe("/api/tutor/plans");

    apiFetchMock.mockReturnValue(okJson({ id: "p 1" }));
    await tutorApi.getTutorPlan("p 1");
    expect(lastCall()[0]).toBe("/api/tutor/plans/p%201");

    apiFetchMock.mockReturnValue(okJson(null));
    await tutorApi.deleteTutorPlan("p1");
    const [url, init] = lastCall();
    expect(url).toBe("/api/tutor/plans/p1");
    expect(init.method).toBe("DELETE");
  });

  it("writes a lesson with a body-less POST", async () => {
    apiFetchMock.mockReturnValue(okJson({ id: "m1-l1", generated: true }));
    await tutorApi.generateTutorLesson("p1", "m1-l1");
    const [url, init] = lastCall();
    expect(url).toBe("/api/tutor/plans/p1/lessons/m1-l1/generate");
    expect(init.method).toBe("POST");
    expect(init.body).toBeUndefined();
    // 422 = the model produced nothing usable; rendered inline, never a toast.
    expect(quietCalls[quietCalls.length - 1]).toEqual(expect.arrayContaining([409, 422, 429, 502]));
  });

  it("probes for entry points without toasting on server-side failures", async () => {
    apiFetchMock.mockReturnValue(Promise.resolve({ ok: false, status: 503, json: async () => ({}) }));
    await expect(tutorApi.probeTutorStatus()).rejects.toMatchObject({ status: 503 });
    expect(lastCall()[0]).toBe("/api/tutor/status");
    expect(quietCalls[quietCalls.length - 1]).toEqual(expect.arrayContaining([404, 500, 502, 503, 504]));
  });

  it("reads the tutor agent row quietly and without the GET cache", async () => {
    apiFetchMock.mockReturnValue(okJson({ id: "a 1", providerName: "OPENAI", model: "m" }));
    const row = await tutorApi.getTutorAgentRow("a 1");
    expect(row.model).toBe("m");
    const [url, init] = lastCall();
    expect(url).toBe("/api/agents/a%201");
    expect(init.skipGetCache).toBe(true);
    expect(quietCalls[quietCalls.length - 1]).toEqual(expect.arrayContaining([404, 500, 503]));
  });

  it("submits a quiz answer as questionId + selectedIndex, including index 0", async () => {
    apiFetchMock.mockReturnValue(okJson({ questionId: "q1", correct: false }));
    await tutorApi.answerTutorQuiz("p1", "m1-l1", "q1", 0);
    const [url, init] = lastCall();
    expect(url).toBe("/api/tutor/plans/p1/lessons/m1-l1/answers");
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body)).toEqual({ questionId: "q1", selectedIndex: 0 });
  });

  it("completes a lesson", async () => {
    apiFetchMock.mockReturnValue(okJson({ id: "p1" }));
    await tutorApi.completeTutorLesson("p1", "m1-l1");
    const [url, init] = lastCall();
    expect(url).toBe("/api/tutor/plans/p1/lessons/m1-l1/complete");
    expect(init.method).toBe("POST");
  });

  it("reads due reviews without the GET cache", async () => {
    apiFetchMock.mockReturnValue(okJson({ cards: [], totalDue: 0, sessionCap: 12 }));
    await tutorApi.getTutorDueReviews();
    const [url, init] = lastCall();
    expect(url).toBe("/api/tutor/reviews/due");
    expect(init.skipGetCache).toBe(true);
  });

  it("encodes the colon in a review card id", async () => {
    apiFetchMock.mockReturnValue(okJson({ cardId: "m1-l2:q1", correct: true }));
    await tutorApi.answerTutorReview("p1", "m1-l2:q1", 2);
    const [url, init] = lastCall();
    expect(url).toBe("/api/tutor/reviews/p1/m1-l2%3Aq1/answer");
    expect(JSON.parse(init.body)).toEqual({ selectedIndex: 2 });
  });

  it("provisions the tutor agent with an optional plan id, quiet on 409", async () => {
    apiFetchMock.mockReturnValue(okJson({ agentId: "a1", created: true }));
    await tutorApi.ensureTutorAgent("p1");
    let [url, init] = lastCall();
    expect(url).toBe("/api/tutor/agent");
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body)).toEqual({ planId: "p1" });
    expect(quietCalls[quietCalls.length - 1]).toEqual(expect.arrayContaining([409]));

    await tutorApi.ensureTutorAgent();
    [url, init] = lastCall();
    expect(JSON.parse(init.body)).toEqual({});
  });
});
