package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
)

// tree makes a minimal unpacked install: deploy/compose/.env and, optionally, a
// launcher script, a state file and a choice file.
func tree(t *testing.T, env, state, script string) string {
	t.Helper()
	root := t.TempDir()
	must := func(err error) {
		if err != nil {
			t.Fatal(err)
		}
	}
	must(os.MkdirAll(composeDir(root), 0o755))
	if env != "" {
		must(os.WriteFile(envPath(root), []byte(env), 0o600))
	}
	if state != "" {
		must(os.WriteFile(statePath(root), []byte(state), 0o600))
	}
	if script != "" {
		must(os.MkdirAll(filepath.Join(root, "scripts"), 0o755))
		must(os.WriteFile(filepath.Join(root, "scripts", "vegaduta"), []byte(script), 0o755))
	}
	return root
}

func read(t *testing.T, p string) string {
	t.Helper()
	b, err := os.ReadFile(p)
	if err != nil {
		t.Fatal(err)
	}
	return string(b)
}

func TestSetEnvKey(t *testing.T) {
	in := []byte("# CORE_MEM_LIMIT=1g\nA=1\nCORE_MEM_LIMIT=\nB=2\n")
	out, ch := setEnvKey(in, "CORE_MEM_LIMIT", "2560m")
	if !ch || string(out) != "# CORE_MEM_LIMIT=1g\nA=1\nCORE_MEM_LIMIT=2560m\nB=2\n" {
		t.Errorf("replace: %q", out)
	}
	if again, ch := setEnvKey(out, "CORE_MEM_LIMIT", "2560m"); ch || string(again) != string(out) {
		t.Error("setting the same value twice must change nothing")
	}
	out, ch = setEnvKey([]byte("A=1"), "NEW_KEY", "v")
	if !ch || string(out) != "A=1\nNEW_KEY=v\n" {
		t.Errorf("append without trailing newline: %q", out)
	}
	out, _ = setEnvKey([]byte("A=1\r\nCORE_MEM_LIMIT=\r\n"), "CORE_MEM_LIMIT", "2560m")
	if string(out) != "A=1\r\nCORE_MEM_LIMIT=2560m\r\n" {
		t.Errorf("CRLF kept: %q", out)
	}
	// A commented-out line is not an assignment: the key is appended instead.
	out, _ = setEnvKey([]byte("#X=1\n"), "X", "2")
	if string(out) != "#X=1\nX=2\n" {
		t.Errorf("comment: %q", out)
	}
}

func TestClearEnvKey(t *testing.T) {
	out, ch, kept := clearEnvKey([]byte("CORE_MEM_LIMIT=2560m\nB=2\n"), "CORE_MEM_LIMIT", "2560m")
	if !ch || kept || string(out) != "CORE_MEM_LIMIT=\nB=2\n" {
		t.Errorf("ours: %q %v %v", out, ch, kept)
	}
	out, ch, kept = clearEnvKey([]byte("CORE_MEM_LIMIT=\"2560m\"\n"), "CORE_MEM_LIMIT", "2560m")
	if !ch || string(out) != "CORE_MEM_LIMIT=\n" {
		t.Errorf("quoted ours: %q %v %v", out, ch, kept)
	}
	if out, ch, kept = clearEnvKey([]byte("CORE_MEM_LIMIT=4g\n"), "CORE_MEM_LIMIT", "2560m"); ch || !kept || string(out) != "CORE_MEM_LIMIT=4g\n" {
		t.Errorf("a value the person set stays: %q %v %v", out, ch, kept)
	}
	if _, ch, kept = clearEnvKey([]byte("CORE_MEM_LIMIT=\n"), "CORE_MEM_LIMIT", "2560m"); ch || kept {
		t.Error("already blank: nothing to do")
	}
	if _, ch, _ = clearEnvKey([]byte("A=1\n"), "CORE_MEM_LIMIT", "2560m"); ch {
		t.Error("absent key: nothing to do")
	}
}

