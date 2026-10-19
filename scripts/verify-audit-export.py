#!/usr/bin/env python3
"""
verify-audit-export.py - verify a VegaDuta tamper-evident audit-log export OFFLINE.

No platform access, no network, no third-party packages are required: this uses only the
Python standard library (hashlib, struct, json, base64, argparse).

WHAT IT PROVES
--------------
The platform periodically SEALS the audit log. Each seal covers a contiguous range of audit
rows and records:

    range_hash      sequential SHA-256 digest of every row in the range, in order
    prev_seal_hash  seal_hash of the previous seal (all-zero genesis for the first)
    seal_hash       SHA-256 over the tenant scope, sequence number, prev_seal_hash,
                    range_hash and the range metadata
    signature       a compact JWS (EdDSA / Ed25519) over {seal_hash, seq, tenant, ...}

Given an export (GET /api/audit-log/export?from=..&to=..) this script recomputes every one of
those values from the exported rows and checks the chain links and the signatures. A modified
row, a deleted or inserted row, a reordered range, a dropped middle seal, or a forged seal
all make it fail with a non-zero exit code and a reason.

TRUST MODEL (read this - it is what makes the check meaningful)
---------------------------------------------------------------
* Hashes alone prove the export is INTERNALLY consistent. Someone with write access to the
  database AND the export could recompute a whole chain. The SIGNATURE is what stops that,
  but only against a key you obtained independently of the export.
* The export embeds the public key of every seal's signer so it is self-contained, but by
  default this script then verifies the signatures against those embedded keys and prints a
  loud warning that the key was not pinned. To get the real guarantee, pin the platform's
  signing key (the A2A operator key, published at https://<api-host>/.well-known/jwks.json):

      --jwks jwks.json            a JWKS file; the key is selected by each seal's kid
      --public-key <base64url>    the raw 32-byte Ed25519 public key (the JWK "x" value)

  With a pinned key, a seal signed by any other key is a failure.
* The chain cannot prove that NEWER seals were not dropped from the END of the history. Keep
  the head hash this script prints ("head seal ...") and pass it next time with
  --expect-head-hash (or compare it yourself): a shorter or different chain is then caught.
  Likewise --expect-prev-hash pins the seal that precedes the first seal of a window export.
* Ranges whose rows were purged by a retention policy are marked "purged" in the export. For
  those only the seal chain and signature are checked (the rows no longer exist); the report
  says how many seals were checked that way.
* Ed25519 signatures are verified with the `cryptography` package when it is installed (fast
  path), otherwise with a small pure-Python RFC 8032 implementation included below (slower,
  a few milliseconds per seal; verified against the RFC 8032 test vectors by --self-test).

EXIT CODES
----------
    0  verified            1  verification FAILED (tampering or inconsistency)
    2  unusable input / bad arguments

USAGE
-----
    python3 scripts/verify-audit-export.py export.json [--jwks jwks.json | --public-key X]
                                           [--expect-head-hash HEX] [--expect-prev-hash HEX]
    python3 scripts/verify-audit-export.py --self-test

The byte-level encoding below MUST stay identical to
core/audit/src/main/java/com/agenticai/core/audit/AuditSealHasher.java (algorithm "audit-seal-v1").
"""

import argparse
import base64
import hashlib
import json
import struct
import sys

ALGORITHM = "audit-seal-v1"
EXPORT_FORMAT = "vegaduta-audit-seal-export/v1"
GENESIS_HASH = "0" * 64
RANGE_SEED = b"audit-range-v1"
ROW_FIELDS = ("id", "actorId", "actionType", "targetType", "targetId", "outcome", "detail")


# --------------------------------------------------------------------------- canonical hashing


def _field(value):
    """null -> 0x00 ; string -> 0x01 || uint32-BE(len(utf8)) || utf8."""
    if value is None:
        return b"\x00"
    if not isinstance(value, str):
        raise ValueError("row field is not a string or null: %r" % (value,))
    raw = value.encode("utf-8")
    return b"\x01" + struct.pack(">I", len(raw)) + raw


def row_bytes(row):
    """Canonical encoding of one audit row (immutable fields only)."""
    out = b"".join(_field(row.get(name)) for name in ROW_FIELDS)
    return out + struct.pack(">q", int(row["createdAtMicros"]))


def row_digest(row):
    return hashlib.sha256(row_bytes(row)).digest()


def range_hash(rows):
    """Sequential digest: state = H(seed); state = H(state || H(row)) for each row, in order."""
    if not rows:
        raise ValueError("a seal range cannot be empty")
    state = hashlib.sha256(RANGE_SEED).digest()
    for row in rows:
        state = hashlib.sha256(state + row_digest(row)).digest()
    return state.hex()


