# Forge

> **On-device multi-LLM workbench for Android.**
> Discover, download, and run GGUF / MediaPipe `.task` models locally with single-model chat, side-by-side compare, and multi-agent pipelines / routers / debates — all on your phone, no cloud round-trip.

---

## What it does

| Surface | Capability |
|--------|-----------|
| **Discover** | Live HuggingFace trending / recent / liked feeds. Tap a card to jump straight into the catalog with details pre-loaded. |
| **Catalog** | Search & browse HF for `gguf` + `mediapipe` models, inspect every variant per repo, see device-fit (GREEN/YELLOW/RED) and license badges before downloading. |
| **Library** | Manage installed models with search, sort (name / size / newest / fastest), automatic benchmarking, side-load (SAF), and deletion confirmation. |
| **Chat** | Single-model conversation with streamed tokens, automatic chat-template detection (ChatML / Llama-3 / Gemma / Phi-3 / Mistral), token usage caption, message export. |
| **Compare** | Pick N installed models, fire the same prompt at all of them, watch responses stream side-by-side with TTFT and tok/s metrics. Export results to Markdown. |
| **Agents** | Build orchestrated workflows — N-step **Pipeline** (with `Single shot` / `Plan → Execute` / `Plan → Execute → Critic` presets), **Router** (classify → dispatch), or **Debate** (multi-participant + optional moderator). |
| **Settings** | HF token (debounced, with onboarding link), default temperature, NPU toggle, device profile, storage summary, app/library versions. |

## Why Forge

- **Truly on-device** — no API keys to OpenAI/Anthropic, no telemetry. Everything runs against models stored in your app's private files dir.
- **Multi-runtime by design** — a single `InferenceRuntime` interface backs `llama.cpp` (CPU, GGUF) and Google's `MediaPipe LLM Inference` (`.task`). ExecuTorch QNN/Exynos and MLC LLM are next.
- **Multi-model first** — Compare and Agents aren't afterthoughts; the whole architecture (RuntimeRegistry, DeviceFitScorer, ChatTemplate detection, ConcurrentHashMap-based session manager) is built so several models can coexist.

## Architecture

25 Gradle modules, strict layering: `feature` → `domain` → `data` / `runtime` / `agent`.

```
:app                    UI entry, manual DI container (Hilt deferred until AGP-9 compat)
:domain                 ChatTemplate, ModelCard, RuntimeId, DeviceFitScore, …
:core:{common,
       hardware,        DeviceProfile + DeviceFitScorer (RAM/NPU heuristics)
       ui}              FormatBadge, LicenseChip, DeviceFitBadge
:feature:{discover, catalog, library,
          chat, compare, agents, settings}
:data:{catalog,         HuggingFace API + interceptor-based auth
       discovery,       Trending / Recent / Liked sources, parallel fan-out
       download,        OkHttp Range-resume + SHA-256
       storage,         Moshi index + DataStore prefs + benchmark store
       agents}
:runtime:{core,         InferenceRuntime, Token streaming flow
          llamacpp,     CMake FetchContent → libllama-jni.so (~40 MB)
          mediapipe,    com.google.mediapipe:tasks-genai
          executorch,   placeholder, Phase 4
          mlc,          placeholder, Phase 5
          registry}     RuntimeRegistry, BenchmarkRunner
:agent:{core,           Agent / Tool abstractions
        orchestrator,   WorkflowOrchestrator: Pipeline + Router + Debate
        tools,          stub
        curator}        stub, Phase 4.5
```

## Build & run

```bash
git clone https://github.com/MLKyu/Forge.git
cd Forge
./gradlew :app:assembleDebug
```

Requires:

- **Android Studio Ladybug** or newer (AGP 9.1, Gradle 9.3, Kotlin 2.2)
- **NDK 27.0.12077973** + CMake 3.22.1 (auto-installed by Android Studio if missing)
- **arm64-v8a device** (no x86 emulator; the native runtime is built only for arm64)

Output: `app/build/outputs/apk/debug/app-debug.apk` (~150 MB — bundles `libllama-jni.so` and MediaPipe's `libllm_inference_engine_jni.so`).

First run: open the app → **Settings** → paste an HF access token (optional but recommended for rate limits and gated repos) → **Catalog** auto-loads trending models → pick a small one (e.g. `unsloth/Qwen2.5-0.5B-Instruct-GGUF` Q4_K_M, ~400 MB) → download → **Chat**.

## Status

Active prototype. The skeleton + Phase 0–3 + Phase 6 are implemented and the project builds cleanly, but the UI flows have **not yet been verified on a physical device** as of the first public push. A reasonable first session is the device-validation checklist in `STATUS.md` (kept locally, not in repo).

### Roadmap

- **Phase 4** — ExecuTorch QNN backend (Snapdragon NPU), ExecuTorch Exynos backend
- **Phase 5** — MLC LLM (Vulkan GPU)
- **Phase 6.5+** — Tool calls wired into Agents, Memory / RAG, AI curator, multi-round Debate
- **Discovery sources** — Reddit r/LocalLLaMA RSS, Ollama Library, HF Blog feed (mapping article → model is open)

### Known limitations

- arm64-v8a only (no x86_64 / armeabi-v7a)
- CPU only — Vulkan / NPU runtimes are scaffolded but not yet wired
- MediaPipe streaming uses a single result-listener slot per inference instance (race-prone under concurrent generate)
- llama.cpp is pinned to `master` via CMake `FetchContent` — pinning to a specific commit is on the roadmap
- License inference relies on HF tag prefixes; many repos surface as `unknown`

## Contributing

This is a personal project; PRs are welcome but `main` is protected — direct pushes are blocked, every change requires a PR with owner approval.

## License

To be decided. Treat the source as **all rights reserved** until a license file is added.
