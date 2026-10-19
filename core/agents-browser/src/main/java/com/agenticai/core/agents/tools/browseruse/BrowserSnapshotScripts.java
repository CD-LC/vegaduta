package com.agenticai.core.agents.tools.browseruse;

/**
 * The in-page JavaScript the v2 browser tools run through {@code Page.evaluate} (no new dependency,
 * no injected files). Each constant is a single function expression; Playwright calls it with one
 * argument.
 *
 * <p>Design, in one paragraph: the first snapshot installs {@code window.__vd} - a registry holding
 * the array of element references the last snapshot listed (1-based, so the number the model sees
 * IS the array index), a DOM generation counter driven by a {@code MutationObserver} (it counts
 * added/removed ELEMENT nodes, ignoring {@code script/style/link/meta/br}), and the shared
 * role/name functions so the later staleness probe computes exactly what the snapshot listed.
 * Elements are referenced by identity, never by writing attributes into the page, so the snapshot
 * does not mutate the DOM it describes and does not trip the page's own mutation observers. A full
 * navigation replaces {@code window}, which wipes the registry - that is the "the document changed"
 * signal.
 *
 * <p>These scripts cannot be unit-tested without a browser: they are covered by the pure
 * parser/formatter tests on the JSON shape they return, and need one real-Chromium smoke test (see
 * the lane report). Known limits, by design: same-origin/cross-origin iframes are not entered (open
 * shadow roots are), inner scroll containers are not scrolled, and a custom checkbox whose real
 * {@code input} is visually hidden behind a label is not listed.
 */
final class BrowserSnapshotScripts {

    private BrowserSnapshotScripts() {}

