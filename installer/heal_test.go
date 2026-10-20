package main

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestMergeWSLConfig(t *testing.T) {
	cases := []struct {
		name, in, want string
		changed        bool
	}{
		{"empty file", "", "[wsl2]\r\nmemory=6GB\r\n", true},
		{"other section kept", "[boot]\nsystemd=true\n", "[boot]\r\nsystemd=true\r\n\r\n[wsl2]\r\nmemory=6GB\r\n", true},
		{"adds to existing wsl2", "[wsl2]\nprocessors=4\n", "[wsl2]\r\nmemory=6GB\r\nprocessors=4\r\n", true},
		{"replaces old memory", "[wsl2]\r\nmemory=2GB\r\nswap=0\r\n", "[wsl2]\r\nmemory=6GB\r\nswap=0\r\n", true},
		{"memory in another section untouched", "[experimental]\nmemory=1GB\n", "[experimental]\r\nmemory=1GB\r\n\r\n[wsl2]\r\nmemory=6GB\r\n", true},
		{"already set", "[wsl2]\r\nmemory=6GB\r\n", "[wsl2]\r\nmemory=6GB\r\n", false},
	}
	for _, c := range cases {
		got, changed := mergeWSLConfig(c.in, 6)
		if got != c.want || changed != c.changed {
			t.Errorf("%s: got %q (changed=%v), want %q (changed=%v)", c.name, got, changed, c.want, c.changed)
		}
	}
}

func TestMemoryTarget(t *testing.T) {
	cases := []struct {
		need, host, want int64
		ok               bool
	}{
		{4, 16, 6, true}, // need + 2 GB headroom
		{4, 7, 5, true},  // capped so the host keeps 2 GB
		{4, 6, 4, true},  // exactly enough
		{4, 5, 3, false}, // cannot spare it
		{4, 0, 0, false}, // host RAM unknown
	}
	for _, c := range cases {
		got, ok := memoryTarget(c.need, c.host)
		if got != c.want || ok != c.ok {
			t.Errorf("memoryTarget(%d, %d) = %d, %v; want %d, %v", c.need, c.host, got, ok, c.want, c.ok)
		}
	}
}

func TestClassifyFailure(t *testing.T) {
	cases := map[string]failureKind{
		"Error response from daemon: error from registry: unauthorized":                        failAuth,
		"write /var/lib/docker/tmp: no space left on device":                                   failDisk,
		"error during connect: open //./pipe/dockerDesktopLinuxEngine: The system cannot find": failEngine,
		"Image ghcr.io/cd-lc/core:0.1.0 Interrupted\nnet/http: TLS handshake timeout":          failNetwork,
		"ERROR: some services did not become healthy":                                          failSlow,
		"something nobody has seen before":                                                     failUnknown,
	}
	for out, want := range cases {
		if got := classifyFailure(out); got != want {
			t.Errorf("classifyFailure(%q) = %v, want %v", out, got, want)
		}
	}
}

func TestSetJSONTrue(t *testing.T) {
	out, changed, err := setJSONTrue([]byte(`{"AutoStart": false, "MemoryMiB": 4096}`), "AutoStart")
	if err != nil || !changed || !strings.Contains(string(out), `"AutoStart": true`) || !strings.Contains(string(out), `"MemoryMiB": 4096`) {
		t.Fatalf("got %s, changed=%v, err=%v", out, changed, err)
	}
	if _, changed, _ := setJSONTrue([]byte(`{"AutoStart": true}`), "AutoStart"); changed {
		t.Fatal("already true must not rewrite the file")
	}
	if _, _, err := setJSONTrue([]byte(`not json`), "AutoStart"); err == nil {
		t.Fatal("invalid JSON must be left alone")
	}
}

func TestUpWithRepairRetriesThenSucceeds(t *testing.T) {
	calls := 0
	start := func(onLine func(string)) error {
		calls++
		if calls == 1 {
			onLine("ERROR: some services did not become healthy")
			return errors.New("exit status 1")
		}
		return nil
	}
	var lines []string
	emit := func(e Event) { lines = append(lines, e.Line) }
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	if err := upWithRepairWait(ctx, start, emit, func(string) {}, 0); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if calls != 2 {
		t.Fatalf("start ran %d times, want 2", calls)
	}
	if !strings.Contains(strings.Join(lines, "\n"), "more time") {
		t.Fatalf("missing repair message in %q", lines)
	}
}

func TestUpWithRepairGivesUp(t *testing.T) {
	calls := 0
	start := func(onLine func(string)) error { calls++; return errors.New("boom") }
	err := upWithRepairWait(context.Background(), start, func(Event) {}, func(string) {}, 0)
	if err == nil || calls != upAttempts {
		t.Fatalf("err=%v calls=%d, want an error after %d attempts", err, calls, upAttempts)
	}
}

func TestOllamaUp(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/tags" {
			http.NotFound(w, r)
			return
		}
		w.Write([]byte(`{"models":[]}`))
	}))
	old := ollamaURL
	defer func() { ollamaURL = old }()
	ollamaURL = srv.URL + "/api/tags"
	if !ollamaUp(context.Background()) {
		t.Fatal("a server answering /api/tags is Ollama running")
	}
	srv.Close()
	if ollamaUp(context.Background()) {
		t.Fatal("nothing listening is not Ollama running")
	}
}
