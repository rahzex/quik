# AGENTS.md — QUIK Codebase Guide

QUIK is an Android SMS/MMS app (fork of QKSMS). Built with Kotlin, Clean Architecture, MVI, RxJava 2, Realm, Dagger 2, and Conductor.

## Module Structure

| Module | Role |
|---|---|
| `domain` | Repository interfaces, `Mapper` interfaces (`CursorTo*`), managers, models, preferences |
| `data` | Repository `*Impl` classes, blocking clients, BroadcastReceivers, Realm migrations |
| `presentation` | Activities, Conductor Controllers, ViewModels, DI wiring, feature UIs |
| `common` | Shared utilities used across modules |
| `android-smsmms` | Forked MMS library — avoid modifying unless fixing MMS-specific bugs |

## Architecture: Clean Architecture + MVI

Each feature follows: `Controller/Activity` → `QkViewModel` → Repository (interface from `domain`, impl from `data`).

**MVI state pattern** — all ViewModels extend `QkViewModel<View, State>`:
- State is a data class; mutate it with `newState { copy(field = newValue) }`.
- Views implement `QkView<State>` and override `render(state: State)`.
- View intent streams are bound in `ViewModel.bindView(view)`.

Example: `presentation/.../feature/compose/ComposeViewModel.kt`

## Package Naming

Source files live under `com/moez/QKSMS/` directory paths but package declarations use `dev.octoshrimpy.quik.*`. Do **not** change directory structure; Gradle maps them correctly.

## Dependency Injection

Dagger 2 (not Hilt). Entry points:
- `presentation/.../injection/AppComponent.kt` — root component
- `presentation/.../injection/AppModule.kt` — binds implementations to interfaces
- `presentation/.../injection/android/ActivityBuilderModule.kt` — per-activity subcomponents

When adding a new repository or manager: declare the interface in `domain`, implement in `data`, bind in `AppModule`, inject with `@Inject`.

## Async / Reactive

RxJava 2 is the primary async mechanism (not Coroutines). Use `AutoDispose` with `autoDisposable(view.scope())` to handle lifecycle in ViewModels. See `QkViewModel.bindView()`.

## Database

Realm (not Room). Models are Realm `RealmObject` subclasses. Raw Android ContentProvider cursors (SMS/MMS) are mapped via `CursorTo*` mapper classes in `domain`/`data`.

## Build

- Requires **JDK 17**. In Android Studio: `Settings > Build > Gradle > Gradle JDK`.
- `./gradlew assembleDebug` — build debug APK
- `./gradlew assembleRelease` — requires keystore at `presentation/my-release-key.keystore` and credentials in `.gradle/.gradlerc`
- `realm-android` plugin must be applied **before** `kotlin-android` in `presentation/build.gradle` (enforced; do not reorder).
- App ID: `dev.octoshrimpy.quik`

## UI / Design Conventions

> **For every UI modification, strictly follow these two design references — no exceptions:**
>
> 1. **`pulse-sms-with-tabs_2.html`** (root of repo) — the canonical visual spec. All color tokens, component specs, spacing, and dialog designs are demonstrated here. This is the primary source of truth.
> 2. **`DESIGN_SYSTEM.md`** (root of repo) — a structured Markdown summary of the HTML spec, including typography, color tokens, border radii, spacing, component patterns, dialog variants, elevation, motion, iconography, and Android XML mappings.
>
> When implementing or modifying any UI element (layouts, dialogs, colors, typography, icons, spacing, animations) you **must**:
> - Open `pulse-sms-with-tabs_2.html` and find the matching screen/component.
> - Cross-reference `DESIGN_SYSTEM.md` for the token/attribute mapping.
> - Never deviate from these specs (colors, radii, shadows, font weights, etc.) without explicit instruction.

## Key Conventions

- **Translations**: Never edit string resources directly. All translations go through [Weblate](https://hosted.weblate.org/engage/quik/). PRs that modify translated strings directly will be rejected.
- **Feature folders**: Each screen lives in `presentation/.../feature/<name>/` and contains `<Name>Activity` or `<Name>Controller`, `<Name>ViewModel`, `<Name>State`, `<Name>View` (interface).
- **Conductor Controllers** are used instead of Fragments (`QkController` base class).
- **Blocking clients** (`data/.../blocking/`) are pluggable — `BlockingManager` delegates to the active `BlockingClient` implementation (QKSMS-native, CallBlocker, ShouldIAnswer, etc.).

## Key Files

- `presentation/.../common/base/QkViewModel.kt` — MVI base class
- `presentation/.../common/base/QkController.kt` — Conductor base controller
- `presentation/.../injection/AppModule.kt` — all DI bindings
- `domain/.../util/Preferences.kt` — all user preference keys
- `domain/.../repository/*.kt` — all repository contracts
- `pulse-sms-with-tabs_2.html` — **canonical UI/design spec** (primary visual reference)
- `DESIGN_SYSTEM.md` — structured design token & component reference derived from the HTML spec