    /**
     * Argument: {@code {max, nameMax, valueMax, digestChars}}. Returns a JSON string: {@code
     * {url,title,gen,total,scrollY,scrollHeight,viewportHeight,elements:[{i,role,name,value,states,href,options,inView}],digest}}.
     */
    static final String SNAPSHOT =
            """
        (opts) => {
          const MAX = opts.max, NAME_MAX = opts.nameMax, VAL_MAX = opts.valueMax, DIGEST = opts.digestChars;
          let vd = window.__vd;
          if (!vd) {
            vd = { gen: 0, els: [null], snapGen: -1, secret: new WeakSet(), nameMax: NAME_MAX };
            const SKIP = { SCRIPT: 1, STYLE: 1, LINK: 1, META: 1, NOSCRIPT: 1, BR: 1, TEMPLATE: 1, HEAD: 1 };
            const rel = (n) => n.nodeType === 1 && !SKIP[n.nodeName];
            const scan = (recs) => {
              for (const r of recs) {
                if (r.type !== 'childList') continue;
                for (const n of r.addedNodes) { if (rel(n)) { vd.gen++; return; } }
                for (const n of r.removedNodes) { if (rel(n)) { vd.gen++; return; } }
              }
            };
            vd.mo = new MutationObserver(scan);
            vd.mo.observe(document.documentElement, { childList: true, subtree: true });
            vd.flush = () => { scan(vd.mo.takeRecords()); };
            vd.clean = (s) => (s == null ? '' : String(s)).replace(/\\s+/g, ' ').trim();
            vd.clip = (s, n) => { s = vd.clean(s); return s.length > n ? s.slice(0, n - 1) + '\\u2026' : s; };
            vd.roleOf = (el) => {
              const ar = el.getAttribute('role');
              if (ar) return ar.split(/\\s+/)[0].toLowerCase();
              const t = el.tagName.toLowerCase();
              if (t === 'a') return el.hasAttribute('href') ? 'link' : 'clickable';
              if (t === 'button' || t === 'summary') return 'button';
              if (t === 'select') return (el.multiple || el.size > 1) ? 'listbox' : 'combobox';
              if (t === 'textarea') return 'textbox';
              if (t === 'input') {
                const ty = (el.getAttribute('type') || 'text').toLowerCase();
                if (ty === 'checkbox' || ty === 'radio') return ty;
                if (ty === 'submit' || ty === 'button' || ty === 'reset' || ty === 'image') return 'button';
                if (ty === 'range') return 'slider';
                if (ty === 'search') return 'searchbox';
                return 'textbox';
              }
              if (el.isContentEditable) return 'textbox';
              return 'clickable';
            };
            vd.nameOf = (el) => {
              const c = vd.clean;
              let n = el.getAttribute('aria-label');
              if (n && c(n)) return c(n);
              const lb = el.getAttribute('aria-labelledby');
              if (lb) {
                const t = lb.split(/\\s+/).map((id) => { const x = document.getElementById(id); return x ? x.textContent : ''; }).join(' ');
                if (c(t)) return c(t);
              }
              if (el.labels && el.labels.length) {
                const t = Array.from(el.labels).map((l) => l.textContent).join(' ');
                if (c(t)) return c(t);
              }
              const tag = el.tagName;
              if (tag === 'INPUT') {
                const ty = (el.getAttribute('type') || 'text').toLowerCase();
                if (ty === 'submit' || ty === 'button' || ty === 'reset') return c(el.value) || ty;
                if (ty === 'image') return c(el.alt) || 'image button';
              }
              const alt = el.getAttribute('alt');
              if (alt && c(alt)) return c(alt);
              if (tag !== 'INPUT' && tag !== 'SELECT' && tag !== 'TEXTAREA' && !el.isContentEditable) {
                const txt = c(el.innerText || el.textContent);
                if (txt) return txt;
                const img = el.querySelector && el.querySelector('img[alt]');
                if (img && c(img.getAttribute('alt'))) return c(img.getAttribute('alt'));
              }
              const ti = el.getAttribute('title');
              if (ti && c(ti)) return c(ti);
              const ph = el.getAttribute('placeholder');
              if (ph && c(ph)) return c(ph);
              const nm = el.getAttribute('name');
              return nm ? c(nm) : '';
            };
            window.__vd = vd;
          }
          vd.nameMax = NAME_MAX;
          vd.flush();
          const clip = vd.clip, clean = vd.clean;
          const vw = innerWidth, vh = innerHeight;
          const SEL = 'a[href],button,input:not([type=hidden]),select,textarea,summary,[role=button],[role=link],[role=checkbox],'
            + '[role=radio],[role=tab],[role=menuitem],[role=menuitemcheckbox],[role=menuitemradio],[role=option],[role=switch],'
            + '[role=combobox],[role=textbox],[role=searchbox],[role=slider],[contenteditable=""],[contenteditable="true"],'
            + '[onclick],[tabindex="0"]';
          const found = [];
          const walk = (root) => {
            root.querySelectorAll(SEL).forEach((e) => { if (found.length < 3000) found.push(e); });
            root.querySelectorAll('*').forEach((e) => { if (e.shadowRoot) walk(e.shadowRoot); });
          };
          walk(document);
          const visibleRect = (el) => {
            const r = el.getBoundingClientRect();
            if (r.width < 1 || r.height < 1) return null;
            const cs = getComputedStyle(el);
            if (cs.visibility === 'hidden' || cs.display === 'none' || parseFloat(cs.opacity) === 0) return null;
            return r;
          };
          const inVp = (r) => r.bottom > 0 && r.right > 0 && r.top < vh && r.left < vw;
          const hit = (el, r) => {
            if (el.getRootNode() !== document) return true;
            const x = Math.min(Math.max(r.left + r.width / 2, 0), vw - 1);
            const y = Math.min(Math.max(r.top + r.height / 2, 0), vh - 1);
            const top = document.elementFromPoint(x, y);
            return !top || top === el || el.contains(top) || top.contains(el);
          };
          const kept = [], keptSet = new Set(), keptName = new Map(), seenLinks = new Set();
          for (const el of found) {
            const r = visibleRect(el);
            if (!r) continue;
            if (inVp(r) && !hit(el, r)) continue;
            const role = vd.roleOf(el);
            const name = clip(vd.nameOf(el), NAME_MAX);
            if (role === 'clickable' && !name) continue;
            let dup = false;
            for (let p = el.parentElement; p; p = p.parentElement) {
              if (keptSet.has(p)) { dup = keptName.get(p) === name; break; }
            }
            if (dup) continue;
            if (role === 'link') {
              const k = (el.getAttribute('href') || '') + '|' + name;
              if (seenLinks.has(k)) continue;
              seenLinks.add(k);
            }
            keptSet.add(el); keptName.set(el, name);
            kept.push({ el: el, r: r, role: role, name: name });
          }
          const total = kept.length;
          let chosen = kept;
          if (total > MAX) {
            const dist = (r) => (r.bottom < 0 ? -r.bottom : (r.top > vh ? r.top - vh : 0));
            chosen = kept.map((k, i) => ({ k: k, i: i, d: dist(k.r) }))
              .sort((a, b) => (a.d - b.d) || (a.i - b.i)).slice(0, MAX).map((x) => x.k);
          }
          const sy = scrollY;
          chosen.sort((a, b) => {
            const ay = Math.round((a.r.top + sy) / 10), by = Math.round((b.r.top + sy) / 10);
            return (ay - by) || (a.r.left - b.r.left);
          });
          vd.els = [null].concat(chosen.map((c) => c.el));
          vd.snapGen = vd.gen;
          const out = chosen.map((c, idx) => {
            const el = c.el, tag = el.tagName;
            const ty = tag === 'INPUT' ? (el.getAttribute('type') || 'text').toLowerCase() : '';
            const states = [];
            let value = '';
            let options = [];
            if (tag === 'INPUT' || tag === 'TEXTAREA') {
              if (vd.secret.has(el)) value = '(secret, hidden)';
              else if (ty === 'password') value = el.value ? '(filled)' : '';
              else if (ty !== 'checkbox' && ty !== 'radio' && ty !== 'submit' && ty !== 'button' && ty !== 'reset' && ty !== 'file' && ty !== 'image') value = clip(el.value, VAL_MAX);
              if (ty && ty !== 'text' && ty !== 'checkbox' && ty !== 'radio' && ty !== 'submit' && ty !== 'button' && ty !== 'reset' && ty !== 'image') states.push('type=' + ty);
              if (ty === 'checkbox' || ty === 'radio') states.push(el.checked ? 'checked' : 'unchecked');
            } else if (tag === 'SELECT') {
              value = clip(Array.from(el.selectedOptions).map((o) => o.textContent).join(', '), VAL_MAX);
              options = Array.from(el.options).slice(0, 8).map((o) => clip(o.textContent, 30));
            } else if (el.isContentEditable) {
              value = vd.secret.has(el) ? '(secret, hidden)' : clip(el.textContent, VAL_MAX);
            }
            const ac = el.getAttribute('aria-checked');
            if (ac === 'true') states.push('checked');
            if (el.disabled || el.getAttribute('aria-disabled') === 'true') states.push('disabled');
            if (el.required || el.getAttribute('aria-required') === 'true') states.push('required');
            if (el.readOnly) states.push('readonly');
            const ae = el.getAttribute('aria-expanded');
            if (ae === 'true') states.push('expanded'); else if (ae === 'false') states.push('collapsed');
            if (el.getAttribute('aria-selected') === 'true' || el.getAttribute('aria-pressed') === 'true') states.push('selected');
            if (el === document.activeElement) states.push('focused');
            const href = c.role === 'link' ? clip(el.getAttribute('href') || '', 80) : '';
            return { i: idx + 1, role: c.role, name: c.name, value: value, states: states, href: href, options: options, inView: inVp(c.r) };
          });
          const body = document.body;
          return JSON.stringify({
            url: location.href, title: document.title, gen: vd.gen, total: total,
            scrollY: Math.round(sy), scrollHeight: document.documentElement.scrollHeight, viewportHeight: vh,
            elements: out, digest: clip(body ? body.innerText : '', DIGEST)
          });
        }
        """;