// envCatalog is the shipped catalog with .env keys on the browser entry. No shipped add-on sets
// .env keys any more, but the mechanism stays (the catalog format allows it), so it is exercised
// with the keys the earlier browser entry used.
func envCatalog(t *testing.T) Catalog {
	t.Helper()
	var m map[string]any
	if err := json.Unmarshal(embeddedCatalog, &m); err != nil {
		t.Fatal(err)
	}
	for _, a := range m["addons"].([]any) {
		if a.(map[string]any)["id"] == "browser" {
			a.(map[string]any)["compose"] = map[string]any{"profiles": []any{}, "flags": []any{},
				"env": map[string]any{"CORE_IMAGE_VARIANT": "-full", "CORE_MEM_LIMIT": "2560m"}}
		}
	}
	b, _ := json.Marshal(m)
	c, err := ParseCatalog(b)
	if err != nil {
		t.Fatal(err)
	}
	return c
}

func TestApplyEnvIsIdempotentAndKeepsEverythingElse(t *testing.T) {
	c := envCatalog(t)
	orig := "POSTGRES_PASSWORD=not-a-real-value\nCORE_IMAGE_VARIANT=\nCORE_MEM_LIMIT=\n# note\nAPP_ENCRYPTION_KEY=abc=\n"
	root := tree(t, orig, "", "")
	if runtime.GOOS != "windows" {
		os.Chmod(envPath(root), 0o600)
	}
	on := BuildPlan(c, selectionOf("workspace", "browser"), nil)
	log, err := ApplyEnv(root, on, catalogEnvValues(c))
	if err != nil || len(log) != 2 {
		t.Fatalf("log=%v err=%v", log, err)
	}
	want := "POSTGRES_PASSWORD=not-a-real-value\nCORE_IMAGE_VARIANT=-full\nCORE_MEM_LIMIT=2560m\n# note\nAPP_ENCRYPTION_KEY=abc=\n"
	if got := read(t, envPath(root)); got != want {
		t.Errorf(".env = %q", got)
	}
	if runtime.GOOS != "windows" {
		if st, _ := os.Stat(envPath(root)); st.Mode().Perm() != 0o600 {
			t.Errorf(".env mode %v: it holds secrets", st.Mode().Perm())
		}
	}
	if log, err = ApplyEnv(root, on, catalogEnvValues(c)); err != nil || len(log) != 0 {
		t.Errorf("second run must change nothing: %v %v", log, err)
	}
	// Switching it off restores the .env.example defaults and nothing else.
	off := BuildPlan(c, selectionOf("workspace"), selectionOf("workspace", "browser"))
	if log, err = ApplyEnv(root, off, catalogEnvValues(c)); err != nil || len(log) != 2 {
		t.Fatalf("off: %v %v", log, err)
	}
	if got := read(t, envPath(root)); got != orig {
		t.Errorf("back to the original, got %q", got)
	}
}

func TestApplyEnvKeepsHandEditedValues(t *testing.T) {
	c := envCatalog(t)
	root := tree(t, "CORE_MEM_LIMIT=6g\nCORE_IMAGE_VARIANT=-full\n", "", "")
	off := BuildPlan(c, selectionOf("workspace"), selectionOf("workspace", "browser"))
	log, err := ApplyEnv(root, off, catalogEnvValues(c))
	if err != nil {
		t.Fatal(err)
	}
	if got := read(t, envPath(root)); got != "CORE_MEM_LIMIT=6g\nCORE_IMAGE_VARIANT=\n" {
		t.Errorf(".env = %q", got)
	}
	if !strings.Contains(strings.Join(log, "|"), "left CORE_MEM_LIMIT alone") {
		t.Errorf("the log must say what was left: %v", log)
	}
}

func TestApplyEnvWithNothingToDoNeedsNoEnvFile(t *testing.T) {
	root := tree(t, "", "", "")
	if _, err := ApplyEnv(root, Plan{EnvSet: map[string]string{}}, nil); err != nil {
		t.Errorf("no keys, no file: %v", err)
	}
	if _, err := ApplyEnv(root, Plan{EnvSet: map[string]string{"CORE_MEM_LIMIT": "2560m"}}, nil); err == nil {
		t.Error("keys to write but no .env must be an error, not a silent skip")
	}
}

