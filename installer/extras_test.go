package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// chainCatalog: app -> mid -> base, plus an independent add-on and a planned one.
func chainCatalog(t *testing.T) Catalog {
	t.Helper()
	mk := func(id string, req ...string) map[string]any {
		return map[string]any{"id": id, "name": strings.ToUpper(id), "description": "d", "category": "tools",
			"kind": "compose-profile", "availability": "available", "sizeMb": 100, "minRamMb": 200,
			"needsGpu": false, "license": "MIT", "free": true, "requires": req, "defaultOn": false,
			"compose": map[string]any{"profiles": []string{}, "flags": []string{}, "env": map[string]string{}}}
	}
	planned := mk("later")
	planned["kind"], planned["availability"], planned["compose"] = "planned", "planned", nil
	b, _ := json.Marshal(map[string]any{"schemaVersion": 1, "addons": []any{
		mk("base"), mk("mid", "base"), mk("app", "mid"), mk("solo"), planned}})
	c, err := ParseCatalog(b)
	if err != nil {
		t.Fatal(err)
	}
	return c
}

func TestResolveAddsTransitiveRequirementsWithNotes(t *testing.T) {
	c := chainCatalog(t)
	got, notes := Resolve(c, selectionOf("app"))
	if strings.Join(got.IDs(c), ",") != "base,mid,app" {
		t.Errorf("closure = %v", got.IDs(c))
	}
	if len(notes) != 2 || notes[0].ID != "base" || notes[1].ID != "mid" {
		t.Fatalf("notes = %+v", notes)
	}
	if !strings.Contains(notes[1].Text, "APP needs it") {
		t.Errorf("note text = %q", notes[1].Text)
	}
	// Idempotent: resolving a closed selection changes nothing and explains nothing.
	again, notes2 := Resolve(c, got)
	if strings.Join(again.IDs(c), ",") != "base,mid,app" || len(notes2) != 0 {
		t.Errorf("second resolve changed things: %v %v", again.IDs(c), notes2)
	}
}

func TestResolveDropsPlannedAndUnknown(t *testing.T) {
	c := chainCatalog(t)
	got, _ := Resolve(c, selectionOf("later", "ghost", "solo"))
	if strings.Join(got.IDs(c), ",") != "solo" {
		t.Errorf("got %v", got.IDs(c))
	}
}

func TestToggleOnAndOff(t *testing.T) {
	c := chainCatalog(t)
	sel, notes, err := Toggle(c, Selection{}, "app", true)
	if err != nil || strings.Join(sel.IDs(c), ",") != "base,mid,app" || len(notes) != 2 {
		t.Fatalf("on: %v %v %v", sel.IDs(c), notes, err)
	}
	// Turning the bottom of the chain off takes everything above it with it, and says so.
	sel, notes, err = Toggle(c, sel, "base", false)
	if err != nil || len(sel) != 0 {
		t.Fatalf("off: %v %v", sel.IDs(c), err)
	}
	if len(notes) != 2 || notes[0].Kind != "dropped" || !strings.Contains(notes[0].Text, "needs BASE") {
		t.Errorf("notes = %+v", notes)
	}
	// An unrelated add-on survives.
	sel, _, _ = Toggle(c, selectionOf("app", "mid", "base", "solo"), "mid", false)
	if strings.Join(sel.IDs(c), ",") != "base,solo" {
		t.Errorf("got %v", sel.IDs(c))
	}
	if _, _, err := Toggle(c, Selection{}, "later", true); err == nil {
		t.Error("a planned add-on must not toggle on")
	}
	if _, _, err := Toggle(c, Selection{}, "ghost", true); err == nil {
		t.Error("an unknown add-on must be an error")
	}
}