    /**
     * Argument: the 1-based index. Returns a JSON string {@code
     * {reg,gen,snapGen,present,connected,role,name}}; {@code reg:false} means the registry is gone
     * (the document was replaced).
     */
    static final String CHECK =
            """
        (i) => {
          const vd = window.__vd;
          if (!vd || !vd.els) return JSON.stringify({ reg: false });
          vd.flush();
          const el = vd.els[i];
          if (!el) return JSON.stringify({ reg: true, gen: vd.gen, snapGen: vd.snapGen, present: false });
          return JSON.stringify({
            reg: true, gen: vd.gen, snapGen: vd.snapGen, present: true, connected: el.isConnected,
            role: vd.roleOf(el), name: vd.clip(vd.nameOf(el), vd.nameMax)
          });
        }
        """;

    /**
     * Argument: the 1-based index. Returns the element (or null) from the registry, for {@code
     * evaluateHandle}.
     */
    static final String ELEMENT =
            """
        (i) => (window.__vd && window.__vd.els && window.__vd.els[i]) || null
        """;

    /**
     * Run on an element handle just before a vault secret is typed into it: hides the characters on
     * screen and in later snapshots.
     */
    static final String MARK_SECRET =
            """
        (el) => {
          if (window.__vd) { window.__vd.secret.add(el); }
          el.style.setProperty('-webkit-text-security', 'disc');
        }
        """;

