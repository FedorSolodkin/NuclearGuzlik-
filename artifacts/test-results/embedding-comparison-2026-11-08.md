# Speaker embedding comparison — 2026-11-08

## Decision

Do not replace the current WeSpeaker production model on this evidence alone. TitaNet-small is a candidate for a measured Android speed trial, but this test shows no accuracy gain at the shared 0.5 threshold and higher host peak memory. CAM++ is not a safe drop-in replacement with the tested thresholds. No production code/model was changed. The initial sherpa-compatible WeSpeaker export is a different binary from the app model. A subsequent control uses the exact APK graph/weights with metadata-only adaptation; it remains a comparison in the common sherpa pipeline, not an APK pipeline A/B.

## Protocol

- Host: macOS-26.6-arm64-arm-64bit, arm64. CPU, 2 threads for segmentation and embeddings, separate sequential process per model. Single run per model/config; host timing is not Android timing and does not include APK setup, ASR or LLM. OS warm-cache/other-process effects were not controlled.
- sherpa-onnx 2.0.1 `OfflineSpeakerDiarization`; same pyannote segmentation for every model, window_shift_ratio=0.1, min_duration_on=0.3 s, min_duration_off=0.5 s, num_clusters=-1. No minimum of two speakers.
- Shared threshold presets 0.5 and 0.35, no model-specific tuning or held-out validation. Numeric thresholds are not interchangeable between embedding models or clustering implementations. These presets do not establish the best achievable score of any model.
- This sherpa pipeline is not the application Kotlin pipeline (10 s windows/5 s hop, RobustSpeakerClustering, boundary refinement). Its absolute DER cannot be presented as the current APK DER.
- DER: exact global one-to-one mapping, zero collar, overlap included; project `src/android/tools/device_test/diar_score.py`. CAM++ oversegmented to 9/11 identities, so a temporary in-memory host copy raised only its hypothesis-count guard to 16; assignment/scoring algorithm unchanged. Production scorer unchanged.
- Peak RSS is macOS `resource.getrusage(RUSAGE_SELF).ru_maxrss`, bytes converted to MiB; cumulative within each process, includes Python, segmentation, model and decoded PCM. It is not Android PSS or isolated embedding memory.

## Public fixtures

