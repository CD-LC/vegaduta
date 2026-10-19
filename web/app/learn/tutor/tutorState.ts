// Pure state logic for /learn/tutor: no React, no DOM, no network. The quiz
// and review-session reducers and the small plan helpers live here so they are
// unit-tested without rendering anything (tutorState.test.ts).
//
// Grading is NEVER done here. The server holds the answer key and returns the
// verdict; these reducers only record what the server said.

import type {
  TutorDueCard,
  TutorLesson,
  TutorLevel,
  TutorPlan,
  TutorQuizAnswerResult,
  TutorQuizQuestion,
  TutorReviewAnswerResult,
} from "../../lib/api/tutorTypes";

export const TUTOR_LEVELS: readonly TutorLevel[] = ["beginner", "intermediate", "advanced"];

/** tutorPage.* message key for each level's label. */
export const LEVEL_LABEL_KEY: Record<TutorLevel, string> = {
  beginner: "levelBeginner",
  intermediate: "levelIntermediate",
  advanced: "levelAdvanced",
};

/** Fallbacks used only until GET /api/tutor/status has answered. */
export const DEFAULT_MAX_TOPIC_CHARS = 300;
export const MIN_TOPIC_CHARS = 2;

// ---------------------------------------------------------------------------
// Start form
// ---------------------------------------------------------------------------

export type TopicProblem = "topicTooShort" | "topicTooLong";

/** Mirrors the server rule (2..maxTopicChars after trimming). */
export function topicProblem(topic: string, maxChars: number = DEFAULT_MAX_TOPIC_CHARS): TopicProblem | null {
  const length = (topic || "").trim().length;
  if (length < MIN_TOPIC_CHARS) return "topicTooShort";
  if (length > maxChars) return "topicTooLong";
  return null;
}

/**
 * The teaching language offered by default: the console's active locale when
 * it is one the picker lists, otherwise English.
 */
export function defaultTutorLanguage(locale: string | null | undefined, supported: readonly string[]): string {
  const wanted = (locale || "").trim();
  if (wanted && supported.includes(wanted)) return wanted;
  const base = wanted.split(/[-_]/)[0];
  if (base && supported.includes(base)) return base;
  return "en";
}

/** True when the URL asks to open the review session (?review=1). */
export function reviewRequested(search: string | null | undefined): boolean {
  if (!search) return false;
  try {
    return new URLSearchParams(search).get("review") === "1";
  } catch {
    return false;
  }
}

// ---------------------------------------------------------------------------
// Plan helpers
// ---------------------------------------------------------------------------

export function progressPercent(completed: number, total: number): number {
  if (!total || total <= 0) return 0;
  const pct = Math.round((Math.max(0, completed) / total) * 100);
  return Math.min(100, Math.max(0, pct));
}

export type FlatLesson = { moduleId: string; moduleTitle: string; lesson: TutorLesson };

export function flattenLessons(plan: Pick<TutorPlan, "modules"> | null | undefined): FlatLesson[] {
  const out: FlatLesson[] = [];
  for (const mod of plan?.modules || []) {
    for (const lesson of mod.lessons || []) {
      out.push({ moduleId: mod.id, moduleTitle: mod.title, lesson });
    }
  }
  return out;
}

export function findLesson(plan: Pick<TutorPlan, "modules"> | null | undefined, lessonId: string): FlatLesson | null {
  return flattenLessons(plan).find((entry) => entry.lesson.id === lessonId) || null;
}

/** The lesson after `lessonId` in course order, or null on the last one. */
export function nextLessonId(plan: Pick<TutorPlan, "modules"> | null | undefined, lessonId: string): string | null {
  const flat = flattenLessons(plan);
  const at = flat.findIndex((entry) => entry.lesson.id === lessonId);
  if (at < 0 || at + 1 >= flat.length) return null;
  return flat[at + 1].lesson.id;
}

/** Where "Continue" should land: the first lesson that is not completed. */
export function firstOpenLessonId(plan: Pick<TutorPlan, "modules"> | null | undefined): string | null {
  const open = flattenLessons(plan).find((entry) => entry.lesson.status !== "COMPLETED");
  return open ? open.lesson.id : null;
}

