package main

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"regexp"
	"runtime"
	"strings"
	"testing"
)

func isWindows() bool { return runtime.GOOS == "windows" }

func mustCatalog(t *testing.T) Catalog {
	t.Helper()
	c, err := loadCatalog()
	if err != nil {
		t.Fatalf("embedded catalog: %v", err)
	}
	return c
}

// repoFile reads a file of the surrounding checkout, skipping the test when
// Setup is built outside one (a source tarball of installer/ alone).
func repoFile(t *testing.T, rel string) []byte {
	t.Helper()
	if _, err := os.Stat(filepath.Join("..", "core", "providers")); err != nil {
		t.Skip("not inside the full repository checkout")
	}
	b, err := os.ReadFile(filepath.Join("..", rel))
	if err != nil {
		t.Fatalf("%s: %v", rel, err)
	}
	return b
}

// The drift guard. core serves addons/catalog.json; Setup embeds a copy. If you
// changed one, run installer/sync-addons-catalog.sh.
func TestCatalogMatchesCore(t *testing.T) {
	core := repoFile(t, "core/providers/src/main/resources/addons/catalog.json")
	if !bytes.Equal(core, embeddedCatalog) {
		t.Fatal("installer/addons/catalog.json differs from core/providers/src/main/resources/addons/catalog.json; run installer/sync-addons-catalog.sh")
	}
}

func TestEmbeddedCatalogIsValid(t *testing.T) {
	c := mustCatalog(t)
	if len(c.Addons) == 0 {
		t.Fatal("empty catalog")
	}
	for _, a := range c.Addons {
		if a.Name == "" || a.Description == "" {
			t.Errorf("%s: missing name or description", a.ID)
		}
		if a.Selectable() && a.License == "" {
			t.Errorf("%s: no licence shown for an installable add-on", a.ID)
		}
	}
	for id, want := range map[string]bool{"workspace": true, "search": false, "documents": false} {
		a, ok := c.Find(id)
		if !ok || a.DefaultOn != want {
			t.Errorf("%s: defaultOn=%v want %v (present=%v)", id, a.DefaultOn, want, ok)
		}
	}
	if got := strings.Join(Defaults(c).IDs(c), ","); got != "workspace,search,tools" {
		t.Errorf("Defaults = %q, want workspace,search,tools (search and the tools runner are the launcher's day-one defaults)", got)
	}
}

func TestDecoderIgnoresUnknownFields(t *testing.T) {
	var raw map[string]any
	if err := json.Unmarshal(embeddedCatalog, &raw); err != nil {
		t.Fatal(err)
	}
	raw["futureTopLevel"] = 1
	for _, a := range raw["addons"].([]any) {
		a.(map[string]any)["futureField"] = map[string]any{"x": 1}
	}
	b, _ := json.Marshal(raw)
	if _, err := ParseCatalog(b); err != nil {
		t.Fatalf("a newer catalog with extra fields must still load: %v", err)
	}
}

func TestCatalogIsUTF8WithMacron(t *testing.T) {
	if !bytes.Contains(embeddedCatalog, []byte("VegaDūta")) {
		t.Error("expected VegaDūta (u with macron) in the catalog copy")
	}
}

