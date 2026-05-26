# AGENTS.md — QUIK Codebase Guide

QUIK is an Android SMS/MMS app (fork of QKSMS). Built with Kotlin, Clean Architecture, MVI, RxJava 2, Realm, Dagger 2, and Conductor.

## Module Structure

| Module | Role |
|---|---|
| `domain` | Repository interfaces, `Mapper` interfaces (`CursorTo*`), managers, models, interactors, preferences |
| `data` | Repository `*Impl` classes, blocking clients, categorization, workers, BroadcastReceivers, Realm migrations |
| `presentation` | Activities, Conductor Controllers, ViewModels/Presenters, DI wiring, feature UIs |
| `common` | Shared utilities used across modules |
| `android-smsmms` | Forked MMS library — avoid modifying unless fixing MMS-specific bugs |

## Architecture: Clean Architecture + MVI

Each feature follows: `Controller/Activity` → `QkViewModel` / `Presenter` → Repository (interface from `domain`, impl from `data`).

**MVI state pattern** — most ViewModels extend `QkViewModel<View, State>`:
- State is a data class; mutate it with `newState { copy(field = newValue) }`.
- Views implement `QkView<State>` and override `render(state: State)`.
- View intent streams are bound in `ViewModel.bindView(view)`.
- Some features (e.g. `settings`, `settings/about`, `settings/swipe`) use a `*Presenter` class instead of `*ViewModel`.

Example ViewModel: `presentation/.../feature/compose/ComposeViewModel.kt`
Example Presenter: `presentation/.../feature/settings/SettingsPresenter.kt`

## Package Naming

Source files live under `com/moez/QKSMS/` directory paths but package declarations use `dev.octoshrimpy.quik.*`. Do **not** change directory structure; Gradle maps them correctly.

## Dependency Injection

Dagger 2 (not Hilt). Entry points:
- `presentation/.../injection/AppComponent.kt` — root component
- `presentation/.../injection/AppComponentManager.kt` — manages component lifecycle
- `presentation/.../injection/AppModule.kt` — binds implementations to interfaces
- `presentation/.../injection/ViewModelKey.kt` — multibinding key for ViewModels
- `presentation/.../injection/android/ActivityBuilderModule.kt` — per-activity subcomponents
- `presentation/.../injection/android/BroadcastReceiverBuilderModule.kt` — per-receiver subcomponents
- `presentation/.../injection/android/ServiceBuilderModule.kt` — per-service subcomponents
- DI scopes: `ActivityScope`, `ControllerScope` (in `injection/scope/`)

When adding a new repository or manager: declare the interface in `domain`, implement in `data`, bind in `AppModule`, inject with `@Inject`.

## Async / Reactive

RxJava 2 is the primary async mechanism (not Coroutines). Use `AutoDispose` with `autoDisposable(view.scope())` to handle lifecycle in ViewModels. See `QkViewModel.bindView()`.

Background work also uses **WorkManager** workers (see `data/.../worker/`).

## Database

Realm (not Room). Models are Realm `RealmObject` subclasses. Raw Android ContentProvider cursors (SMS/MMS) are mapped via `CursorTo*` mapper classes in `domain`/`data`.

## Domain Layer

### Repositories (interfaces in `domain/.../repository/`)
- `BackupRepository` — backup/restore
- `BlockingRepository` — blocked numbers
- `ContactRepository` — contacts & contact groups
- `ConversationRepository` — conversation list & threading
- `EmojiReactionRepository` — emoji reactions on messages
- `MessageContentFilterRepository` — content-based message filters
- `MessageRepository` — SMS/MMS message CRUD
- `ScheduledMessageRepository` — scheduled messages
- `SyncRepository` — syncing messages from the system content provider

### Models (`domain/.../model/`)
`Attachment`, `BackupFile`, `BlockedNumber`, `Contact`, `ContactGroup`, `Conversation`, `EmojiReaction`, `EmojiSyncNeeded`, `Message`, `MessageCategory`, `MessageContentFilter`, `MmsPart`, `PhoneNumber`, `Recipient`, `ScheduledMessage`, `SearchResult`, `SyncLog`

### Interactors (`domain/.../interactor/`)
Key interactors: `CategorizeConversation`, `DeduplicateMessages`, `DeleteConversations`, `DeleteMessages`, `DeleteOldMessages`, `MarkBlocked`/`MarkUnblocked`, `MarkRead`/`MarkUnread`, `MarkArchived`/`MarkUnarchived`, `MarkPinned`/`MarkUnpinned`, `SendNewMessage`, `SendScheduledMessage`, `SyncMessages`, and others.

### Categorization
`domain/.../categorization/SmsCategorizer` — interface for classifying SMS into categories (Personal, Transaction, Promo, OTP, etc.). Implemented by `data/.../categorization/SmsCategorizerImpl`.

