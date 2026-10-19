import { describe, expect, it, vi } from "vitest";
import { GraduationCap, Compass } from "lucide-react";

// tutorAvailability imports the api barrel for its probe; withTutorRow (the
// only thing tested from it here) is pure, so the barrel is stubbed out.
vi.mock("../../lib/api", () => ({ api: {} }));

import type {
  TutorDueCard,
  TutorLesson,
  TutorPlan,
  TutorQuizAnswerResult,
  TutorQuizQuestion,
  TutorReviewAnswerResult,
} from "../../lib/api/tutorTypes";
import { withTutorRow } from "./tutorAvailability";
import {
  applyQuizAnswer,
  defaultTutorLanguage,
  findLesson,
  firstOpenLessonId,
  flattenLessons,
  initQuizState,
  initReviewState,
  nextLessonId,
  progressPercent,
  quizReducer,
  quizScore,
  replaceLesson,
  reviewCardKey,
  reviewReducer,
  reviewRequested,
  topicProblem,
  wrongQuestions,
} from "./tutorState";

function question(id: string, extra: Partial<TutorQuizQuestion> = {}): TutorQuizQuestion {
  return { id, question: `Question ${id}?`, options: ["a", "b", "c"], answered: false, ...extra };
}

function lesson(id: string, extra: Partial<TutorLesson> = {}): TutorLesson {
  return {
    id,
    title: `Lesson ${id}`,
    objective: "objective",
    status: "NOT_STARTED",
    generated: false,
    content: null,
    quiz: null,
    ...extra,
  };
}

function plan(): TutorPlan {
  return {
    id: "p1",
    topic: "Photosynthesis",
    language: "en",
    level: "beginner",
    goal: null,
    status: "ACTIVE",
    totalLessons: 3,
    completedLessons: 1,
    dueReviews: 0,
    tutorAgentId: null,
    createdAt: "2026-10-01T00:00:00Z",
    updatedAt: "2026-10-01T00:00:00Z",
    modules: [
      { id: "m1", title: "Basics", lessons: [lesson("m1-l1", { status: "COMPLETED" }), lesson("m1-l2")] },
      { id: "m2", title: "More", lessons: [lesson("m2-l1")] },
    ],
  };
}

function graded(questionId: string, correct: boolean): TutorQuizAnswerResult {
  return {
    questionId,
    correct,
    selectedIndex: correct ? 1 : 0,
    correctIndex: 1,
    explanation: "because",
    addedToReview: !correct,
    lessonStatus: "IN_PROGRESS",
  };
}

describe("start form helpers", () => {
  it("mirrors the server topic rule after trimming", () => {
    expect(topicProblem("")).toBe("topicTooShort");
    expect(topicProblem("  a  ")).toBe("topicTooShort");
    expect(topicProblem("ab")).toBeNull();
    expect(topicProblem("x".repeat(300))).toBeNull();
    expect(topicProblem("x".repeat(301))).toBe("topicTooLong");
    expect(topicProblem("x".repeat(11), 10)).toBe("topicTooLong");
  });

  it("defaults the teaching language to the UI locale when the picker lists it", () => {
    const supported = ["en", "hi", "pt"];
    expect(defaultTutorLanguage("hi", supported)).toBe("hi");
    expect(defaultTutorLanguage("pt-BR", supported)).toBe("pt");
    expect(defaultTutorLanguage("xx", supported)).toBe("en");
    expect(defaultTutorLanguage(null, supported)).toBe("en");
  });

  it("opens the review session only for ?review=1", () => {
    expect(reviewRequested("?review=1")).toBe(true);
    expect(reviewRequested("?a=b&review=1")).toBe(true);
    expect(reviewRequested("?review=0")).toBe(false);
    expect(reviewRequested("")).toBe(false);
    expect(reviewRequested(null)).toBe(false);
  });
});

