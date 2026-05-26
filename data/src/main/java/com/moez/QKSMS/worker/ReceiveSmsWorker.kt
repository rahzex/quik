/*
 * Copyright (C) 2025
 *
 * This file is part of QUIK.
 *
 * QUIK is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QUIK is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QUIK.  If not, see <http://www.gnu.org/licenses/>.
 */
package dev.octoshrimpy.quik.worker

import android.content.Context
import androidx.work.ForegroundInfo
import androidx.work.Worker
import androidx.work.WorkerParameters
import dev.octoshrimpy.quik.blocking.BlockingClient
import dev.octoshrimpy.quik.categorization.SmsCategorizer
import dev.octoshrimpy.quik.categorization.TransactionParser
import dev.octoshrimpy.quik.interactor.UpdateBadge
import dev.octoshrimpy.quik.manager.NotificationManager
import dev.octoshrimpy.quik.manager.ShortcutManager
import dev.octoshrimpy.quik.model.Conversation
import dev.octoshrimpy.quik.model.Message
import dev.octoshrimpy.quik.model.MessageCategory
import dev.octoshrimpy.quik.repository.ContactRepository
import dev.octoshrimpy.quik.repository.ConversationRepository
import dev.octoshrimpy.quik.repository.FinanceRepository
import dev.octoshrimpy.quik.repository.MessageContentFilterRepository
import dev.octoshrimpy.quik.repository.MessageRepository
import dev.octoshrimpy.quik.util.Preferences
import io.realm.Realm
import timber.log.Timber
import javax.inject.Inject

class ReceiveSmsWorker(appContext: Context, workerParams: WorkerParameters)
    : Worker(appContext, workerParams) {
    companion object {
        const val INPUT_DATA_KEY_MESSAGE_ID = "messageId"
    }

    @Inject lateinit var conversationRepo: ConversationRepository
    @Inject lateinit var blockingClient: BlockingClient
    @Inject lateinit var prefs: Preferences
    @Inject lateinit var messageRepo: MessageRepository
    @Inject lateinit var notificationManager: NotificationManager
    @Inject lateinit var updateBadge: UpdateBadge
    @Inject lateinit var shortcutManager: ShortcutManager
    @Inject lateinit var filterRepo: MessageContentFilterRepository
    @Inject lateinit var contactsRepo: ContactRepository

    /**
     * Injected by InjectionWorkerFactory. Used to determine the category
     * of every new incoming message in real time.
     */
    lateinit var categorizer: SmsCategorizer

    /**
     * Phase 2: Extracts structured financial data from TRANSACTIONS messages.
     * Injected by InjectionWorkerFactory.
     */
    lateinit var transactionParser: TransactionParser
    lateinit var financeRepo: FinanceRepository

    override fun doWork(): Result {
        Timber.v("started")

        val messageId = inputData.getLong(INPUT_DATA_KEY_MESSAGE_ID, -1)
        if (messageId < 0) {
            Timber.v("failed. message id was {messageId}")
            return Result.failure(inputData)
        }

        val message = messageRepo.getMessage(messageId) ?: return Result.failure(inputData)

        val action = blockingClient.shouldBlock(message.address).blockingGet()

        when {
            ((action is BlockingClient.Action.Block) && prefs.drop.get()) -> {
                // blocked and 'drop blocked' remove from db and don't continue
                Timber.v("address is blocked and drop blocked is on. dropped")
                messageRepo.deleteMessages(listOf(message.id))
                return Result.failure(inputData)
            }

            action is BlockingClient.Action.Block -> {
                // blocked
                Timber.v("address is blocked")
                messageRepo.markRead(listOf(message.threadId))
                conversationRepo.markBlocked(
                    listOf(message.threadId),
                    prefs.blockingManager.get(),
                    action.reason
                )
            }

            action is BlockingClient.Action.Unblock -> {
                // unblock
                Timber.v("unblock conversation if blocked")
                conversationRepo.markUnblocked(message.threadId)
            }
        }

        val messageFilterAction = filterRepo.isBlocked(message.getText(), message.address, contactsRepo)
        if (messageFilterAction) {
            Timber.v("message dropped based on content filters")
            messageRepo.deleteMessages(listOf(message.id))
            return Result.failure(inputData)
        }

        // update and fetch conversation
        conversationRepo.updateConversations(listOf(message.threadId))
        val conversation = conversationRepo.getOrCreateConversation(message.threadId)
            ?: return Result.failure(inputData)

        // don't notify (continue) for blocked conversations
        if (conversation.blocked) {
            Timber.v("no notifications for blocked")
            return Result.failure(inputData)
        }

        // unarchive conversation if necessary
        if (conversation.archived) {
            Timber.v("conversation unarchived")
            conversationRepo.markUnarchived(listOf(conversation.id))
        }

        // ── Categorize the incoming message ──────────────────────────────────────
        // Only runs when the user has auto-categorize enabled (default: true).
        //
        // We categorize AFTER unarchiving but BEFORE sending notifications so that
        // the notification shown to the user already reflects the correct category.
        //
        // Realm writes must happen on the thread that opened the Realm instance.
        // Since doWork() already runs on a background IO thread managed by WorkManager,
        // it's safe to do a synchronous Realm.getDefaultInstance() here.
        if (prefs.autoCategorize.get()) {
            val category = categorizer.categorize(
                address = message.address,
                body    = message.body
            )
            Timber.v("categorized as: $category")

            Realm.getDefaultInstance().use { realm ->
                realm.executeTransaction { r ->
                    // Update the individual message's category.
                    // `.apply {}` is used because `?.field = value` is not valid Kotlin —
                    // null-safe assignment on the left side of `=` is not supported.
                    r.where(Message::class.java)
                        .equalTo("id", message.id)
                        .findFirst()
                        ?.apply { categoryId = category.name }

                    // Update the parent conversation's category so the tab filter works
                    r.where(Conversation::class.java)
                        .equalTo("id", conversation.id)
                        .findFirst()
                        ?.apply { categoryId = category.name }
                }
            }
        }

        // ── Phase 2: Parse financial data from TRANSACTIONS messages ─────────
        if (prefs.autoCategorize.get()) {
            val msgCategory = try {
                MessageCategory.valueOf(
                    Realm.getDefaultInstance().use { r ->
                        r.where(Message::class.java).equalTo("id", message.id).findFirst()?.categoryId ?: ""
                    }
                )
            } catch (_: Exception) { null }

            if (msgCategory == MessageCategory.TRANSACTIONS) {
                transactionParser.parse(message.address, message.body)?.let { data ->
                    financeRepo.saveTransaction(data, message)
                    if (data.availableBalance > 0.0) {
                        financeRepo.updateAccountBalance(
                            senderAddress = message.address,
                            accountLast4  = data.accountLast4,
                            balance       = data.availableBalance,
                            ts            = message.date
                        )
                    }
                    Timber.v("finance: parsed ${if (data.isDebit) "debit" else "credit"} ₹${data.amount}")
                }
            }
        }

        // update/create notification
        Timber.v("update/create notification")
        notificationManager.update(conversation.id)

        // update shortcuts
        Timber.v("update shortcuts")
        shortcutManager.updateShortcuts()
        shortcutManager.getOrCreateShortcut(conversation.id)

        // update the badge and widget
        Timber.v("update badge and widget")
        updateBadge.execute(Unit)

        Timber.v("finished")

        return Result.success()
    }

    override fun getForegroundInfo() = ForegroundInfo(
        0,
        notificationManager.getForegroundNotificationForWorkersOnOlderAndroids()
    )

}