/** Returns a copy of the plan with one lesson replaced (matched by id). */
export function replaceLesson<P extends Pick<TutorPlan, "modules">>(plan: P, lesson: TutorLesson): P {
  return {
    ...plan,
    modules: plan.modules.map((module) => ({
      ...module,
      lessons: module.lessons.map((existing) => (existing.id === lesson.id ? lesson : existing)),
    })),
  };
}

/**
 * Records a graded answer on the lesson so the plan held in memory matches
 * what the server now stores (the question becomes answered and carries the
 * key, and a NOT_STARTED lesson moves to whatever status the server reported).
 *
 * The server keeps the FIRST answer to a question as the record; a retake is
 * graded for practice only. So a question that is already answered keeps its
 * stored pick and verdict here too, and a reload shows the same thing.
 */
export function applyQuizAnswer(lesson: TutorLesson, result: TutorQuizAnswerResult): TutorLesson {
  const status =
    result.lessonStatus === "NOT_STARTED" || result.lessonStatus === "IN_PROGRESS" || result.lessonStatus === "COMPLETED"
      ? result.lessonStatus
      : lesson.status;
  return {
    ...lesson,
    status,
    quiz: (lesson.quiz || []).map((question) =>
      question.id === result.questionId && !question.answered
        ? {
            ...question,
            answered: true,
            selectedIndex: result.selectedIndex,
            correct: result.correct,
            correctIndex: result.correctIndex,
            explanation: result.explanation,
          }
        : question
    ),
  };
}

/** The questions the learner last answered wrongly, in quiz order. */
export function wrongQuestions(lesson: Pick<TutorLesson, "quiz"> | null | undefined): string[] {
  return (lesson?.quiz || [])
    .filter((question) => question.answered && question.correct === false)
    .map((question) => question.question);
}

// ---------------------------------------------------------------------------
// Lesson quiz: one question at a time, graded by the server
// ---------------------------------------------------------------------------

export type QuizPhase = "answering" | "submitting" | "feedback" | "done";

export type QuizVerdict = {
  correct: boolean;
  selectedIndex: number;
  correctIndex: number;
  explanation: string;
  addedToReview: boolean;
};

export type QuizState = {
  /** Question ids in display order. */
  order: string[];
  /** Index into `order` of the question on screen; === order.length when done. */
  index: number;
  /** The option picked for the current question, before it is submitted. */
  selected: number | null;
  phase: QuizPhase;
  /** Server verdicts by question id. */
  verdicts: Record<string, QuizVerdict>;
  error: string | null;
};

export type QuizAction =
  | { type: "select"; optionIndex: number }
  | { type: "submit" }
  | { type: "graded"; result: TutorQuizAnswerResult }
  | { type: "failed"; error: string }
  | { type: "next" }
  | { type: "restart" };

function verdictOf(question: TutorQuizQuestion): QuizVerdict | null {
  if (
    !question.answered ||
    typeof question.correct !== "boolean" ||
    typeof question.selectedIndex !== "number" ||
    typeof question.correctIndex !== "number"
  ) {
    return null;
  }
  return {
    correct: question.correct,
    selectedIndex: question.selectedIndex,
    correctIndex: question.correctIndex,
    explanation: question.explanation || "",
    // The server adds a card exactly when the answer was wrong.
    addedToReview: !question.correct,
  };
}

/**
 * Builds the starting state from what the server already knows: questions
 * answered in an earlier visit keep their verdicts and the quiz resumes at
 * the first unanswered one.
 */
export function initQuizState(questions: TutorQuizQuestion[] | null | undefined): QuizState {
  const list = questions || [];
  const verdicts: Record<string, QuizVerdict> = {};
  for (const question of list) {
    const verdict = verdictOf(question);
    if (verdict) verdicts[question.id] = verdict;
  }
  const order = list.map((question) => question.id);
  const firstOpen = order.findIndex((id) => !verdicts[id]);
  const done = order.length > 0 && firstOpen < 0;
  return {
    order,
    index: done ? order.length : Math.max(0, firstOpen),
    selected: null,
    phase: done ? "done" : "answering",
    verdicts,
    error: null,
  };
}

