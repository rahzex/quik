/*
 * Copyright (C) 2026 QUIK
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
package dev.octoshrimpy.quik.interactor

import dev.octoshrimpy.quik.categorization.SmsCategorizer
import dev.octoshrimpy.quik.model.Conversation
import dev.octoshrimpy.quik.model.Message
import io.reactivex.Flowable
import io.realm.Realm
import javax.inject.Inject

/**
 * Interactor (use-case) that categorizes a single conversation and persists the result.
 *
 * WHAT IS AN INTERACTOR?
 * In Clean Architecture an "Interactor" (also called "Use Case") represents a single
 * business action. It lives in the `domain` layer and orchestrates data reads/writes
 * through Repository interfaces — never touching Android framework classes directly.
 *
 * WHY IS THIS SEPARATE FROM ReceiveSmsWorker?
 * Workers are Android platform classes — they can't easily be unit-tested or reused.
 * By extracting the categorize-and-save logic into this Interactor, we can:
 *   - Call it from [ReceiveSmsWorker] for real-time categorization
 *   - Reuse the same logic from [CategorizeAllMessagesWorker] during batch processing
 *   - Unit-test it without an Android device (just inject a fake SmsCategorizer)
 *
 * Params = threadId (Long): the Realm primary key of the Conversation to categorize.
 *
 * The base [Interactor] class handles scheduling on Schedulers.io() automatically.
 */
class CategorizeConversation @Inject constructor(
    private val categorizer: SmsCategorizer
) : Interactor<Long>() {

    /**
     * Builds the RxJava [Flowable] that performs the actual work.
     *
     * Steps:
     * 1. Open a Realm instance (scoped to this function via `use {}`)
     * 2. Find the Conversation by threadId
     * 3. Find the most recent incoming Message in that thread to get sender + body
     * 4. Ask the categorizer for the best category
     * 5. Write the category back to both Conversation and all its Messages in Realm
     *
     * WHY Realm.getDefaultInstance().use {}?
     * Realm instances are thread-local. We open one here (on the IO thread because
     * the base Interactor subscribes on Schedulers.io) and close it in `use {}` to
     * avoid memory leaks.
     */
    override fun buildObservable(params: Long): Flowable<*> = Flowable.fromCallable {
        // `use {}` is Kotlin's try-with-resources — Realm is closed when the block exits
        Realm.getDefaultInstance().use { realm ->

            // Retrieve the Conversation object for this thread from Realm.
            // findFirst() returns null if the conversation doesn't exist yet.
            val conversation = realm.where(Conversation::class.java)
                .equalTo("id", params)
                .findFirst()
                ?: return@fromCallable  // nothing to do if conversation not found

            // Find the most recent incoming message to use its address + body for
            // categorization. We prefer incoming messages (not "me") because outgoing
            // messages are written by the user, not the sender we're categorizing.
            val lastIncoming = realm.where(Message::class.java)
                .equalTo("threadId", params)
                .equalTo("read", true)  // any read message is stable; avoids race conditions
                .sort("date", io.realm.Sort.DESCENDING)
                .findFirst()

            // Fall back to the conversation's last message (could be outgoing/draft)
            // if no incoming message exists (e.g. freshly created outgoing conversation)
            val referenceMessage = lastIncoming
                ?: conversation.lastMessage
                ?: return@fromCallable  // no messages at all → skip

            // Ask the categorizer engine for the best category
            val category = categorizer.categorize(
                address = referenceMessage.address,
                body    = referenceMessage.body
            )

            // Write to Realm inside a transaction.
            // Realm requires ALL writes to happen inside executeTransaction { }.
            // The lambda receives a managed Realm instance — changes are committed
            // atomically when the lambda returns without throwing.
            realm.executeTransaction { r ->
                // Update the Conversation's category.
                // `.apply {}` is used instead of `?.field = value` because in Kotlin,
                // null-safe assignment (?.field = value) is not valid syntax.
                // `.apply {}` runs the block only if the object is non-null.
                r.where(Conversation::class.java)
                    .equalTo("id", params)
                    .findFirst()
                    ?.apply { categoryId = category.name }

                // Update all Messages in this thread
                r.where(Message::class.java)
                    .equalTo("threadId", params)
                    .findAll()
                    .forEach { it.categoryId = category.name }
            }
        }
    }
}