def seal_hash(seal):
    preimage = "\n".join([
        ALGORITHM,
        seal["tenantScope"],
        str(seal["seq"]),
        seal["prevSealHash"],
        seal["rangeHash"],
        str(seal["rowCount"]),
        seal["firstRowId"],
        str(seal["firstRowAtMicros"]),
        seal["lastRowId"],
        str(seal["lastRowAtMicros"]),
    ])
    return hashlib.sha256(preimage.encode("utf-8")).hexdigest()


# --------------------------------------------------------------------------- Ed25519 (RFC 8032)

_P = 2 ** 255 - 19
_Q = 2 ** 252 + 27742317777372353535851937790883648493
_D = -121665 * pow(121666, _P - 2, _P) % _P
_SQRT_M1 = pow(2, (_P - 1) // 4, _P)


def _recover_x(y, sign):
    if y >= _P:
        return None
    x2 = (y * y - 1) * pow(_D * y * y + 1, _P - 2, _P) % _P
    if x2 == 0:
        return None if sign else 0
    x = pow(x2, (_P + 3) // 8, _P)
    if (x * x - x2) % _P != 0:
        x = x * _SQRT_M1 % _P
    if (x * x - x2) % _P != 0:
        return None
    if (x & 1) != sign:
        x = _P - x
    return x


def _point_add(a, b):
    aa = (a[1] - a[0]) * (b[1] - b[0]) % _P
    bb = (a[1] + a[0]) * (b[1] + b[0]) % _P
    cc = 2 * a[3] * b[3] * _D % _P
    dd = 2 * a[2] * b[2] % _P
    e, f, g, h = bb - aa, dd - cc, dd + cc, bb + aa
    return (e * f % _P, g * h % _P, f * g % _P, e * h % _P)


def _point_mul(s, point):
    result = (0, 1, 1, 0)
    while s > 0:
        if s & 1:
            result = _point_add(result, point)
        point = _point_add(point, point)
        s >>= 1
    return result


def _point_equal(a, b):
    if (a[0] * b[2] - b[0] * a[2]) % _P != 0:
        return False
    if (a[1] * b[2] - b[1] * a[2]) % _P != 0:
        return False
    return True


def _decompress(raw):
    if len(raw) != 32:
        return None
    y = int.from_bytes(raw, "little")
    sign = y >> 255
    y &= (1 << 255) - 1
    x = _recover_x(y, sign)
    if x is None:
        return None
    return (x, y, 1, x * y % _P)


_GY = 4 * pow(5, _P - 2, _P) % _P
_GX = _recover_x(_GY, 0)
_G = (_GX, _GY, 1, _GX * _GY % _P)


def _ed25519_verify_pure(public, message, signature):
    if len(public) != 32 or len(signature) != 64:
        return False
    a = _decompress(public)
    if a is None:
        return False
    r_bytes = signature[:32]
    r = _decompress(r_bytes)
    if r is None:
        return False
    s = int.from_bytes(signature[32:], "little")
    if s >= _Q:
        return False
    h = int.from_bytes(hashlib.sha512(r_bytes + public + message).digest(), "little") % _Q
    return _point_equal(_point_mul(s, _G), _point_add(r, _point_mul(h, a)))


def ed25519_verify(public, message, signature):
    """True iff `signature` is a valid Ed25519 signature of `message` under `public` (32 raw bytes)."""
    try:
        from cryptography.exceptions import InvalidSignature  # type: ignore
        from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey  # type: ignore
    except Exception:  # ImportError, or a broken install: fall back to the stdlib implementation
        return _ed25519_verify_pure(public, message, signature)
    try:
        Ed25519PublicKey.from_public_bytes(public).verify(signature, message)
        return True
    except InvalidSignature:
        return False
    except Exception:
        return False


# --------------------------------------------------------------------------- JWS handling


def _b64url_decode(text):
    if not isinstance(text, str):
        raise ValueError("not a base64url string")
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


def check_jws(seal, public_key_bytes):
    """Return None if the seal's JWS verifies against the key and binds this seal, else a reason."""
    jws = seal.get("signature")
    if not jws:
        return "seal is unsigned"
    parts = jws.split(".")
    if len(parts) != 3:
        return "signature is not a compact JWS"
    try:
        header = json.loads(_b64url_decode(parts[0]))
        payload = json.loads(_b64url_decode(parts[1]))
        signature = _b64url_decode(parts[2])
    except Exception as exc:  # malformed base64 / json
        return "signature is malformed (%s)" % exc
    if header.get("alg") != "EdDSA":
        return "unsupported JWS alg %r (expected EdDSA)" % (header.get("alg"),)
    signing_input = (parts[0] + "." + parts[1]).encode("ascii")
    if not ed25519_verify(public_key_bytes, signing_input, signature):
        return "signature does not verify against the signer key"
    if payload.get("seal_hash") != seal["sealHash"]:
        return "signed seal_hash differs from the seal's seal_hash"
    if payload.get("seq") != seal["seq"] or payload.get("tenant") != seal["tenantScope"]:
        return "signed seq/tenant differ from the seal's"
    if seal.get("keyId") and header.get("kid") not in (None, seal["keyId"]):
        return "JWS kid differs from the seal's key id"
    return None


class KeyResolver:
    """Pinned keys (preferred) or, with a warning, the keys embedded in the export."""

    def __init__(self, pinned_single, pinned_by_kid, embedded):
        self.pinned_single = pinned_single
        self.pinned_by_kid = pinned_by_kid
        self.embedded = embedded
        self.pinned = pinned_single is not None or bool(pinned_by_kid)

    def key_for(self, seal):
        kid = seal.get("keyId")
        if self.pinned_single is not None:
            return self.pinned_single
        if self.pinned_by_kid:
            if kid not in self.pinned_by_kid:
                raise KeyError("signer key id %r is not in the pinned JWKS" % (kid,))
            return self.pinned_by_kid[kid]
        raw = seal.get("publicKey") or self.embedded.get(kid)
        if not raw:
            raise KeyError("no public key available for key id %r" % (kid,))
        return _b64url_decode(raw)


# --------------------------------------------------------------------------- verification


class Failure(Exception):
    def __init__(self, seq, reason):
        super().__init__(reason)
        self.seq = seq
        self.reason = reason


def verify_bundle(bundle, resolver, expect_head=None, expect_prev=None):
    """Raise Failure on the first inconsistency; otherwise return a summary dict."""
    if bundle.get("format") != EXPORT_FORMAT:
        raise ValueError("unrecognised export format %r (expected %s)" % (bundle.get("format"), EXPORT_FORMAT))
    seals = bundle.get("seals")
    rows = bundle.get("rows")
    if not isinstance(seals, list) or not isinstance(rows, list):
        raise ValueError("export has no 'seals' / 'rows' arrays")

    summary = {"seals": 0, "rows": 0, "purged": 0, "key_ids": set(), "head_seq": None, "head_hash": None}
    cursor = 0
    expected_prev = None
    expected_seq = None
    first = True
    for seal in seals:
        seq = seal.get("seq")
        if seal.get("algorithm") != ALGORITHM:
            raise Failure(seq, "unsupported seal algorithm %r" % (seal.get("algorithm"),))
        if first:
            if seq == 1:
                expected_prev = GENESIS_HASH
            elif expect_prev is not None:
                expected_prev = expect_prev
            else:
                expected_prev = seal["prevSealHash"]  # window starts mid-chain; cannot be checked
                print("note: export starts at seal %s; its link to earlier history is NOT verified "
                      "(pass --expect-prev-hash to pin it)" % seq)
            first = False
        elif seq != expected_seq:
            raise Failure(seq, "sequence gap: expected seal %s but found %s (a seal was dropped or reordered)"
                          % (expected_seq, seq))
        if seal["prevSealHash"] != expected_prev:
            raise Failure(seq, "chain link broken: prev_seal_hash does not match the previous seal")
        if seal_hash(seal) != seal["sealHash"]:
            raise Failure(seq, "seal_hash does not match the seal's own fields")
        try:
            key = resolver.key_for(seal)
        except KeyError as exc:
            raise Failure(seq, str(exc))
        problem = check_jws(seal, key)
        if problem:
            raise Failure(seq, problem)
        if seal.get("keyId"):
            summary["key_ids"].add(seal["keyId"])

        if seal.get("purged"):
            summary["purged"] += 1
        else:
            count = seal["rowCount"]
            chunk = rows[cursor:cursor + count]
            if count < 1 or len(chunk) != count:
                raise Failure(seq, "export holds fewer rows than the seal covers "
                                   "(rows were deleted or the export is truncated)")
            if range_hash(chunk) != seal["rangeHash"]:
                raise Failure(seq, "range_hash mismatch: rows in this range were modified, reordered, "
                                   "inserted or removed")
            if chunk[0]["id"] != seal["firstRowId"] or chunk[-1]["id"] != seal["lastRowId"]:
                raise Failure(seq, "first/last row ids do not match the seal")
            if chunk[0]["createdAtMicros"] != seal["firstRowAtMicros"] \
                    or chunk[-1]["createdAtMicros"] != seal["lastRowAtMicros"]:
                raise Failure(seq, "first/last row timestamps do not match the seal")
            cursor += count
            summary["rows"] += count
        expected_prev = seal["sealHash"]
        expected_seq = seq + 1
        summary["seals"] += 1
        summary["head_seq"] = seq
        summary["head_hash"] = seal["sealHash"]

    if cursor != len(rows):
        raise Failure(None, "export carries %d row(s) not covered by any seal" % (len(rows) - cursor))
    if expect_head is not None and summary["head_hash"] != expect_head:
        raise Failure(summary["head_seq"],
                      "head seal hash %s does not match --expect-head-hash (history was truncated or replaced)"
                      % summary["head_hash"])
    return summary


# --------------------------------------------------------------------------- self test / CLI

_RFC8032_VECTORS = [
    ("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a", "",
     "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155"
     "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"),
    ("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c", "72",
     "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da"
     "085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00"),
]


def self_test():
    for pub, msg, sig in _RFC8032_VECTORS:
        pub_b, msg_b, sig_b = bytes.fromhex(pub), bytes.fromhex(msg), bytes.fromhex(sig)
        if not _ed25519_verify_pure(pub_b, msg_b, sig_b):
            print("SELF-TEST FAILED: RFC 8032 vector did not verify")
            return 1
        bad = bytearray(sig_b)
        bad[0] ^= 1
        if _ed25519_verify_pure(pub_b, msg_b, bytes(bad)):
            print("SELF-TEST FAILED: a corrupted signature verified")
            return 1
    print("self-test OK: pure-Python Ed25519 matches the RFC 8032 test vectors")
    return 0


def _load_json(path):
    if path == "-":
        return json.load(sys.stdin)
    with open(path, "r", encoding="utf-8") as handle:
        return json.load(handle)


def main(argv=None):
    parser = argparse.ArgumentParser(description="Verify a VegaDuta audit-log export offline.")
    parser.add_argument("export", nargs="?", help="export JSON file from GET /api/audit-log/export ('-' = stdin)")
    parser.add_argument("--jwks", help="JWKS file holding the platform's pinned Ed25519 public key(s)")
    parser.add_argument("--public-key", help="pinned raw 32-byte Ed25519 public key, base64url (the JWK 'x' value)")
    parser.add_argument("--expect-head-hash", help="seal_hash of the newest seal you recorded last time")
    parser.add_argument("--expect-prev-hash", help="seal_hash of the seal just before the first seal in this export")
    parser.add_argument("--self-test", action="store_true", help="check the built-in Ed25519 against RFC 8032 and exit")
    args = parser.parse_args(argv)

    if args.self_test:
        return self_test()
    if not args.export:
        parser.error("an export file is required (or use --self-test)")

    try:
        bundle = _load_json(args.export)
        pinned_single = _b64url_decode(args.public_key) if args.public_key else None
        pinned_by_kid = {}
        if args.jwks:
            for jwk in _load_json(args.jwks).get("keys", []):
                if jwk.get("kty") == "OKP" and jwk.get("crv") == "Ed25519" and jwk.get("x"):
                    pinned_by_kid[jwk.get("kid")] = _b64url_decode(jwk["x"])
            if not pinned_by_kid:
                raise ValueError("the JWKS holds no Ed25519 OKP key")
        embedded = {k.get("kid"): k.get("publicKey") for k in bundle.get("keys", []) if isinstance(k, dict)}
    except (OSError, ValueError, KeyError, TypeError) as exc:
        print("cannot read input: %s" % exc)
        return 2

    resolver = KeyResolver(pinned_single, pinned_by_kid, embedded)
    if not resolver.pinned:
        print("WARNING: no --jwks/--public-key given. Signatures are checked against the keys EMBEDDED in "
              "the export, which proves internal consistency only. Pin the platform key for a real guarantee.")
    try:
        summary = verify_bundle(bundle, resolver, args.expect_head_hash, args.expect_prev_hash)
    except Failure as failure:
        where = "seal %s: " % failure.seq if failure.seq is not None else ""
        print("FAILED: %s%s" % (where, failure.reason))
        return 1
    except (ValueError, KeyError, TypeError) as exc:
        print("cannot verify, malformed export: %s" % exc)
        return 2

    print("OK: %d seal(s), %d row(s) recomputed, %d purged range(s) checked by chain+signature only"
          % (summary["seals"], summary["rows"], summary["purged"]))
    print("signer key id(s): %s (%s)" % (", ".join(sorted(summary["key_ids"])) or "none",
                                         "pinned" if resolver.pinned else "NOT pinned"))
    if summary["head_hash"]:
        print("head seal %s hash %s  <- record this and pass --expect-head-hash next time"
              % (summary["head_seq"], summary["head_hash"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
