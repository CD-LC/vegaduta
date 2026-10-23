// Release fetch/verify/unpack, mirroring install.sh: resolve the tag, fetch
// vegaduta-<ver>.tar.gz plus the release's SHA256SUMS, refuse anything whose
// checksum is not listed, verify the keyless Sigstore signature of SHA256SUMS
// when cosign is installed (mandatory with VEGADUTA_REQUIRE_SIGNATURE=1), and
// untar with one leading component stripped.
package main

import (
	"archive/tar"
	"compress/gzip"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"time"
)

// semverRe is a stable release version: what "latest" may resolve to.
var semverRe = regexp.MustCompile(`^\d+\.\d+\.\d+$`)

// releaseVersionRe is what --version accepts: a stable version or a pre-release
// such as 1.0.0-rc.1, as release.yml publishes and install.sh installs. The
// suffix is dot-separated alphanumeric identifiers, so nothing that could
// change the download URL (a slash, "..", a query) gets through.
var releaseVersionRe = regexp.MustCompile(`^\d+\.\d+\.\d+(-[0-9A-Za-z]+(\.[0-9A-Za-z]+)*)?$`)

// githubWeb and githubAPI are vars so tests can point them at a fake GitHub.
var (
	githubWeb = "https://github.com"
	githubAPI = "https://api.github.com"
)

var httpClient = &http.Client{Timeout: 10 * time.Minute, Transport: newTransport()}

// noRedirectClient reads a redirect's Location without following it.
var noRedirectClient = &http.Client{
	Timeout:       30 * time.Second,
	Transport:     httpClient.Transport,
	CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
}

// validateVersion normalises an explicit --version ("v1.2.3" -> "1.2.3") and
// rejects anything else; "" (the latest release) is fine.
func validateVersion(v string) (string, error) {
	if v == "" {
		return "", nil
	}
	n := strings.TrimPrefix(v, "v")
	if !releaseVersionRe.MatchString(n) {
		return "", fmt.Errorf("invalid version %q (expected X.Y.Z or a pre-release such as X.Y.Z-rc.1)", v)
	}
	return n, nil
}

// resolveVersion returns the version to install: an explicit --version, else
// the newest stable VegaDuta release of the repo.
func resolveVersion(ctx context.Context, c config) (string, error) {
	if c.version != "" {
		return validateVersion(c.version)
	}
	return latestRelease(ctx, c.repo)
}

// stableTag returns the version of a tag that is a VegaDuta stable release
// (vX.Y.Z). Other tags in the repo - the SDK releases (sdk-v0.1.0), pre-releases
// (v1.0.0-rc.1) - are not what "latest" means.
func stableTag(tag string) (string, bool) {
	v, ok := strings.CutPrefix(tag, "v")
	if !ok || !semverRe.MatchString(v) {
		return "", false
	}
	return v, true
}

// latestRelease resolves "latest" the way install.sh does: the Location of
// https://github.com/<repo>/releases/latest, which costs no API quota (the API
// allows an unauthenticated address 60 requests an hour, and a shared office or
// campus address runs out). Only when that does not name a vX.Y.Z tag - GitHub
// once marked the Java SDK release the Latest - is the release list read from
// the API and filtered.
func latestRelease(ctx context.Context, repo string) (string, error) {
	tag, redirErr := latestTagFromRedirect(ctx, repo)
	if v, ok := stableTag(tag); ok {
		return v, nil
	}
	v, apiErr := latestFromAPI(ctx, repo)
	if apiErr == nil {
		return v, nil
	}
	var why []string
	switch {
	case tag != "":
		why = append(why, fmt.Sprintf("GitHub's Latest release of %s is %q, which is not a VegaDuta release (those are tagged vX.Y.Z)", repo, tag))
	case redirErr != nil:
		why = append(why, redirErr.Error())
	}
	why = append(why, apiErr.Error())
	return "", fmt.Errorf("could not work out the latest release of %s: %s; pass --version X.Y.Z to choose one", repo, strings.Join(why, "; "))
}

func latestTagFromRedirect(ctx context.Context, repo string) (string, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodHead, fmt.Sprintf("%s/%s/releases/latest", githubWeb, repo), nil)
	if err != nil {
		return "", err
	}
	resp, err := noRedirectClient.Do(req)
	if err != nil {
		return "", withProxyHint(fmt.Errorf("could not reach GitHub: %w", err))
	}
	resp.Body.Close()
	loc := resp.Header.Get("Location")
	i := strings.LastIndex(loc, "/releases/tag/")
	if resp.StatusCode < 300 || resp.StatusCode >= 400 || i < 0 {
		return "", fmt.Errorf("%s/%s/releases/latest answered HTTP %d without a release", githubWeb, repo, resp.StatusCode)
	}
	tag, err := url.PathUnescape(loc[i+len("/releases/tag/"):])
	if err != nil {
		return "", err
	}
	return tag, nil
}

