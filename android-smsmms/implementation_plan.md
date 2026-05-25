# SMS Categorization App (SMS Organizer Alternative)

This document outlines the step-by-step implementation plan for building a privacy-focused, on-device SMS categorization app, serving as an open-source alternative to Microsoft's SMS Organizer.

## Goal Description

Create a modern, clean, and open-source Android SMS application that automatically categorizes incoming messages into tabs (Personal, Transactions, OTP, Updates, Promos, Spam) and extracts financial data to provide a dedicated spending and balance tracking dashboard. The app will be built on top of the QUIK SMS (QKSMS fork) codebase to leverage its robust MVI architecture, RxJava/Realm tech stack, and existing messaging capabilities.

## Architecture & Foundational Decisions

*   **Base Application:** Fork of **QUIK SMS**.
    *   *Why:* It provides a solid foundation with Clean Architecture (MVI), RxJava 2, Dagger 2, and a Realm database. It's much easier to extend with a complex categorization engine than simpler apps.
*   **Categorization Engine:** On-device rule-based engine using Regex, keyword matching, and sender ID analysis.
    *   *Privacy:* 100% offline, zero data sent to the cloud.
*   **Data Extraction (Finance):** Regex templates tailored specifically for major Indian banks and services (HDFC, SBI, ICICI, Axis, PNB, IDFC First, UPI apps, Credit Cards, etc.).
*   **UI/UX Aesthetic:** Clean, minimal, modern Light Mode (matching HTML mockup `pulse-sms-with-tabs_1.html`).
*   **Distribution:** F-Droid and GitHub Releases initially.
*   **Licensing:** GPLv3 (inherited from QUIK SMS).

## Proposed UI Design

Check `pulse-sms-with-tabs_1.html` for the detailed UI mockups and design.

---

## Phased Implementation Plan

---

## Phase 1: Core Categorization & UI Foundation

**Goal:** Ship a working, categorized SMS app with tabbed inbox, bottom navigation, and the core categorization engine.

**UI Screens in scope (from HTML mockup):**
- **Messages screen** — Bottom nav (Messages | Finance | Settings) + horizontal scrollable tabs (All | Personal | Transactions | OTP | Updates | Promos | Spam) + search bar + conversation list with category badges
- **Thread screen** — Existing thread view + category badge in the header (no parsed financial cards — those are Phase 2)
- **Settings screen (partial)** — New tab-based settings with "Messaging" and "Categorisation" sections only; "Appearance" section wired to existing prefs

**Design decisions locked in for Phase 1:**
- Navigation Drawer is **removed entirely**. Replaced by Bottom Navigation Bar.
- Finance tab shows an empty placeholder screen in Phase 1.
- OTP tab is visible in Phase 1 but OTP-specific features (copy button, auto-expiry) are Phase 3.
- Parsed financial cards inside message bubbles are Phase 2.
- "Move to Category" is accessible via **long-press context menu** on conversation rows.
- First-run re-categorization of existing messages runs as a **silent background WorkManager worker**.
- Custom Rules UI is deferred to a later phase; Phase 1 has only the built-in regex engine.

---

### Step 1 — Define `MessageCategory` Enum

**Module:** `domain`
**File to create:** `domain/src/main/java/com/moez/QKSMS/model/MessageCategory.kt`

Create a Kotlin enum representing all possible SMS categories. This is the single source of truth used across all layers.

```kotlin
package dev.octoshrimpy.quik.model

enum class MessageCategory {
    ALL,           // Default stored value AND the "All" tab filter — covers unprocessed/uncategorized messages
    PERSONAL,
    TRANSACTIONS,
    OTP,
    UPDATES,
    PROMOS,
    SPAM
}
```

**Notes:**
- `ALL` serves dual purpose: it is both the Realm default stored on new messages (before or without categorization) and the "All" tab UI filter which shows every conversation regardless of category.
- There is no separate `UNCATEGORIZED` value — messages that haven't been categorized yet simply carry `ALL`, which means they appear in the "All" tab and are not hidden from the user.

---

### Step 2 — Add `categoryId` Field to Realm Models

**Module:** `domain`
**Files to modify:**
- `domain/src/main/java/com/moez/QKSMS/model/Conversation.kt`
- `domain/src/main/java/com/moez/QKSMS/model/Message.kt`

**Change in `Conversation.kt`:** Add one field and one computed property:
```kotlin
@Index var categoryId: String = MessageCategory.ALL.name
```
Add computed property for convenience:
```kotlin
val category: MessageCategory
    get() = try { MessageCategory.valueOf(categoryId) } catch (e: IllegalArgumentException) { MessageCategory.ALL }
```