func TestBuildPlanMapsCatalogToLauncher(t *testing.T) {
	c := mustCatalog(t)
	sel, _ := Resolve(c, selectionOf("workspace", "search", "documents", "browser", "tools", "model-qwen35-4b"))
	p := BuildPlan(c, sel, nil)
	if got := strings.Join(p.Profiles, ","); got != "workspace,rag,tools" {
		t.Errorf("profiles = %q (catalog order, catalog names)", got)
	}
	if got := strings.Join(p.Flags, ","); got != "--search,--browser" {
		t.Errorf("flags = %q", got)
	}
	if len(p.EnvSet) != 0 {
		t.Errorf("env = %v (no shipped add-on sets .env keys any more)", p.EnvSet)
	}
	if len(p.Pulls) != 1 || p.Pulls[0].Tag != "qwen3.5:4b" {
		t.Errorf("pulls = %+v", p.Pulls)
	}
	if len(p.FlagsOff) != 0 || len(p.EnvUnset) != 0 {
		t.Errorf("nothing is off, got %v %v", p.FlagsOff, p.EnvUnset)
	}
	// Everything the catalog does not select is listed as off, whether the launcher switches it with a flag or a profile.
	if q := BuildPlan(c, selectionOf("workspace"), nil); strings.Join(q.FlagsOff, ",") != "--search,--browser,profile:tools" {
		t.Errorf("flagsOff = %v", q.FlagsOff)
	}
}

func TestBuildPlanForDeselected(t *testing.T) {
	c := mustCatalog(t)
	cur := selectionOf("workspace", "search", "documents", "browser", "tools")
	p := BuildPlan(c, selectionOf("workspace"), cur)
	if strings.Join(p.Profiles, ",") != "workspace" {
		t.Errorf("profiles = %v", p.Profiles)
	}
	if strings.Join(p.FlagsOff, ",") != "--search,--browser,profile:tools" {
		t.Errorf("flagsOff = %v (the tools runner is a profile the launcher keeps as a choice, so it has an off entry too)", p.FlagsOff)
	}
	if len(p.EnvUnset) != 0 {
		t.Errorf("envUnset = %v", p.EnvUnset)
	}
	if strings.Join(p.Turned, ",") != "search,documents,browser,tools" {
		t.Errorf("turned off = %v", p.Turned)
	}
	// Nothing selected still yields a valid, empty plan.
	empty := BuildPlan(c, Selection{}, nil)
	if len(empty.Profiles) != 0 || len(empty.Flags) != 0 || len(empty.EnvSet) != 0 {
		t.Errorf("empty plan = %+v", empty)
	}
}

func TestSharedEnvKeyIsOnlyUnsetWhenNobodyWantsIt(t *testing.T) {
	b, _ := json.Marshal(map[string]any{"schemaVersion": 1, "addons": []any{
		map[string]any{"id": "one", "name": "One", "description": "d", "category": "tools", "kind": "compose-profile",
			"availability": "available", "license": "MIT", "requires": []string{},
			"compose": map[string]any{"env": map[string]string{"SHARED_KEY": "x"}}},
		map[string]any{"id": "two", "name": "Two", "description": "d", "category": "tools", "kind": "compose-profile",
			"availability": "available", "license": "MIT", "requires": []string{},
			"compose": map[string]any{"env": map[string]string{"SHARED_KEY": "x"}}},
	}})
	c, err := ParseCatalog(b)
	if err != nil {
		t.Fatal(err)
	}
	if p := BuildPlan(c, selectionOf("one"), nil); len(p.EnvUnset) != 0 || p.EnvSet["SHARED_KEY"] != "x" {
		t.Errorf("a wanted key must not be unset: %+v", p)
	}
	if p := BuildPlan(c, Selection{}, nil); strings.Join(p.EnvUnset, ",") != "SHARED_KEY" {
		t.Errorf("unwanted by both: %+v", p)
	}
}