describe("plan helpers", () => {
  it("computes a bounded progress percentage", () => {
    expect(progressPercent(0, 0)).toBe(0);
    expect(progressPercent(1, 3)).toBe(33);
    expect(progressPercent(3, 3)).toBe(100);
    expect(progressPercent(9, 3)).toBe(100);
    expect(progressPercent(-1, 3)).toBe(0);
  });

  it("walks lessons in course order across modules", () => {
    const p = plan();
    expect(flattenLessons(p).map((e) => e.lesson.id)).toEqual(["m1-l1", "m1-l2", "m2-l1"]);
    expect(findLesson(p, "m2-l1")?.moduleTitle).toBe("More");
    expect(findLesson(p, "nope")).toBeNull();
    expect(nextLessonId(p, "m1-l2")).toBe("m2-l1");
    expect(nextLessonId(p, "m2-l1")).toBeNull();
    expect(nextLessonId(p, "nope")).toBeNull();
    expect(firstOpenLessonId(p)).toBe("m1-l2");
    expect(flattenLessons(null)).toEqual([]);
  });

  it("answers null for 'continue' when every lesson is completed", () => {
    const p = plan();
    p.modules.forEach((m) => m.lessons.forEach((l) => (l.status = "COMPLETED")));
    expect(firstOpenLessonId(p)).toBeNull();
  });

  it("replaces one lesson without mutating the plan", () => {
    const p = plan();
    const written = lesson("m1-l2", { generated: true, quiz: [question("q1")] });
    const next = replaceLesson(p, written);
    expect(findLesson(next, "m1-l2")?.lesson.generated).toBe(true);
    expect(findLesson(p, "m1-l2")?.lesson.generated).toBe(false);
    expect(findLesson(next, "m1-l1")?.lesson).toBe(findLesson(p, "m1-l1")?.lesson);
  });

  it("records a graded answer on the lesson and lists the missed questions", () => {
    const start = lesson("m1-l2", { generated: true, quiz: [question("q1"), question("q2")] });
    const afterWrong = applyQuizAnswer(start, graded("q1", false));
    expect(afterWrong.status).toBe("IN_PROGRESS");
    expect(afterWrong.quiz?.[0]).toMatchObject({ answered: true, correct: false, selectedIndex: 0, correctIndex: 1 });
    expect(afterWrong.quiz?.[1].answered).toBe(false);
    expect(wrongQuestions(afterWrong)).toEqual(["Question q1?"]);
    const afterRight = applyQuizAnswer(afterWrong, graded("q2", true));
    expect(wrongQuestions(afterRight)).toEqual(["Question q1?"]);
    expect(start.quiz?.[0].answered).toBe(false);
    expect(wrongQuestions(null)).toEqual([]);
  });

  it("keeps the first answer as the record when a question is retaken", () => {
    const start = lesson("m1-l2", { generated: true, quiz: [question("q1")] });
    const first = applyQuizAnswer(start, graded("q1", false));
    const retaken = applyQuizAnswer(first, graded("q1", true));
    expect(retaken.quiz?.[0]).toMatchObject({ answered: true, correct: false });
    expect(wrongQuestions(retaken)).toEqual(["Question q1?"]);
  });

  it("keeps the lesson status when the server reports an unknown one", () => {
    const start = lesson("m1-l2", { status: "COMPLETED", quiz: [question("q1")] });
    const result = { ...graded("q1", true), lessonStatus: "SOMETHING_NEW" };
    expect(applyQuizAnswer(start, result).status).toBe("COMPLETED");
  });
});

