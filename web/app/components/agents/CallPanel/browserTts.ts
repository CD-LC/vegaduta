/**
 * Web Speech API fallback for when ElevenLabs audio isn't available.
 *
 * Returns true only when an utterance was actually handed to the browser, so a
 * caller that tracks a "speaking" UI state knows whether to expect the
 * lifecycle callbacks below (a browser with no speechSynthesis, or a throw,
 * means nothing will ever fire and the caller must clear its own state).
 *
 * `onEnd`/`onError` are optional and additive - the three CallPanel call sites
 * that only ever wanted fire-and-forget audio keep working unchanged. Note
 * that `speechSynthesis.cancel()` (used both here and by callers that stop
 * playback) fires the CANCELLED utterance's own end/error handler, so any
 * caller using these callbacks to drive state must guard against a stale
 * callback landing after it has already moved on - see AgentPlayground's
 * speakTokenRef.
 */
export function playBrowserTTS(
  text: string,
  options?: { onEnd?: () => void; onError?: () => void }
): boolean {
  // Use Web Speech API as fallback when ElevenLabs audio isn't available
  if (typeof window !== "undefined" && window.speechSynthesis) {
    try {
      const utterance = new SpeechSynthesisUtterance(text);
      utterance.rate = 1.0;
      utterance.pitch = 1.0;
      utterance.volume = 1.0;
      if (options?.onEnd) utterance.onend = () => options.onEnd?.();
      if (options?.onError) utterance.onerror = () => options.onError?.();
      window.speechSynthesis.cancel();  // Clear any pending utterances
      window.speechSynthesis.speak(utterance);
      console.log(`[Voice] Speaking reply via browser TTS`);
      return true;
    } catch (err) {
      console.warn(`[Voice] Browser TTS failed:`, err);
      // Silently fail - the reply text is still visible
      return false;
    }
  } else {
    console.debug(`[Voice] Web Speech API not available in this browser`);
    return false;
  }
}