func TestAssessTotalsAndShortfalls(t *testing.T) {
	c := mustCatalog(t)
	sel := selectionOf("workspace", "documents", "model-qwen35-9b")

	// Plenty of everything: totals only, no warnings.
	big := Hardware{DockerMemMB: 32 * 1024, HostRAMMB: 64 * 1024, DiskFreeMB: 500 * 1024}
	tot, warns := Assess(c, sel, nil, big, true)
	if len(warns) != 0 {
		t.Errorf("unexpected warnings: %+v", warns)
	}
	if tot.SelectedDiskMB != 1350+4140+7168 || tot.DownloadMB != tot.SelectedDiskMB {
		t.Errorf("disk totals = %+v", tot)
	}
	if tot.NeedDiskMB != tot.DownloadMB+8*1024 {
		t.Errorf("a fresh install adds the base stack (preflight's 8 GB): %+v", tot)
	}
	if tot.DockerRAMMB != 4*1024+3072+2048 {
		t.Errorf("container memory = base 4 GB + workspace + documents, got %d", tot.DockerRAMMB)
	}
	if tot.ModelRAMMB != 14336 {
		t.Errorf("model memory = %d", tot.ModelRAMMB)
	}

	// A small machine: every shortfall is named, each once.
	small := Hardware{DockerMemMB: 4 * 1024, HostRAMMB: 8 * 1024, DiskFreeMB: 5 * 1024}
	_, warns = Assess(c, sel, nil, small, true)
	codes := map[string]int{}
	for _, w := range warns {
		codes[w.Code]++
	}
	for _, code := range []string{"disk_short", "docker_memory_short", "model_memory_short"} {
		if codes[code] != 1 {
			t.Errorf("%s warned %d times: %+v", code, codes[code], warns)
		}
	}

	// Already installed items are not downloaded again, and an existing install needs no base.
	cur := selectionOf("workspace", "documents", "model-qwen35-9b")
	tot, warns = Assess(c, sel, cur, Hardware{DiskFreeMB: 1024}, false)
	if tot.DownloadMB != 0 || tot.NeedDiskMB != 0 || len(warns) != 0 {
		t.Errorf("re-run with nothing new: %+v %+v", tot, warns)
	}

	// Unknown hardware (0) never invents a warning.
	_, warns = Assess(c, sel, nil, Hardware{}, true)
	if len(warns) != 0 {
		t.Errorf("unknown hardware produced %+v", warns)
	}
}

func TestAssessOnlyTheLargestModelCounts(t *testing.T) {
	c := mustCatalog(t)
	tot, _ := Assess(c, selectionOf("model-qwen35-2b", "model-qwen35-4b"), nil, Hardware{}, false)
	if tot.ModelRAMMB != 10240 {
		t.Errorf("models run one at a time, so memory is the largest not the sum: %d", tot.ModelRAMMB)
	}
	if tot.DownloadMB != 1536+3072 {
		t.Errorf("but every download counts: %d", tot.DownloadMB)
	}
}

func TestUnsupportedOnWindows(t *testing.T) {
	c := mustCatalog(t)
	search, _ := c.Find("search")
	docs, _ := c.Find("documents")
	if Unsupported(search, "windows") == "" {
		t.Error("the Windows launcher has no flags: --search cannot be applied there")
	}
	if Unsupported(search, "darwin") != "" || Unsupported(docs, "windows") != "" {
		t.Error("profile-based add-ons and other OSes are supported")
	}
	got := StripUnsupported(c, selectionOf("search", "documents"), "windows")
	if strings.Join(got.IDs(c), ",") != "documents" {
		t.Errorf("got %v", got.IDs(c))
	}
}

func TestParseIDs(t *testing.T) {
	c := mustCatalog(t)
	sel, err := ParseIDs(c, " workspace, search ,,")
	if err != nil || strings.Join(sel.IDs(c), ",") != "workspace,search" {
		t.Errorf("%v %v", sel.IDs(c), err)
	}
	if s, err := ParseIDs(c, ""); err != nil || len(s) != 0 {
		t.Errorf("empty list is a valid choice: %v %v", s, err)
	}
	if _, err := ParseIDs(c, "workspace,nope"); err == nil || !strings.Contains(err.Error(), "nope") {
		t.Errorf("unknown id: %v", err)
	}
	if _, err := ParseIDs(c, "local-images"); err == nil || !strings.Contains(err.Error(), "planned") {
		t.Errorf("planned id: %v", err)
	}
}

