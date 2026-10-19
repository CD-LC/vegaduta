import { describe, expect, it } from "vitest";
import { nextSort, sortTasks } from "./scheduledTaskSort";
import type { ScheduledTask } from "../../lib/scheduledTasks";

const t = (id: string, over: Partial<ScheduledTask> = {}) =>
  ({ id, name: id, enabled: true, nextRunAt: null, lastRunAt: null, runCount: 0, createdAt: "2026-01-01T00:00:00Z", ...over }) as ScheduledTask;
const ids = (xs: ScheduledTask[]) => xs.map((x) => x.id);

describe("sortTasks", () => {
  const list = [
    t("b", { nextRunAt: "2026-02-01T00:00:00Z", runCount: 3, createdAt: "2026-01-02T00:00:00Z" }),
    t("a", { nextRunAt: "2026-01-15T00:00:00Z", lastRunAt: "2026-01-10T00:00:00Z", runCount: 9 }),
    t("c", { enabled: false, nextRunAt: "2026-01-01T00:00:00Z", createdAt: "2026-01-03T00:00:00Z" }),
  ];
  it("sorts by name both ways", () => {
    expect(ids(sortTasks(list, { key: "name", dir: "asc" }))).toEqual(["a", "b", "c"]);
    expect(ids(sortTasks(list, { key: "name", dir: "desc" }))).toEqual(["c", "b", "a"]);
  });
  it("keeps tasks with no next run (paused) last in both directions", () => {
    expect(ids(sortTasks(list, { key: "nextRun", dir: "asc" }))).toEqual(["a", "b", "c"]);
    expect(ids(sortTasks(list, { key: "nextRun", dir: "desc" }))).toEqual(["b", "a", "c"]);
  });
  it("puts never-run tasks last when sorting by last run", () => {
    expect(ids(sortTasks(list, { key: "lastRun", dir: "desc" }))[0]).toBe("a");
  });
  it("defaults to newest created first and does not mutate the input", () => {
    const before = ids(list);
    expect(ids(sortTasks(list, { key: "created", dir: "desc" }))).toEqual(["c", "b", "a"]);
    expect(ids(list)).toEqual(before);
  });
  it("toggles direction on the active column and starts a new column sensibly", () => {
    expect(nextSort({ key: "name", dir: "asc" }, "name")).toEqual({ key: "name", dir: "desc" });
    expect(nextSort({ key: "name", dir: "asc" }, "lastRun")).toEqual({ key: "lastRun", dir: "desc" });
  });
});
