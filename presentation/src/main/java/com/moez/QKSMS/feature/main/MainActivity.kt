/*
 * Copyright (C) 2017 Moez Bhatti <moez.bhatti@gmail.com>
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 */
package dev.octoshrimpy.quik.feature.main

import android.Manifest
import android.animation.ObjectAnimator
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProviders
import androidx.recyclerview.widget.ItemTouchHelper
import com.google.android.material.snackbar.Snackbar
import com.jakewharton.rxbinding2.view.clicks
import com.jakewharton.rxbinding2.widget.textChanges
import com.uber.autodispose.android.lifecycle.scope
import com.uber.autodispose.autoDisposable
import dagger.android.AndroidInjection
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.common.Navigator
import dev.octoshrimpy.quik.common.base.QkThemedActivity
import dev.octoshrimpy.quik.common.util.extensions.autoScrollToStart
import dev.octoshrimpy.quik.common.util.extensions.dismissKeyboard
import dev.octoshrimpy.quik.common.util.extensions.resolveThemeColor
import dev.octoshrimpy.quik.common.util.extensions.scrapViews
import dev.octoshrimpy.quik.common.util.extensions.setBackgroundTint
import dev.octoshrimpy.quik.common.util.extensions.setTint
import dev.octoshrimpy.quik.common.util.extensions.setVisible
import dev.octoshrimpy.quik.common.widget.TextInputDialog
import dev.octoshrimpy.quik.feature.blocking.BlockingDialog
import dev.octoshrimpy.quik.databinding.MainActivityBinding
import dev.octoshrimpy.quik.databinding.MainPermissionHintBinding
import dev.octoshrimpy.quik.databinding.MainSyncingBinding
import dev.octoshrimpy.quik.feature.changelog.ChangelogDialog
import dev.octoshrimpy.quik.feature.conversations.ConversationItemTouchCallback
import dev.octoshrimpy.quik.feature.conversations.ConversationsAdapter
import dev.octoshrimpy.quik.manager.ChangelogManager
import dev.octoshrimpy.quik.model.MessageCategory
import dev.octoshrimpy.quik.repository.SyncRepository
import io.reactivex.Observable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import javax.inject.Inject

class MainActivity : QkThemedActivity(), MainView {

    @Inject lateinit var blockingDialog: BlockingDialog
    @Inject lateinit var disposables: CompositeDisposable
    @Inject lateinit var navigator: Navigator
    @Inject lateinit var conversationsAdapter: ConversationsAdapter
    @Inject lateinit var searchAdapter: SearchAdapter
    @Inject lateinit var itemTouchCallback: ConversationItemTouchCallback
    @Inject lateinit var viewModelFactory: ViewModelProvider.Factory

    private lateinit var binding: MainActivityBinding
    private lateinit var snackbarBinding: MainPermissionHintBinding
    private lateinit var syncingBinding: MainSyncingBinding

    override val onNewIntentIntent: Subject<Intent> = PublishSubject.create()
    override val activityResumedIntent: Subject<Boolean> = PublishSubject.create()
    override val queryChangedIntent by lazy { binding.toolbarSearch.textChanges() }
    override val composeIntent by lazy { binding.compose.clicks() }
    override val optionsItemIntent: Subject<Int> = PublishSubject.create()
    override val dismissRatingIntent: Subject<Unit> = PublishSubject.create()
    override val rateIntent: Subject<Unit> = PublishSubject.create()
    override val conversationsSelectedIntent by lazy { conversationsAdapter.selectionChanges }
    override val confirmDeleteIntent: Subject<List<Long>> = PublishSubject.create()
    override val renameConversationIntent: Subject<String> = PublishSubject.create()
    override val swipeConversationIntent by lazy { itemTouchCallback.swipes }
    override val changelogMoreIntent by lazy { changelogDialog.moreClicks }
    override val undoArchiveIntent: Subject<Unit> = PublishSubject.create()
    override val snackbarButtonIntent: Subject<Unit> = PublishSubject.create()