func TestSyncStateProfiles(t *testing.T) {
	root := tree(t, "", "tier=personal\nprofiles=workspace rag\nbuild=0\nsearch=1\ntools=1\n", "")
	changed, err := SyncStateProfiles(root, []string{"workspace"})
	if err != nil || !changed {
		t.Fatalf("%v %v", changed, err)
	}
	if got := read(t, statePath(root)); got != "tier=personal\nprofiles=workspace\nbuild=0\nsearch=1\ntools=1\n" {
		t.Errorf("state = %q (only profiles= may change)", got)
	}
	if changed, _ = SyncStateProfiles(root, []string{"workspace"}); changed {
		t.Error("idempotent")
	}
	if changed, _ = SyncStateProfiles(root, nil); !changed || !strings.Contains(read(t, statePath(root)), "\nprofiles=\n") {
		t.Errorf("the last profile must be removable: %q", read(t, statePath(root)))
	}
	// A state file of another tier, or none at all, is left alone.
	lite := tree(t, "", "tier=lite\nprofiles=rag\n", "")
	if changed, _ = SyncStateProfiles(lite, nil); changed || read(t, statePath(lite)) != "tier=lite\nprofiles=rag\n" {
		t.Error("lite state touched")
	}
	if changed, err = SyncStateProfiles(tree(t, "", "", ""), nil); changed || err != nil {
		t.Errorf("no state: %v %v", changed, err)
	}
}

func TestDetectCurrent(t *testing.T) {
	c := mustCatalog(t)
	if cur := DetectCurrent(tree(t, "", "", ""), c); cur.Installed || len(cur.Sel) != 0 {
		t.Errorf("empty tree: %+v", cur)
	}
	// An install that predates the choice file: rebuilt from the launcher's state and .env.
	root := tree(t, "CORE_IMAGE_VARIANT=-full\nCORE_MEM_LIMIT=2560m\n",
		"tier=personal\nprofiles=workspace rag\nsearch=1\ntools=1\nbrowser=\n", "")
	cur := DetectCurrent(root, c)
	if !cur.Installed || cur.Source != "state" {
		t.Fatalf("%+v", cur)
	}
	if got := strings.Join(cur.Sel.IDs(c), ","); got != "workspace,search,documents,tools" {
		t.Errorf("reconstructed = %q", got)
	}
	withBrowser := tree(t, "", "tier=personal\nprofiles=workspace\nsearch=0\ntools=0\nbrowser=1\n", "")
	if got := strings.Join(DetectCurrent(withBrowser, c).Sel.IDs(c), ","); got != "workspace,browser" {
		t.Errorf("browser from the launcher state: %q", got)
	}
	// Half an add-on is not the add-on: a state that names none of the flags leaves them off.
	half := tree(t, "CORE_IMAGE_VARIANT=-full\n", "tier=personal\nprofiles=workspace\nsearch=0\n", "")
	if got := strings.Join(DetectCurrent(half, c).Sel.IDs(c), ","); got != "workspace" {
		t.Errorf("half browser: %q", got)
	}
	// The remembered choice supplies what the files cannot show (model packs), but the launcher's
	// state decides every launcher-managed add-on: a stale choice must not undo a later change.
	if err := saveChoice(root, c, selectionOf("workspace", "search", "model-qwen35-4b")); err != nil {
		t.Fatal(err)
	}
	cur = DetectCurrent(root, c)
	if cur.Source != "choice" || strings.Join(cur.Sel.IDs(c), ",") != "workspace,search,documents,model-qwen35-4b,tools" {
		t.Errorf("documents and tools were added outside Setup and must show as on: %+v", cur)
	}
	os.WriteFile(statePath(root), []byte("tier=personal\nprofiles=workspace\nsearch=0\ntools=1\n"), 0o600)
	if got := strings.Join(DetectCurrent(root, c).Sel.IDs(c), ","); got != "workspace,model-qwen35-4b,tools" {
		t.Errorf("search switched off outside Setup (--no-search) must show as off: %q", got)
	}
	// With no launcher state to compare with, the choice stands alone; unknown or planned ids in it are dropped.
	noState := tree(t, "", "", "")
	os.WriteFile(addonsPath(noState), []byte(`{"schema":1,"selected":["workspace","local-images","ghost"]}`), 0o600)
	if got := strings.Join(DetectCurrent(noState, c).Sel.IDs(c), ","); got != "workspace" {
		t.Errorf("stale ids kept: %q", got)
	}
	lite := tree(t, "", "tier=lite\nprofiles=rag\n", "")
	if DetectCurrent(lite, c).Installed {
		t.Error("a lite state is not a personal install")
	}
}

