package main

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
)

// fakeOllama is a loopback Ollama: /api/tags answers 200 and /api/pull streams
// the given NDJSON lines. It points the package's ollamaURL at itself.
type fakeOllama struct {
	srv    *httptest.Server
	mu     sync.Mutex
	pulled []string
}

func newFakeOllama(t *testing.T, pullBody string) *fakeOllama {
	t.Helper()
	f := &fakeOllama{}
	f.srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/tags":
			w.Write([]byte(`{"models":[]}`))
		case "/api/pull":
			var body struct {
				Model string `json:"model"`
			}
			_ = json.NewDecoder(r.Body).Decode(&body)
			f.mu.Lock()
			f.pulled = append(f.pulled, body.Model)
			f.mu.Unlock()
			w.Header().Set("Content-Type", "application/x-ndjson")
			w.Write([]byte(pullBody))
		default:
			http.NotFound(w, r)
		}
	}))
	old := ollamaURL
	ollamaURL = f.srv.URL + "/api/tags"
	t.Cleanup(func() { ollamaURL = old; f.srv.Close() })
	return f
}

const pullOK = `{"status":"pulling manifest"}
{"status":"pulling 6a0746a1ec1a","digest":"sha256:6a07","total":1000,"completed":250}
{"status":"pulling 6a0746a1ec1a","digest":"sha256:6a07","total":1000,"completed":1000}
{"status":"verifying sha256 digest"}
{"status":"success"}
`

// modelRun drives the post-start half of an install for a tree that already ran
// the launcher, with the 4B model pack selected.
func modelRun(t *testing.T, goos, state string) (root string, x extrasRun, lines *[]string) {
	t.Helper()
	root = tree(t, "CORE_IMAGE_VARIANT=\n", state, "")
	lines = new([]string)
	emit := func(e Event) { *lines = append(*lines, e.Line) }
	c := config{tier: "personal", extraSel: selectionOf("workspace", "model-qwen35-4b"), extraSet: true}
	x = prepareExtras(root, c, goos, emit)
	if err := x.beforeUp(root, emit); err != nil {
		t.Fatal(err)
	}
	x.afterUp(context.Background(), root, emit)
	return root, x, lines
}

func chosen(t *testing.T, root string, x extrasRun) string {
	t.Helper()
	ids, err := readChoice(root)
	if err != nil {
		t.Fatal(err)
	}
	return strings.Join(ids, ",")
}

// On Windows the launcher has no `models` subcommand, so Setup pulls through the
// Ollama the launcher uses (here: the one on this computer), and says so.
func TestWindowsModelPullGoesToOllamaNotTheLauncher(t *testing.T) {
	f := newFakeOllama(t, pullOK)
	root, x, lines := modelRun(t, "windows", "tier=personal\nprofiles=workspace\nlocal_ai=host\nlocal_model=qwen3.5:4b\n")
	if len(f.pulled) != 1 || f.pulled[0] != "qwen3.5:4b" {
		t.Fatalf("Ollama was asked to pull %v, want [qwen3.5:4b]; log: %v", f.pulled, *lines)
	}
	if got := chosen(t, root, x); got != "workspace,model-qwen35-4b" {
		t.Errorf("a pulled model must be remembered, got %q", got)
	}
	if strings.Contains(strings.Join(*lines, "\n"), "Could not download") {
		t.Errorf("unexpected failure line: %v", *lines)
	}
}

// A pull that fails must say so and must not be recorded as installed, so the
// next run of Setup still offers it (any OS).
func TestFailedModelPullIsNotRecordedAsInstalled(t *testing.T) {
	for _, goos := range []string{"windows", "linux"} {
		t.Run(goos, func(t *testing.T) {
			newFakeOllama(t, `{"status":"pulling manifest"}`+"\n"+`{"error":"pull model manifest: file does not exist"}`+"\n")
			root, x, lines := modelRun(t, goos, "tier=personal\nprofiles=workspace\nlocal_ai=host\n")
			log := strings.Join(*lines, "\n")
			if !strings.Contains(log, "Could not download qwen3.5:4b") {
				t.Errorf("the failure must be reported:\n%s", log)
			}
			if got := chosen(t, root, x); got != "workspace" {
				t.Errorf("the failed pack must not be remembered, got %q", got)
			}
		})
	}
}