**Change in `Message.kt`:** Add the same field + computed property (needed so Phase 2 can query individual transaction messages without re-scanning):
```kotlin
@Index var categoryId: String = MessageCategory.ALL.name

val category: MessageCategory
    get() = try { MessageCategory.valueOf(categoryId) } catch (e: IllegalArgumentException) { MessageCategory.ALL }
```

**Why `@Index`?** Realm will use these as filter predicates frequently (tab switching), so indexing avoids full-table scans.

---

### Step 3 — Realm Migration: Schema Version 16

**Module:** `data`
**File to modify:** `data/src/main/java/com/moez/QKSMS/migration/QkRealmMigration.kt`

Bump `SCHEMA_VERSION` constant from `15` to `16`.

Add the migration block for version `15 → 16`:

```kotlin
if (version == 15L) {
    realm.schema.get("Conversation")
        ?.addField("categoryId", String::class.java, FieldAttribute.REQUIRED)
        ?.addIndex("categoryId")
        ?.transform { obj ->
            obj.setString("categoryId", MessageCategory.ALL.name)
        }

    realm.schema.get("Message")
        ?.addField("categoryId", String::class.java, FieldAttribute.REQUIRED)
        ?.addIndex("categoryId")
        ?.transform { obj ->
            obj.setString("categoryId", MessageCategory.ALL.name)
        }

    version++
}
```

**Important:** All existing conversations and messages get `ALL` by default. The first-run worker in Step 11 will update them to their correct specific categories.

---

### Step 4 — `SmsCategorizer` Interface

**Module:** `domain`
**File to create:** `domain/src/main/java/com/moez/QKSMS/categorization/SmsCategorizer.kt`

```kotlin
package dev.octoshrimpy.quik.categorization

import dev.octoshrimpy.quik.model.MessageCategory

interface SmsCategorizer {
    /**
     * Categorizes a single SMS given its sender address and body text.
     * @param address The sender address (phone number or alphanumeric sender ID, e.g. "VM-HDFCBK")
     * @param body    The full SMS body text
     * @return        The best-matching [MessageCategory]
     */
    fun categorize(address: String, body: String): MessageCategory
}
```

---

### Step 5 — `SmsCategorizerImpl` (Rule Engine)

**Module:** `data`
**File to create:** `data/src/main/java/com/moez/QKSMS/categorization/SmsCategorizerImpl.kt`

This is the core intellectual work of Phase 1. Implement a priority-ordered rule chain:

**Rule priority order (highest to lowest):**
1. OTP detection
2. Transaction detection (sender ID + amount keywords)
3. Promotional detection
4. Update detection
5. Spam detection
6. Default → PERSONAL

**Key regex patterns to implement:**

```
OTP patterns:
  - \b(otp|one.?time.?password|verification.?code|auth.?code|passcode)\b.*\b\d{4,8}\b
  - \b\d{4,8}\b.*(is your|as your).*(otp|code|password|pin)
  - your\s+(otp|code|pin)\s+(is|:)\s*\d{4,8}
  → Returns MessageCategory.OTP

Transaction sender ID patterns (Regex on address):
  - \b[A-Z]{2}-[A-Z]{3,8}(BK|BNK|FIN|PAY|UPI|BANK)\b  (e.g. VM-HDFCBK, AM-ICICIB)
  - Known sender keywords: HDFCBK, ICICIB, SBIINB, AXISBK, PNBSMS, IDFCBK, KOTAKB, YESBNK,
    PAYTMB, GPAY, PHONEPE, AMZNPAY, MOBIKWK
  - Body transaction keywords: debited|credited|payment|transferred|withdrawn|deposited|
    spent|received|refund|EMI|mandate|auto.?debit
  - Combined: sender IS known bank/payment sender OR (sender looks like XX-XXXXXX AND body has ₹/Rs/INR amount)
  → Returns MessageCategory.TRANSACTIONS

Promotional patterns:
  - Body keywords: \b(off|discount|sale|offer|coupon|cashback|promo|deal|flat\s+\d+%|
    use\s+code|apply\s+code|hurry|limited.?time|expires?|valid\s+till)\b
  - Sender patterns: AD-*, DM-*, VM-* with no bank suffix
  → Returns MessageCategory.PROMOS

Update patterns:
  - Body keywords: \b(order|delivery|shipped|dispatched|ofd|out.?for.?delivery|
    your\s+appointment|booking\s+confirmed|ticket|boarded|flight|PNR|
    account.?update|statement|due\s+date|recharge)\b
  - Sender patterns: typically alphanumeric sender IDs not matching bank/promo patterns
  → Returns MessageCategory.UPDATES

Spam patterns:
  - Body keywords: \b(click\s+here|win|winner|prize|lottery|selected|
    claim\s+now|free\s+gift|loan\s+approved|get\s+rich|earn\s+from\s+home)\b
  - URL patterns combined with sender not in contacts
  → Returns MessageCategory.SPAM

Default:
  - Address is a plain phone number (all digits, possibly with +/- formatting)
  - OR none of the above rules match
  → Returns MessageCategory.PERSONAL
```

