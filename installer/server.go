// The wizard: a loopback-only HTTP server that renders the installer page,
// exposes preflight/install/status as JSON, and streams progress as SSE. The
// page is the whole UI - the product itself is already browser-based, so the
// installer is too.
package main

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
	_ "embed"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

//go:embed ui/index.html
var wizardHTML []byte

//go:embed ui/logo-mark.webp
var logoMark []byte

// Event is one thing the UI shows: a log line, a download progress update, a
// phase change, or the terminal state (console URL or error).
type Event struct {
	Phase string `json:"phase,omitempty"`
	Line  string `json:"line,omitempty"`
	Done  int64  `json:"done,omitempty"`
	Total int64  `json:"total,omitempty"`
	URL   string `json:"url,omitempty"`
	Err   string `json:"error,omitempty"`
}

// hub fans events out to every /api/events subscriber and replays history to
// late joiners (the page can reload mid-install without losing the log).
type hub struct {
	mu   sync.Mutex
	log  []Event
	subs map[chan Event]struct{}
}

func newHub() *hub { return &hub{subs: map[chan Event]struct{}{}} }

func (h *hub) emit(e Event) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.log = append(h.log, e)
	for ch := range h.subs {
		select {
		case ch <- e:
		default: // a stalled browser must not block the install
		}
	}
}

func (h *hub) subscribe() (chan Event, []Event, func()) {
	ch := make(chan Event, 64)
	h.mu.Lock()
	h.subs[ch] = struct{}{}
	hist := append([]Event(nil), h.log...)
	h.mu.Unlock()
	return ch, hist, func() { h.mu.Lock(); delete(h.subs, ch); h.mu.Unlock() }
}

// reset drops the replayed history. A new install run starts from a clean log:
// without it a fresh page load would replay the previous run's error event,
// which the page treats as terminal (it shows the stale failure and stops
// listening), so a retry looked exactly like a dead install.
func (h *hub) reset() {
	h.mu.Lock()
	h.log = nil
	h.mu.Unlock()
}

// wizardToken gates every /api/* call. The wizard sits on a fixed, well-known
// loopback port and its POSTs install software (brew/winget, a release), so an
// arbitrary web page could otherwise drive them: fetch() with a plain
// content-type needs no CORS preflight, and EventSource sends no headers. The
// token is random per machine, persisted 0600 inside the install dir so a
// second instance's "already running" URL still opens a working page, and only
// ever travels inside the wizard URL (?k=).
func wizardToken(dir string) string {
	p := filepath.Join(dir, ".vd-setup-token")
	if b, err := os.ReadFile(p); err == nil {
		if t := strings.TrimSpace(string(b)); len(t) == 32 {
			return t
		}
	}
	raw := make([]byte, 16)
	if _, err := rand.Read(raw); err != nil {
		fatalf("could not generate the setup token: %v", err)
	}
	t := hex.EncodeToString(raw)
	if err := os.MkdirAll(dir, 0o700); err == nil {
		_ = os.WriteFile(p, []byte(t), 0o600) // best effort: token survives anyway for this run
	}
	return t
}

// gateAPI wraps the mux: every /api/* request must (a) arrive on a loopback
// Host - a DNS-rebinding page reaches 127.0.0.1 but still carries the
// attacker's hostname in Host - and (b) carry the setup token as ?k= (GET and
// EventSource) or the X-Vd-Token header. The wizard page itself stays public:
// it holds no secrets and cannot act on its own.
func gateAPI(token string, mux http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !strings.HasPrefix(r.URL.Path, "/api/") {
			mux.ServeHTTP(w, r)
			return
		}
		host := r.Host
		if h, _, err := net.SplitHostPort(host); err == nil {
			host = h
		}
		if host != "127.0.0.1" && host != "localhost" && host != "[::1]" {
			http.Error(w, "this installer only answers its own browser tab", http.StatusForbidden)
			return
		}
		tok := r.Header.Get("X-Vd-Token")
		if tok == "" {
			tok = r.URL.Query().Get("k")
		}
		if subtle.ConstantTimeCompare([]byte(tok), []byte(token)) != 1 {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		mux.ServeHTTP(w, r)
	})
}