describe("quizReducer", () => {
  const questions = [question("q1"), question("q2")];

  it("starts at the first question with nothing selected", () => {
    const state = initQuizState(questions);
    expect(state).toMatchObject({ index: 0, selected: null, phase: "answering", verdicts: {} });
    expect(initQuizState(null).order).toEqual([]);
  });

  it("resumes after the questions the server says were already answered", () => {
    const state = initQuizState([
      question("q1", { answered: true, correct: false, selectedIndex: 0, correctIndex: 2, explanation: "why" }),
      question("q2"),
    ]);
    expect(state.index).toBe(1);
    expect(state.phase).toBe("answering");
    expect(state.verdicts.q1).toMatchObject({ correct: false, correctIndex: 2, addedToReview: true });
  });

  it("opens on the results when every question was answered before", () => {
    const state = initQuizState([
      question("q1", { answered: true, correct: true, selectedIndex: 1, correctIndex: 1 }),
      question("q2", { answered: true, correct: false, selectedIndex: 0, correctIndex: 1 }),
    ]);
    expect(state.phase).toBe("done");
    expect(quizScore(state)).toEqual({ correct: 1, answered: 2, total: 2 });
  });

  it("does not trust an 'answered' question that carries no answer key", () => {
    const state = initQuizState([question("q1", { answered: true }), question("q2")]);
    expect(state.index).toBe(0);
    expect(state.verdicts).toEqual({});
  });

  it("cannot submit without a choice", () => {
    const state = initQuizState(questions);
    expect(quizReducer(state, { type: "submit" })).toBe(state);
  });

  it("runs select -> submit -> graded -> next through to the results", () => {
    let state = initQuizState(questions);
    state = quizReducer(state, { type: "select", optionIndex: 0 });
    expect(state.selected).toBe(0);
    state = quizReducer(state, { type: "submit" });
    expect(state.phase).toBe("submitting");
    // The choice is locked while the server grades.
    expect(quizReducer(state, { type: "select", optionIndex: 2 })).toBe(state);
    state = quizReducer(state, { type: "graded", result: graded("q1", false) });
    expect(state.phase).toBe("feedback");
    expect(state.verdicts.q1).toMatchObject({ correct: false, addedToReview: true, explanation: "because" });
    state = quizReducer(state, { type: "next" });
    expect(state).toMatchObject({ index: 1, selected: null, phase: "answering" });
    state = quizReducer(state, { type: "select", optionIndex: 1 });
    state = quizReducer(state, { type: "submit" });
    state = quizReducer(state, { type: "graded", result: graded("q2", true) });
    state = quizReducer(state, { type: "next" });
    expect(state.phase).toBe("done");
    expect(quizScore(state)).toEqual({ correct: 1, answered: 2, total: 2 });
  });

  it("ignores a verdict for a question that is not on screen", () => {
    let state = initQuizState(questions);
    state = quizReducer(state, { type: "select", optionIndex: 0 });
    state = quizReducer(state, { type: "submit" });
    expect(quizReducer(state, { type: "graded", result: graded("q2", true) })).toBe(state);
  });

  it("returns to answering with the choice kept when the request fails", () => {
    let state = initQuizState(questions);
    state = quizReducer(state, { type: "select", optionIndex: 2 });
    state = quizReducer(state, { type: "submit" });
    state = quizReducer(state, { type: "failed", error: "No connection" });
    expect(state).toMatchObject({ phase: "answering", selected: 2, error: "No connection" });
    expect(quizReducer(state, { type: "select", optionIndex: 1 }).error).toBeNull();
  });

  it("restart clears the verdicts and goes back to the first question", () => {
    let state = initQuizState([
      question("q1", { answered: true, correct: true, selectedIndex: 1, correctIndex: 1 }),
      question("q2", { answered: true, correct: true, selectedIndex: 1, correctIndex: 1 }),
    ]);
    state = quizReducer(state, { type: "restart" });
    expect(state).toMatchObject({ index: 0, phase: "answering", verdicts: {} });
  });

  it("'next' only works from feedback", () => {
    const state = initQuizState(questions);
    expect(quizReducer(state, { type: "next" })).toBe(state);
  });
});