func TestSaveChoiceIsIdempotent(t *testing.T) {
	c := mustCatalog(t)
	root := tree(t, "", "", "")
	if err := saveChoice(root, c, selectionOf("search", "workspace")); err != nil {
		t.Fatal(err)
	}
	first := read(t, addonsPath(root))
	if !strings.Contains(first, `"workspace"`) || strings.Index(first, "workspace") > strings.Index(first, "search") {
		t.Errorf("catalog order expected: %s", first)
	}
	if err := saveChoice(root, c, selectionOf("workspace", "search")); err != nil {
		t.Fatal(err)
	}
	if read(t, addonsPath(root)) != first {
		t.Error("an unchanged choice must not rewrite the file")
	}
	if err := saveChoice(root, c, Selection{}); err != nil || !strings.Contains(read(t, addonsPath(root)), `"selected": []`) {
		t.Errorf("an empty choice must be recorded as []: %v %s", err, read(t, addonsPath(root)))
	}
}

func TestChooseSelectionPrecedence(t *testing.T) {
	c := mustCatalog(t)
	ids := func(s Selection) string { return strings.Join(s.IDs(c), ",") }
	fresh, installed := Current{Sel: Selection{}}, Current{Installed: true, Sel: selectionOf("search")}
	if got := ids(ChooseSelection(c, fresh, nil, false)); got != "workspace,search,tools" {
		t.Errorf("fresh default = %q (workspace, plus the web search and tools runner the launcher turns on for a fresh install)", got)
	}
	if got := ids(ChooseSelection(c, installed, nil, false)); got != "search" {
		t.Errorf("re-run keeps what is installed (and does not re-add defaults) = %q", got)
	}
	if got := ids(ChooseSelection(c, installed, selectionOf("documents"), true)); got != "documents" {
		t.Errorf("explicit wins = %q", got)
	}
	if got := ids(ChooseSelection(c, installed, Selection{}, true)); got != "" {
		t.Errorf("explicit none = %q", got)
	}
	if got := ids(ChooseSelection(c, fresh, nil, false, "documents", "browser", "nope")); got != "workspace,search,documents,browser,tools" {
		t.Errorf("legacy flags add = %q", got)
	}
	if got := ids(ChooseSelection(c, fresh, selectionOf("search"), true, "documents")); got != "search" {
		t.Errorf("legacy flags do not override an explicit list = %q", got)
	}
}

func TestReadLauncherCaps(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("windows launcher takes no flags")
	}
	newer := tree(t, "", "", "case $1 in\n --no-search) :;;\n --no-browser) :;;\n --no-tools) :;;\nesac\n")
	if caps := readLauncherCaps(newer); !caps.NoSearch || !caps.NoBrowser || !caps.NoTools {
		t.Errorf("%+v", caps)
	}
	older := tree(t, "", "", "case $1 in\n --search) :;;\nesac\n")
	if caps := readLauncherCaps(older); caps.NoSearch || caps.NoBrowser || caps.NoTools {
		t.Errorf("%+v", caps)
	}
	if caps := readLauncherCaps(t.TempDir()); caps.NoSearch {
		t.Error("no launcher, no capabilities")
	}
}