func TestParseCatalogRejects(t *testing.T) {
	good := func() map[string]any {
		var m map[string]any
		_ = json.Unmarshal(embeddedCatalog, &m)
		return m
	}
	find := func(m map[string]any, id string) map[string]any {
		for _, a := range m["addons"].([]any) {
			if a.(map[string]any)["id"] == id {
				return a.(map[string]any)
			}
		}
		t.Fatalf("no %s", id)
		return nil
	}
	cases := []struct {
		name   string
		mutate func(m map[string]any)
		want   string
	}{
		{"schema", func(m map[string]any) { m["schemaVersion"] = 2 }, "schemaVersion"},
		{"unknown profile", func(m map[string]any) {
			find(m, "documents")["compose"].(map[string]any)["profiles"] = []any{"made-up"}
		}, "not a launcher profile"},
		{"unknown flag", func(m map[string]any) {
			find(m, "search")["compose"].(map[string]any)["flags"] = []any{"--rm-rf"}
		}, "not a launcher flag"},
		{"env newline", func(m map[string]any) {
			find(m, "search")["compose"].(map[string]any)["env"] = map[string]any{"CORE_MEM_LIMIT": "1g\nEVIL=1"}
		}, "not allowed in .env"},
		{"env key", func(m map[string]any) {
			find(m, "search")["compose"].(map[string]any)["env"] = map[string]any{"core_mem": "1g"}
		}, "not allowed in .env"},
		{"duplicate id", func(m map[string]any) {
			a := m["addons"].([]any)
			m["addons"] = append(a, a[0])
		}, "duplicate"},
		{"bad id", func(m map[string]any) { find(m, "search")["id"] = "Search!" }, "not valid"},
		{"reserved id", func(m map[string]any) { find(m, "search")["id"] = "host" }, "not valid"},
		{"unknown requirement", func(m map[string]any) { find(m, "search")["requires"] = []any{"nope"} }, "unknown add-on"},
		{"self requirement", func(m map[string]any) { find(m, "search")["requires"] = []any{"search"} }, "itself"},
		{"requires planned", func(m map[string]any) { find(m, "search")["requires"] = []any{"local-images"} }, "planned"},
		{"cycle", func(m map[string]any) {
			find(m, "search")["requires"] = []any{"documents"}
			find(m, "documents")["requires"] = []any{"search"}
		}, "cycle"},
		{"kind mismatch", func(m map[string]any) { find(m, "local-images")["kind"] = "compose-profile" }, "disagree"},
		{"model tag", func(m map[string]any) { find(m, "model-qwen35-2b")["model"] = "--help" }, "model tag"},
		{"model default on", func(m map[string]any) { find(m, "model-qwen35-2b")["defaultOn"] = true }, "on by default"},
		{"env conflict", func(m map[string]any) {
			find(m, "search")["compose"].(map[string]any)["env"] = map[string]any{"CORE_MEM_LIMIT": "9g"}
			find(m, "documents")["compose"].(map[string]any)["env"] = map[string]any{"CORE_MEM_LIMIT": "1g"}
		}, "conflicts"},
	}
	for _, tc := range cases {
		m := good()
		tc.mutate(m)
		b, _ := json.Marshal(m)
		if _, err := ParseCatalog(b); err == nil || !strings.Contains(err.Error(), tc.want) {
			t.Errorf("%s: err=%v, want it to mention %q", tc.name, err, tc.want)
		}
	}
	if _, err := ParseCatalog([]byte("{")); err == nil {
		t.Error("garbage JSON must be rejected")
	}
}

// The catalog may only name things the launcher really has. These read the
// launcher in the checkout, so a rename there fails here.
func TestCatalogNamesExistInLauncher(t *testing.T) {
	script := string(repoFile(t, "scripts/vegaduta"))
	c := mustCatalog(t)
	for _, a := range c.Addons {
		if !a.IsCompose() {
			continue
		}
		for _, p := range a.Compose.Profiles {
			if p == "rag" || p == "workspace" { // the personal launcher's documented profiles
				if !regexp.MustCompile(`\b` + p + `\b`).MatchString(script) {
					t.Errorf("%s: launcher does not mention profile %s", a.ID, p)
				}
			}
		}
		for _, f := range a.Compose.Flags {
			if !strings.Contains(script, f+")") {
				t.Errorf("%s: launcher has no %s option", a.ID, f)
			}
		}
	}
	for flag := range launcherFlags {
		off, _ := flagOff(flag, launcherCaps{NoSearch: true, NoBrowser: true})
		if off != "" && !strings.Contains(script, off+")") {
			t.Logf("launcher in this checkout has no %s (older launcher: Setup will not pass it)", off)
		}
	}
}

// addonServices is how a switched-off add-on is stopped; the names must be the
// compose services and profiles that exist.
func TestServiceNamesExistInCompose(t *testing.T) {
	text := string(repoFile(t, "deploy/compose/personal.yml")) + string(repoFile(t, "deploy/compose/docker-compose.search.yml"))
	for id, s := range addonServices {
		re := regexp.MustCompile(`(?m)^  ` + regexp.QuoteMeta(s.Service) + `:\n(?:.*\n){0,3}?\s+profiles: \["` + s.Profile + `"\]`)
		if !re.MatchString(text) {
			t.Errorf("%s: no service %s with profile %s in deploy/compose", id, s.Service, s.Profile)
		}
	}
}