### Managers (`domain/.../manager/`)
`ActiveConversationManager`, `AlarmManager`, `BillingManager`, `ChangelogManager`, `KeyManager`, `NotificationManager`, `PermissionManager`, `RatingManager`, `ReferralManager`, `ShortcutManager`, `SpeakManager`, `WidgetManager`

## Data Layer

### Blocking clients (`data/.../blocking/`)
`BlockingManager` delegates to the active `BlockingClient`:
- `QksmsBlockingClient` — native QUIK block list
- `CallBlockerBlockingClient` — CallBlocker integration
- `CallControlBlockingClient` — CallControl integration
- `ShouldIAnswerBlockingClient` — ShouldIAnswer integration

### Workers (`data/.../worker/`)
- `CategorizeAllMessagesWorker` — bulk categorization of existing messages
- `HousekeepingWorker` — scheduled cleanup (old OTPs, auto-delete, etc.)
- `ReceiveMmsWorker` / `ReceiveSmsWorker` — incoming message processing
- `InjectionWorkerFactory` — Dagger-aware WorkerFactory

### Services (`data/.../service/`)
- `AutoDeleteService` — handles auto-deletion of messages by rules
- `HeadlessSmsSendService` — sends SMS without a UI

### Receivers (`data/.../receiver/`)
Notable: `SmsReceivedReceiver`, `MmsReceivedReceiver`, `MmsWapPushReceiver`, `BootReceiver`, `DefaultSmsChangedReceiver`, `NightModeReceiver`, `SendScheduledMessageReceiver`, `SendDelayedMessageReceiver`, and others.

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
- **Feature folders**: Each screen lives in `presentation/.../feature/<name>/` and contains `<Name>Activity` or `<Name>Controller`, `<Name>ViewModel` or `<Name>Presenter`, `<Name>State`, `<Name>View` (interface).
- **Conductor Controllers** are used instead of Fragments (`QkController` base class).
- **Blocking clients** (`data/.../blocking/`) are pluggable — `BlockingManager` delegates to the active `BlockingClient` implementation (QKSMS-native, CallBlocker, CallControl, ShouldIAnswer).

## Feature Inventory (`presentation/.../feature/`)

| Feature folder | Entry point | Notes |
|---|---|---|
| `backup` | `BackupActivity` | Backup & restore |
| `blocking/filters` | Controller | Message content filters |
| `blocking/manager` | Controller | Blocking manager (select provider) |
| `blocking/messages` | Controller | Blocked messages list |
| `blocking/numbers` | Controller | Blocked numbers list |
| `changelog` | Controller | In-app changelog |
| `compose` | `ComposeActivity` + `ComposeViewModel` | New/existing conversation compose |
| `contacts` | Controller + `ContactsViewModel` | Contact picker |
| `conversationinfo` | Controller | Thread info & settings |
| `conversations` | Controller | Main conversation list |
| `extensions` | Controller | App extensions |
| `gallery` | Controller + `GalleryViewModel` | Media gallery |
| `main` | `MainActivity` + `MainViewModel` | App shell with tab bar |
| `messageutils` | Controller | Per-message actions |
| `notificationprefs` | Controller + `NotificationPrefsViewModel` | Per-thread notification settings |
| `plus` | Controller + `PlusViewModel` | Premium/plus screen |
| `qkreply` | Activity + `QkReplyViewModel` | Quick-reply heads-up |
| `scheduled` | Controller + `ScheduledViewModel` | Scheduled messages list |
| `settings` | `SettingsActivity` + `SettingsPresenter` | Main settings |
| `settings/about` | Controller + `AboutPresenter` | About screen |
| `settings/autodelete` | `AutoDeleteDialog` | Auto-delete OTP/promo dialog |
| `settings/swipe` | Controller + `SwipeActionsPresenter` | Swipe action config |
| `themepicker` | Controller | Theme picker |
| `widget` | Widget provider | Home screen widget |

## Key Files

- `presentation/.../common/base/QkViewModel.kt` — MVI base class
- `presentation/.../common/base/QkController.kt` — Conductor base controller
- `presentation/.../injection/AppModule.kt` — all DI bindings
- `presentation/.../injection/android/ActivityBuilderModule.kt` — activity subcomponents
- `presentation/.../injection/android/BroadcastReceiverBuilderModule.kt` — receiver subcomponents
- `presentation/.../injection/android/ServiceBuilderModule.kt` — service subcomponents
- `domain/.../util/Preferences.kt` — all user preference keys
- `domain/.../repository/*.kt` — all repository contracts
- `domain/.../categorization/SmsCategorizer.kt` — SMS categorization interface
- `data/.../worker/HousekeepingWorker.kt` — scheduled cleanup logic
- `data/.../service/AutoDeleteService.kt` — auto-delete service
- `pulse-sms-with-tabs_2.html` — **canonical UI/design spec** (primary visual reference)
- `DESIGN_SYSTEM.md` — structured design token & component reference derived from the HTML spec