func serveWizard(ctx context.Context, c config) error {
	token := wizardToken(installRoot(c))
	h := newHub()
	// installRunning gates the run instead of a sync.Once: a failed or finished
	// install may be retried (the fix-then-Install-again path, or applying an
	// Extras change on an existing install), while a second click during a run
	// must not start a parallel one.
	var installMu sync.Mutex
	installRunning := false
	var upgradeOnce sync.Once
	var upgradeNote string
	mux := http.NewServeMux()
	mux.HandleFunc("GET /", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Write(wizardHTML)
	})
	mux.HandleFunc("GET /logo-mark.webp", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "image/webp")
		w.Header().Set("Cache-Control", "public, max-age=3600")
		w.Write(logoMark)
	})
	mux.HandleFunc("GET /api/preflight", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, runPreflight(c))
	})
	mux.HandleFunc("GET /api/plan", func(w http.ResponseWriter, r *http.Request) {
		installed := ""
		if c.from != "" {
			installed = installedVersion(c.from)
		}
		// The release lookup behind the upgrade note is one network call: do it once per run of Setup.
		upgradeOnce.Do(func() { upgradeNote = upgradeAdvice(r.Context(), c, installed, runtime.GOOS) })
		writeJSON(w, map[string]string{
			"tier": c.tier, "dir": c.dir, "repo": c.repo,
			"version": planVersion(c, installed, c.from != ""), "installed": installed,
			"upgrade": upgradeNote, "build": buildVersion, "goos": runtime.GOOS,
		})
	})
	var dockerBusy atomic.Bool
	dockerAction := func(fn func(context.Context, func(Event)) error) http.HandlerFunc {
		return func(w http.ResponseWriter, r *http.Request) {
			if !dockerBusy.CompareAndSwap(false, true) {
				writeJSON(w, map[string]bool{"started": false})
				return
			}
			h.emit(Event{Phase: "docker"})
			go func() {
				defer dockerBusy.Store(false)
				// ctx, not r.Context(): the POST returns at once, and with it its
				// context - the brew/winget/daemon-wait work must outlive it.
				if err := fn(ctx, h.emit); err != nil {
					h.emit(Event{Err: err.Error()})
				}
				h.emit(Event{Phase: "recheck"})
			}()
			writeJSON(w, map[string]bool{"started": true})
		}
	}
	mux.HandleFunc("POST /api/docker/install", dockerAction(dockerInstall))
	mux.HandleFunc("POST /api/docker/start", dockerAction(dockerStart))
	mux.HandleFunc("POST /api/install", func(w http.ResponseWriter, r *http.Request) {
		var body struct {
			Locale    string `json:"locale"`
			Documents bool   `json:"documents"`
			Browser   bool   `json:"browser"`
			// RestartDocker is the wizard's consent box (Windows): raising Docker's memory restarts Docker Desktop and WSL.
			RestartDocker bool      `json:"restartDocker"`
			Extras        *[]string `json:"extras"` // the Extras step's final list; absent from older pages
		}
		_ = json.NewDecoder(r.Body).Decode(&body) // empty body is fine
		var pageSel Selection
		if body.Extras != nil {
			var err error
			if pageSel, err = selectionFromPage(*body.Extras); err != nil {
				http.Error(w, err.Error(), http.StatusBadRequest)
				return
			}
		}
		installMu.Lock()
		if installRunning {
			installMu.Unlock()
			writeJSON(w, map[string]any{"started": false, "running": true})
			return
		}
		installRunning = true
		installMu.Unlock()
		// Fresh run: drop the previous run's events so a page that subscribes now
		// does not replay (and act on) the old failure or "done" state.
		h.reset()
		h.emit(Event{Phase: "begin"})
		cc := c
		if isLocale(body.Locale) {
			cc.locale = body.Locale
		}
		cc.documents = body.Documents || cc.documents
		cc.browser = body.Browser || cc.browser
		cc.dockerRAM = body.RestartDocker || cc.dockerRAM
		if body.Extras != nil {
			cc.extraSel, cc.extraSet = pageSel, true
		}
		go func() {
			defer func() { installMu.Lock(); installRunning = false; installMu.Unlock() }()
			// One click end to end: when the stack is up, open the finish-setup
			// link ourselves (the page still shows it as a button).
			emit := func(e Event) {
				h.emit(e)
				if e.Phase == "done" && e.URL != "" && !c.noBrowser {
					openBrowser(e.URL)
				}
			}
			if err := runInstall(ctx, cc, emit); err != nil {
				h.emit(Event{Phase: "failed", Err: err.Error()})
			}
		}()
		writeJSON(w, map[string]any{"started": true, "running": true})
	})
	registerExtras(mux, c)
	mux.HandleFunc("GET /api/events", func(w http.ResponseWriter, r *http.Request) {
		serveEvents(w, r, h)
	})

	ln, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", c.port))
	if err != nil {
		// Already running (a second double-click): reopen the SAME url
		// instead of a new random port - a moved port is what leaves
		// every older tab spinning on a dead backend forever.
		if c.port > 0 && strings.Contains(err.Error(), "address already in use") {
			url := fmt.Sprintf("http://127.0.0.1:%d/?k=%s", c.port, token)
			fmt.Printf("VegaDuta setup is already running: %s\n", url)
			if !c.noBrowser {
				openBrowser(url)
			}
			<-ctx.Done()
			return nil
		}
		return err
	}
	srv := &http.Server{Handler: gateAPI(token, mux)}
	url := fmt.Sprintf("http://127.0.0.1:%d/?k=%s", ln.Addr().(*net.TCPAddr).Port, token)
	fmt.Printf("VegaDuta setup is ready in your browser: %s\n", url)
	fmt.Println("(keep this window open; close it to cancel)")
	if !c.noBrowser {
		openBrowser(url)
	}
	go func() { <-ctx.Done(); srv.Shutdown(context.Background()) }()
	err = srv.Serve(ln)
	if err == http.ErrServerClosed {
		return nil
	}
	return err
}

