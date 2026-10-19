// Response/request shapes of core's personal-tutor API (/api/tutor/**,
// com.agenticai.core.tutor). Re-exported through ./types so pages keep
// importing from "../lib/api"; kept in its own file because types.ts is a
// hot shared file.

export type TutorLevel = "beginner" | "intermediate" | "advanced";
export type TutorPlanStatus = "ACTIVE" | "COMPLETED";
export type TutorLessonStatus = "NOT_STARTED" | "IN_PROGRESS" | "COMPLETED";

export interface TutorStatus {
  enabled: boolean;
  generationAvailable: boolean;
  remindersEnabled: boolean;
  limits: {
    maxPlansPerUser: number;
    maxLessonsPerPlan: number;
    maxTopicChars: number;
    reviewSessionCap: number;
    generationsPerHour: number;
  };
}

export interface TutorPlanSummary {
  id: string;
  topic: string;
  /** BCP-47 tag of the language the course is taught in, e.g. "en", "hi". */
  language: string;
  level: TutorLevel;
  goal: string | null;
  status: TutorPlanStatus;
  totalLessons: number;
  completedLessons: number;
  dueReviews: number;
  tutorAgentId: string | null;
  createdAt: string;
  updatedAt: string;
}

/**
 * While `answered` is false the four optional fields are ABSENT from the JSON:
 * the answer key is never sent before the learner has attempted the question.
 */
export interface TutorQuizQuestion {
  id: string;
  question: string;
  options: string[];
  answered: boolean;
  selectedIndex?: number;
  correct?: boolean;
  correctIndex?: number;
  explanation?: string;
}

export interface TutorLessonContent {
  /** Markdown, written in the plan's language. */
  explanation: string;
  keyPoints: string[];
  workedExample: string | null;
}

export interface TutorLesson {
  id: string;
  title: string;
  objective: string;
  status: TutorLessonStatus;
  /** false => content and quiz are null until the lesson is generated. */
  generated: boolean;
  content: TutorLessonContent | null;
  quiz: TutorQuizQuestion[] | null;
}

export interface TutorModule {
  id: string;
  title: string;
  lessons: TutorLesson[];
}

export interface TutorPlan extends TutorPlanSummary {
  modules: TutorModule[];
}

export interface TutorGeneratePlanRequest {
  topic: string;
  level?: TutorLevel;
  language?: string;
  goal?: string;
  weeklyMinutes?: number;
}

export interface TutorQuizAnswerResult {
  questionId: string;
  correct: boolean;
  selectedIndex: number;
  correctIndex: number;
  explanation: string;
  /** true exactly when the answer was wrong. */
  addedToReview: boolean;
  lessonStatus: string;
}

/** A review card that is due. Never carries the answer. */
export interface TutorDueCard {
  planId: string;
  planTopic: string;
  language: string;
  cardId: string;
  lessonId: string;
  question: string;
  options: string[];
  dueAt: string;
  reps: number;
  lapses: number;
}

export interface TutorDueReviews {
  cards: TutorDueCard[];
  totalDue: number;
  sessionCap: number;
}

export interface TutorReviewAnswerResult {
  cardId: string;
  correct: boolean;
  selectedIndex: number;
  correctIndex: number;
  explanation: string;
  nextDueAt: string;
  intervalDays: number;
  remainingDue: number;
}

export interface TutorAgentResult {
  agentId: string;
  created: boolean;
}