func TestUpArgsFlagsOff(t *testing.T) {
	c := mustCatalog(t)
	on := BuildPlan(c, selectionOf("workspace", "search"), nil)
	off := BuildPlan(c, selectionOf("workspace"), nil)
	newer := launcherCaps{NoSearch: true, NoBrowser: true, NoTools: true}
	if got := strings.Join(upArgs("personal", on, newer), " "); got != "personal --profile workspace --search --no-browser --no-tools" && !isWindows() {
		t.Errorf("on: %q", got)
	}
	if isWindows() {
		return
	}
	if got := strings.Join(upArgs("personal", off, newer), " "); got != "personal --profile workspace --no-search --no-browser --no-tools" {
		t.Errorf("off: %q", got)
	}
	// An older release's launcher has no --no-search: Setup must not hand it one.
	if got := strings.Join(upArgs("personal", off, launcherCaps{}), " "); got != "personal --profile workspace" {
		t.Errorf("old launcher: %q", got)
	}
	// A launcher that has --no-search but not --no-tools gets only what it understands.
	if got := strings.Join(upArgs("personal", off, launcherCaps{NoSearch: true}), " "); got != "personal --profile workspace --no-search" {
		t.Errorf("partial launcher: %q", got)
	}
	// No profile selected at all is a legal run.
	if got := strings.Join(upArgs("personal", BuildPlan(c, Selection{}, nil), launcherCaps{}), " "); got != "personal" {
		t.Errorf("empty: %q", got)
	}
}

func TestExtrasHTTP(t *testing.T) {
	root := t.TempDir()
	mux := http.NewServeMux()
	registerExtras(mux, config{tier: "personal", dir: root})

	get := func(path string, body string) (int, extrasView) {
		var req *http.Request
		if body == "" {
			req = httptest.NewRequest("GET", path, nil)
		} else {
			req = httptest.NewRequest("POST", path, strings.NewReader(body))
		}
		rec := httptest.NewRecorder()
		mux.ServeHTTP(rec, req)
		var v extrasView
		_ = json.Unmarshal(rec.Body.Bytes(), &v)
		return rec.Code, v
	}

	code, v := get("/api/extras", "")
	if code != 200 || !v.Applicable || v.Installed {
		t.Fatalf("fresh: %d %+v", code, v)
	}
	if strings.Join(v.Selected, ",") != "workspace,search,tools" {
		t.Errorf("defaultOn items and the launcher's day-one defaults must be preselected, got %v", v.Selected)
	}
	if len(v.Items) != 11 || v.Kept == "" || !strings.Contains(v.Kept, "never deleted") {
		t.Errorf("items=%d kept=%q", len(v.Items), v.Kept)
	}
	var planned int
	for _, it := range v.Items {
		if !it.Selectable {
			planned++
		}
	}
	if planned != 2 {
		t.Errorf("2 planned entries must be shown unselectable, got %d", planned)
	}

	code, v = get("/api/extras/plan", `{"selected":["workspace"],"toggle":{"id":"documents","on":true}}`)
	if code != 200 || strings.Join(v.Selected, ",") != "workspace,documents" {
		t.Fatalf("toggle: %d %v", code, v.Selected)
	}
	if strings.Join(v.Plan.Profiles, ",") != "workspace,rag" || v.Totals.DownloadMB != 1350+4140 {
		t.Errorf("plan=%+v totals=%+v", v.Plan, v.Totals)
	}
	if code, _ = get("/api/extras/plan", `{"selected":["bogus"]}`); code != 400 {
		t.Errorf("unknown id: status %d", code)
	}
	if code, _ = get("/api/extras/plan", `{"selected":["local-images"]}`); code != 400 {
		t.Errorf("planned id: status %d", code)
	}

	lite := http.NewServeMux()
	registerExtras(lite, config{tier: "lite", dir: root})
	rec := httptest.NewRecorder()
	lite.ServeHTTP(rec, httptest.NewRequest("GET", "/api/extras", nil))
	if !strings.Contains(rec.Body.String(), `"applicable":false`) {
		t.Errorf("lite tier has no extras: %s", rec.Body.String())
	}
}
