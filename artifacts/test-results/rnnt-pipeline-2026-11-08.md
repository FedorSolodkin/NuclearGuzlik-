# RNNT → diarization → JSON device audit (2026-11-08)

Device: Xiaomi M2101K6G, Android 13, CPU. Separate `ru.guzlik.voice.dev` package; no user recordings modified. GigaAM v3 RNNT punct int8, WeSpeaker, pyannote. Uses production `WhisperHelper`, `SpeakerDiarizer`, and `JsonTranscriptExporter`.

Public fixture: [`ru_meeting_2spk.wav` and reference](https://github.com/zzzatoox/neuro-steno/tree/master/test_audio). Dialogue 33.795 s; monologue is its first 5.000 s (the reference first speaker ends at 4779 ms, the second starts at 5279 ms). This small TTS fixture is a regression check, not a representative speech-quality benchmark.

## Initial complete pipeline run

`RnntPipelineDeviceTest.monologueAndDialogueExportWithAutomaticAndExplicitCounts`: 26.482 s total, FAIL on the strict speaker-ID assertion in the two dialogue modes. All four mode reports were saved before the test failed.

| Case | Count mode | Found voices | ASR ms | Diarization ms | Acoustic DER | Strict JSON result |
|---|---|---:|---:|---:|---:|---|
| 5 s monologue | auto | 1 | 963 | 922 | 1.533% | PASS |
| 5 s monologue | fixed 1 | 1 | 963 | 847 | 1.533% | PASS |
| 33.795 s dialogue | auto | 2 | 6803 | 6728 | 3.211% | FAIL: one `unknown` ID |
| 33.795 s dialogue | fixed 2 | 2 | 6803 | 6948 | 3.211% | FAIL: one `unknown` ID |

ASR runs once per audio and is reused by the auto/fixed-count passes. Times exclude model initialization, audio decoding and UI. ASR + automatic diarization is 13.531 s for the dialogue (RTF ≈ 0.400), not ASR-only RTF ≈ 0.201.

DER is computed from acoustic `turns`, not ASR word durations, using `src/android/tools/device_test/diar_score.py`: global optimal label mapping, no collar, overlaps included. The monologue reference is clipped to [0, 5000) ms. These numbers cannot be compared directly with competitor aggregate DER results or different collar settings.

### Failure isolated

Both dialogue modes produce the same single unknown unit: **«К» at 29391–29551 ms**. The next acoustic speaker interval starts at 29516 ms: 35 ms overlap out of a 160 ms word. The preceding different speaker ends at 29032 ms. The reference confirms the phrase «К пятнице, седьмого ноября.» is one speaker at 29445–31372 ms. The following recognized word «пятнице» starts at 29551 ms and has the correct speaker. This isolates a short-word boundary issue, not a missing participant or a reason to replace `unknown` blindly with speaker 1.

The complete exported dialogue preserves the ASR lexical sequence in order (no lost or repeated words), but `speaker_id: "unknown"` violates this test's required `speaker_N` IDs. This failure was reported to the diarization agent and root for a general acoustic-boundary fix.

Local raw reports: `/tmp/guzlik-asr-comparison/pipeline/*.json` and `*.der.json`; device reports: `files/comparison/pipeline/` in the isolated test package. No private recordings are included.

## RNNT chunk planner fix

The previous ASR planner padded each VAD region by 300 ms. Adjacent regions whose merged length exceeded the 22-second cap were sent to separate recognition requests with overlapping padding, decoding the same samples twice. New planner assigns a shared padded boundary at the midpoint of the **original speech gap**, preserving original speech while making recognition requests disjoint. Using padded bounds themselves is unsafe near file edges where padding is clipped.

Six unit regressions cover adjacent long speech, short gaps, clipped file start/end, normal merging and preserved long silence. The parent's combined build and unit tests passed before the device run. VAD input remains 512-sample windows as in upstream.

## Remaining verification

The separate device test `silenceAndPrecancelledSpeechDoNotProduceText` **passed in 2.817 s**: five seconds of silence produced no text; a precancelled speech token produced no text. Log: `/tmp/guzlik-asr-comparison/rnnt-silence-phone.log`. Long recordings, real overlapping voices, LLM output, UI/import persistence and complete offline APK installation are not covered by this audit.

## Handoff after boundary fix

The short-onset issue above has been fixed in SpeakerTimeline using unique acoustic evidence continuing into the next confidently assigned word (onset expansion bounded to160 ms). The global coverage threshold and speaker IDs were not rewritten. Regression and negative tests were added. Final unit tests and debug/test APK assembly passed. The four-mode device pipeline was **not rerun after this final fix**, because the user requested an immediate stop and push. The earlier device FAIL remains recorded above and must not be presented as a post-fix PASS.

Also fixed: RNNT no longer applies Whisper-specific outro/credit/repetition removal, preserving spoken text and native word times. Silence and precancelled recognition passed on the phone before the final boundary fix.