    // Category tab subject
    private val categoryTabSubject: Subject<MessageCategory> = PublishSubject.create()
    override val categoryTabSelectedIntent: Observable<MessageCategory> by lazy { categoryTabSubject }

    // Bottom nav subject
    private val bottomNavSubject: Subject<Int> = PublishSubject.create()
    override val bottomNavSelectedIntent: Observable<Int> by lazy { bottomNavSubject }

    // Move to category subject
    private val moveToCategorySubject: Subject<Pair<Long, MessageCategory>> = PublishSubject.create()
    override val moveToCategoryIntent: Observable<Pair<Long, MessageCategory>> by lazy { moveToCategorySubject }

    private val viewModel by lazy {
        ViewModelProviders.of(this, viewModelFactory)[MainViewModel::class.java]
    }
    private val itemTouchHelper by lazy { ItemTouchHelper(itemTouchCallback) }
    private val progressAnimator by lazy {
        ObjectAnimator.ofInt(syncingBinding.syncingProgress, "progress", 0, 0)
    }
    private val changelogDialog by lazy { ChangelogDialog(this) }
    private val backPressedSubject: Subject<Unit> = PublishSubject.create()

    // Keep track of the active tab view for styling
    private var activeTabView: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidInjection.inject(this)
        super.onCreate(savedInstanceState)
        binding = MainActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        viewModel.bindView(this)
        onNewIntentIntent.onNext(intent)

        snackbarBinding = MainPermissionHintBinding.bind(binding.snackbar.inflate()).also {
            it.snackbarButton.clicks()
                .autoDisposable(scope(Lifecycle.Event.ON_DESTROY))
                .subscribe(snackbarButtonIntent)
        }

        syncingBinding = MainSyncingBinding.bind(binding.syncing.inflate()).also {
            it.syncingProgress.progressTintList = ColorStateList.valueOf(theme.blockingFirst().theme)
            it.syncingProgress.indeterminateTintList = ColorStateList.valueOf(theme.blockingFirst().theme)
        }

        // Overflow icon opens context popup menu
        binding.overflowIcon.setOnClickListener { view ->
            val popup = androidx.appcompat.widget.PopupMenu(this, view)
            menuInflater.inflate(R.menu.main, popup.menu)
            // Hide selection-specific items (no selection active from overflow)
            listOf(R.id.select_all, R.id.archive, R.id.unarchive, R.id.delete,
                R.id.add, R.id.pin, R.id.unpin, R.id.read, R.id.unread,
                R.id.block, R.id.rename).forEach { id ->
                popup.menu.findItem(id)?.isVisible = false
            }
            popup.setOnMenuItemClickListener { item ->
                optionsItemIntent.onNext(item.itemId)
                true
            }
            popup.show()
        }