// Ollama closing the stream without a final "success" is a failure, not a pull.
func TestModelPullWithoutSuccessIsAFailure(t *testing.T) {
	newFakeOllama(t, `{"status":"pulling manifest"}`+"\n")
	root, x, lines := modelRun(t, "windows", "tier=personal\nprofiles=workspace\nlocal_ai=host\n")
	if got := chosen(t, root, x); got != "workspace" {
		t.Errorf("remembered %q", got)
	}
	if !strings.Contains(strings.Join(*lines, "\n"), "Could not download qwen3.5:4b") {
		t.Errorf("no failure line: %v", *lines)
	}
}

// No Ollama anywhere (not running here, and the install does not use the
// container): fail loudly instead of reporting a download that never happened.
func TestWindowsModelPullWithNoOllamaFailsLoudly(t *testing.T) {
	old := ollamaURL
	ollamaURL = "http://127.0.0.1:1/api/tags"
	t.Cleanup(func() { ollamaURL = old })
	root, x, lines := modelRun(t, "windows", "tier=personal\nprofiles=workspace\n")
	log := strings.Join(*lines, "\n")
	if !strings.Contains(log, "Could not download qwen3.5:4b") || !strings.Contains(strings.ToLower(log), "ollama") {
		t.Errorf("want a loud, specific failure:\n%s", log)
	}
	if got := chosen(t, root, x); got != "workspace" {
		t.Errorf("remembered %q", got)
	}
}

// The install that uses the Ollama container pulls inside that container.
func TestWindowsModelPullUsesTheOllamaContainerWhenThatIsWhatTheInstallRuns(t *testing.T) {
	newFakeOllama(t, pullOK) // an unrelated host Ollama must not be used when the state says container
	var asked []string
	old := pullViaContainer
	pullViaContainer = func(ctx context.Context, root, tag string, emit func(Event)) error {
		asked = append(asked, tag)
		emit(Event{Line: "pulling in the container"})
		return nil
	}
	t.Cleanup(func() { pullViaContainer = old })
	root, x, _ := modelRun(t, "windows", "tier=personal\nprofiles=workspace\nlocal_ai=container\nlocal_model=qwen3.5:4b\n")
	if len(asked) != 1 || asked[0] != "qwen3.5:4b" {
		t.Fatalf("container pull asked for %v", asked)
	}
	if got := chosen(t, root, x); got != "workspace,model-qwen35-4b" {
		t.Errorf("remembered %q", got)
	}

	pullViaContainer = func(context.Context, string, string, func(Event)) error {
		return errors.New("no ollama container is running")
	}
	root2, x2, lines := modelRun(t, "windows", "tier=personal\nprofiles=workspace\nlocal_ai=container\n")
	if got := chosen(t, root2, x2); got != "workspace" {
		t.Errorf("a failed container pull must not be remembered: %q", got)
	}
	if !strings.Contains(strings.Join(*lines, "\n"), "no ollama container is running") {
		t.Errorf("the reason must be shown: %v", *lines)
	}
}

func TestPullModelsReportsWhichPullsFailed(t *testing.T) {
	newFakeOllama(t, `{"error":"boom"}`+"\n")
	failed := PullModels(context.Background(), t.TempDir(), "windows", []Pull{{ID: "model-qwen35-4b", Tag: "qwen3.5:4b"}}, func(Event) {})
	if len(failed) != 1 || failed[0].ID != "model-qwen35-4b" {
		t.Errorf("failed = %+v", failed)
	}
}