// latestFromAPI reads the newest published stable vX.Y.Z release from the API.
func latestFromAPI(ctx context.Context, repo string) (string, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, fmt.Sprintf("%s/repos/%s/releases?per_page=30", githubAPI, repo), nil)
	if err != nil {
		return "", err
	}
	req.Header.Set("Accept", "application/vnd.github+json")
	resp, err := httpClient.Do(req)
	if err != nil {
		return "", withProxyHint(fmt.Errorf("could not reach GitHub's API: %w", err))
	}
	defer resp.Body.Close()
	switch {
	case resp.StatusCode == http.StatusForbidden || resp.StatusCode == http.StatusTooManyRequests:
		return "", fmt.Errorf("GitHub's API is rate-limiting this network (HTTP %d; unauthenticated callers get 60 requests an hour) - try again later", resp.StatusCode)
	case resp.StatusCode == http.StatusNotFound:
		return "", fmt.Errorf("no published release of %s was found (is the repository name right?)", repo)
	case resp.StatusCode != http.StatusOK:
		return "", fmt.Errorf("GitHub's API answered HTTP %d", resp.StatusCode)
	}
	var rels []struct {
		Tag        string `json:"tag_name"`
		Draft      bool   `json:"draft"`
		Prerelease bool   `json:"prerelease"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&rels); err != nil {
		return "", fmt.Errorf("could not read GitHub's release list: %w", err)
	}
	var seen []string
	for _, r := range rels {
		if v, ok := stableTag(r.Tag); ok && !r.Draft && !r.Prerelease {
			return v, nil
		}
		seen = append(seen, r.Tag)
	}
	if len(rels) == 0 {
		return "", fmt.Errorf("no published release of %s was found", repo)
	}
	return "", fmt.Errorf("none of the %d newest releases of %s is a stable VegaDuta release (tagged vX.Y.Z); saw %s",
		len(rels), repo, strings.Join(seen, ", "))
}

// fetchRelease downloads and verifies the tarball, returning its path inside a
// temp dir the caller must remove.
func fetchRelease(ctx context.Context, c config, version string, onEvent func(Event)) (tgz, tmp string, err error) {
	tmp, err = os.MkdirTemp("", "vegaduta-install-*")
	if err != nil {
		return "", "", err
	}
	fail := func(e error) (string, string, error) { os.RemoveAll(tmp); return "", "", e }

	base := fmt.Sprintf("https://github.com/%s/releases/download/v%s", c.repo, version)
	name := fmt.Sprintf("vegaduta-%s.tar.gz", version)
	url := base + "/" + name

	// install.sh's VEGADUTA_RELEASE_URL contract: a custom tarball URL (or a
	// file:// payload bundled inside the app). Its digest comes from
	// --release-sha256, else the SHA256SUMS sitting next to the tarball.
	var want string
	if c.releaseURL != "" {
		url = c.releaseURL
		name = filepath.Base(strings.TrimSuffix(url, "/"))
		want = c.releaseSHA
	}
	if want == "" {
		sums := filepath.Join(tmp, "SHA256SUMS")
		sumBase := base
		if c.releaseURL != "" {
			sumBase = url[:len(url)-len(filepath.Base(url))-1]
		}
		// Current releases publish SHA256SUMS; v0.1.0 named it SHA256SUMS.txt
		// (install.sh falls back the same way).
		if err := download(ctx, sumBase+"/SHA256SUMS", sums, nil); err != nil {
			if err2 := download(ctx, sumBase+"/SHA256SUMS.txt", sums, nil); err2 != nil {
				return fail(fmt.Errorf("downloading SHA256SUMS: %w (refusing an unverified install)", err))
			}
		}
		want, err = sumFor(sums, name)
		if err != nil {
			return fail(err)
		}
		if c.releaseURL == "" {
			if err := verifySignature(ctx, c, base, tmp, sums); err != nil {
				return fail(err)
			}
		}
	}

	tgz = filepath.Join(tmp, name)
	onEvent(Event{Line: "Downloading VegaDuta v" + version + " (" + name + ")"})
	err = download(ctx, url, tgz, func(done, total int64) {
		onEvent(Event{Phase: "download", Done: done, Total: total})
	})
	if err != nil {
		return fail(fmt.Errorf("downloading %s: %w", name, err))
	}
	got, err := sha256File(tgz)
	if err != nil {
		return fail(err)
	}
	if got != want {
		return fail(fmt.Errorf("checksum mismatch for %s (expected %s, got %s) - not installing", name, want, got))
	}
	onEvent(Event{Line: "SHA-256 of " + name + " verified"})
	return tgz, tmp, nil
}

// sumFor reads a sha256sum-format file and returns the hex digest recorded for
// name ("<sha256>  <name>", with optional "./" or "*" prefixes, as install.sh).
func sumFor(sumsFile, name string) (string, error) {
	data, err := os.ReadFile(sumsFile)
	if err != nil {
		return "", err
	}
	for line := range strings.Lines(string(data)) {
		fields := strings.Fields(line)
		if len(fields) != 2 {
			continue
		}
		n := strings.TrimPrefix(strings.TrimPrefix(fields[1], "*"), "./")
		if n == name {
			return strings.ToLower(fields[0]), nil
		}
	}
	return "", fmt.Errorf("SHA256SUMS has no entry for %s", name)
}

// verifySignature mirrors install.sh: when cosign is installed and the release
// publishes a bundle, verify SHA256SUMS is signed by this repo's release
// workflow. VEGADUTA_REQUIRE_SIGNATURE=1 makes the check mandatory.
func verifySignature(ctx context.Context, c config, base, tmp, sums string) error {
	require := os.Getenv("VEGADUTA_REQUIRE_SIGNATURE") == "1"
	if _, err := exec.LookPath("cosign"); err != nil {
		if require {
			return fmt.Errorf("VEGADUTA_REQUIRE_SIGNATURE=1 but cosign is not installed")
		}
		return nil
	}
	bundle := filepath.Join(tmp, "SHA256SUMS.sigstore.json")
	if err := download(ctx, base+"/SHA256SUMS.sigstore.json", bundle, nil); err != nil {
		if require {
			return fmt.Errorf("release v%s publishes no signature bundle", strings.TrimPrefix(c.version, "v"))
		}
		return nil // bundle optional, like install.sh
	}
	out, err := exec.CommandContext(ctx, "cosign", "verify-blob", sums,
		"--bundle", bundle,
		"--certificate-identity-regexp", `^https://github\.com/`+regexp.QuoteMeta(c.repo)+`/\.github/workflows/release\.yml@refs/tags/v`,
		"--certificate-oidc-issuer", "https://token.actions.githubusercontent.com").CombinedOutput()
	if err != nil {
		return fmt.Errorf("cosign could not verify SHA256SUMS: %s", strings.TrimSpace(string(out)))
	}
	return nil
}

