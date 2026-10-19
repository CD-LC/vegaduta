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
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"time"
)

var semverRe = regexp.MustCompile(`^\d+\.\d+\.\d+$`)

var httpClient = &http.Client{Timeout: 10 * time.Minute}

// resolveVersion returns the X.Y.Z to install: an explicit --version, else the
// tag of the repo's latest published release (same redirect trick install.sh
// uses, over the GitHub API).
func resolveVersion(ctx context.Context, c config) (string, error) {
	if c.version != "" {
		v := strings.TrimPrefix(c.version, "v")
		if !semverRe.MatchString(v) {
			return "", fmt.Errorf("invalid version %q (expected X.Y.Z)", c.version)
		}
		return v, nil
	}
	req, err := http.NewRequestWithContext(ctx, "GET",
		fmt.Sprintf("https://api.github.com/repos/%s/releases/latest", c.repo), nil)
	if err != nil {
		return "", err
	}
	req.Header.Set("Accept", "application/vnd.github+json")
	resp, err := httpClient.Do(req)
	if err != nil {
		return "", fmt.Errorf("could not resolve the latest release of %s: %w", c.repo, err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return "", fmt.Errorf("no published release of %s (GET %s -> %d); pass --version X.Y.Z",
			c.repo, req.URL, resp.StatusCode)
	}
	var body struct {
		Tag string `json:"tag_name"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		return "", err
	}
	v := strings.TrimPrefix(body.Tag, "v")
	if !semverRe.MatchString(v) {
		return "", fmt.Errorf("latest release tag %q is not X.Y.Z", body.Tag)
	}
	return v, nil
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
		if err := download(ctx, sumBase+"/SHA256SUMS", sums, nil); err != nil {
			return fail(fmt.Errorf("downloading SHA256SUMS: %w (refusing an unverified install)", err))
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
		return err
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
	for {
		hdr, err := tr.Next()
		if err == io.EOF {
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
			if err := os.Symlink(hdr.Linkname, target); err != nil && !os.IsExist(err) {
				return err
			}
		}
	}
}