// The whole decision path against a temp tree: the same inputs twice produce
// the same arguments and leave every file byte for byte as it was.
func TestPrepareExtrasEndToEnd(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("posix launcher arguments")
	}
	script := "case $1 in --no-search) :;; --no-browser) :;; --no-tools) :;; esac\n"
	root := tree(t, "CORE_IMAGE_VARIANT=\nCORE_MEM_LIMIT=\n", "", script)
	c := config{tier: "personal"}
	noop := func(Event) {}

	// Fresh install, defaults: the build terminal, and the web search the launcher turns on for a fresh install.
	x := prepareExtras(root, c, "linux", noop)
	if got := strings.Join(x.args("personal"), " "); got != "personal --profile workspace --profile tools --search --no-browser" {
		t.Fatalf("fresh args = %q", got)
	}
	if err := x.beforeUp(root, noop); err != nil {
		t.Fatal(err)
	}

	// The wizard sends documents + search + browser.
	c.extraSel, c.extraSet = selectionOf("workspace", "documents", "search", "browser"), true
	x = prepareExtras(root, c, "linux", noop)
	wantArgs := "personal --profile workspace --profile rag --search --browser --no-tools"
	if got := strings.Join(x.args("personal"), " "); got != wantArgs {
		t.Fatalf("args = %q want %q", got, wantArgs)
	}
	if err := x.beforeUp(root, noop); err != nil {
		t.Fatal(err)
	}
	if got := read(t, envPath(root)); got != "CORE_IMAGE_VARIANT=\nCORE_MEM_LIMIT=\n" {
		t.Fatalf(".env = %q (no shipped add-on writes .env keys)", got)
	}
	// The launcher ran and saved its state; Setup records the choice.
	os.WriteFile(statePath(root), []byte("tier=personal\nprofiles=workspace rag\nsearch=1\ntools=0\nbrowser=1\n"), 0o600)
	x.afterUp(t.Context(), root, noop)
	if cur := DetectCurrent(root, x.cat); strings.Join(cur.Sel.IDs(x.cat), ",") != "workspace,search,documents,browser" {
		t.Fatalf("remembered = %v", cur.Sel.IDs(x.cat))
	}

	// Idempotence: the same run again changes no file and builds the same command.
	envBefore, stateBefore, choiceBefore := read(t, envPath(root)), read(t, statePath(root)), read(t, addonsPath(root))
	x2 := prepareExtras(root, c, "linux", noop)
	if got := strings.Join(x2.args("personal"), " "); got != wantArgs {
		t.Errorf("second run args = %q", got)
	}
	if err := x2.beforeUp(root, noop); err != nil {
		t.Fatal(err)
	}
	x2.afterUp(t.Context(), root, noop)
	if read(t, envPath(root)) != envBefore || read(t, statePath(root)) != stateBefore || read(t, addonsPath(root)) != choiceBefore {
		t.Error("an identical re-run rewrote a file")
	}

	// Re-running Setup with no choices shows the current set and keeps it.
	again := prepareExtras(root, config{tier: "personal"}, "linux", noop)
	if strings.Join(again.sel.IDs(again.cat), ",") != "workspace,search,documents,browser" {
		t.Errorf("a plain re-run must keep the installed set, got %v", again.sel.IDs(again.cat))
	}

	// Remove documents and browser: profiles shrink in the launcher's memory, env is restored,
	// and the stop list names exactly what was turned off.
	c.extraSel = selectionOf("workspace", "search")
	x3 := prepareExtras(root, c, "linux", noop)
	if got := strings.Join(x3.args("personal"), " "); got != "personal --profile workspace --search --no-browser --no-tools" {
		t.Errorf("removal args = %q", got)
	}
	if strings.Join(x3.plan.Turned, ",") != "documents,browser" {
		t.Errorf("turned off = %v", x3.plan.Turned)
	}
	if err := x3.beforeUp(root, noop); err != nil {
		t.Fatal(err)
	}
	if got := read(t, statePath(root)); got != "tier=personal\nprofiles=workspace\nsearch=1\ntools=0\nbrowser=1\n" {
		t.Errorf("state = %q (only profiles= is Setup's to change; the launcher clears browser= with --no-browser)", got)
	}
	if got := read(t, envPath(root)); got != "CORE_IMAGE_VARIANT=\nCORE_MEM_LIMIT=\n" {
		t.Errorf(".env = %q", got)
	}
}

func TestPrepareExtrasOtherTiersAndWindows(t *testing.T) {
	noop := func(Event) {}
	if x := prepareExtras(t.TempDir(), config{tier: "lite"}, "linux", noop); x.active || len(x.describe()) != 0 {
		t.Error("lite has no extras")
	}
	var lines []string
	c := config{tier: "personal", extraSel: selectionOf("workspace", "search"), extraSet: true}
	x := prepareExtras(t.TempDir(), c, "windows", func(e Event) { lines = append(lines, e.Line) })
	if strings.Join(x.sel.IDs(x.cat), ",") != "workspace" || len(x.plan.Flags) != 0 {
		t.Errorf("windows plan must not contain flag-based extras: %v %+v", x.sel.IDs(x.cat), x.plan)
	}
	if !strings.Contains(strings.Join(lines, "|"), "Skipped on this operating system: Web search") {
		t.Errorf("the skip must be said out loud: %v", lines)
	}
}

func TestListExtrasPrintsEverything(t *testing.T) {
	var b strings.Builder
	printExtras(&b)
	out := b.String()
	for _, want := range []string{"workspace", "search", "documents", "browser", "model-qwen35-4b", "planned", "on by default", "are kept"} {
		if !strings.Contains(out, want) {
			t.Errorf("--list-extras misses %q:\n%s", want, out)
		}
	}
}