**Implementation notes:**
- All regex patterns are precompiled (`companion object { val OTP_REGEX = Regex(..., IGNORE_CASE) }`) at class load time to avoid recompilation overhead.
- The function is `@Singleton` (Dagger scope) so the compiled patterns live in memory.
- Inject with `@Inject constructor()` — no external dependencies needed.

---

### Step 6 — Add New Preferences

**Module:** `domain`
**File to modify:** `domain/src/main/java/com/moez/QKSMS/util/Preferences.kt`

Add two new preference keys inside the `Preferences` class:

```kotlin
val autoCategorize = rxPrefs.getBoolean("auto_categorize", true)
val categorizedV1Done = rxPrefs.getBoolean("categorized_v1_done", false)
```

- `autoCategorize`: Toggle shown in Settings → Categorisation section. When false, incoming messages are saved with `UNCATEGORIZED` and all tabs show everything.
- `categorizedV1Done`: One-time migration flag. Checked on app launch; when false, the background CategorizeAllWorker is enqueued.

---

### Step 7 — `CategorizeConversation` Interactor

**Module:** `domain`
**File to create:** `domain/src/main/java/com/moez/QKSMS/interactor/CategorizeConversation.kt`

```kotlin
class CategorizeConversation @Inject constructor(
    private val categorizer: SmsCategorizer,
    private val conversationRepo: ConversationRepository,
    private val messageRepo: MessageRepository
) : Interactor<Long>() {
    // Params: threadId: Long
    override fun buildObservable(params: Long): Completable {
        // 1. Load conversation + its last message body/address from Realm
        // 2. Call categorizer.categorize(address, body)
        // 3. Persist category on both the Conversation and the triggering Message
    }
}
```

This interactor is called by `ReceiveSmsWorker` for every new incoming message.

---

### Step 8 — `CategorizeAllMessages` Worker (First-Run)

**Module:** `data`
**File to create:** `data/src/main/java/com/moez/QKSMS/worker/CategorizeAllMessagesWorker.kt`

A `Worker` (WorkManager) that:
1. Opens a Realm instance.
2. Queries all `Conversation` objects where `categoryId == "ALL"` (i.e., not yet specifically categorized).
3. For each conversation, retrieves the most recent message body + sender address.
4. Calls `SmsCategorizer.categorize(address, body)`.
5. Writes the result back to `Conversation.categoryId` in a Realm transaction. If the categorizer returns `ALL` (no specific match), the conversation stays as `ALL` and will still appear in the "All" tab.
6. Also updates the `categoryId` on each `Message` in that thread to the same value (bulk update per thread).
7. When complete, sets `prefs.categorizedV1Done` to `true`.

**Constraints:** `Constraints.Builder().setRequiresBatteryNotLow(false).build()` — run immediately on first launch, no battery constraint, but run on `Schedulers.io()` via WorkManager's background thread.

**Enqueue in:** `QKApplication.onCreate()` — check `!prefs.categorizedV1Done.get()`, then enqueue as `OneTimeWorkRequest`.

---

### Step 9 — Wire Categorization Into `ReceiveSmsWorker`

**Module:** `data`
**File to modify:** `data/src/main/java/com/moez/QKSMS/worker/ReceiveSmsWorker.kt`

**Add injection:**
```kotlin
@Inject lateinit var categorizer: SmsCategorizer
```

After the existing block that updates the conversation (`conversationRepo.updateConversations(...)`), add:

```kotlin
if (prefs.autoCategorize.get()) {
    val category = categorizer.categorize(message.address, message.body)
    // Write to Realm: set message.categoryId and conversation.categoryId
    Realm.getDefaultInstance().use { realm ->
        realm.executeTransaction {
            it.where(Message::class.java).equalTo("id", message.id).findFirst()
                ?.categoryId = category.name
            it.where(Conversation::class.java).equalTo("id", conversation.id).findFirst()
                ?.categoryId = category.name
        }
    }
}
```

This ensures every new incoming SMS is categorized at receive time.

---

### Step 10 — Update `ConversationRepository` Interface

