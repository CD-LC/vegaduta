import { apiFetch, toJson, toJsonQuietOn, jsonBody } from "./core";
import type {
  TutorAgentResult,
  TutorDueReviews,
  TutorGeneratePlanRequest,
  TutorLesson,
  TutorPlan,
  TutorPlanSummary,
  TutorQuizAnswerResult,
  TutorReviewAnswerResult,
  TutorStatus,
} from "./tutorTypes";

// Personal tutor (core com.agenticai.core.tutor, /api/tutor/**). Everything is
// scoped server-side to the signed-in member; no call names a user or tenant.
//
// With the feature flag off core maps NO /api/tutor route, so every call
// answers 404. getTutorStatus is quiet on that 404: it is the page's "is this
// feature here?" probe, not a failure worth a toast.
//
// The calls whose refusals the page renders inline (a rejected topic, the plan
// cap, the hourly limit, a model that produced nothing usable - 422 -, a
// gateway failure, a full agent roster) are quiet on those statuses for the same reason; the error still
// throws with .status and the server's plain-language message.
const enc = encodeURIComponent;

export const tutorApi = {
  getTutorStatus: (): Promise<TutorStatus> => apiFetch("/api/tutor/status").then(toJsonQuietOn([404])),

  // The same probe for entry points on OTHER pages (the card on /learn, the
  // jump palette row). A learner who did not ask for the tutor must never get
  // a toast about it, so server-side failures are quiet here too.
  probeTutorStatus: (): Promise<TutorStatus> =>
    apiFetch("/api/tutor/status", { skipGetCache: true }).then(toJsonQuietOn([404, 500, 502, 503, 504])),

  listTutorPlans: (): Promise<TutorPlanSummary[]> => apiFetch("/api/tutor/plans").then(toJson<TutorPlanSummary[]>),

  generateTutorPlan: (request: TutorGeneratePlanRequest): Promise<TutorPlan> =>
    apiFetch("/api/tutor/plans/generate", { method: "POST", ...jsonBody(request) }).then(
      toJsonQuietOn([400, 409, 422, 429, 502, 503])
    ),

  getTutorPlan: (planId: string): Promise<TutorPlan> => apiFetch(`/api/tutor/plans/${enc(planId)}`).then(toJson<TutorPlan>),

  deleteTutorPlan: (planId: string): Promise<null> =>
    apiFetch(`/api/tutor/plans/${enc(planId)}`, { method: "DELETE" }).then(toJson<null>),

  // Idempotent: the first call writes the lesson (one model call, several
  // seconds), later calls return the cached lesson.
  generateTutorLesson: (planId: string, lessonId: string): Promise<TutorLesson> =>
    apiFetch(`/api/tutor/plans/${enc(planId)}/lessons/${enc(lessonId)}/generate`, { method: "POST" }).then(
      toJsonQuietOn([400, 409, 422, 429, 502, 503])
    ),

  answerTutorQuiz: (
    planId: string,
    lessonId: string,
    questionId: string,
    selectedIndex: number
  ): Promise<TutorQuizAnswerResult> =>
    apiFetch(`/api/tutor/plans/${enc(planId)}/lessons/${enc(lessonId)}/answers`, {
      method: "POST",
      ...jsonBody({ questionId, selectedIndex }),
    }).then(toJsonQuietOn([400, 404, 409])),

  completeTutorLesson: (planId: string, lessonId: string): Promise<TutorPlan> =>
    apiFetch(`/api/tutor/plans/${enc(planId)}/lessons/${enc(lessonId)}/complete`, { method: "POST" }).then(toJson<TutorPlan>),

  // skipGetCache: what is due changes with the clock, not only with this
  // browser's own writes, so a remembered copy would hide newly due cards.
  getTutorDueReviews: (): Promise<TutorDueReviews> =>
    apiFetch("/api/tutor/reviews/due", { skipGetCache: true }).then(toJson<TutorDueReviews>),

  // cardId looks like "m1-l2:q1" - the colon must be encoded in the path.
  answerTutorReview: (planId: string, cardId: string, selectedIndex: number): Promise<TutorReviewAnswerResult> =>
    apiFetch(`/api/tutor/reviews/${enc(planId)}/${enc(cardId)}/answer`, {
      method: "POST",
      ...jsonBody({ selectedIndex }),
    }).then(toJsonQuietOn([400, 404, 409])),

  // Find-or-create the caller's own tutor agent. 409 = the workspace has no
  // room for another agent; lessons, quizzes and reviews keep working.
  ensureTutorAgent: (planId?: string | null): Promise<TutorAgentResult> =>
    apiFetch("/api/tutor/agent", { method: "POST", ...jsonBody(planId ? { planId } : {}) }).then(
      toJsonQuietOn([404, 409])
    ),

  // The tutor agent's own row (provider, model) for the call panel. Best
  // effort: the caller falls back to the id alone, so no failure toasts.
  // skipGetCache: the agent may have been created one call earlier.
  getTutorAgentRow: (agentId: string): Promise<any> =>
    apiFetch(`/api/agents/${enc(agentId)}`, { skipGetCache: true }).then(
      toJsonQuietOn([400, 403, 404, 409, 429, 500, 502, 503, 504])
    ),
};