    /**
     * Cheap "did the page observably change" fingerprint: url, text length, element count, scroll
     * offsets and every form control's value/checked state, hashed. The hash stays on the server -
     * never in a result.
     */
    static final String FINGERPRINT =
            """
        () => {
          try {
            let h = 0;
            const add = (s) => { for (let k = 0; k < s.length; k++) h = (h * 31 + s.charCodeAt(k)) | 0; };
            const b = document.body;
            add(location.href);
            add('|' + (b ? b.innerText.length : 0));
            add('|' + document.getElementsByTagName('*').length);
            add('|' + Math.round(scrollY) + '|' + Math.round(scrollX));
            document.querySelectorAll('input,textarea,select').forEach((e) => {
              add('|' + ((e.type === 'checkbox' || e.type === 'radio') ? (e.checked ? 1 : 0) : e.value));
            });
            return String(h);
          } catch (e) { return 'err'; }
        }
        """;

    /**
     * Argument: {@code [direction, px]}. Returns JSON {@code
     * {scrollY,scrollHeight,viewportHeight}}.
     */
    static final String SCROLL =
            """
        (a) => {
          const dir = a[0], px = a[1], vh = innerHeight;
          if (dir === 'top') scrollTo(0, 0);
          else if (dir === 'bottom') scrollTo(0, document.documentElement.scrollHeight);
          else scrollBy(0, (dir === 'up' ? -1 : 1) * (px > 0 ? px : Math.round(vh * 0.8)));
          return JSON.stringify({ scrollY: Math.round(scrollY), scrollHeight: document.documentElement.scrollHeight, viewportHeight: vh });
        }
        """;

    /**
     * Run on an element handle: the form value for inputs/selects (never a password), else the
     * visible text.
     */
    static final String ELEMENT_TEXT =
            """
        (el) => {
          const t = el.tagName;
          if (window.__vd && window.__vd.secret.has(el)) return '(secret field - value hidden)';
          if (t === 'INPUT' && (el.type || '').toLowerCase() === 'password') return '(password field - value hidden)';
          if (t === 'INPUT' || t === 'TEXTAREA' || t === 'SELECT') return el.value == null ? '' : String(el.value);
          return el.innerText || el.textContent || '';
        }
        """;

    /** Visible text of the document body, for {@code browser_extract_text} without an index. */
    static final String BODY_TEXT =
            """
        () => (document.body ? document.body.innerText : '')
        """;
}