export function quizReducer(state: QuizState, action: QuizAction): QuizState {
  switch (action.type) {
    case "select":
      if (state.phase !== "answering") return state;
      return { ...state, selected: action.optionIndex, error: null };
    case "submit":
      if (state.phase !== "answering" || state.selected == null) return state;
      return { ...state, phase: "submitting", error: null };
    case "graded": {
      if (state.phase !== "submitting") return state;
      const { result } = action;
      // A verdict for a question that is not on screen is a stale response.
      if (state.order[state.index] !== result.questionId) return state;
      return {
        ...state,
        phase: "feedback",
        verdicts: {
          ...state.verdicts,
          [result.questionId]: {
            correct: result.correct,
            selectedIndex: result.selectedIndex,
            correctIndex: result.correctIndex,
            explanation: result.explanation || "",
            addedToReview: result.addedToReview,
          },
        },
      };
    }
    case "failed":
      if (state.phase !== "submitting") return state;
      // Back to answering with the choice kept, so one click retries.
      return { ...state, phase: "answering", error: action.error };
    case "next": {
      if (state.phase !== "feedback") return state;
      const index = state.index + 1;
      const done = index >= state.order.length;
      return { ...state, index, selected: null, phase: done ? "done" : "answering", error: null };
    }
    case "restart":
      if (state.order.length === 0) return state;
      return { ...state, index: 0, selected: null, phase: "answering", verdicts: {}, error: null };
    default:
      return state;
  }
}

export function quizScore(state: QuizState): { correct: number; answered: number; total: number } {
  const verdicts = state.order.map((id) => state.verdicts[id]).filter(Boolean);
  return {
    correct: verdicts.filter((verdict) => verdict.correct).length,
    answered: verdicts.length,
    total: state.order.length,
  };
}

// ---------------------------------------------------------------------------
// Review session: the due cards, one by one
// ---------------------------------------------------------------------------

export type ReviewPhase = "answering" | "submitting" | "feedback" | "done";

export type ReviewState = {
  cards: TutorDueCard[];
  /** Index of the card on screen; === cards.length when done. */
  index: number;
  selected: number | null;
  phase: ReviewPhase;
  /** The server's verdict for the card on screen (feedback phase only). */
  result: TutorReviewAnswerResult | null;
  answered: number;
  correct: number;
  /** Due cards left across all courses, as last reported by the server. */
  remainingDue: number;
  error: string | null;
};

export type ReviewAction =
  | { type: "select"; optionIndex: number }
  | { type: "submit" }
  | { type: "graded"; result: TutorReviewAnswerResult }
  | { type: "failed"; error: string }
  | { type: "skip" }
  | { type: "next" };

export function initReviewState(cards: TutorDueCard[] | null | undefined, totalDue?: number): ReviewState {
  const list = cards || [];
  return {
    cards: list,
    index: 0,
    selected: null,
    phase: list.length === 0 ? "done" : "answering",
    result: null,
    answered: 0,
    correct: 0,
    remainingDue: typeof totalDue === "number" ? totalDue : list.length,
    error: null,
  };
}

function advance(state: ReviewState): ReviewState {
  const index = state.index + 1;
  return {
    ...state,
    index,
    selected: null,
    result: null,
    error: null,
    phase: index >= state.cards.length ? "done" : "answering",
  };
}

export function reviewReducer(state: ReviewState, action: ReviewAction): ReviewState {
  switch (action.type) {
    case "select":
      if (state.phase !== "answering") return state;
      return { ...state, selected: action.optionIndex, error: null };
    case "submit":
      if (state.phase !== "answering" || state.selected == null) return state;
      return { ...state, phase: "submitting", error: null };
    case "graded": {
      if (state.phase !== "submitting") return state;
      const card = state.cards[state.index];
      if (!card || card.cardId !== action.result.cardId) return state;
      return {
        ...state,
        phase: "feedback",
        result: action.result,
        answered: state.answered + 1,
        correct: state.correct + (action.result.correct ? 1 : 0),
        remainingDue: Math.max(0, action.result.remainingDue),
      };
    }
    case "failed":
      if (state.phase !== "submitting") return state;
      return { ...state, phase: "answering", error: action.error };
    case "skip":
      // A card the server no longer knows (deleted course) can be stepped over.
      if (state.phase !== "answering") return state;
      return advance(state);
    case "next":
      if (state.phase !== "feedback") return state;
      return advance(state);
    default:
      return state;
  }
}

/** A card id is "<lessonId>:<questionId>"; the same card can be due in two plans. */
export function reviewCardKey(card: Pick<TutorDueCard, "planId" | "cardId">): string {
  return `${card.planId}/${card.cardId}`;
}
