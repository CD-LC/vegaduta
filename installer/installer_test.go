package main

import (
	"archive/tar"
	"bytes"
	"compress/gzip"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
)

func TestVersionGE(t *testing.T) {
	for _, tc := range []struct {
		a, b string
		want bool
	}{
		{"5.5.1", "2.24.0", true},
		{"2.24.0", "2.24.0", true},
		{"2.23.9", "2.24.0", false},
		{"2.24", "2.24.0", true},
		{"v2.25.0-rc.1", "2.24.0", true},
		{"10.0", "2.99", true},
	} {
		if got := versionGE(tc.a, tc.b); got != tc.want {
			t.Errorf("versionGE(%q,%q)=%v want %v", tc.a, tc.b, got, tc.want)
		}
	}
}

func TestSumFor(t *testing.T) {
	f := filepath.Join(t.TempDir(), "SHA256SUMS")
	os.WriteFile(f, []byte(
		"aaa111  vegaduta-1.2.3.tar.gz\n"+
			"bbb222  ./other.tgz\n"+
			"ccc333 *binary.bin\n"), 0o600)
	for name, want := range map[string]string{
		"vegaduta-1.2.3.tar.gz": "aaa111",
		"other.tgz":             "bbb222",
		"binary.bin":            "ccc333",
	} {
		got, err := sumFor(f, name)
		if err != nil || got != want {
			t.Errorf("sumFor(%q)=%q,%v want %q", name, got, err, want)
		}
	}
	if _, err := sumFor(f, "missing.tgz"); err == nil {
		t.Error("sumFor: expected an error for a missing name")
	}
}

func TestNeedsSourceBuild(t *testing.T) {
	write := func(t *testing.T, body string) string {
		root := t.TempDir()
		dir := filepath.Join(root, "deploy", "compose")
		if err := os.MkdirAll(dir, 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(dir, ".env"), []byte(body), 0o600); err != nil {
			t.Fatal(err)
		}
		return root
	}
	if !needsSourceBuild(t.TempDir()) {
		t.Error("missing .env should need a source build")
	}
	if !needsSourceBuild(write(t, "FOO=1\nVEGADUTA_VERSION=\n")) {
		t.Error("empty VEGADUTA_VERSION should need a source build")
	}
	if !needsSourceBuild(write(t, "FOO=1\n")) {
		t.Error("absent VEGADUTA_VERSION should need a source build")
	}
	if needsSourceBuild(write(t, "VEGADUTA_VERSION=1.2.3\n")) {
		t.Error("pinned VEGADUTA_VERSION should pull, not build")
	}
	if needsSourceBuild(write(t, "VEGADUTA_VERSION=\"2.0.0\"\n")) {
		t.Error("quoted pinned version should pull, not build")
	}
}

// makeTar builds a gzipped tar with a single top-level dir, like the release.
func makeTar(t *testing.T, members map[string]string) string {
	t.Helper()
	var buf bytes.Buffer
	gz := gzip.NewWriter(&buf)
	tw := tar.NewWriter(gz)
	for name, body := range members {
		tw.WriteHeader(&tar.Header{Name: name, Mode: 0o644, Size: int64(len(body)), Typeflag: tar.TypeReg})
		tw.Write([]byte(body))
	}
	tw.Close()
	gz.Close()
	f := filepath.Join(t.TempDir(), "r.tar.gz")
	os.WriteFile(f, buf.Bytes(), 0o600)
	return f
}

func TestUnpackReleaseStripsTopLevel(t *testing.T) {
	dir := t.TempDir()
	tgz := makeTar(t, map[string]string{
		"vegaduta-1.2.3/scripts/vegaduta":            "#!/bin/sh\necho hi\n",
		"vegaduta-1.2.3/deploy/compose/.env.example": "X=1\n",
	})
	if err := unpackRelease(tgz, dir); err != nil {
		t.Fatal(err)
	}
	for _, p := range []string{"scripts/vegaduta", "deploy/compose/.env.example"} {
		if _, err := os.Stat(filepath.Join(dir, p)); err != nil {
			t.Errorf("expected %s unpacked: %v", p, err)
		}
	}
}

func TestUnpackReleaseRejectsTraversal(t *testing.T) {
	dir := t.TempDir()
	tgz := makeTar(t, map[string]string{"top/../escape.txt": "x"})
	if err := unpackRelease(tgz, dir); err == nil {
		t.Fatal("expected traversal to be refused")
	}
	if _, err := os.Stat(filepath.Join(dir, "..", "escape.txt")); err == nil {
		t.Fatal("escape.txt was written outside the install dir")
	}
}

func TestResolveVersionValidation(t *testing.T) {
	for _, bad := range []string{"x.y.z", "1.2", "../etc", "latest", "1.2.3.4.5"} {
		c := config{version: bad}
		if _, err := resolveVersion(t.Context(), c); err == nil {
			t.Errorf("resolveVersion(%q): expected an error", bad)
		}
	}
	c := config{version: "v1.2.3"}
	if v, err := resolveVersion(t.Context(), c); err != nil || v != "1.2.3" {
		t.Errorf("resolveVersion(v1.2.3)=%q,%v want 1.2.3", v, err)
	}
}

func TestSetupURLRe(t *testing.T) {
	line := "  First-run setup  http://localhost:3000/login?setup=AbC_dEf-123  (one-time)"
	if got := setupURLRe.FindString(line); got != "http://localhost:3000/login?setup=AbC_dEf-123" {
		t.Errorf("setupURLRe = %q", got)
	}
	for _, no := range []string{"sign in at the console", "http://localhost:3000/login", "setup= missing scheme"} {
		if got := setupURLRe.FindString(no); got != "" {
			t.Errorf("setupURLRe(%q) = %q, want none", no, got)
		}
	}
}

func TestTierArgsAndNeeds(t *testing.T) {
	cat := mustCatalog(t)
	plan := func(ids ...string) Plan {
		sel, _ := Resolve(cat, selectionOf(ids...))
		return BuildPlan(cat, sel, nil)
	}
	caps := launcherCaps{NoSearch: true, NoBrowser: true}
	want := "personal --profile workspace --no-search --no-browser"
	if runtime.GOOS == "windows" {
		want = "personal -Profile workspace"
	}
	if got := strings.Join(upArgs("personal", plan("workspace"), caps), " "); got != want {
		t.Errorf("upArgs(personal)=%q want %q", got, want)
	}
	if runtime.GOOS != "windows" {
		if got := strings.Join(upArgs("personal", plan("workspace", "documents"), caps), " "); got != "personal --profile workspace --profile rag --no-search --no-browser" {
			t.Errorf("upArgs(personal, documents)=%q", got)
		}
	}
	// Extras never leak into the other tiers.
	for tier, want := range map[string]string{"lite": "up --lite", "full": "up --full"} {
		if runtime.GOOS == "windows" {
			continue
		}
		if got := strings.Join(upArgs(tier, plan("workspace", "documents", "search"), caps), " "); got != want {
			t.Errorf("upArgs(%s)=%q want %q", tier, got, want)
		}
	}
	for _, tier := range []string{"lite", "full", "personal"} {
		mem, disk, ports := tierNeeds(tier)
		if mem <= 0 || disk <= 0 || len(ports) == 0 {
			t.Errorf("tierNeeds(%q) returned empty requirements", tier)
		}
	}
}
