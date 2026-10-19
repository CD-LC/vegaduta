// A tiny offline QR encoder (byte mode, error correction L, versions 1-4,
// mask 0) embedded as plain JS source into templates that need a QR code, so
// no library and no network call is needed. qrMatrix(text) returns an array of
// rows of 0/1, or null when the text is longer than 78 bytes. Verified in
// qr.test.ts by structure; decode-verified against OpenCV during development.

export const QR_JS = String.raw`function qrMatrix(text) {
  var b = unescape(encodeURIComponent(String(text))), n = b.length, i, j;
  var caps = [0, 17, 32, 53, 78], v = 1;
  while (v <= 4 && n > caps[v]) v++;
  if (v > 4) return null;
  var dataCw = [0, 19, 34, 55, 80][v], ecCw = [0, 7, 10, 15, 20][v], size = 17 + 4 * v;
  var bits = [];
  function put(val, len) { for (var k = len - 1; k >= 0; k--) bits.push((val >> k) & 1); }
  put(4, 4); put(n, 8);
  for (i = 0; i < n; i++) put(b.charCodeAt(i), 8);
  put(0, Math.min(4, dataCw * 8 - bits.length));
  while (bits.length % 8) bits.push(0);
  var cw = [];
  for (i = 0; i < bits.length; i += 8) { var x = 0; for (j = 0; j < 8; j++) x = x * 2 + bits[i + j]; cw.push(x); }
  for (var pad = 0; cw.length < dataCw; pad++) cw.push(pad % 2 ? 17 : 236);
  function mul(a, c) { var z = 0; for (var k = 7; k >= 0; k--) { z = (z << 1) ^ ((z >> 7) * 285); z ^= ((c >> k) & 1) * a; } return z & 255; }
  var coef = []; for (i = 0; i < ecCw; i++) coef.push(0); coef[ecCw - 1] = 1;
  var root = 1;
  for (i = 0; i < ecCw; i++) {
    for (j = 0; j < ecCw; j++) { coef[j] = mul(coef[j], root); if (j + 1 < ecCw) coef[j] ^= coef[j + 1]; }
    root = mul(root, 2);
  }
  var rem = []; for (i = 0; i < ecCw; i++) rem.push(0);
  cw.forEach(function (d) { var f = d ^ rem.shift(); rem.push(0); for (var k = 0; k < ecCw; k++) rem[k] ^= mul(coef[k], f); });
  var all = cw.concat(rem);
  var m = [], fn = [];
  for (i = 0; i < size; i++) { m.push(new Array(size).fill(0)); fn.push(new Array(size).fill(false)); }
  function set(xx, yy, dark) { if (xx >= 0 && yy >= 0 && xx < size && yy < size) { m[yy][xx] = dark ? 1 : 0; fn[yy][xx] = true; } }
  for (i = 0; i < size; i++) { set(6, i, i % 2 === 0); set(i, 6, i % 2 === 0); }
  [[3, 3], [size - 4, 3], [3, size - 4]].forEach(function (c) {
    for (var dy = -4; dy <= 4; dy++) for (var dx = -4; dx <= 4; dx++) { var d = Math.max(Math.abs(dx), Math.abs(dy)); set(c[0] + dx, c[1] + dy, d !== 2 && d !== 4); }
  });
  if (v >= 2) for (var ay = -2; ay <= 2; ay++) for (var ax = -2; ax <= 2; ax++) set(size - 7 + ax, size - 7 + ay, Math.max(Math.abs(ax), Math.abs(ay)) !== 1);
  function fmt(bitsv) {
    function bt(k) { return (bitsv >> k) & 1; }
    var k;
    for (k = 0; k <= 5; k++) set(8, k, bt(k));
    set(8, 7, bt(6)); set(8, 8, bt(7)); set(7, 8, bt(8));
    for (k = 9; k < 15; k++) set(14 - k, 8, bt(k));
    for (k = 0; k < 8; k++) set(size - 1 - k, 8, bt(k));
    for (k = 8; k < 15; k++) set(8, size - 15 + k, bt(k));
    set(8, size - 8, 1);
  }
  fmt(0);
  var idx = 0;
  for (var right = size - 1; right >= 1; right -= 2) {
    if (right === 6) right = 5;
    for (var vert = 0; vert < size; vert++) for (j = 0; j < 2; j++) {
      var xx = right - j, up = ((right + 1) & 2) === 0, yy = up ? size - 1 - vert : vert;
      if (!fn[yy][xx] && idx < all.length * 8) { m[yy][xx] = (all[idx >> 3] >> (7 - (idx & 7))) & 1; idx++; }
    }
  }
  for (var y2 = 0; y2 < size; y2++) for (var x2 = 0; x2 < size; x2++) if (!fn[y2][x2] && (x2 + y2) % 2 === 0) m[y2][x2] ^= 1;
  var data = 8, r = data;
  for (i = 0; i < 10; i++) r = (r << 1) ^ ((r >> 9) * 0x537);
  fmt(((data << 10) | r) ^ 0x5412);
  return m;
}
function qrSvg(text, px) {
  var m = qrMatrix(text); if (!m) return "";
  var s = m.length, q = 4, d = "";
  for (var y = 0; y < s; y++) for (var x = 0; x < s; x++) if (m[y][x]) d += "M" + (x + q) + " " + (y + q) + "h1v1h-1z";
  var t = s + q * 2;
  return '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ' + t + ' ' + t + '" width="' + (px || 200) + '" height="' + (px || 200) + '" shape-rendering="crispEdges"><rect width="' + t + '" height="' + t + '" fill="#fff"/><path d="' + d + '" fill="#000"/></svg>';
}`;
