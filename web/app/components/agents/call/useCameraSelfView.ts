// Attaches/detaches the local camera track's self-view onto the <video>
// element. Runs on cameraOn changes rather than inline in startCall because
// the <video> element only exists once React has rendered the cameraOn
// branch. With edge sight on, the self-view shows the LOCAL
// (never-published) stream and doubles as edgeSightTick's frame source.

import { useEffect, type RefObject } from "react";
import { Track, type Room } from "livekit-client";

export function useCameraSelfView(
  cameraOn: boolean,
  roomRef: RefObject<Room | null>,
  localVideoRef: RefObject<HTMLVideoElement | null>,
  edgeStreamRef: RefObject<MediaStream | null>
) {
  useEffect(() => {
    if (!cameraOn) return undefined;
    const el = localVideoRef.current;
    if (edgeStreamRef.current) {
      if (el) {
        el.srcObject = edgeStreamRef.current;
        el.play?.().catch(() => {});
      }
      return () => {
        if (el) el.srcObject = null;
      };
    }
    const track = roomRef.current?.localParticipant?.getTrackPublication(Track.Source.Camera)?.videoTrack;
    if (el && track) track.attach(el);
    return () => {
      if (el && track) track.detach(el);
    };
  }, [cameraOn]);
}
