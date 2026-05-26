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
package dev.octoshrimpy.quik.worker

import android.content.Context
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import dev.octoshrimpy.quik.categorization.SmsCategorizer
import dev.octoshrimpy.quik.model.Conversation
import dev.octoshrimpy.quik.model.Message
import dev.octoshrimpy.quik.model.MessageCategory
import dev.octoshrimpy.quik.util.Preferences
import io.realm.Realm
import io.realm.Sort
import timber.log.Timber

/**
 * One-time background worker that categorizes ALL existing historical messages.
 *
 * WHEN IS THIS TRIGGERED?
 * [QKApplication.onCreate] checks [Preferences.categorizedV1Done]. If it's false
 * (i.e. this is the first launch after Phase 1 is installed), it enqueues this worker.
 * After the worker finishes successfully, the flag is set to true and the worker
 * is never enqueued again.
 *
 * WHY WORKMANAGER INSTEAD OF A SIMPLE COROUTINE?
 * WorkManager is Android's recommended way to run guaranteed background work. It:
 *   - Survives app restarts (if the user kills the app mid-categorization, it resumes)
 *   - Respects battery optimization settings properly
 *   - Integrates with Dagger via [InjectionWorkerFactory]
 *
 * PERFORMANCE NOTE
 * This iterates every Conversation in Realm. For a user with thousands of messages
 * this could take a few seconds to a few minutes. Because it runs on a background
 * thread via WorkManager, the UI stays completely responsive.
 *
 * INJECTION
 * WorkManager workers cannot use constructor injection (WorkManager creates them via
 * reflection). Instead, [InjectionWorkerFactory] manually injects the lateinit fields
 * after creating the instance. See InjectionWorkerFactory.kt.
 */
class CategorizeAllMessagesWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : Worker(appContext, workerParams) {

    companion object {
        // Tag used to identify this work request in WorkManager's queue.
        // Using a tag lets us cancel or query the work later if needed.
        private const val WORKER_TAG = "categorize_all_messages_v3"

        /**
         * Enqueues this worker as a one-time background job.
         * Call this from [QKApplication] on first launch.
         *
         * OneTimeWorkRequestBuilder<T> creates a request that runs exactly once.
         * No constraints — we want this to run immediately so the inbox looks right
         * as soon as possible after the user opens the app.
         */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<CategorizeAllMessagesWorker>()
                .addTag(WORKER_TAG)
                .build()

            // enqueueUniqueWork ensures only one instance of this worker runs at a time,
            // even if enqueue() is accidentally called multiple times.
            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    WORKER_TAG,
                    androidx.work.ExistingWorkPolicy.KEEP, // don't restart if already running
                    request
                )

            Timber.d("CategorizeAllMessagesWorker: enqueued")
        }
    }

    // These are injected by InjectionWorkerFactory AFTER construction.
    // `lateinit` tells Kotlin "I promise this will be set before I use it."
    lateinit var categorizer: SmsCategorizer
    lateinit var prefs: Preferences

    override fun doWork(): Result {
        Timber.d("CategorizeAllMessagesWorker: started")

        // Open Realm on this background thread.
        // Each thread must open its own Realm instance — Realm is thread-local.
        Realm.getDefaultInstance().use { realm ->

            // Re-categorize ALL conversations so the new PERSONAL/ALL logic applies.
            // This is safe to do on every version bump — conversations get the right
            // category assigned and the worker won't run again until the next version.
            val uncategorized = realm
                .where(Conversation::class.java)
                .isNotEmpty("recipients")     // skip ghost conversations with no recipients
                .findAll()

            Timber.d("CategorizeAllMessagesWorker: ${uncategorized.size} conversations to process")

            // Process each conversation individually.
            // We use copyFromRealm() to get an unmanaged (plain Kotlin) list so we
            // can safely iterate while Realm is still open and performing other writes.
            val snapshot = realm.copyFromRealm(uncategorized)

            snapshot.forEach { conversation ->
                // Get the most recent incoming message for this thread.
                // We use the managed Realm query (not the snapshot) for the messages
                // because we only need the address + body strings, not full objects.
                val lastMessage = realm.where(Message::class.java)
                    .equalTo("threadId", conversation.id)
                    .sort("date", Sort.DESCENDING)
                    .findFirst()
                    ?: return@forEach  // skip threads with no messages

                // Ask the categorizer for the best category based on sender + body
                val category = categorizer.categorize(
                    address = lastMessage.address,
                    body    = lastMessage.body
                )

                // Always write the category — this is a full re-categorization pass.
                realm.executeTransaction { r ->
                    r.where(Conversation::class.java)
                        .equalTo("id", conversation.id)
                        .findFirst()
                        ?.apply { categoryId = category.name }

                    r.where(Message::class.java)
                        .equalTo("threadId", conversation.id)
                        .findAll()
                        .forEach { it.categoryId = category.name }
                }
            }
        }

        // Mark the one-time migration as done so this worker never runs again
        prefs.categorizedV1Done.set(true)
        prefs.categorizedV2Done.set(true)
        prefs.categorizedV3Done.set(true)

        Timber.d("CategorizeAllMessagesWorker: completed successfully")
        return Result.success()
    }
}