func serveEvents(w http.ResponseWriter, r *http.Request, h *hub) {
	fl, ok := w.(http.Flusher)
	if !ok {
		http.Error(w, "streaming unsupported", 500)
		return
	}
	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	ch, hist, unsub := h.subscribe()
	defer unsub()
	write := func(e Event) {
		b, _ := json.Marshal(e)
		fmt.Fprintf(w, "data: %s\n\n", b)
		fl.Flush()
	}
	for _, e := range hist {
		write(e)
	}
	for {
		select {
		case <-r.Context().Done():
			return
		case e := <-ch:
			write(e)
		}
	}
}

// setupURLRe catches the one-time first-run link personal mode prints
// (docs/personal-mode.md): <console>/login?setup=<token>. When it appears the
// wizard finishes on it instead of the bare console URL, so the user lands
// straight on "choose your password".
var setupURLRe = regexp.MustCompile(`https?://[^\s"']+/login\?setup=[^\s"']+`)

// isLocale whitelists the console's supported language codes so the wizard's
// choice can land in .env unchanged (and nothing else can).
// isLocale whitelists the console's supported language codes
// (web/app/lib/i18n-config.ts SUPPORTED_LOCALES) so the wizard's choice can
// land in .env unchanged (and nothing else can).
func isLocale(s string) bool {
	switch s {
	case "en", "es", "fr", "de", "pt", "ar", "zh", "ja",
		"hi", "bn", "mr", "te", "ta", "gu", "ur", "kn", "or", "ml", "pa",
		"as", "mai", "sat", "ks", "ne", "kok", "sd", "doi", "mni", "brx", "sa":
		return true
	}
	return false
}

