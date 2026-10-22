// Smoke coverage for /learn/tutor with the api mocked: the page hides itself
// when the server has the feature off (404 on the status probe), shows the
// start form and the course list when it is on, opens a course, and honours
// the ?review=1 deep link used by the review-due notification.

import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { ReactNode } from "react";

const apiMock = vi.hoisted(() => ({
  getTutorStatus: vi.fn(),
  listTutorPlans: vi.fn(),
  getTutorPlan: vi.fn(),
  getTutorDueReviews: vi.fn(),
  generateTutorPlan: vi.fn(),
  deleteTutorPlan: vi.fn(),
}));

vi.mock("../../lib/api", () => ({ api: apiMock }));

vi.mock("../../components/AppShell", () => ({
  default: ({ children }: { children?: ReactNode }) => <div>{children}</div>,
}));

vi.mock("../../components/ax/PageHeader", () => ({
  default: ({ title }: { title?: ReactNode }) => <h1>{title}</h1>,
}));

vi.mock("../../components/RouteTransition", () => ({
  usePageReady: (): void => undefined,
}));

vi.mock("../../components/Select", () => ({
  default: ({ id, value, onChange, options }: any) => (
    <select id={id} value={value} onChange={onChange}>
      {(options || []).map((o: any) => (
        <option key={o.value} value={o.value}>
          {o.label}
        </option>
      ))}
    </select>
  ),
}));

// The lesson's chat/voice panel has its own heavy imports (call panel, voice
// chain); the smoke test covers the page states, not the conversation.
vi.mock("./TutorChat", () => ({
  default: (): null => null,
  LessonListenButton: (): null => null,
}));

vi.mock("next/link", () => ({
  default: ({ href, children }: { href: string; children?: ReactNode }) => <a href={href}>{children}</a>,
}));

vi.mock("../../lib/i18n", () => ({
  useTranslations: () => (key: string) => key,
  useLocale: () => "en",
  SUPPORTED_LOCALES: [
    { code: "en", label: "English", dir: "ltr" },
    { code: "hi", label: "Hindi", dir: "ltr" },
  ],
}));

import TutorPage from "./page";

const STATUS = {
  enabled: true,
  generationAvailable: true,
  remindersEnabled: false,
  limits: {
    maxPlansPerUser: 20,
    maxLessonsPerPlan: 60,
    maxTopicChars: 300,
    reviewSessionCap: 12,
    generationsPerHour: 10,
  },
};

const SUMMARY = {
  id: "p1",
  topic: "Photosynthesis",
  language: "en",
  level: "beginner",
  goal: null as string | null,
  status: "ACTIVE",
  totalLessons: 2,
  completedLessons: 1,
  dueReviews: 3,
  tutorAgentId: null as string | null,
  createdAt: "2026-10-01T00:00:00Z",
  updatedAt: "2026-10-01T00:00:00Z",
};

describe("/learn/tutor", () => {
  beforeEach(() => {
    Object.values(apiMock).forEach((fn) => fn.mockReset());
    window.history.replaceState({}, "", "/learn/tutor");
  });

  afterEach(() => {
    cleanup();
  });

  it("shows a plain 'not available' state when the feature is off (404)", async () => {
    apiMock.getTutorStatus.mockRejectedValue(
      Object.assign(new Error("No endpoint matches this request."), { status: 404 })
    );
    render(<TutorPage />);
    expect(await screen.findByText("unavailableTitle")).toBeInTheDocument();
    // The only action must go somewhere that exists: /learn has no index page any more.
    expect(screen.getByRole("link", { name: "backToHelp" })).toHaveAttribute("href", "/help");
    expect(screen.queryByTestId("tutor-start")).not.toBeInTheDocument();
    expect(apiMock.listTutorPlans).not.toHaveBeenCalled();
  });

  it("shows the error and a retry for any other status failure", async () => {
    apiMock.getTutorStatus.mockRejectedValue(Object.assign(new Error("Service restarting"), { status: 503 }));
    render(<TutorPage />);
    expect(await screen.findByText("Service restarting")).toBeInTheDocument();
    expect(screen.getByText("retry")).toBeInTheDocument();
  });

  it("shows the start form and an empty course list when on", async () => {
    apiMock.getTutorStatus.mockResolvedValue(STATUS);
    apiMock.listTutorPlans.mockResolvedValue([]);
    render(<TutorPage />);
    expect(await screen.findByTestId("tutor-start")).toBeInTheDocument();
    expect(await screen.findByText("noCoursesTitle")).toBeInTheDocument();
    expect(screen.queryByTestId("tutor-due-banner")).not.toBeInTheDocument();
  });

  it("lists courses with the due banner and opens one", async () => {
    apiMock.getTutorStatus.mockResolvedValue(STATUS);
    apiMock.listTutorPlans.mockResolvedValue([SUMMARY]);
    apiMock.getTutorPlan.mockResolvedValue({
      ...SUMMARY,
      modules: [
        {
          id: "m1",
          title: "Basics",
          lessons: [
            {
              id: "m1-l1",
              title: "Light",
              objective: "o",
              status: "COMPLETED",
              generated: true,
              content: null,
              quiz: null,
            },
            {
              id: "m1-l2",
              title: "Water",
              objective: "o",
              status: "NOT_STARTED",
              generated: false,
              content: null,
              quiz: null,
            },
          ],
        },
      ],
    });
    render(<TutorPage />);
    expect(await screen.findByTestId("tutor-course-p1")).toBeInTheDocument();
    expect(screen.getByTestId("tutor-due-banner")).toBeInTheDocument();
    fireEvent.click(screen.getByText("openCourse"));
    expect(await screen.findByTestId("tutor-plan")).toBeInTheDocument();
    expect(apiMock.getTutorPlan).toHaveBeenCalledWith("p1");
    expect(screen.getByTestId("tutor-lesson-row-m1-l2")).toBeInTheDocument();
  });

  it("opens the review session for ?review=1", async () => {
    window.history.replaceState({}, "", "/learn/tutor?review=1");
    apiMock.getTutorStatus.mockResolvedValue(STATUS);
    apiMock.listTutorPlans.mockResolvedValue([]);
    apiMock.getTutorDueReviews.mockResolvedValue({ cards: [], totalDue: 0, sessionCap: 12 });
    render(<TutorPage />);
    expect(await screen.findByTestId("tutor-review")).toBeInTheDocument();
    await waitFor(() => expect(apiMock.getTutorDueReviews).toHaveBeenCalled());
    expect(await screen.findByText("reviewEmptyTitle")).toBeInTheDocument();
  });
});
