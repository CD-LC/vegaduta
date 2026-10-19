"""
Tests for app/render_ass.py (caption geometry and the ASS document) and the
caption-related parts of render_ffmpeg's filter graph.
"""

from app import render_ass, render_ffmpeg


class TestCaptionStyling:
    def test_style_scales_with_the_frame_rather_than_being_fixed(self):
        # This endpoint renders whatever aspect the caller asks for, so a
        # hardcoded point size is legible in one and unreadable in another.
        vertical = render_ass.caption_style(1080, 1920)
        landscape = render_ass.caption_style(1920, 1080)
        assert vertical["font_size"] == 54
        assert vertical["margin_v"] == 115
        # Half the height -> half the type and half the margin.
        assert landscape["font_size"] == 30
        assert landscape["margin_v"] == 65

    def test_style_has_a_floor_for_a_tiny_frame(self):
        # A 128px-tall render would otherwise ask for 4px type.
        style = render_ass.caption_style(128, 128)
        assert style["font_size"] == 20
        assert style["margin_v"] == 24

    def test_ass_declares_the_output_resolution_explicitly(self):
        # THE regression test for the bug two real renders found. Without
        # PlayResX/PlayResY matching the frame, libass scales every size by
        # height/288 - 6.67x on a 1920-tall frame, which rendered 54px type at
        # ~360px and overflowed the picture.
        ass = render_ass.build_ass("", 1080, 1920)
        assert "PlayResX: 1080" in ass
        assert "PlayResY: 1920" in ass

    def test_ass_style_carries_the_pixel_values(self):
        # Fields are looked up THROUGH the document's own Format line rather
        # than by hardcoded index. Writing the indices by hand got them wrong
        # by one on the first attempt, and an ASS style line has 23 fields -
        # exactly the kind of thing that should be read, not counted.
        ass = render_ass.build_ass("", 1080, 1920)
        lines = ass.splitlines()
        fmt = next(line for line in lines if line.startswith("Format: Name"))
        keys = [k.strip() for k in fmt[len("Format:") :].split(",")]
        style_line = next(line for line in lines if line.startswith("Style: Default"))
        values = [v.strip() for v in style_line[len("Style:") :].split(",")]
        assert len(keys) == len(values), f"{len(keys)} format keys vs {len(values)} values"
        style = dict(zip(keys, values, strict=True))

        assert style["Fontsize"] == "54"
        assert style["Fontname"] == "DejaVu Sans"
        # BorderStyle 3 = opaque box, the only option that stays readable over
        # arbitrary generated B-roll of any colour.
        assert style["BorderStyle"] == "3"
        # Alignment 2 = bottom centre.
        assert style["Alignment"] == "2"
        assert style["MarginV"] == "115"
        # Fully opaque white text over a ~44%-black box.
        assert style["PrimaryColour"] == "&H00FFFFFF"

    def test_dialogue_lines_are_forced_onto_the_declared_style(self):
        # libass silently falls back when a Dialogue references a style the
        # header does not define, which is a caption that renders at some
        # other size for no visible reason.
        ass = render_ass.build_ass(
            "Dialogue: 0,0:00:01.00,0:00:03.00,NotDefault,,0,0,0,,Hello", 1080, 1920
        )
        dialogue = next(line for line in ass.splitlines() if line.startswith("Dialogue:"))
        assert ",Default,," in dialogue, dialogue
        assert "Hello" in dialogue

    def test_dialogue_text_containing_commas_survives(self):
        # The text field is the 10th and may contain commas; splitting without
        # a maxsplit would truncate the caption at its first comma.
        ass = render_ass.build_ass(
            "Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,One, two, three", 1080, 1920
        )
        assert "One, two, three" in ass

    def test_non_dialogue_lines_from_the_converter_are_dropped(self):
        # The converter's own [Script Info] is discarded, not edited - which
        # is the point: its PlayResY of 288 is the value that caused the bug,
        # so nothing from its header may survive into the final document.
        converted = "\n".join(
            [
                "[Script Info]",
                "PlayResY: 288",
                "Comment: 0,0:00:00.00,0:00:01.00,Default,,0,0,0,,x",
            ]
        )
        ass = render_ass.build_ass(converted, 1080, 1920)
        assert "PlayResY: 288" not in ass
        assert "PlayResY: 1920" in ass
        assert "Comment:" not in ass

    def test_empty_input_still_produces_a_valid_document(self):
        ass = render_ass.build_ass("", 1080, 1920)
        assert ass.startswith("[Script Info]")
        assert "[V4+ Styles]" in ass
        assert "[Events]" in ass

    def test_pad_keeps_aspect_instead_of_stretching(self):
        # force_original_aspect_ratio=decrease + pad is what letterboxes a
        # 16:9 clip into a 9:16 frame. Without it a landscape B-roll clip is
        # stretched vertically, which is immediately visible and ugly.
        graph = render_ffmpeg.build_filter(1, 1080, 1920, 30, None)
        assert "force_original_aspect_ratio=decrease" in graph
        assert "pad=1080:1920:(ow-iw)/2:(oh-ih)/2" in graph

    def test_filter_path_escaping(self):
        # The subtitles filter's filename crosses two levels of ffmpeg
        # parsing, so ':' and friends must be escaped or the filter graph
        # fails to parse at all.
        escaped = render_ffmpeg._escape_filter_path("/tmp/a:b[c]/captions.srt")
        assert "\\:" in escaped
        assert "\\[" in escaped