**Module:** `domain`
**File to modify:** `domain/src/main/java/com/moez/QKSMS/repository/ConversationRepository.kt`

Add one new method:
```kotlin
fun getConversationsByCategory(
    category: MessageCategory,
    unreadAtTop: Boolean,
    archived: Boolean = false
): RealmResults<Conversation>
```

When `category == MessageCategory.ALL`, this should behave identically to `getConversations(unreadAtTop, archived)` — no category filter applied, all conversations returned.

---

### Step 11 — Implement `getConversationsByCategory` in `ConversationRepositoryImpl`

**Module:** `data`
**File to modify:** `data/src/main/java/com/moez/QKSMS/repository/ConversationRepositoryImpl.kt`

```kotlin
override fun getConversationsByCategory(
    category: MessageCategory,
    unreadAtTop: Boolean,
    archived: Boolean
): RealmResults<Conversation> {
    val query = Realm.getDefaultInstance()
        .where(Conversation::class.java)
        .equalTo("archived", archived)
        .equalTo("blocked", false)

    if (category != MessageCategory.ALL) {
        query.equalTo("categoryId", category.name)
    }

    return if (unreadAtTop) {
        query.sort(
            arrayOf("pinned", "unread", "date"),
            arrayOf(Sort.DESCENDING, Sort.DESCENDING, Sort.DESCENDING)
        ).findAllAsync()
    } else {
        query.sort(arrayOf("pinned", "date"), arrayOf(Sort.DESCENDING, Sort.DESCENDING))
            .findAllAsync()
    }
}
```

---

### Step 12 — Update `MainState`

**Module:** `presentation`
**File to modify:** `presentation/src/main/java/com/moez/QKSMS/feature/main/MainState.kt`

**Changes:**

1. Add `activeCategory: MessageCategory = MessageCategory.ALL` to the `Inbox` data class (and `Archived` if needed).
2. Remove the `Archived` page from being a main nav item (since archived is now accessible differently or via Settings in Phase 1 — to be confirmed). For Phase 1, the bottom nav only has Messages, Finance, Settings, so Archived is removed from primary navigation.

Updated `MainState.kt`:
```kotlin
data class MainState(
    val hasError: Boolean = false,
    val page: MainPage = Inbox(),
    val upgraded: Boolean = true,
    val showRating: Boolean = false,
    val syncing: SyncRepository.SyncProgress = SyncRepository.SyncProgress.Idle,
    val defaultSms: Boolean = true,
    val smsPermission: Boolean = true,
    val contactPermission: Boolean = true,
    val notificationPermission: Boolean = true,
)

sealed class MainPage

data class Inbox(
    val activeCategory: MessageCategory = MessageCategory.ALL,
    val addContact: Boolean = false,
    val markPinned: Boolean = true,
    val markRead: Boolean = false,
    val data: RealmResults<Conversation>? = null,
    val selected: Int = 0
) : MainPage()

data class Searching(
    val loading: Boolean = false,
    val data: List<SearchResult>? = null
) : MainPage()

// Finance page — placeholder for Phase 2
object Finance : MainPage()
```

**Note:** `Archived` page and `NavItem.ARCHIVED` are removed from `MainState` since the NavigationDrawer is removed. Archive functionality moves to a contextual action (long-press menu) in Phase 1.

---

### Step 13 — Update `MainView` Interface

**Module:** `presentation`
**File to modify:** `presentation/src/main/java/com/moez/QKSMS/feature/main/MainView.kt`

Add new intent streams needed for the new UI:
```kotlin
val categoryTabSelectedIntent: Observable<MessageCategory>
val bottomNavSelectedIntent: Observable<Int>  // item IDs: R.id.nav_messages, R.id.nav_finance, R.id.nav_settings
val moveToCategoryIntent: Observable<Pair<Long, MessageCategory>>  // threadId to new category
```

Remove intents tied to the old NavigationDrawer (`navigationIntent`, `drawerToggledIntent`, `homeIntent`).

---

### Step 14 — Update `MainViewModel`

**Module:** `presentation`
**File to modify:** `presentation/src/main/java/com/moez/QKSMS/feature/main/MainViewModel.kt`

