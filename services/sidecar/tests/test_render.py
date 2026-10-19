"""
Tests for app/render.py (Video Pipeline Stage 3): the job registry, the
render orchestration and the ffmpeg argv it produces. Input fetching is in
test_render_fetch.py, the completion callback in test_render_callback.py and
the caption document in test_render_captions.py.

No ffmpeg, no network, no real render. What is tested here is everything
that decides WHETHER a render runs and against WHAT - the guards, the
argument construction, and the error messages - because those are the parts
that must be provably right without a two-minute encode in CI. The one
thing deliberately not covered is that ffmpeg produces a correct mp4; that
is what the build plan's manual check ("watch the whole 90 seconds") is
for, and no unit test substitutes for it.
"""

import os

import pytest

from app import render, render_ffmpeg
from app.render_errors import RenderError


@pytest.fixture(autouse=True)
def workspace(tmp_path, monkeypatch):
    monkeypatch.setattr(render, "WORKSPACE", str(tmp_path))
    return tmp_path


class TestFilterGraph:
    def test_one_chain_per_clip_plus_concat(self):
        graph = render_ffmpeg.build_filter(3, 1080, 1920, 30, None)
        assert graph.count("scale=1080:1920") == 3
        assert "[v0][v1][v2]concat=n=3:v=1:a=0[vcat]" in graph
        assert graph.endswith("[vcat]null[vout]")

    def test_captions_use_the_ass_filter_not_the_subtitles_filter(self):
        # `subtitles=` applies style overrides against libass's own default
        # script resolution; `ass=` renders the document as written. Two real
        # renders were needed to establish that the difference matters - see
        # build_ass's docstring.
        graph = render_ffmpeg.build_filter(1, 1080, 1920, 30, "/app/workspace/abc/captions.ass")
        assert "ass=filename='/app/workspace/abc/captions.ass'" in graph
        assert "subtitles=" not in graph
        assert "[vout]" in graph


class TestRenderPreflight:
    def _request(self, **overrides):
        request = {
            "audio": {"content_base64": "aGVsbG8="},
            "edit_list": [
                {"beat_id": "b1", "duration_sec": 2.0, "clip": {"content_base64": "aGVsbG8="}}
            ],
            "width": 1080,
            "height": 1920,
            "fps": 30,
        }
        request.update(overrides)
        return request

    def test_empty_edit_list_is_refused(self, workspace, monkeypatch):
        monkeypatch.setattr(render_ffmpeg, "probe_duration", lambda path: 2.0)
        job = render.RenderJob(job_id="t1")
        with pytest.raises(RenderError, match="edit_list is empty"):
            render._render(job, self._request(edit_list=[]), str(workspace))

    def test_a_beat_with_no_clip_names_the_beat(self, workspace, monkeypatch):
        # This is the build plan's DoD item: "a failed render (missing clip)
        # returns a useful error naming the missing beat, not a raw 500".
        monkeypatch.setattr(render_ffmpeg, "probe_duration", lambda path: 2.0)
        job = render.RenderJob(job_id="t2")
        request = self._request(edit_list=[{"beat_id": "hook-03", "duration_sec": 2.0, "clip": {}}])
        with pytest.raises(RenderError, match="hook-03"):
            render._render(job, request, str(workspace))

    def test_a_beat_whose_clip_file_is_missing_names_the_beat(self, workspace, monkeypatch):
        monkeypatch.setattr(render_ffmpeg, "probe_duration", lambda path: 2.0)
        job = render.RenderJob(job_id="t3")
        request = self._request(
            edit_list=[{"beat_id": "beat-07", "duration_sec": 2.0, "clip": {"path": "gone.mp4"}}]
        )
        with pytest.raises(RenderError, match="beat-07"):
            render._render(job, request, str(workspace))

    def test_a_zero_duration_beat_is_refused_with_the_reason(self, workspace, monkeypatch):
        # duration_sec must be the MEASURED audio duration from /tts. A zero
        # here means the caller passed episode.json's estimate, or nothing.
        monkeypatch.setattr(render_ffmpeg, "probe_duration", lambda path: 2.0)
        job = render.RenderJob(job_id="t4")
        request = self._request(
            edit_list=[{"beat_id": "b9", "duration_sec": 0, "clip": {"content_base64": "aGVsbG8="}}]
        )
        with pytest.raises(RenderError, match="MEASURED audio duration"):
            render._render(job, request, str(workspace))