References and WAVs: [neuro-steno test_audio](https://github.com/zzzatoox/neuro-steno/tree/master/test_audio), [eval/ref](https://github.com/zzzatoox/neuro-steno/tree/master/eval/ref). Two TTS meeting files plus the repository’s real_ru_2spk fixture; labels taken as supplied, not independently hand-audited. Small convenience set, not a general Russian benchmark.

| Fixture | Duration s | Reference voices | WAV SHA-256 |
|---|---:|---:|---|
| ru_meeting_2spk | 33.795 | 2 | `6be226d056959f537d0eb05a2b3a7e479b33fb998f1eef23ec56f5753742d790` |
| ru_meeting_3spk | 29.063 | 3 | `3295e0a2ee67c1a484b972b79099cc1c7fc46b7ae54534d2733eb2d0d9523839` |
| real_ru_2spk | 102.326 | 2 | `6955aabb5ccbf84ce2540e8862d10be4e31add7faa961a51d9b591005e7d5a40` |

Single-speaker check: longest non-overlapping reference utterance from real_ru_2spk, cropped to its supplied boundaries, 6.950 s. Reference covers the crop as one speaker. This tests one clean turn, not a long monologue, changing microphone distance, or noise.

## Results: DER % / inferred voices

| Model | Threshold | Meeting 2 voices | Meeting 3 voices | Real 2 voices | Single turn 1 voice | Macro DER, 3 full files |
|---|---:|---:|---:|---:|---:|---:|
| camplus | 0.35 | 54.53% / 6 | 34.98% / 4 | 68.34% / 11 | 0.45% / 1 | 52.62% |
| camplus | 0.5 | 42.67% / 4 | 49.42% / 4 | 60.36% / 9 | 0.45% / 1 | 50.82% |
| titanet | 0.35 | 15.84% / 4 | 34.30% / 6 | 51.56% / 6 | 0.45% / 1 | 33.90% |
| titanet | 0.5 | 2.91% / 2 | 29.07% / 2 | 11.57% / 2 | 0.45% / 1 | 14.51% |
| wespeaker | 0.35 | 2.91% / 2 | 29.07% / 2 | 44.80% / 4 | 0.45% / 1 | 25.59% |
| wespeaker | 0.5 | 2.91% / 2 | 29.07% / 2 | 11.57% / 2 | 0.45% / 1 | 14.51% |

All three tested sherpa-compatible models kept the clean single turn as one voice. At threshold 0.5, WeSpeaker and TitaNet produced identical scored turns on these fixtures; both merged the three-voice meeting into two. The model swap alone did not fix that case.

## Host speed, model size and memory

| Model | Threshold | 3 full files processing s | Load s | Peak RSS MiB | Embedding file MiB |
|---|---:|---:|---:|---:|---:|
| camplus | 0.35 | 6.87 | 0.121 | 238.9 | 28.2 |
| camplus | 0.5 | 6.37 | 0.132 | 214.0 | 28.2 |
| titanet | 0.35 | 6.22 | 0.166 | 363.2 | 38.4 |
| titanet | 0.5 | 5.35 | 0.059 | 364.0 | 38.4 |
| wespeaker | 0.35 | 12.51 | 0.028 | 216.8 | 25.3 |
| wespeaker | 0.5 | 11.69 | 0.076 | 194.4 | 25.3 |

## Model provenance

All runs use segmentation SHA-256 `220ad67ca923bef2fa91f2390c786097bf305bceb5e261d4af67b38e938e1079`.

| Model | Embedding SHA-256 |
|---|---|
| camplus | `357a834f702b80161e5b981182c038e18553c1f2ca752ed6cec2052365d4129b` |
| titanet | `ad4a1802485d8b34c722d2a9d04249662f2ece5d28a7a039063ca22f515a789e` |
| wespeaker | `e9848563da86f263117134dfd7ad63c92355b37de492b55e325400c9d9c39012` |

The tested WeSpeaker SHA differs from the APK `speaker_model.onnx` SHA `3955447b0499dc9e0a4541a895df08b03c69098eba4e56c02b5603e9f7f4fcbb`. The initial export is not treated as equivalent to the app graph. A metadata-only control of the exact app graph is documented below. Directly loading the APK model into the sherpa extractor in a separate host process failed on missing model metadata and exited natively (139); the app uses its own ONNX/FBANK wrapper. No Android app was affected. The segmentation export also differs from the APK segmentation hash. The pipeline/export differences prevent attributing these absolute results or runtime ratios to the current APK. The metadata-only control below addresses the embedding-weight difference, but not the difference between sherpa and Kotlin pipeline/frontend.

TitaNet-small source: [official sherpa speaker-model release](https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/nemo_en_titanet_small.onnx). WeSpeaker/CAM++ are the existing local official sherpa comparison files, exact identities pinned by hashes above.

## Next implementation step

1. Keep WeSpeaker for this release; target short-turn assignment and supported-cluster merging with real examples.
2. If trying TitaNet, add an explicit compatible extractor and run the same complete Kotlin pipeline on the same phone. The current SpeakerEmbedder rank-based ONNX dispatch does not establish support for the TitaNet input contract. Measure end-to-end DER, word-to-speaker errors, peak PSS and latency before switching.
3. Expand the frozen test set with long monologues and similar live voices. Do not tune on these three files and report that as held-out improvement.

## Local reproduction and outputs

Temporary script: `/tmp/guzlik-asr-comparison/compare_embeddings.py`; use `/tmp/voicemind-sherpa-venv/bin/python SCRIPT {wespeaker,titanet,camplus} --threshold {0.5,0.35}` sequentially. Exact per-case intervals, error components, model hashes and run parameters are in `/tmp/guzlik-asr-comparison/embedding-comparison/*.json`. No private recordings were read or uploaded.

## Exact production-weight metadata-only control

The original APK WeSpeaker model cannot be read directly by sherpa because required metadata is absent. A temporary copy was constructed by preserving every original ModelProto field except metadata_props byte-for-byte and appending the compatible export’s metadata. No graph, initializer tensor, input/output declaration or operator was edited. The binary protobuf graph SHA is identical before and after adaptation. This is a stronger control than assuming equal weights from equal model names.

- Original APK model SHA-256: `3955447b0499dc9e0a4541a895df08b03c69098eba4e56c02b5603e9f7f4fcbb`
- Metadata-only copy SHA-256: `355dfcb79f91dc5dd6d9f9e2c5669c8068203fd75cf146322ee09555fcdd2d7a`
- Original and adapted graph SHA-256: `1614439adfc90dfc777dd866260c0de94096548d90acea8f50c9c5b2e4eb9ecf`
- Initial sherpa-compatible export graph SHA-256 (different): `3247a23b25effa9659dde48ed38693dc45e1d89a6f2a922632909f69589ccda3`

The metadata control preserves weights, but the inference/frontend remains sherpa rather than the app’s Kotlin FBANK and clustering, and the shared segmentation is a different export. It does not measure current APK quality or speed.

| Exact app weights + metadata | Threshold | Meeting 2 DER / K | Meeting 3 DER / K | Real 2 DER / K | Single-turn DER / K | 3-file host time s | Peak RSS MiB |
|---|---:|---:|---:|---:|---:|---:|---:|
| WeSpeaker APK | 0.5 | 2.91% / 2 | 29.07% / 2 | 11.57% / 2 | 0.45% / 1 | 20.19 | 168.9 |
| WeSpeaker APK | 0.35 | 2.91% / 2 | 29.07% / 2 | 44.80% / 4 | 0.45% / 1 | 20.97 | 161.3 |

The exact production embedding weights reproduce the same DER/K outcomes as the alternative WeSpeaker export under both tested common thresholds. TitaNet-small at 0.5 matches their accuracy on these fixtures and ran faster on the host, with higher peak RSS. It merits an explicit Android adapter benchmark, not an unmeasured production swap.

Metadata control script and byte-preservation proof: `/tmp/guzlik-asr-comparison/add_model_metadata.py`, `/tmp/guzlik-asr-comparison/embedding-comparison/metadata-control.json`. Both original APK files remain untouched.

Machine-readable run results and metadata-control proof are archived beside this report in `embedding-comparison-2026-11-08/`.
