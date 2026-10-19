// generate_music hands the model "/api/musicgen/tracks/<uuid>"; the signed-in
// chat only shows an audio player if MediaCard recognises that path.

import { describe, it, expect } from "vitest";
import { extractMediaRefs } from "./MediaCard";

const ID = "0f8fad5b-d9cb-469f-a165-70867728950e";

describe("MediaCard music refs", () => {
  it("recognises a bare generate_music track URL", () => {
    const refs = extractMediaRefs(`Here is your jingle: /api/musicgen/tracks/${ID}`);
    expect(refs).toHaveLength(1);
    expect(refs[0].kind).toBe("music");
    expect(refs[0].id).toBe(ID);
  });

  it("recognises a markdown-linked track URL", () => {
    const refs = extractMediaRefs(`[Bakery jingle](/api/musicgen/tracks/${ID})`);
    expect(refs.map((r) => r.kind)).toEqual(["music"]);
  });

  it("still tells speech from music", () => {
    const refs = extractMediaRefs(`/api/elevenlabs/audio/${ID} and /api/musicgen/tracks/${ID}`);
    expect(refs.map((r) => r.kind)).toEqual(["elevenlabs-audio", "music"]);
  });
});