**Inject new dependency:**
```kotlin
@Inject lateinit var conversationRepo: ConversationRepository
```
(Already injected — just confirm it's available)

**Add new intent bindings in `bindView()`:**

```kotlin
// Category tab selection
view.categoryTabSelectedIntent
    .autoDisposable(view.scope())
    .subscribe { category ->
        val page = state.page
        if (page is Inbox) {
            newState { copy(page = page.copy(activeCategory = category)) }
            loadConversationsForCategory(category)
        }
    }

// Move to category (from long-press context menu)
view.moveToCategoryIntent
    .autoDisposable(view.scope())
    .subscribe { (threadId, newCategory) ->
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction {
                it.where(Conversation::class.java).equalTo("id", threadId).findFirst()
                    ?.categoryId = newCategory.name
            }
        }
    }

// Bottom nav selection
view.bottomNavSelectedIntent
    .autoDisposable(view.scope())
    .subscribe { itemId ->
        when (itemId) {
            R.id.nav_messages -> newState { copy(page = Inbox()) }
            R.id.nav_finance  -> newState { copy(page = Finance) }
            R.id.nav_settings -> navigator.showSettings()
        }
    }
```

**Private helper:**
```kotlin
private fun loadConversationsForCategory(category: MessageCategory) {
    val data = conversationRepo.getConversationsByCategory(
        category, prefs.unreadAtTop.get()
    )
    newState { copy(page = (page as? Inbox)?.copy(data = data) ?: page) }
}
```

**Remove** all `NavItem`-related code, drawer toggle handling, and `ChangelogDialog` forcing (or defer to settings).

---

### Step 15 — Redesign `activity_main.xml`

**Module:** `presentation`
**File to modify:** `presentation/src/main/res/layout/activity_main.xml`

**Remove:** `DrawerLayout`, `NavigationView`, all drawer-related views.

**New structure:**
```xml
<androidx.constraintlayout.widget.ConstraintLayout>

    <!-- Top header bar: "Messages" title + search icon + overflow icon -->
    <LinearLayout android:id="@+id/inbox_head" ... >
        <TextView android:id="@+id/inbox_title" android:text="Messages" />
        <ImageButton android:id="@+id/search_icon" />
        <ImageButton android:id="@+id/overflow_icon" />
    </LinearLayout>

    <!-- Inline search bar (visible when search is active) -->
    <LinearLayout android:id="@+id/search_bar" android:visibility="gone" ... >
        <ImageView />  <!-- search icon -->
        <EditText android:id="@+id/toolbar_search" android:hint="Search messages…" />
    </LinearLayout>

    <!-- Horizontal scrollable category tabs -->
    <HorizontalScrollView android:id="@+id/category_tabs_scroll"
        android:scrollbars="none" ... >
        <LinearLayout android:id="@+id/category_tabs_container"
            android:orientation="horizontal" ... />
            <!-- Tabs populated programmatically from MessageCategory values -->
    </HorizontalScrollView>

    <!-- Thin divider below tabs -->
    <View android:id="@+id/tabs_divider" android:background="@color/border" ... />

    <!-- Conversation list (full height, above bottom nav) -->
    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/recycler" ... />

    <!-- Finance placeholder (shown when Finance tab selected) -->
    <LinearLayout android:id="@+id/finance_placeholder"
        android:visibility="gone" ... >
        <TextView android:text="Finance — Coming in Phase 2" />
    </LinearLayout>

    <!-- Syncing overlay (existing pattern from MainSyncingBinding) -->
    <ViewStub android:id="@+id/syncing_stub" ... />

    <!-- Permission hint snackbar (existing pattern) -->
    <ViewStub android:id="@+id/permission_hint_stub" ... />

    <!-- Bottom Navigation Bar -->
    <com.google.android.material.bottomnavigation.BottomNavigationView
        android:id="@+id/bottom_nav"
        app:menu="@menu/bottom_nav_menu" ... />

    <!-- FAB (compose) — positioned above bottom nav -->
    <com.google.android.material.floatingactionbutton.FloatingActionButton
        android:id="@+id/compose" ... />

</androidx.constraintlayout.widget.ConstraintLayout>
```

---

### Step 16 — Create Bottom Navigation Menu Resource

**Module:** `presentation`
**File to create:** `presentation/src/main/res/menu/bottom_nav_menu.xml`

```xml
<menu>
    <item android:id="@+id/nav_messages"
          android:icon="@drawable/ic_message"
          android:title="Messages" />
    <item android:id="@+id/nav_finance"
          android:icon="@drawable/ic_finance"
          android:title="Finance" />
    <item android:id="@+id/nav_settings"
          android:icon="@drawable/ic_person"
          android:title="Settings" />
</menu>
```

**Also create vector drawables:**
- `ic_finance.xml` — dollar/rupee sign icon (matching HTML's `<line x1="12" y1="1" x2="12" y2="23"/>` rupee icon)
- Re-use existing `ic_message_black_24dp.xml` and `ic_settings_black_24dp.xml` from current resources, or create new ones matching the HTML design (chat bubble, person icon).

---

### Step 17 — Rewrite `MainActivity.kt`

**Module:** `presentation`
**File to modify:** `presentation/src/main/java/com/moez/QKSMS/feature/main/MainActivity.kt`

**Remove:**
- All `DrawerLayout`/`NavigationView` binding and toggle code.
- `DrawerBadgesExperiment` injection and usage.
- `NavItem` sealed class handling (all nav item clicks).
- All references to `binding.drawer.*`.
- `ActionBarDrawerToggle`.

**Add:**

1. **Category tabs setup** — in `onViewCreated`/`onCreate`, programmatically create one `TextView` tab per `MessageCategory` value (excluding `ALL` which becomes "All"), add to `category_tabs_container`. Style active tab with accent underline.

2. **Tab click intent stream:**
```kotlin
override val categoryTabSelectedIntent: Observable<MessageCategory> by lazy {
    categoryTabSubject  // PublishSubject<MessageCategory> emitted on tab TextView click
}
```

3. **Bottom nav intent:**
```kotlin
override val bottomNavSelectedIntent: Observable<Int> by lazy {
    binding.bottomNav.itemSelected()  // RxBinding for BottomNavigationView
}
```

4. **Move to Category:**
   - In `ConversationItemTouchCallback` or `ConversationsAdapter` long-press callback, emit into `moveToCategorySubject: Subject<Pair<Long, MessageCategory>>`.
   - Show a `MaterialAlertDialog` or `BottomSheetDialog` listing all categories.

5. **`render(state: MainState)` update:**
```kotlin
override fun render(state: MainState) {
    when (val page = state.page) {
        is Inbox -> {
            binding.recycler.isVisible = true
            binding.financePlaceholder.isVisible = false
            page.data?.let { conversationsAdapter.updateData(it) }
            updateActiveCategoryTab(page.activeCategory)
        }
        is Finance -> {
            binding.recycler.isVisible = false
            binding.financePlaceholder.isVisible = true
        }
        is Searching -> {
            // existing search rendering
        }
    }
    // Sync state, permission hints — keep existing logic
}
```

---

### Step 18 — Update `ConversationsAdapter` to Show Category Badges

**Module:** `presentation`
**File to modify:** `presentation/src/main/java/com/moez/QKSMS/feature/conversations/ConversationsAdapter.kt`

In the `onBindViewHolder` / `bind()` method:

1. Get `conversation.category` from the bound item.
2. Show a colored chip/tag below the sender name for non-PERSONAL, non-UNCATEGORIZED categories:
   - `TRANSACTIONS` → green background pill (`#E6F4EA` bg, `#155731` text) — example text from snippet or "Transaction"
   - `OTP` → purple background pill
   - `UPDATES` → blue background pill
   - `PROMOS` → amber background pill
   - `SPAM` → red background pill
3. The category badge `TextView` is a new view in `list_item_conversation.xml`. Set `visibility = VISIBLE` when category is not `ALL`, `GONE` otherwise (plain personal conversations and un-yet-categorized messages need no badge).

**Update `list_item_conversation.xml`:**
Add a `TextView` (`@+id/category_badge`) between the sender name row and the snippet row. Style with `background` as a drawable with rounded corners (`4dp`), appropriate `textColor`, small `textSize` (10sp), horizontal padding `6dp`, vertical padding `2dp`.

---

### Step 19 — Update `ComposeActivity` Thread Header: Category Badge

**Module:** `presentation`
**File to modify:** `presentation/src/main/java/com/moez/QKSMS/feature/compose/ComposeActivity.kt`

In the thread header, after the contact name, display the conversation's category as a small badge (matching the HTML Thread screen: `<div class="thread-badge" style="background:var(--green-bg);color:#155731;">Transaction</div>`).

Fetch the category via `conversationRepo.getConversationAsync(threadId).category` and display it in a new `TextView` (`@+id/category_badge`) in the existing thread header layout (`compose_activity.xml` toolbar area).

No parsed financial cards in bubbles — those are Phase 2.

---

### Step 20 — "Move to Category" Long-Press Context Menu

**Module:** `presentation`
**File to modify:** `presentation/src/main/java/com/moez/QKSMS/feature/conversations/ConversationsAdapter.kt`

In the existing selection/context menu handling (triggered by long-press), add a new menu option: **"Move to Category"**.

When selected:
1. Show an `AlertDialog` or `BottomSheetDialogFragment` listing all `MessageCategory` values except `ALL` as radio buttons (PERSONAL, TRANSACTIONS, OTP, UPDATES, PROMOS, SPAM).
2. On confirmation, emit `(threadId, selectedCategory)` into the `moveToCategoryIntent` observable.

The `MainViewModel` handles the Realm write (see Step 14).

After the write, the conversation automatically moves out of the current filtered tab's list (because Realm `RealmResults` auto-updates with `findAllAsync()`).

---

### Step 21 — New Settings Screen (Partial — Phase 1 Scope)

The current `SettingsActivity` + `SettingsController` covers all existing QUIK settings. In Phase 1, the Settings **tab** in the bottom nav navigates to a new `SettingsActivity` (or reuses the existing one). The goal is to restructure the visual design to match the HTML mockup, but only implementing the Phase 1 sections.

**Phase 1 settings sections:**

| Section | Items |
|---|---|
| **Messaging** | Default SMS App (existing), Delivery Reports (existing), Character Counter (existing), Group Messaging (existing) |
| **Categorisation** | Auto-categorise toggle (new pref `prefs.autoCategorize`), Blocked senders (links to existing `BlockingActivity`) |
| **Appearance** | Theme (existing), Text size (existing) |

**Implementation approach:**

Rather than a full rewrite of `SettingsController`, create a new `SettingsController` layout (`controller_settings.xml`) that uses `RecyclerView` with a custom `SettingsSectionAdapter` or plain `LinearLayout` with `ViewGroup` sections — matching the HTML's grouped card style (`border-radius: 12px`, border, grouped rows).

Each settings row is an `include` of a shared `item_settings_row.xml` layout (icon + title + subtitle + right element which can be a toggle, chevron, or value badge).

**Files:**
- Modify `presentation/src/main/res/layout/controller_settings.xml` (or create a new variant)
- Modify `SettingsController.kt` to render the new sections and wire Phase 1 items
- Create `item_settings_row.xml` layout

---

### Step 22 — DI Wiring

**Module:** `presentation`
**File to modify:** `presentation/src/main/java/com/moez/QKSMS/injection/AppModule.kt`

Add bindings:
```kotlin
@Binds abstract fun bindSmsCategorizer(impl: SmsCategorizerImpl): SmsCategorizer
```

**File to modify:** `presentation/src/main/java/com/moez/QKSMS/injection/android/ActivityBuilderModule.kt`

No new Activities are added in Phase 1 — existing `MainActivity` and `SettingsActivity` are modified in place. However, if a new `FinancePlaceholderActivity` is created (unlikely — the placeholder is an in-screen view), register it here.

**Worker injection:**
- Register `CategorizeAllMessagesWorker` in `InjectionWorkerFactory` (already used by `ReceiveSmsWorker`).

---

### Step 23 — Remove Archived / Drawer-Only Nav Items

**Module:** `presentation`
**Files to modify:**
- `MainView.kt` — remove `navigationIntent: Observable<NavItem>`, `drawerToggledIntent`, `homeIntent`
- `MainActivity.kt` — remove all references to `binding.drawer.*`, `DrawerLayout`, `ActionBarDrawerToggle`
- `MainViewModel.kt` — remove `NavItem` when block
- `MainState.kt` — remove `drawerOpen: Boolean` field, remove `Archived` as a primary `MainPage`

**What happens to Archived conversations?**
Archived conversations are still accessible via the `ConversationRepository.getConversations(archived = true)` query. In Phase 1, provide access via the **overflow (⋮) menu** in the Messages screen header, with an "Archived" menu item that shows a filtered list in the same RecyclerView (temporary simple approach).

---

### Step 24 — Unit Tests for `SmsCategorizerImpl`

**Module:** `data`
**File to create:** `data/src/androidTest/java/com/moez/QKSMS/categorization/SmsCategorizerImplTest.kt`

Write parameterized tests for each category using realistic Indian SMS samples:

```
OTP tests:
  "VM-SBIINB: Your OTP is 847291. Do not share. Valid for 10 min."
  → expect OTP

  "AM-PAYTMB: 394821 is your OTP for login. OTP expires in 2 min."
  → expect OTP

Transaction tests:
  "VM-HDFCBK: Acct XX4821 debited Rs.4,200.00 on 23-May via UPI ref 428192811. Avl Bal Rs.38,421."
  → expect TRANSACTIONS

  "AM-ICICIB: INR 12,000.00 credited to Acct XX9934 via NEFT. Avl Bal: INR 50,821.00"
  → expect TRANSACTIONS

Promo tests:
  "AD-SWIGGY: Get 50% off your next order. Use code SAVE50. Valid till tonight!"
  → expect PROMOS

Update tests:
  "VM-FKFLIP: Your Flipkart order #FKrt12345 is out for delivery. Expected: Today by 7pm."
  → expect UPDATES

Personal tests:
  "Hey bhai aaj aayega na? Let me know."  (from +91 98765 43210)
  → expect PERSONAL

Spam tests:
  "Congratulations! You have WON a prize. Click here to claim: http://bit.ly/xxx"
  → expect SPAM
```

Aim for a minimum of 30 test cases across all categories before considering the engine stable.

---

### Step 25 — End-to-End Verification Checklist

Before considering Phase 1 complete:

- [ ] `./gradlew assembleDebug` builds without errors
- [ ] Navigation Drawer is gone; Bottom Nav bar shows Messages | Finance | Settings
- [ ] Messages screen: All 7 tabs visible (All, Personal, Transactions, OTP, Updates, Promos, Spam), horizontally scrollable
- [ ] Switching tabs filters the conversation list correctly via Realm `RealmResults`
- [ ] New incoming SMS is categorized within `ReceiveSmsWorker` and appears in the correct tab
- [ ] Conversations show colored category badge in the list (green=Transactions, purple=OTP, etc.)
- [ ] Thread screen shows category badge in the header next to contact name
- [ ] Long-press on a conversation shows "Move to Category" option; selecting a different category moves the conversation to the correct tab immediately
- [ ] Finance tab shows the placeholder view
- [ ] Settings tab shows new layout with Messaging + Categorisation + Appearance sections
- [ ] Auto-categorise toggle in Settings works: when toggled off, new messages land in `ALL` (appear only in the "All" tab, no specific category badge)
- [ ] First-run: On fresh install (or after clearing app data), all historical messages get categorized by the background worker without any special UI shown
- [ ] Realm migration: Upgrading from schema v15 to v16 does not crash or lose data
- [ ] Unit tests for `SmsCategorizerImpl` pass with the 30+ test cases

---

## Phase 2: Finance Dashboard & Parsing
*(Detailed plan to be created after Phase 1 is shipped.)*

1.  **Regex Parsing Templates:** Create a library of Regex patterns for 12+ targeted Indian banks/services. Extract: Amount, Transaction Type (Debit/Credit), Merchant, Account Number/Last 4 digits, Date, Available Balance.
2.  **Database Updates:** Create new Realm models `ParsedTransaction` and `AccountBalance`.
3.  **Finance Dashboard UI:** Implement the Finance screen (Phone 3 in HTML mockup): Summary card (Spent/Received this month), Accounts section, Upcoming Bills/reminders.
4.  **Thread screen parsed cards:** Add the embedded debit/credit detected cards inside message bubbles in `ComposeActivity`.

---

## Phase 3: OTP Intelligence
*(Detailed plan to be created after Phase 2 is shipped.)*

1.  **OTP Detection refinement:** Add more precise rules to the categorization engine for edge-case OTP formats.
2.  **UI Enhancements:** Extract OTP string; show a prominent inline "Copy" button in the message list and in the Android notification.
3.  **Auto-expiry:** Logic to auto-delete or hide OTPs after a configurable duration (7 days default, as shown in Settings > OTP > Auto-delete OTPs).
4.  **OTP notification timeout:** Auto-dismiss OTP notifications after a configurable duration (10 min default).
5.  **Custom Rules UI:** Settings > Categorisation > Custom Rules sub-page — let users create keyword/sender rules that override the built-in engine.
6.  **Settings OTP Section:** Wire OTP settings rows shown in HTML mockup.

---

## Verification Plan

### Testing Dataset & Demo SMS Collection
*   **Collect Test Datasets:** Gather or generate standard, anonymized test SMS datasets for Indian banking formats (HDFC, ICICI, SBI, etc.), transactional notifications, OTPs, updates, promotions, and spam.
*   **User-Provided Real SMS Data:** The user will export and provide the SMS history from their personal device. This dataset will serve as the primary source for manual verification of real-world bank SMS strings and categorization accuracy.
*   **Privacy Guard:** Ensure any user-provided dataset is stored securely on the test device/local workspace and is used strictly for offline verification without any cloud logging or tracking.

### Automated Tests
*   **Unit Tests:** Thoroughly test the `SmsCategorizerImpl` and the Regex Parsing templates with the gathered datasets and dummy Indian SMS strings to ensure high accuracy and zero regressions.

### Manual Verification
*   Compile the APK and install it on an Android device with an active SIM or import the user's exported SMS history database.
*   Verify the initial sync correctly categorizes historical messages via the background worker.
*   Verify new incoming messages are routed to the correct tabs.
*   Verify the Finance dashboard (Phase 2) accurately reflects the parsed data from the transaction SMS.
