"""Schema checks for contracts/inbound: every fixture validates against the schema for its message type,
the deliberately malformed one fails, and no schema is left without a fixture.

What this does NOT check: that the handlers (core/omnichannel's VoiceRelayWebSocketHandler, core/billing's
RazorpayGatewayClient) parse these payloads the way the schemas say. The schemas were read from that code;
nothing replays the fixtures through it yet (contracts/README.md, "What is verified").

Run: python -m unittest discover -s contracts/consumers/tools -p 'test_*.py'   (standard library only)
"""

from __future__ import annotations

import json
import unittest
from pathlib import Path

import consumer_contract as cc

INBOUND = cc.REPO_ROOT / "contracts" / "inbound"
RAZORPAY = INBOUND / "razorpay"
TWILIO = INBOUND / "twilio-conversation-relay"


def _load(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def _check(schema_path: Path, value) -> list[str]:
    return cc.validate(_load(schema_path), value, base_dir=schema_path.parent)


class RazorpayFixtures(unittest.TestCase):
    def test_every_event_fixture_validates_against_its_schema(self):
        seen = set()
        for fixture in sorted(RAZORPAY.glob("fixtures/[01][0-9]-subscription.*.json")):
            body = _load(fixture)["body"]
            schema = RAZORPAY / "schemas" / f"{body['event']}.schema.json"
            self.assertTrue(schema.exists(), f"{fixture.name}: no schema for {body['event']}")
            self.assertEqual(_check(schema, body), [], fixture.name)
            seen.add(schema.name)
        self.assertEqual(len(seen), 10, "one fixture per subscription.* event the parser maps")

    def test_every_schema_has_a_fixture(self):
        events = {_load(f)["body"]["event"] for f in RAZORPAY.glob("fixtures/*.json")}
        for schema in RAZORPAY.glob("schemas/subscription.*.schema.json"):
            event = schema.name.removesuffix(".schema.json")
            self.assertIn(event, events, f"{schema.name} has no fixture")

    def test_unknown_event_fixture_fits_the_envelope_but_not_a_subscription_schema(self):
        body = _load(RAZORPAY / "fixtures" / "11-payment.failed-unknown-event.json")["body"]
        self.assertEqual(_check(RAZORPAY / "schemas" / "_envelope.schema.json", body), [])
        self.assertNotEqual(_check(RAZORPAY / "schemas" / "subscription.activated.schema.json", body), [])

    def test_the_malformed_fixture_fails(self):
        body = _load(RAZORPAY / "fixtures" / "99-malformed-missing-subscription-id.json")["body"]
        errors = _check(RAZORPAY / "schemas" / "subscription.activated.schema.json", body)
        self.assertTrue(any("missing required id" in e for e in errors), errors)

    def test_a_perturbed_fixture_is_caught(self):
        """The validator must really check: break a valid fixture and it has to fail."""
        body = _load(RAZORPAY / "fixtures" / "02-subscription.activated.json")["body"]
        schema = RAZORPAY / "schemas" / "subscription.activated.schema.json"
        wrong_event = {**body, "event": "subscription.charged"}
        self.assertNotEqual(_check(schema, wrong_event), [])
        bad_id = json.loads(json.dumps(body))
        bad_id["payload"]["subscription"]["entity"]["id"] = "plan_not_a_subscription"
        self.assertNotEqual(_check(schema, bad_id), [])


class TwilioFixtures(unittest.TestCase):
    TYPES = {"setup", "prompt", "interrupt", "dtmf", "error"}

    def test_every_handled_message_fixture_validates_against_its_schema(self):
        seen = set()
        for fixture in sorted(TWILIO.glob("fixtures/0*.json")):
            message = _load(fixture)
            if message["type"] not in self.TYPES:
                continue  # 07-unknown-type: the handler ignores it, there is no schema to hold it to
            self.assertEqual(_check(TWILIO / "schemas" / f"{message['type']}.schema.json", message), [], fixture.name)
            seen.add(message["type"])
        self.assertEqual(seen, self.TYPES)

    def test_the_unknown_type_fixture_matches_no_schema(self):
        message = _load(TWILIO / "fixtures" / "07-unknown-type.json")
        self.assertNotIn(message["type"], self.TYPES)

    def test_a_perturbed_message_is_caught(self):
        self.assertNotEqual(_check(TWILIO / "schemas" / "dtmf.schema.json", {"type": "dtmf"}), [])
        self.assertNotEqual(_check(TWILIO / "schemas" / "dtmf.schema.json", {"type": "prompt", "digit": "1"}), [])


class OutboundSchemasStillValidate(unittest.TestCase):
    def test_validate_keeps_rejecting_what_it_rejected(self):
        schema = {"type": "object", "required": ["a"], "properties": {"a": {"type": "string"}}, "additionalProperties": False}
        self.assertEqual(cc.validate(schema, {"a": "x"}), [])
        self.assertEqual(len(cc.validate(schema, {"a": 1, "b": 2})), 2)

    def test_an_unresolvable_ref_is_an_error_not_a_pass(self):
        self.assertEqual(len(cc.validate({"$ref": "./x.json"}, {})), 1)


if __name__ == "__main__":
    unittest.main()