describe("reviewReducer", () => {
  function card(cardId: string, planId = "p1"): TutorDueCard {
    return {
      planId,
      planTopic: "Photosynthesis",
      language: "en",
      cardId,
      lessonId: "m1-l1",
      question: `Card ${cardId}?`,
      options: ["a", "b"],
      dueAt: "2026-10-03T10:00:00Z",
      reps: 0,
      lapses: 1,
    };
  }

  function answered(cardId: string, correct: boolean, remainingDue: number): TutorReviewAnswerResult {
    return {
      cardId,
      correct,
      selectedIndex: 0,
      correctIndex: correct ? 0 : 1,
      explanation: "because",
      nextDueAt: "2026-10-04T10:00:00Z",
      intervalDays: 1,
      remainingDue,
    };
  }

  it("is done immediately when nothing is due", () => {
    expect(initReviewState([]).phase).toBe("done");
    expect(initReviewState(null).cards).toEqual([]);
  });

  it("uses the server's total as the starting remaining count", () => {
    expect(initReviewState([card("m1-l1:q1")], 30).remainingDue).toBe(30);
    expect(initReviewState([card("m1-l1:q1")]).remainingDue).toBe(1);
  });

  it("walks the cards, counting right answers and tracking what is still due", () => {
    let state = initReviewState([card("m1-l1:q1"), card("m1-l1:q2")], 5);
    state = reviewReducer(state, { type: "select", optionIndex: 0 });
    state = reviewReducer(state, { type: "submit" });
    state = reviewReducer(state, { type: "graded", result: answered("m1-l1:q1", true, 4) });
    expect(state).toMatchObject({ phase: "feedback", answered: 1, correct: 1, remainingDue: 4 });
    expect(state.result?.intervalDays).toBe(1);
    state = reviewReducer(state, { type: "next" });
    expect(state).toMatchObject({ index: 1, phase: "answering", selected: null, result: null });
    state = reviewReducer(state, { type: "select", optionIndex: 0 });
    state = reviewReducer(state, { type: "submit" });
    state = reviewReducer(state, { type: "graded", result: answered("m1-l1:q2", false, 4) });
    state = reviewReducer(state, { type: "next" });
    expect(state).toMatchObject({ phase: "done", answered: 2, correct: 1, remainingDue: 4 });
  });

  it("ignores a verdict for a different card and cannot submit unselected", () => {
    let state = initReviewState([card("m1-l1:q1")]);
    expect(reviewReducer(state, { type: "submit" })).toBe(state);
    state = reviewReducer(state, { type: "select", optionIndex: 1 });
    state = reviewReducer(state, { type: "submit" });
    expect(reviewReducer(state, { type: "graded", result: answered("other", true, 0) })).toBe(state);
  });

  it("a failed answer can be retried or the card skipped", () => {
    let state = initReviewState([card("m1-l1:q1"), card("m1-l1:q2")]);
    state = reviewReducer(state, { type: "select", optionIndex: 1 });
    state = reviewReducer(state, { type: "submit" });
    state = reviewReducer(state, { type: "failed", error: "gone" });
    expect(state).toMatchObject({ phase: "answering", selected: 1, error: "gone", answered: 0 });
    state = reviewReducer(state, { type: "skip" });
    expect(state).toMatchObject({ index: 1, phase: "answering", error: null, answered: 0 });
    state = reviewReducer(state, { type: "skip" });
    expect(state.phase).toBe("done");
  });

  it("keys a card by plan and card id", () => {
    expect(reviewCardKey(card("m1-l1:q1", "p9"))).toBe("p9/m1-l1:q1");
  });
});

describe("withTutorRow (jump palette)", () => {
  const rows = [
    { href: "/help", icon: Compass, label: "Guide", groupLabel: "Help" },
    { href: "/learn", icon: Compass, label: "Learn", groupLabel: "Help" },
    { href: "/help/docs", icon: Compass, label: "Docs", groupLabel: "Help" },
  ];

  it("adds the tutor right after Learn, in Learn's group, only when available", () => {
    expect(withTutorRow(rows, false, GraduationCap, "Personal tutor")).toBe(rows);
    const out = withTutorRow(rows, true, GraduationCap, "Personal tutor");
    expect(out.map((r) => r.href)).toEqual(["/help", "/learn", "/learn/tutor", "/help/docs"]);
    expect(out[2]).toMatchObject({ label: "Personal tutor", groupLabel: "Help" });
  });

  it("adds nothing when the rail has no Learn row, and never adds it twice", () => {
    const noLearn = rows.filter((r) => r.href !== "/learn");
    expect(withTutorRow(noLearn, true, GraduationCap, "Personal tutor")).toBe(noLearn);
    const once = withTutorRow(rows, true, GraduationCap, "Personal tutor");
    expect(withTutorRow(once, true, GraduationCap, "Personal tutor")).toBe(once);
  });
});
