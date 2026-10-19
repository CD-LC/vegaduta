"use client";

import { useRef, useState } from "react";
import type { useTranslations } from "../../../lib/i18n";

type Translate = ReturnType<typeof useTranslations>;

export interface MicTestResult {
  success: boolean;
  audioSize: number;
  maxLevel: number;
  mimeType: string;
  message: string;
  solution?: string;
  error?: string;
}

// Microphone diagnostics: state + the 3-second capture test (extracted from CallPanel).
export function useMicTest(t: Translate) {
  const [testingMicrophone, setTestingMicrophone] = useState(false);
  const [micTestResult, setMicTestResult] = useState<MicTestResult | null>(null);
  const [audioLevel, setAudioLevel] = useState(0);
  const [showMicTest, setShowMicTest] = useState(false);
  const audioContextRef = useRef<AudioContext | null>(null);
  const analyserRef = useRef<AnalyserNode | null>(null);

  /**
   * Test microphone: Request access, capture audio for 3 seconds,
   * show real-time audio level, and report if audio was captured.
   * Useful for diagnosing "audio blob empty" errors.
   */
  async function testMicrophone() {
    if (testingMicrophone) return;
    setTestingMicrophone(true);
    setMicTestResult(null);
    setAudioLevel(0);

    const testDurationMs = 3000;
    let testStream: MediaStream | null = null;
    let testRecorder: MediaRecorder | null = null;
    let testChunks: Blob[] = [];
    let maxLevel = 0;

    try {
      console.log(`[Voice Test] Starting 3-second microphone test...`);

      // Request microphone with same settings as push-to-talk
      testStream = await navigator.mediaDevices.getUserMedia({
        audio: {
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true,
          sampleRate: 16000,
        },
      });
      console.log(`[Voice Test] Microphone access granted, stream active: ${testStream.active}`);

      // Create audio context for level monitoring
      const audioContext = new (window.AudioContext || (window as any).webkitAudioContext)();
      audioContextRef.current = audioContext;

      const analyser = audioContext.createAnalyser();
      analyser.fftSize = 2048;
      const microphone = audioContext.createMediaStreamSource(testStream);
      microphone.connect(analyser);
      analyserRef.current = analyser;

      // Start recording
      const mimeType = MediaRecorder.isTypeSupported("audio/wav")
        ? "audio/wav"
        : MediaRecorder.isTypeSupported("audio/webm")
        ? "audio/webm"
        : "";

      testRecorder = new MediaRecorder(testStream, mimeType ? { mimeType } : {});
      testChunks = [];

      testRecorder.ondataavailable = (e) => {
        if (e.data && e.data.size > 0) {
          testChunks.push(e.data);
          console.log(`[Voice Test] Chunk received: ${e.data.size} bytes`);
        }
      };

      testRecorder.onerror = (e) => {
        console.error(`[Voice Test] Recording error:`, e.error);
      };

      testRecorder.start();

      // Monitor audio level every 50ms
      const levelCheckInterval = setInterval(() => {
        const dataArray = new Uint8Array(analyser.frequencyBinCount);
        analyser.getByteFrequencyData(dataArray);

        // Calculate RMS (root mean square) of audio frequency data
        let sum = 0;
        for (let i = 0; i < dataArray.length; i++) {
          sum += dataArray[i] * dataArray[i];
        }
        const rms = Math.sqrt(sum / dataArray.length);
        const level = Math.min(100, (rms / 128) * 100); // Normalize to 0-100

        if (level > maxLevel) maxLevel = level;
        setAudioLevel(Math.round(level));

        console.log(`[Voice Test] Audio level: ${Math.round(level)}%`);
      }, 50);

      // Wait for test duration
      await new Promise((resolve) => setTimeout(resolve, testDurationMs));

      clearInterval(levelCheckInterval);
      testRecorder.stop();

      // Give recorder a moment to finish
      await new Promise((resolve) => {
        testRecorder!.onstop = () => resolve(null);
      });

      // Calculate total audio captured
      const totalBlob = new Blob(testChunks, { type: testRecorder.mimeType || mimeType || "audio/webm" });
      const audioWasCaptured = totalBlob.size > 0 && maxLevel > 10;

      console.log(`[Voice Test] Test complete: totalSize=${totalBlob.size} bytes, maxLevel=${Math.round(maxLevel)}%, captured=${audioWasCaptured}`);

      setMicTestResult({
        success: audioWasCaptured,
        audioSize: totalBlob.size,
        maxLevel: Math.round(maxLevel),
        mimeType: testRecorder.mimeType || mimeType || "audio/webm",
        message: audioWasCaptured
          ? `Success! Microphone is working. Captured ${totalBlob.size} bytes, peak level ${Math.round(maxLevel)}%`
          : `Warning: Microphone may not be capturing audio. Size: ${totalBlob.size} bytes, peak level: ${Math.round(maxLevel)}%`,
      });
    } catch (err: any) {
      console.error(`[Voice Test] Microphone test failed:`, err);
      let errorMsg = err.message || "Microphone test failed";
      let solution = "";

      if (err.name === "NotAllowedError") {
        // Fix #10 (P2): reuse the single localized micDenied string instead
        // of this hand-rolled duplicate - the "solution" sub-text below is
        // additional actionable detail, not a second copy of the same denial
        // message, so it stays.
        errorMsg = t("micDenied");
        solution =
          "You need to grant microphone permission. Click the browser's microphone icon in the address bar and select 'Always allow'.";
      } else if (err.name === "NotFoundError") {
        errorMsg = "No microphone found";
        solution = "Check that your device has a working microphone and it's properly connected.";
      } else if (err.name === "NotReadableError") {
        errorMsg = "Microphone is in use or not readable";
        solution = "Your microphone may be in use by another application. Close other apps using audio and try again.";
      } else if (err.name === "SecurityError") {
        errorMsg = "Security error accessing microphone";
        solution = "This site must be served over HTTPS for microphone access. Check your browser's security settings.";
      }

      setMicTestResult({
        success: false,
        audioSize: 0,
        maxLevel: 0,
        mimeType: "unknown",
        message: errorMsg,
        solution: solution,
        error: err.name,
      });
    } finally {
      // Cleanup
      if (testStream) {
        testStream.getTracks().forEach((t) => t.stop());
      }
      if (audioContextRef.current) {
        void audioContextRef.current.close();
        audioContextRef.current = null;
      }
      setTestingMicrophone(false);
      setAudioLevel(0);
    }
  }

  return { testingMicrophone, micTestResult, setMicTestResult, audioLevel, showMicTest, setShowMicTest, testMicrophone };
}
