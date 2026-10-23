package main

import (
	"context"
	"errors"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
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
		"Error response from daemon: Ports are not available: exposing port TCP 127.0.0.1:3000 -> 0.0.0.0:0: listen tcp 127.0.0.1:3000: bind: An attempt was made to access a socket in a way forbidden by its access permissions.\nERROR: some services did not become healthy": failPort,
		"Bind for 0.0.0.0:8080 failed: port is already allocated": failPort,
		"something nobody has seen before":                        failUnknown,
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
	if err := upWithRepairWait(ctx, start, emit, func(string) {}, nil, 0); err != nil {
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
	err := upWithRepairWait(context.Background(), start, func(Event) {}, func(string) {}, nil, 0)
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

// A port a listener holds is busy; so is one that refuses a bind (Windows
// reserved ranges look free to a dial).
func TestBusyPorts(t *testing.T) {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Skip(err)
	}
	defer l.Close()
	held := l.Addr().(*net.TCPAddr).Port
	free, _ := net.Listen("tcp", "127.0.0.1:0")
	freePort := free.Addr().(*net.TCPAddr).Port
	free.Close()
	got := busyPorts([]int{held, freePort})
	if len(got) != 1 || got[0] != held {
		t.Fatalf("busyPorts = %v, want [%d]", got, held)
	}
}

// The busy-port message names the ports this install is configured to publish
// (WEB_HOST_PORT / CORE_HOST_PORT moved or not), and does not recommend
// restarting the Windows NAT service as if it were free.
func TestHostPortsFollowTheConfiguredOverrides(t *testing.T) {
	root := tree(t, "WEB_HOST_PORT=18101\n", "", "")
	t.Setenv("WEB_HOST_PORT", "")
	t.Setenv("CORE_HOST_PORT", "")
	if got := ints(hostPorts(root, "personal")); got != "18101, 8080" {
		t.Errorf("from .env: %q", got)
	}
	t.Setenv("CORE_HOST_PORT", "18102")
	if got := ints(hostPorts(root, "personal")); got != "18101, 18102" {
		t.Errorf("env adds the core port: %q", got)
	}
	t.Setenv("WEB_HOST_PORT", "19000")
	if got := ints(hostPorts(root, "personal")); got != "19000, 18102" {
		t.Errorf("the process environment beats .env (compose does the same): %q", got)
	}
	if got := ints(hostPorts(t.TempDir(), "lite")); got != "19000, 18102, 8180" {
		t.Errorf("lite keeps its other ports: %q", got)
	}
}

func TestPortAdviceNamesThePortsAndWarnsAboutWinNAT(t *testing.T) {
	win := portAdvice([]int{18101, 18102}, nil, "windows")
	for _, want := range []string{"18101, 18102", "excludedportrange", "WEB_HOST_PORT", "winnat", "administrator", "WSL", "Hyper-V"} {
		if !strings.Contains(win, want) {
			t.Errorf("windows advice misses %q:\n%s", want, win)
		}
	}
	if strings.Contains(win, "3000") || strings.Contains(win, "8080") {
		t.Errorf("must not name ports that are not configured:\n%s", win)
	}
	if strings.Index(win, "WEB_HOST_PORT") > strings.Index(win, "winnat") {
		t.Errorf("moving the port is the gentle fix and comes before restarting winnat:\n%s", win)
	}
	busy := portAdvice([]int{3000, 8080}, []int{8080}, "windows")
	if !strings.Contains(busy, "8080 is") || !strings.Contains(busy, "3000, 8080") {
		t.Errorf("name the busy port and the configured ones:\n%s", busy)
	}
	if linux := portAdvice([]int{3000, 8080}, nil, "linux"); strings.Contains(linux, "winnat") || !strings.Contains(linux, "3000, 8080") {
		t.Errorf("only Windows has winnat:\n%s", linux)
	}
	if none := portAdvice(nil, nil, "linux"); !strings.Contains(none, "A port VegaD") {
		t.Errorf("no ports known: %s", none)
	}
}

func TestPortFailureMessageUsesThePortsItWasGiven(t *testing.T) {
	start := func(onLine func(string)) error {
		onLine("Bind for 127.0.0.1:18101 failed: port is already allocated")
		return errors.New("exit status 1")
	}
	old := busyPortsNow
	busyPortsNow = func(context.Context, []int) []int { return []int{18101} }
	t.Cleanup(func() { busyPortsNow = old })
	var lines []string
	emit := func(e Event) { lines = append(lines, e.Line) }
	err := upWithRepairWait(context.Background(), start, emit, func(string) {}, []int{18101, 18102}, 0)
	if err == nil {
		t.Fatal("a busy port cannot be repaired by retrying")
	}
	if got := strings.Join(lines, "\n"); !strings.Contains(got, "18101, 18102") || !strings.Contains(got, "18101 is") || strings.Contains(got, "3000 or 8080") {
		t.Errorf("message = %q", got)
	}
}

// Raising Docker's memory on Windows restarts Docker Desktop and shuts WSL down,
// closing every distro the person has open. That needs their say-so.
func TestRaisingDockerMemoryNeedsConsentWhereItRestartsDocker(t *testing.T) {
	oldFn, oldRestart := raiseMemory, raiseRestartsDocker
	t.Cleanup(func() { raiseMemory, raiseRestartsDocker = oldFn, oldRestart })
	calls := 0
	raiseMemory = func(_ context.Context, gb int64, emit func(Event)) (bool, error) { calls++; return true, nil }
	raiseRestartsDocker = true
	var lines []string
	emit := func(e Event) { lines = append(lines, e.Line) }

	raiseDockerMemoryWithConsent(context.Background(), 6, 2, 4, false, emit)
	if calls != 0 {
		t.Fatal("Docker was restarted without consent")
	}
	got := strings.Join(lines, "\n")
	for _, want := range []string{"restart", "WSL", "--allow-docker-restart", "Docker Desktop > Settings > Resources"} {
		if !strings.Contains(got, want) {
			t.Errorf("the refusal must tell the person %q:\n%s", want, got)
		}
	}
	raiseDockerMemoryWithConsent(context.Background(), 6, 2, 4, true, emit)
	if calls != 1 {
		t.Errorf("with consent the memory is raised: %d calls", calls)
	}
	// Where raising memory restarts nothing there is nothing to ask.
	raiseRestartsDocker = false
	raiseDockerMemoryWithConsent(context.Background(), 6, 2, 4, false, emit)
	if calls != 2 {
		t.Errorf("no consent needed where nothing restarts: %d calls", calls)
	}
}

func TestBackupWSLConfigKeepsTheFirstOriginal(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, ".wslconfig")
	if err := backupWSLConfig(p, nil); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(p + ".vegaduta.bak"); err == nil {
		t.Error("nothing to back up when the file did not exist")
	}
	if err := backupWSLConfig(p, []byte("[wsl2]\nprocessors=4\n")); err != nil {
		t.Fatal(err)
	}
	if err := backupWSLConfig(p, []byte("[wsl2]\nmemory=6GB\n")); err != nil {
		t.Fatal(err)
	}
	if b, _ := os.ReadFile(p + ".vegaduta.bak"); string(b) != "[wsl2]\nprocessors=4\n" {
		t.Errorf("the backup must stay the person's own file, got %q", b)
	}
}