        itemTouchCallback.adapter = conversationsAdapter
        conversationsAdapter.autoScrollToStart(binding.recyclerView)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)

        // Wire up bottom navigation
        binding.bottomNav.setOnNavigationItemSelectedListener { item ->
            bottomNavSubject.onNext(item.itemId)
            true
        }

        // Build category tabs programmatically
        setupCategoryTabs()

        // Theme tints
        theme
            .autoDisposable(scope())
            .subscribe { theme ->
                syncingBinding.syncingProgress.progressTintList = ColorStateList.valueOf(theme.theme)
                syncingBinding.syncingProgress.indeterminateTintList = ColorStateList.valueOf(theme.theme)
                binding.compose.setBackgroundTint(theme.theme)
                binding.compose.setTint(theme.textPrimary)
                // Update active tab underline color
                activeTabView?.setTextColor(theme.theme)
            }

        // Wire up move-to-category from adapter long-press
        conversationsAdapter.moveToCategoryRequest
            .autoDisposable(scope(Lifecycle.Event.ON_DESTROY))
            .subscribe { threadId ->
                showMoveToCategoryDialog(threadId)
            }
    }

    private fun setupCategoryTabs() {
        val categories = MessageCategory.values()
        val accentColor = 0xFF1a56db.toInt() // fixed accent blue matching HTML --accent and bottom nav
        val defaultColor = resolveThemeColor(android.R.attr.textColorSecondary)
        val displayNames = mapOf(
            MessageCategory.ALL          to "All",
            MessageCategory.PERSONAL     to "Personal",
            MessageCategory.TRANSACTIONS to "Transactions",
            MessageCategory.OTP          to "OTP",
            MessageCategory.UPDATES      to "Updates",
            MessageCategory.PROMOS       to "Promos",
            MessageCategory.SPAM         to "Spam"
        )

        categories.forEach { category ->
            val label = displayNames[category] ?: category.name
            val tab = TextView(this).apply {
                text = label
                setPadding(28, 0, 28, 0)
                setTextColor(defaultColor)
                textSize = 12f
                isAllCaps = false
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT
                )
                gravity = android.view.Gravity.CENTER
                // HTML: border-bottom: 2px solid transparent — use layer drawable for underline
                background = buildTabBackground(false, accentColor)
                setOnClickListener {
                    categoryTabSubject.onNext(category)
                }
            }
            binding.categoryTabsContainer.addView(tab)
        }
        // Activate the first (ALL) tab
        (binding.categoryTabsContainer.getChildAt(0) as? TextView)?.let { tab ->
            tab.setTextColor(accentColor)
            tab.background = buildTabBackground(true, accentColor)
            activeTabView = tab
        }
    }

    private fun buildTabBackground(active: Boolean, accentColor: Int): android.graphics.drawable.LayerDrawable {
        val underline = android.graphics.drawable.GradientDrawable().apply {
            setColor(if (active) accentColor else android.graphics.Color.TRANSPARENT)
        }
        val layer = android.graphics.drawable.LayerDrawable(arrayOf(underline))
        val dp2 = (2 * resources.displayMetrics.density).toInt()
        layer.setLayerInset(0, 0, dp2 * 20, 0, 0) // push underline to bottom 2dp strip
        return layer
    }

    private fun updateActiveCategoryTab(category: MessageCategory) {
        val accentColor = 0xFF1a56db.toInt() // fixed accent blue matching HTML --accent and bottom nav
        val defaultColor = resolveThemeColor(android.R.attr.textColorSecondary)
        val index = MessageCategory.values().indexOf(category)
        activeTabView?.let { prev ->
            prev.setTextColor(defaultColor)
            prev.background = buildTabBackground(false, accentColor)
        }
        val newActive = binding.categoryTabsContainer.getChildAt(index) as? TextView
        newActive?.let { tab ->
            tab.setTextColor(accentColor)
            tab.background = buildTabBackground(true, accentColor)
        }
        activeTabView = newActive
    }

    private fun showMoveToCategoryDialog(threadId: Long) {
        val categories = MessageCategory.values().filter { it != MessageCategory.ALL }
        val labels = categories.map { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Move to Category")
            .setItems(labels) { _, which ->
                moveToCategorySubject.onNext(Pair(threadId, categories[which]))
            }
            .setNegativeButton(R.string.button_cancel, null)
            .show()
    }

    override fun onNewIntent(intent: Intent?) =
        intent?.let {
            super.onNewIntent(intent)
            it.run(onNewIntentIntent::onNext)
        } ?: Unit

    override fun render(state: MainState) {
        if (state.hasError) {
            finish()
            return
        }

        val addContact = when (state.page) {
            is Inbox -> state.page.addContact
            is Archived -> state.page.addContact
            else -> false
        }

        val markPinned = when (state.page) {
            is Inbox -> state.page.markPinned
            is Archived -> state.page.markPinned
            else -> true
        }

        val markRead = when (state.page) {
            is Inbox -> state.page.markRead
            is Archived -> state.page.markRead
            else -> true
        }

        val selectedConversations = when (state.page) {
            is Inbox -> state.page.selected
            is Archived -> state.page.selected
            else -> 0
        }

        // In new layout, search bar is always visible; title row toggles based on selection
        binding.inboxHead.isVisible = state.page is Inbox || state.page is Searching || state.page is Archived
        binding.toolbarTitle.text = when {
            state.page is Inbox && state.page.selected > 0 ->
                getString(R.string.main_title_selected, state.page.selected)
            state.page is Archived && state.page.selected > 0 ->
                getString(R.string.main_title_selected, state.page.selected)
            state.page is Archived -> getString(R.string.title_archived)
            else -> getString(R.string.main_title_messages)
        }

        // Category tabs only visible on Inbox/Archived
        binding.categoryTabsScroll.isVisible = state.page is Inbox || state.page is Archived
        binding.tabsDivider.isVisible = true

        // Context action menu (shown when conversations selected)
        // These are surfaced via the overflowIcon popup in onCreate

        binding.compose.setVisible(state.page is Inbox || state.page is Archived)
        conversationsAdapter.emptyView = binding.empty.takeIf {
            state.page is Inbox || state.page is Archived
        }
        searchAdapter.emptyView = binding.empty.takeIf { state.page is Searching }

        when (state.page) {
            is Inbox -> {
                binding.recyclerView.isVisible = true
                binding.financePlaceholder.isVisible = false
                if (binding.recyclerView.adapter !== conversationsAdapter)
                    binding.recyclerView.adapter = conversationsAdapter
                conversationsAdapter.updateData(state.page.data)
                itemTouchHelper.attachToRecyclerView(binding.recyclerView)
                binding.empty.setText(R.string.inbox_empty_text)
                updateActiveCategoryTab(state.page.activeCategory)
            }

            is Searching -> {
                binding.recyclerView.isVisible = true
                binding.financePlaceholder.isVisible = false
                if (binding.recyclerView.adapter !== searchAdapter)
                    binding.recyclerView.adapter = searchAdapter
                searchAdapter.data = state.page.data ?: listOf()
                itemTouchHelper.attachToRecyclerView(null)
                binding.empty.setText(R.string.inbox_search_empty_text)
            }

            is Archived -> {
                binding.recyclerView.isVisible = true
                binding.financePlaceholder.isVisible = false
                if (binding.recyclerView.adapter !== conversationsAdapter)
                    binding.recyclerView.adapter = conversationsAdapter
                conversationsAdapter.updateData(state.page.data)
                itemTouchHelper.attachToRecyclerView(null)
                binding.empty.setText(R.string.archived_empty_text)
            }

            is Finance -> {
                binding.recyclerView.isVisible = false
                binding.financePlaceholder.isVisible = true
            }

            else -> {}
        }

        when (state.syncing) {
            is SyncRepository.SyncProgress.Idle -> {
                syncingBinding.root.isVisible = false
                snackbarBinding.root.isVisible = (!state.defaultSms ||
                        !state.smsPermission ||
                        !state.contactPermission ||
                        !state.notificationPermission)
            }

            is SyncRepository.SyncProgress.Running -> {
                syncingBinding.root.isVisible = true
                syncingBinding.syncingProgress.max = state.syncing.max
                progressAnimator.apply {
                    setIntValues(syncingBinding.syncingProgress.progress, state.syncing.progress)
                }.start()
                syncingBinding.syncingProgress.isIndeterminate = state.syncing.indeterminate
                snackbarBinding.root.isVisible = false
            }

            is SyncRepository.SyncProgress.ParsingEmojis -> {
                syncingBinding.root.isVisible = true
                syncingBinding.syncingLabel.setText(getString(R.string.main_sync_emojis))
                syncingBinding.syncingProgress.max = state.syncing.max
                progressAnimator.apply {
                    setIntValues(syncingBinding.syncingProgress.progress, state.syncing.progress)
                }.start()
                syncingBinding.syncingProgress.isIndeterminate = state.syncing.indeterminate
                snackbarBinding.root.isVisible = false
            }
        }

        when {
            !state.defaultSms -> {
                snackbarBinding.snackbarTitle.setText(R.string.main_default_sms_title)
                snackbarBinding.snackbarMessage.setText(R.string.main_default_sms_message)
                snackbarBinding.snackbarButton.setText(R.string.main_default_sms_change)
            }
            !state.smsPermission -> {
                snackbarBinding.snackbarTitle.setText(R.string.main_permission_required)
                snackbarBinding.snackbarMessage.setText(R.string.main_permission_sms)
                snackbarBinding.snackbarButton.setText(R.string.main_permission_allow)
            }
            !state.contactPermission -> {
                snackbarBinding.snackbarTitle.setText(R.string.main_permission_required)
                snackbarBinding.snackbarMessage.setText(R.string.main_permission_contacts)
                snackbarBinding.snackbarButton.setText(R.string.main_permission_allow)
            }
            !state.notificationPermission -> {
                snackbarBinding.snackbarTitle.setText(R.string.main_permission_required)
                snackbarBinding.snackbarMessage.setText(R.string.main_permission_notifications)
                snackbarBinding.snackbarButton.setText(R.string.main_permission_allow)
            }
        }
    }

    override fun onResume() =
        super.onResume().also { activityResumedIntent.onNext(true) }

    override fun onPause() =
        super.onPause().also { activityResumedIntent.onNext(false) }

    override fun onDestroy() =
        super.onDestroy().also { disposables.dispose() }

    override fun requestDefaultSms() =
        navigator.showDefaultSmsDialog(this)

    override fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CONTACTS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            permissions += Manifest.permission.POST_NOTIFICATIONS
        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 0)
    }

    override fun clearSearch() {
        dismissKeyboard()
        binding.toolbarSearch.text = null
    }

    override fun clearSelection() = conversationsAdapter.clearSelection()

    override fun toggleSelectAll() = conversationsAdapter.toggleSelectAll()

    override fun themeChanged() = binding.recyclerView.scrapViews()

    override fun showBlockingDialog(conversations: List<Long>, block: Boolean) {
        blockingDialog.show(this, conversations, block)
    }

    override fun showDeleteDialog(conversations: List<Long>) {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_delete_title)
            .setMessage(
                resources.getQuantityString(
                    R.plurals.dialog_delete_message,
                    conversations.size,
                    conversations.size
                )
            )
            .setPositiveButton(R.string.button_delete) { _, _ -> confirmDeleteIntent.onNext(conversations) }
            .setNegativeButton(R.string.button_cancel, null)
            .show()
    }

    override fun showRenameDialog(conversationName: String) =
        TextInputDialog(
            this,
            getString(R.string.info_name),
            renameConversationIntent::onNext
        )
            .setText(conversationName)
            .show()

    override fun showChangelog(changelog: ChangelogManager.CumulativeChangelog) =
        changelogDialog.show(changelog)

    override fun showArchivedSnackbar(countConversationsArchived: Int, isArchiving: Boolean) =
        Snackbar.make(
            binding.root,
            if (isArchiving) {
                resources.getQuantityString(R.plurals.toast_archived, countConversationsArchived, countConversationsArchived)
            } else {
                resources.getQuantityString(R.plurals.toast_unarchived, countConversationsArchived, countConversationsArchived)
            },
            if (countConversationsArchived < 10) Snackbar.LENGTH_LONG
            else Snackbar.LENGTH_INDEFINITE
        ).let {
            it.setAction(R.string.button_undo) { undoArchiveIntent.onNext(Unit) }
            it.setActionTextColor(colors.theme().theme)
            it.show()
        }

    override fun onBackPressed() {
        // If searching, clear search; otherwise default back
        if (binding.toolbarSearch.text?.isNotEmpty() == true) {
            clearSearch()
        } else {
            super.onBackPressed()
        }
    }
}
