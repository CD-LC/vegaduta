"""Room-name allowlists must be full matches.

``re.match(r"^...$", s)`` also accepts ``s`` plus ONE trailing newline (``$``
matches before a final "\\n"), so a room name such as ``voice-<uuid>-abcd1234\\n``
used to parse into a RoomTarget whose agent id then travels, unchecked, to
core. Both patterns are now applied with ``fullmatch``.
"""

import pytest

from app import main

AGENT = "0f8fad5b-d9cb-469f-a165-70867728950e"
SHARE = "AbCdEf0123456789xyz_-"


@pytest.mark.parametrize("mode", ["voice", "sight"])
def test_authenticated_room_name_parses(mode):
    target = main._parse_room_name(f"{mode}-{AGENT}-abcd1234")
    assert target is not None
    assert (target.mode, target.agent_id, target.share_id) == (mode, AGENT, None)


@pytest.mark.parametrize("mode", ["voice", "sight"])
def test_share_room_name_parses(mode):
    target = main._parse_room_name(f"share-{mode}-{SHARE}-abcd1234")
    assert target is not None
    assert (target.mode, target.agent_id, target.share_id) == (mode, None, SHARE)


@pytest.mark.parametrize(
    "room_name",
    [
        f"voice-{AGENT}-abcd1234\n",
        f"voice-{AGENT}-abcd1234\r\n",
        f"voice-{AGENT}-abcd1234\n\n",
        f"voice-{AGENT}\n-abcd1234",
        f"share-voice-{SHARE}-abcd1234\n",
        f"share-sight-{SHARE}-abcd1234\r\n",
        f"\nvoice-{AGENT}-abcd1234",
    ],
)
def test_room_name_with_a_newline_is_refused(room_name):
    assert main._parse_room_name(room_name) is None
