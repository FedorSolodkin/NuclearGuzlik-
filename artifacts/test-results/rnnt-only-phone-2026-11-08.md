# GigaAM RNNT-only smoke test — 2026-11-08

Xiaomi M2101K6G, Android 13, CPU, 4 threads; GigaAM v3 Transducer punct int8. Three public neuro-steno fixtures. ASR timing excludes model initialization, diarization and LLM; single run, not an accuracy comparison. VAD uses 512-sample windows.

| Fixture | Audio seconds | ASR seconds | Timed words |
|---|---:|---:|---:|
| ru_meeting_2spk | 33.80 | 6.33 | 53 |
| ru_meeting_3spk | 29.06 | 6.85 | 58 |
| real_ru_2spk | 102.33 | 19.23 | 178 |

`AsrComparisonDeviceTest`: PASS, 39.684 s. All three results identify the RNNT backend; native word timing exists and stays inside audio bounds.

`testStoreDebugUnitTest`: 252 passed, 3 skipped, zero failures. Contest APK assembled with eight embedded files. `verify_contest_bundle.py --apk` verified exact model set, byte counts and SHA-256, with no CTC or Whisper model. `apksigner verify`: PASS. Full diarization/LLM/end-to-end installation of this newly assembled APK was not retested in this run.
