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
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import dev.octoshrimpy.quik.categorization.TransactionParser
import dev.octoshrimpy.quik.model.Message
import dev.octoshrimpy.quik.model.ParsedTransaction
import dev.octoshrimpy.quik.repository.FinanceRepository
import dev.octoshrimpy.quik.util.Preferences
import io.realm.Realm
import io.realm.Sort
import timber.log.Timber

/**
 * One-time background worker that parses all historical TRANSACTIONS-category messages
 * and saves extracted financial data to the [ParsedTransaction] Realm table.
 *
 * WHEN TRIGGERED:
 * [QKApplication.onCreate] checks [Preferences.parsedTransactionsV1Done]. When false
 * (i.e. first launch after Phase 2), this worker is enqueued. On success it sets the
 * flag to true so it never runs again.
 *
 * BATCH WRITES (50 per transaction):
 * Realm write transactions hold a write lock. Committing every 50 rows keeps the
 * lock duration short, preventing UI-thread Realm reads from being blocked during
 * a large backfill.
 *
 * INJECTION:
 * Fields are set by [InjectionWorkerFactory] after WorkManager creates the instance
 * via reflection (constructor injection is not available for WorkManager workers).
 */
class ParseAllTransactionsWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : Worker(appContext, workerParams) {

    companion object {
        private const val WORKER_TAG  = "parse_all_transactions_v1"
        private const val BATCH_SIZE  = 50

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<ParseAllTransactionsWorker>()
                .addTag(WORKER_TAG)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORKER_TAG, ExistingWorkPolicy.KEEP, request)
            Timber.d("ParseAllTransactionsWorker: enqueued")
        }
    }

    // Injected by InjectionWorkerFactory
    lateinit var transactionParser: TransactionParser
    lateinit var financeRepo: FinanceRepository
    lateinit var prefs: Preferences

    override fun doWork(): Result {
        Timber.d("ParseAllTransactionsWorker: started")

        Realm.getDefaultInstance().use { realm ->
            // Find all TRANSACTIONS + BILL_REMINDER messages that have NOT yet been parsed.
            // "Not yet parsed" = no corresponding ParsedTransaction row exists.
            val allTxnMessages = realm
                .where(Message::class.java)
                .beginGroup()
                    .equalTo("categoryId", "TRANSACTIONS")
                    .or()
                    .equalTo("categoryId", "BILL_REMINDER")
                .endGroup()
                .sort("date", Sort.DESCENDING)
                .findAll()

            Timber.d("ParseAllTransactionsWorker: ${allTxnMessages.size} TRANSACTIONS messages found")

            // copyFromRealm() detaches from Realm so we can safely iterate while writing.
            val snapshot = realm.copyFromRealm(allTxnMessages)

            var parsed = 0
            var batch  = mutableListOf<Pair<ParsedTransaction, String>>() // (txn, compositeBalKey)

            snapshot.forEach { message ->
                // Skip if already parsed (idempotent re-run safety).
                val alreadyExists = realm.where(ParsedTransaction::class.java)
                    .equalTo("id", message.id)
                    .count() > 0
                if (alreadyExists) return@forEach

                val data = transactionParser.parse(message.address, message.body)
                    ?: return@forEach  // not parseable — skip silently

                val cal = java.util.Calendar.getInstance().apply { timeInMillis = message.date }
                val txn = ParsedTransaction().apply {
                    id               = message.id
                    threadId         = message.threadId
                    date             = message.date
                    amount           = data.amount
                    isDebit          = data.isDebit
                    merchant         = data.merchant
                    reference        = data.reference
                    accountLast4     = data.accountLast4
                    availableBalance = data.availableBalance
                    method           = data.method
                    bankName         = data.bankName
                    dueDateMs        = data.dueDateMs
                    minDue           = data.minDue
                    year             = cal.get(java.util.Calendar.YEAR)
                    month            = cal.get(java.util.Calendar.MONTH) + 1
                }
                // Use bankName as key prefix so all sender IDs for same bank+account merge
                val keyPrefix = data.bankName.ifBlank { message.address }
                batch.add(Pair(txn, "$keyPrefix:${data.accountLast4}"))
                parsed++

                // Commit in batches to keep write-lock duration short.
                if (batch.size >= BATCH_SIZE) {
                    commitBatch(realm, batch)
                    batch = mutableListOf()
                }
            }

            // Commit any remaining items.
            if (batch.isNotEmpty()) commitBatch(realm, batch)

            Timber.d("ParseAllTransactionsWorker: parsed $parsed transactions")
        }

        prefs.parsedTransactionsV1Done.set(true)
        Timber.d("ParseAllTransactionsWorker: completed")
        return Result.success()
    }

    private fun commitBatch(
        realm: Realm,
        batch: List<Pair<ParsedTransaction, String>>
    ) {
        realm.executeTransaction { r ->
            batch.forEach { (txn, balKey) ->
                r.copyToRealmOrUpdate(txn)

                // Upsert AccountBalance if this message carries a known balance.
                if (txn.availableBalance > 0 && txn.accountLast4.isNotBlank()) {
                    val existing = r.where(dev.octoshrimpy.quik.model.AccountBalance::class.java)
                        .equalTo("id", balKey)
                        .findFirst()
                    if (existing == null || existing.lastUpdated < txn.date) {
                        r.copyToRealmOrUpdate(
                            dev.octoshrimpy.quik.model.AccountBalance().apply {
                                id            = balKey
                                senderPattern = balKey.substringBefore(":")
                                accountLast4  = txn.accountLast4
                                this.balance  = txn.availableBalance
                                bankName      = txn.bankName.ifBlank { balKey.substringBefore(":") }
                                accountType   = dev.octoshrimpy.quik.categorization.TransactionParserImpl
                                    .extractAccountType(txn.method, txn.method)
                                lastUpdated   = txn.date
                            }
                        )
                    }
                }
            }
        }
    }
}