// runInstall is the pipeline shared by the wizard and --yes mode.
func runInstall(ctx context.Context, c config, emit func(Event)) error {
	var setupLink string
	emitLine := func(s string) {
		if u := setupURLRe.FindString(s); u != "" {
			setupLink = u
		}
		emit(Event{Line: s})
	}
	if c.tier == "personal" {
		emit(Event{Phase: "docker", Line: "Checking Ollama (free local AI models)"})
		ensureOllama(ctx, emit)
	}
	emit(Event{Phase: "docker", Line: "Checking Docker"})
	if err := ensureDocker(ctx, c.tier, c.dockerRAM, emit); err != nil {
		return err
	}
	root := c.from
	if root != "" {
		// A tree is already there (a re-run): it is used as it is, and its version is said out loud.
		installed := installedVersion(root)
		if installed != "" {
			emit(Event{Line: "Using the VegaDuta " + installed + " that is already in " + root})
		}
		if adv := upgradeAdvice(ctx, c, installed, runtime.GOOS); adv != "" {
			emit(Event{Line: adv})
		}
	}
	if root == "" {
		ver := "custom"
		if c.releaseURL == "" {
			emit(Event{Phase: "resolve", Line: "Finding the latest release"})
			v, err := resolveVersion(ctx, c)
			if err != nil {
				return err
			}
			ver = v
		}
		emit(Event{Phase: "download", Line: "Release: v" + ver})
		tgz, tmp, err := fetchRelease(ctx, c, ver, emit)
		if err != nil {
			return err
		}
		defer os.RemoveAll(tmp)
		emit(Event{Phase: "unpack", Line: "Unpacking to " + c.dir})
		if err := unpackRelease(tgz, c.dir); err != nil {
			return err
		}
		root = c.dir
	}
	emit(Event{Phase: "configure", Line: "Generating secrets (.env)"})
	if err := runCLI(ctx, root, emitLine, "secrets"); err != nil {
		return fmt.Errorf("secrets: %w", err)
	}
	if c.locale != "" {
		// The console's proxy falls back to APP_LOCALE when no language cookie
		// exists, so the whole product starts in the chosen language.
		envPath := filepath.Join(root, "deploy", "compose", ".env")
		if data, err := os.ReadFile(envPath); err == nil && !strings.Contains(string(data), "APP_LOCALE=") {
			_ = os.WriteFile(envPath, append(data, []byte("APP_LOCALE="+c.locale+"\n")...), 0o600)
		}
	}
	x := prepareExtras(root, c, runtime.GOOS, emit)
	for _, l := range x.describe() {
		emit(Event{Line: l})
	}
	if err := x.beforeUp(root, emit); err != nil {
		return err
	}
	argv := x.args(c.tier)
	upLine := "Starting the stack - first start pulls images and runs migrations"
	// A bundled/source payload has no VEGADUTA_VERSION pinned in .env: the
	// launcher cannot pull images it has no tag for, so build them instead.
	if needsSourceBuild(root) {
		if b := readLauncherCaps(root).buildArg(); b != "" {
			argv = append(argv, b)
			upLine = "Starting the stack - no published release is pinned, so the images are built from source (takes a while the first time)"
		}
	}
	emit(Event{Phase: "up", Line: upLine})
	start := func(onLine func(string)) error { return runCLI(ctx, root, onLine, argv...) }
	if err := upWithRepair(ctx, start, emit, emitLine, hostPorts(root, c.tier)); err != nil {
		return fmt.Errorf("up: %w", err)
	}
	failedPulls := x.afterUp(ctx, root, emit)
	url := consoleURL(root)
	emit(Event{Phase: "wait", Line: "Waiting for the console at " + url})
	if err := waitHTTP(ctx, url, 3*time.Minute); err != nil {
		emit(Event{Line: "console is not answering yet - it may still be starting"})
	}
	if setupLink != "" {
		url = setupLink
	}
	done := "VegaDuta is up."
	if len(failedPulls) > 0 {
		tags := make([]string, len(failedPulls))
		for i, p := range failedPulls {
			tags[i] = p.Tag
		}
		done = "VegaDuta is up, but the model " + strings.Join(tags, ", ") + " was NOT downloaded (see the log above). Click Install again to retry it."
	}
	emit(Event{Phase: "done", Line: done, URL: url})
	return nil
}

// needsSourceBuild reports whether the install has no published release
// pinned: an absent or empty VEGADUTA_VERSION in deploy/compose/.env means
// every image tag resolves empty, so the launcher must build (--build/-Build)
// rather than pull.
func needsSourceBuild(root string) bool {
	data, err := os.ReadFile(filepath.Join(root, "deploy", "compose", ".env"))
	if err != nil {
		return true
	}
	for line := range strings.Lines(string(data)) {
		if v, ok := strings.CutPrefix(strings.TrimSpace(line), "VEGADUTA_VERSION="); ok {
			return strings.Trim(strings.TrimSpace(v), `"'`) == ""
		}
	}
	return true
}

// consoleURL reads WEB_HOST_PORT - process env first (compose interpolation
// prefers it over .env), then the install's .env; default 3000.
func consoleURL(root string) string {
	port := os.Getenv("WEB_HOST_PORT")
	if port == "" {
		port = "3000"
	}
	data, err := os.ReadFile(filepath.Join(root, "deploy", "compose", ".env"))
	if err == nil {
		for line := range strings.Lines(string(data)) {
			if v, ok := strings.CutPrefix(strings.TrimSpace(line), "WEB_HOST_PORT="); ok {
				port = strings.Trim(strings.TrimSpace(v), `"'`)
			}
		}
	}
	return "http://localhost:" + port
}

func waitHTTP(ctx context.Context, url string, max time.Duration) error {
	deadline := time.Now().Add(max)
	for time.Now().Before(deadline) {
		req, err := http.NewRequestWithContext(ctx, "GET", url, nil)
		if err == nil {
			if resp, err := httpClient.Do(req); err == nil {
				resp.Body.Close()
				if resp.StatusCode < 500 {
					return nil
				}
			}
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(3 * time.Second):
		}
	}
	return fmt.Errorf("timed out waiting for %s", url)
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(v)
}

func openBrowser(url string) {
	var name string
	args := []string{url}
	switch runtime.GOOS {
	case "darwin":
		name = "open"
	case "windows":
		name, args = "rundll32", []string{"url.dll,FileProtocolHandler", url}
	default:
		name = "xdg-open"
	}
	exec.Command(name, args...).Start()
}
