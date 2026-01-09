# Repository Guidelines

## Project Structure and Module Organization
- `app/` is the main Android module. Primary code lives in `app/src/main/java/com/coni/hyperisle`, with packages such as `ui/`, `service/`, `data/`, `overlay/`, `receiver/`, `worker/`, `util/`, `models/`, and `debug/`.
- Resources and assets are under `app/src/main/res/` (strings, themes, drawables, mipmaps).
- Unit tests are in `app/src/test/...`, and instrumented tests are in `app/src/androidTest/...`.
- `toolkit/` is included via composite build (`includeBuild("toolkit")`).
- Generated output and IDE metadata live in `build/`, `.gradle/`, and `.idea/` (do not commit).

## Build, Test, and Development Commands
- `./gradlew :app:assembleDebug` (or `gradlew.bat` on Windows): build a debug APK.
- `./gradlew :app:installDebug`: install the debug build on a connected device (API 35+).
- `./gradlew test`: run local unit tests.
- `./gradlew connectedAndroidTest`: run instrumentation tests on a device or emulator.
- `./gradlew lint`: run Android lint checks.
- always run build and test commands after finishing code edits
- always run `.\gradlew :app:compileDebugKotlin` at the end of every response unless the user explicitly says not to

## Coding Style and Naming Conventions
- Kotlin only; UI is Jetpack Compose with Material 3.
- Follow existing Kotlin style and 4-space indentation; let Android Studio handle formatting.
- Naming patterns: `*Screen.kt` for screens, `*ViewModel.kt` for view models, `*Receiver.kt` for broadcast receivers, `*Service.kt` for services, and `*Translator.kt` for translators.
- Keep packages aligned with feature area (UI in `ui/`, background logic in `service/`).
- Always add logging logic to new added features for debug-app. Use existing logging method and give command for new all logs.

## Skill Usage for Requests
- Always look for skills in this directory: C:\Users\bekir\.codex\skills
- UI/UX changes or styling requests: use the `frontend-design` skill.
- Refactors or maintainability cleanups: use `code-refactoring`.
- LLM features, prompts, or RAG: use `llm-application-dev`.
- Adding or updating skills: use `skill-creator`; installing skills: use `skill-installer`.

## Testing Guidelines
- Unit tests use JUnit4; Android tests use AndroidX JUnit, Espresso, and Compose test APIs.
- Name tests `*Test.kt` and place them in the matching package under `app/src/test` or `app/src/androidTest`.
- No explicit coverage target is defined; prioritize decision logic, workers, and utilities.

## Commit and Pull Request Guidelines
- Recent commits use short, lowercase summaries (example: `debug log`). Keep subjects concise and action-focused.
- Do not push directly to `main`. Create a feature or fix branch.
- PRs should include a clear description, linked issues when available, and screenshots for UI changes (per `CONTRIBUTING.md`).
- Mention test results and device/API level when relevant.

## Configuration and Security
- `local.properties` is local-only for Android SDK paths; do not commit it.
- Keep keystore and signing artifacts out of the repo (see `.gitignore`).