func sha256File(path string) (string, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()
	h := sha256.New()
	if _, err := io.Copy(h, f); err != nil {
		return "", err
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}

func download(ctx context.Context, url, dest string, progress func(done, total int64)) error {
	if p, ok := strings.CutPrefix(url, "file://"); ok {
		return copyFile(p, dest, progress)
	}
	req, err := http.NewRequestWithContext(ctx, "GET", url, nil)
	if err != nil {
		return err
	}
	resp, err := httpClient.Do(req)
	if err != nil {
		return withProxyHint(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return fmt.Errorf("GET %s -> %d", url, resp.StatusCode)
	}
	f, err := os.OpenFile(dest, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o600)
	if err != nil {
		return err
	}
	defer f.Close()
	var w io.Writer = f
	if progress != nil {
		w = io.MultiWriter(f, &progressWriter{n: resp.ContentLength, fn: progress})
	}
	_, err = io.Copy(w, resp.Body)
	return err
}

func copyFile(src, dest string, progress func(done, total int64)) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	out, err := os.OpenFile(dest, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o600)
	if err != nil {
		return err
	}
	defer out.Close()
	var w io.Writer = out
	if progress != nil {
		if st, err := in.Stat(); err == nil {
			w = io.MultiWriter(out, &progressWriter{n: st.Size(), fn: progress})
		}
	}
	_, err = io.Copy(w, in)
	return err
}

type progressWriter struct {
	done int64
	n    int64
	fn   func(done, total int64)
}

func (p *progressWriter) Write(b []byte) (int, error) {
	p.done += int64(len(b))
	p.fn(p.done, p.n)
	return len(b), nil
}

// unpackRelease extracts the tarball into dir, stripping the single top-level
// directory release tarballs are built with, and refusing any member that
// would escape dir.
// symlink is os.Symlink; a var so tests can make it fail the way Windows does.
var symlink = os.Symlink

func unpackRelease(tgz, dir string) error {
	f, err := os.Open(tgz)
	if err != nil {
		return err
	}
	defer f.Close()
	zr, err := gzip.NewReader(f)
	if err != nil {
		return err
	}
	defer zr.Close()
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	// The lexical root keeps the cheap name check; the resolved root (the tempdir
	// is behind /var -> /private/var on macOS) is what an EvalSymlinks answer is
	// compared against - comparing resolved against lexical would refuse every
	// write on such systems.
	root := filepath.Clean(dir)
	resolvedRoot := root
	if r, err := filepath.EvalSymlinks(root); err == nil {
		resolvedRoot = r
	}
	insideLexical := func(p string) bool {
		return p == root || strings.HasPrefix(p, root+string(os.PathSeparator))
	}
	insideResolved := func(p string) bool {
		return p == resolvedRoot || strings.HasPrefix(p, resolvedRoot+string(os.PathSeparator))
	}
	// resolvedInside follows symlinks already on disk, so a member cannot write
	// through a link planted by an earlier member (a lexical check on the name
	// alone does not see that).
	resolvedInside := func(p string) bool {
		if r, err := filepath.EvalSymlinks(p); err == nil {
			return insideResolved(r)
		}
		return insideLexical(p)
	}
	tr := tar.NewReader(zr)
	// Links Windows would not create (no admin rights or Developer Mode): copied
	// from their file once the whole archive is on disk.
	type deferredLink struct{ target, source, name string }
	var copies []deferredLink
	for {
		hdr, err := tr.Next()
		if err == io.EOF {
			for _, c := range copies {
				if err := copyRegular(c.source, c.target); err != nil {
					return fmt.Errorf("could not copy %s in place of its symlink: %w", c.name, err)
				}
			}
			return nil
		}
		if err != nil {
			return err
		}
		// Strip the first path component (the release's top-level dir).
		name := hdr.Name
		if i := strings.Index(name, "/"); i >= 0 {
			name = name[i+1:]
		} else {
			continue
		}
		if name == "" {
			continue
		}
		target := filepath.Clean(filepath.Join(dir, name))
		if !insideLexical(target) {
			return fmt.Errorf("tar member escapes the install dir: %s", hdr.Name)
		}
		switch hdr.Typeflag {
		case tar.TypeDir:
			if err := os.MkdirAll(target, 0o755); err != nil {
				return err
			}
			if !resolvedInside(target) {
				return fmt.Errorf("tar member escapes the install dir through a symlink: %s", hdr.Name)
			}
		case tar.TypeReg:
			parent := filepath.Dir(target)
			if err := os.MkdirAll(parent, 0o755); err != nil {
				return err
			}
			if !resolvedInside(parent) {
				return fmt.Errorf("tar member escapes the install dir through a symlink: %s", hdr.Name)
			}
			w, err := os.OpenFile(target, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, os.FileMode(hdr.Mode)&0o777)
			if err != nil {
				return err
			}
			if _, err := io.Copy(w, tr); err != nil {
				w.Close()
				return err
			}
			w.Close()
		case tar.TypeSymlink:
			// The link's TARGET is confinement-checked too: a -> /etc followed
			// by a regular member a/x would otherwise write through it.
			if filepath.IsAbs(hdr.Linkname) {
				return fmt.Errorf("tar member is an absolute symlink: %s -> %s", hdr.Name, hdr.Linkname)
			}
			parent := filepath.Dir(target)
			if err := os.MkdirAll(parent, 0o755); err != nil {
				return err
			}
			resolved := false
			if r, err := filepath.EvalSymlinks(parent); err == nil {
				parent = r
				resolved = true
			}
			candidate := filepath.Clean(filepath.Join(parent, hdr.Linkname))
			if (resolved && !insideResolved(candidate)) || (!resolved && !insideLexical(candidate)) {
				return fmt.Errorf("tar symlink target escapes the install dir: %s -> %s", hdr.Name, hdr.Linkname)
			}
			if err := symlink(hdr.Linkname, target); err != nil && !os.IsExist(err) {
				copies = append(copies, deferredLink{target, candidate, hdr.Name})
			}
		}
	}
}

// copyRegular writes src's bytes to dst (replacing it); src must be a regular file.
func copyRegular(src, dst string) error {
	info, err := os.Stat(src)
	if err != nil {
		return err
	}
	if !info.Mode().IsRegular() {
		return fmt.Errorf("%s is not a regular file", src)
	}
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	_ = os.Remove(dst)
	out, err := os.OpenFile(dst, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, info.Mode().Perm())
	if err != nil {
		return err
	}
	if _, err := io.Copy(out, in); err != nil {
		out.Close()
		return err
	}
	return out.Close()
}
