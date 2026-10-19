#!/usr/bin/env bash
# Computes a real X-Razorpay-Signature for every fixture in this directory
# against a fixture-only throwaway webhook secret, and writes it back into
# each fixture's _meta.headers.X-Razorpay-Signature field - so the contract
# test can call the REAL RazorpayGatewayClient#verifyWebhookSignature (which
# calls com.razorpay.Utils.verifyWebhookSignature(rawBody, signatureHeader,
# webhookSecret) - plain HMAC-SHA256 hex digest of the raw JSON body against
# the secret, confirmed from RazorpayGatewayClient.java's own
# verifyWebhookSignature method, read directly), not just skip straight to
# schema validation and never actually exercise signature verification.
#
# "Raw body" here means the exact bytes of this fixture's "body" object,
# re-serialized with NO whitespace changes from however the test harness
# reads it - run this from the same language/library the JUnit test uses to
# read+re-serialize the fixture, or the signature won't match at test time
# even though it's "correct" by this script's own math. This script uses
# python3's json.dumps with separators=(",", ":") as ONE reference
# implementation; if the JUnit fixture loader uses Jackson with different
# serialization (key order, spacing), regenerate signatures using that same
# serialization instead, or - simpler and less fragile - compute the
# signature in the test itself against whatever bytes it just read off disk
# rather than trusting a pre-computed value from this script at all. This
# script exists mainly so a human can eyeball a real signature and verify
# the mechanism, not as the one true source of truth the test must match
# byte-for-byte.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FIXTURE_SECRET="${RAZORPAY_FIXTURE_WEBHOOK_SECRET:-fixture-only-throwaway-secret-do-not-use-in-any-real-environment}"

for f in "${SCRIPT_DIR}"/[0-9]*.json; do
  python3 - "$f" "$FIXTURE_SECRET" << 'PYEOF'
import hashlib
import hmac
import json
import sys

path, secret = sys.argv[1], sys.argv[2]
with open(path) as fh:
    doc = json.load(fh)

raw_body = json.dumps(doc["body"], separators=(",", ":")).encode("utf-8")
signature = hmac.new(secret.encode("utf-8"), raw_body, hashlib.sha256).hexdigest()
doc["_meta"]["headers"]["X-Razorpay-Signature"] = signature

with open(path, "w") as fh:
    json.dump(doc, fh, indent=2)
    fh.write("\n")

print(f"{path}: signature set ({signature[:12]}...)")
PYEOF
done