class TestFfmpegInputRestrictions:
    """SEC-031: no ffmpeg/ffprobe input may pull in a network protocol or a
    playlist-style demuxer (hls/concat/image2) - checked on the real argv."""

    def _render_argvs(self, workspace, monkeypatch, **request_overrides):
        argvs = []

        def fake_run(argv, *, what, timeout):
            argvs.append(list(argv))
            return ""

        def fake_run_writing_ass(argv, *, what, timeout):
            fake_run(argv, what=what, timeout=timeout)
            if what == "ffmpeg srt->ass":
                with open(argv[-1], "w", encoding="utf-8") as handle:
                    handle.write("[Script Info]\n")
            if what == "ffmpeg render":
                with open(argv[-1], "wb") as handle:
                    handle.write(b"mp4")
            return ""

        monkeypatch.setattr(render_ffmpeg, "run", fake_run_writing_ass)
        monkeypatch.setattr(render_ffmpeg, "probe_duration", lambda path: 2.0)
        request = TestRenderPreflight._request(self, **request_overrides)
        render._render(render.RenderJob(job_id="sec031"), request, str(workspace))
        return argvs

    @staticmethod
    def _inputs(argv):
        """(protocol_whitelist, format_whitelist) in force for every -i. The
        options must sit immediately before THIS -i, so they bind to it."""
        found = []
        for index, token in enumerate(argv):
            if token == "-i":
                assert argv[index - 4] == "-protocol_whitelist", argv
                assert argv[index - 2] == "-format_whitelist", argv
                found.append((argv[index - 3], argv[index - 1]))
        return found

    def test_every_render_input_is_file_only_with_a_demuxer_allow_list(
        self, workspace, monkeypatch
    ):
        argvs = self._render_argvs(
            workspace,
            monkeypatch,
            srt="1\n00:00:00,000 --> 00:00:01,000\nhi\n",
            edit_list=[
                {"beat_id": "b1", "duration_sec": 1.0, "clip": {"content_base64": "aGVsbG8="}},
                {"beat_id": "b2", "duration_sec": 1.0, "clip": {"content_base64": "aGVsbG8="}},
            ],
        )
        inputs = [pair for argv in argvs for pair in self._inputs(argv)]
        # srt->ass, two clips + narration, thumbnail = 5 inputs
        assert len(inputs) == 5
        for proto, formats in inputs:
            assert proto == "file"
            allowed = set(formats.split(","))
            assert not allowed & {"hls", "concat", "image2", "ffconcat", "tee", "data"}

    def test_clips_are_limited_to_video_container_demuxers(self, workspace, monkeypatch):
        argvs = self._render_argvs(workspace, monkeypatch)
        encode = next(a for a in argvs if "-filter_complex" in a)
        pairs = self._inputs(encode)
        assert pairs[0] == ("file", "mov,matroska,avi")
        assert pairs[-1][1] == render_ffmpeg.AUDIO_DEMUXERS

    def test_ffprobe_is_restricted_too(self, monkeypatch):
        seen = []

        def fake_run(argv, *, what, timeout):
            seen.append(argv)
            return "1.5\n"

        monkeypatch.setattr(render_ffmpeg, "run", fake_run)
        assert render_ffmpeg.probe_duration("/w/narration.wav") == 1.5
        argv = seen[0]
        assert argv[argv.index("-protocol_whitelist") + 1] == "file"
        assert "hls" not in argv[argv.index("-format_whitelist") + 1].split(",")
        assert argv.index("-format_whitelist") < argv.index("/w/narration.wav")


class TestJobRegistry:
    def test_unknown_job_is_none(self):
        assert render.get_job("does-not-exist") is None

    def test_artifact_of_an_unknown_job_is_a_named_error(self):
        with pytest.raises(RenderError, match="no such render job"):
            render.artifact_path("nope", "video")

    def test_artifact_of_an_unfinished_job_says_the_status(self):
        job = render.RenderJob(job_id="running-1", status="running")
        render._register(job)
        with pytest.raises(RenderError, match="is running, not completed"):
            render.artifact_path("running-1", "video")

    def test_unknown_artifact_kind_is_refused(self, workspace):
        job = render.RenderJob(
            job_id="done-1",
            status="completed",
            result={"video_path": "done-1/episode.mp4", "thumbnail_path": "done-1/t.jpg"},
        )
        render._register(job)
        with pytest.raises(RenderError, match="unknown artifact kind"):
            render.artifact_path("done-1", "audio")

    def test_a_completed_artifact_resolves_once_on_disk(self, workspace):
        os.makedirs(workspace / "done-2", exist_ok=True)
        (workspace / "done-2" / "episode.mp4").write_bytes(b"mp4")
        job = render.RenderJob(
            job_id="done-2",
            status="completed",
            result={"video_path": "done-2/episode.mp4", "thumbnail_path": "done-2/t.jpg"},
        )
        render._register(job)
        assert render.artifact_path("done-2", "video").endswith("episode.mp4")

    def test_a_missing_file_for_a_completed_job_is_reported_honestly(self, workspace):
        # Finished jobs are evicted past RENDER_RETAIN_JOBS and their
        # directories deleted, so "completed" does not imply "still on disk".
        job = render.RenderJob(
            job_id="done-3",
            status="completed",
            result={"video_path": "done-3/episode.mp4", "thumbnail_path": "done-3/t.jpg"},
        )
        render._register(job)
        with pytest.raises(RenderError, match="no longer on disk"):
            render.artifact_path("done-3", "video")


class TestRunErrorReporting:
    def test_a_missing_ffmpeg_binary_says_where_it_comes_from(self, monkeypatch):
        monkeypatch.setattr(render_ffmpeg, "FFMPEG", "definitely-not-a-real-binary-xyz")
        with pytest.raises(RenderError, match="apt-get layer"):
            render_ffmpeg.run(
                ["definitely-not-a-real-binary-xyz", "-version"],
                what="probe",
                timeout=10,
            )
