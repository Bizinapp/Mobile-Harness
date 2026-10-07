# Native voice pipeline

Mobile Harness uses a native Android audio path rather than a WebView/Web Audio path:

```text
AudioRecord (16 kHz mono PCM)
  -> PcmFrameSplitter (20 ms frames)
  -> Silero VAD INT8 ONNX
  -> whisper.cpp JNI (tiny.en Q5_K_M)
  -> VoiceController / agent
  -> KittenTTS nano or Piper PCM chunks
  -> AudioTrack (streamed playback)
```

## Why not AudioWorklet?

`AudioWorklet`, `SharedArrayBuffer`, and `MediaStreamAudioDestinationNode` are Web Audio APIs. This application is a native Kotlin/Compose Android application, so `AudioRecord` and `AudioTrack` avoid a WebView, JavaScript runtime, extra audio copies, and browser permission behavior.

## Code boundaries

- `AudioPipeline.kt` defines the 16 kHz mono PCM contract, 20 ms frame splitter, and VAD interface.
- `AudioRecordSource.kt` captures microphone PCM with bounded buffers.
- `AudioTrackSink.kt` streams PCM in `AudioTrack.MODE_STREAM` and flushes immediately on barge-in.
- `NativeVoiceSession.kt` coordinates VAD-gated capture and injected STT providers.
- `SttEngine` and `TtsEngine` remain the compatibility boundaries used by `VoiceController`.

The model providers are intentionally injected rather than silently adding large native libraries to every APK:

- `SileroVadModel`: INT8 ONNX provider.
- `WhisperSttModel`: whisper.cpp JNI provider.
- `NeuralTtsModel`: KittenTTS or Piper provider.

Until those model providers are installed, the existing Android `SpeechRecognizer` and Android `TextToSpeech` implementations remain the safe fallback. `EnergyVadEngine` exists only as a deterministic development fallback; it is not a replacement for Silero in production.

## Model packaging

Models should be downloaded and verified on first use, not placed in the base APK. Each model needs:

- a pinned version and SHA-256,
- an ARM64-compatible runtime/provider,
- an explicit storage budget,
- cancellation and deletion support,
- a license/attribution record.

The target profile is:

| Component | Target | Initial delivery |
|---|---|---|
| VAD | Silero INT8 ONNX | optional local voice pack |
| STT | whisper.cpp `tiny.en` Q5_K_M | optional local voice pack |
| TTS primary | KittenTTS nano INT8 | optional local voice pack |
| TTS fallback | Piper `en_US-lessac-low` | optional local voice pack |
| Last-resort fallback | Android system engines | built-in |

The current branch contains the native data-plane foundation and provider contracts. The next implementation step is to vendor/build the selected ONNX and whisper.cpp runtimes, add model download verification, and connect concrete providers to `VoiceBar`.
