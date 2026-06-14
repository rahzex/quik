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
 * [QKApplication.onCreate] checks [Preferences.parsedTransactionsV1Done] and
 * [Preferences.parsedTransactionsV2Done]. When either is false this worker is enqueued.
 * On success both flags are set so it never runs again needlessly.
 *
 * PHASE 1 — Parse new messages:
 * Finds TRANSACTIONS/BILL_REMINDER messages not yet in [ParsedTransaction], parses them,
 * saves the rows, and upserts [AccountBalance] when the SMS carries an explicit "Avl Bal"
 * field OR applies a ±delta to an existing row when it doesn't.
 *
 * PHASE 2 — Rebuild account balances (one-time, gated by [parsedTransactionsV2Done]):
 * For every existing [AccountBalance] row, fetches all matching [ParsedTransaction] rows
 * in ascending date order and replays the running balance:
 *  - Explicit "Avl Bal" message → use as ground truth baseline.
 *  - Subsequent debit/credit without balance → apply ±delta from baseline.
 * This corrects accounts that had balances stuck at the last explicit-balance message.
 *
 * BATCH WRITES (50 per transaction):
 * Realm write transactions hold a write lock. Committing every 50 rows keeps the
 * lock duration short, preventing UI-thread Realm reads from being blocked.
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
        // internal so CategorizeAllMessagesWorker can reference it when building the chain
        internal const val WORKER_TAG = "parse_all_transactions_v1"
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

        // Skip all finance processing when the Finance feature is disabled.
        // Do NOT set the parsedTransactionsV*Done flags here so the worker
        // re-runs correctly if the user later re-enables Finance.
        if (!prefs.financeEnabled.get()) {
            Timber.d("ParseAllTransactionsWorker: skipped — Finance feature is disabled")
            return Result.success()
        }

        return try {
            Realm.getDefaultInstance().use { realm ->

                // ── Phase 1: Parse any new / previously-unparsed messages ──────────────────
                // Sort ASCENDING (oldest → newest) so delta-based balance updates are applied
                // in chronological order, giving a correct running total per account.
                val allTxnMessages = realm
                    .where(Message::class.java)
                    .beginGroup()
                        .equalTo("categoryId", "TRANSACTIONS")
                        .or()
                        .equalTo("categoryId", "BILL_REMINDER")
                    .endGroup()
                    .sort("date", Sort.ASCENDING)
                    .findAll()

                Timber.d("ParseAllTransactionsWorker: ${allTxnMessages.size} TRANSACTIONS messages found")

                // copyFromRealm() detaches from Realm so we can safely iterate while writing.
                val snapshot = realm.copyFromRealm(allTxnMessages)

                var parsed = 0
                var batch  = mutableListOf<Pair<ParsedTransaction, String>>()

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

                // ── Phase 2: Rebuild account balances via delta replay ──────────────────────
                // Runs once (gated by parsedTransactionsV2Done). Corrects accounts whose balance
                // was stuck at the last explicit-balance SMS because subsequent debits/credits
                // (without an "Avl Bal" field) were not applied by the old code path.
                if (!prefs.parsedTransactionsV2Done.get()) {
                    rebuildAccountBalances(realm)
                    prefs.parsedTransactionsV2Done.set(true)
                }
            }

            prefs.parsedTransactionsV1Done.set(true)
            Timber.d("ParseAllTransactionsWorker: completed")
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "ParseAllTransactionsWorker: failed — will retry")
            Result.retry()
        }
    }

    /**
     * Writes [AccountBalance] rows for a batch of parsed transactions.
     *
     * Two sub-paths per transaction:
     *  - `availableBalance > 0`: the SMS contained an explicit "Avl Bal" field — use it as the
     *    authoritative balance (most accurate). Creates or updates the row if this message is newer.
     *  - `availableBalance ≤ 0` but `accountLast4` is known: no explicit balance in the SMS.
     *    Applies a ±delta to an **existing** baseline row. No-op if the account has no row yet
     *    (avoids creating a row with an unknown starting balance).
     */
    private fun commitBatch(
        realm: Realm,
        batch: List<Pair<ParsedTransaction, String>>
    ) {
        realm.executeTransaction { r ->
            batch.forEach { (txn, balKey) ->
                r.copyToRealmOrUpdate(txn)

                if (txn.accountLast4.isBlank()) return@forEach

                if (txn.availableBalance > 0) {
                    // SMS contained an explicit "Avl Bal" — use it directly (most accurate).
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
                } else {
                    // No explicit balance in SMS — apply ±delta to an existing baseline.
                    // Messages are sorted ASCENDING so this always moves the balance forward in time.
                    val existing = r.where(dev.octoshrimpy.quik.model.AccountBalance::class.java)
                        .equalTo("id", balKey)
                        .findFirst()
                    // Only apply delta if a baseline row already exists and this message is newer.
                    if (existing != null && txn.date > existing.lastUpdated) {
                        val newBalance = if (txn.isDebit) {
                            (existing.balance - txn.amount).coerceAtLeast(0.0)
                        } else {
                            existing.balance + txn.amount
                        }
                        r.copyToRealmOrUpdate(
                            dev.octoshrimpy.quik.model.AccountBalance().apply {
                                id            = balKey
                                senderPattern = existing.senderPattern
                                accountLast4  = txn.accountLast4
                                this.balance  = newBalance
                                bankName      = txn.bankName.ifBlank { existing.bankName }
                                    .ifBlank { balKey.substringBefore(":") }
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

    /**
     * Replays every [ParsedTransaction] row per account in ascending date order to compute
     * the correct running balance, then writes it back to the [AccountBalance] row.
     *
     * Strategy per account:
     *  1. Find all [ParsedTransaction] rows matching [AccountBalance.accountLast4], sorted oldest → newest.
     *  2. Walk: when `availableBalance > 0` is encountered, adopt it as a ground-truth baseline;
     *     for subsequent rows without an explicit balance, apply ±delta.
     *  3. Write the final computed balance back.
     *
     * Does NOT filter by `bankName` in the Realm query because old [ParsedTransaction] rows may
     * have `bankName=""` even when the corresponding [AccountBalance] has a valid `bankName`.
     * Using `accountLast4` alone is sufficient for the vast majority of users.
     *
     * Idempotent — safe to call multiple times; gated by [Preferences.parsedTransactionsV2Done]
     * in [doWork] so it only runs once per device.
     */
    private fun rebuildAccountBalances(realm: Realm) {
        Timber.d("ParseAllTransactionsWorker: phase 2 — rebuilding AccountBalance via delta replay")

        val knownAccounts = realm
            .where(dev.octoshrimpy.quik.model.AccountBalance::class.java)
            .findAll()
            .map { realm.copyFromRealm(it) }

        if (knownAccounts.isEmpty()) {
            Timber.d("ParseAllTransactionsWorker: no AccountBalance rows found, skipping rebuild")
            return
        }

        Timber.d("ParseAllTransactionsWorker: rebuilding ${knownAccounts.size} account(s)")

        knownAccounts.forEach { acct ->
            // Fetch all ParsedTransactions for this account's last-4, oldest first.
            val txns = realm
                .where(ParsedTransaction::class.java)
                .equalTo("accountLast4", acct.accountLast4)
                .sort("date", Sort.ASCENDING)
                .findAll()
                .map { realm.copyFromRealm(it) }

            if (txns.isEmpty()) return@forEach

            var balance  = 0.0
            var baseline = false   // true once an explicit "Avl Bal" message is seen
            var latestTs = 0L

            txns.forEach { txn ->
                when {
                    txn.availableBalance > 0.0 -> {
                        // Explicit available-balance field — adopt as ground truth.
                        balance  = txn.availableBalance
                        baseline = true
                        latestTs = txn.date
                    }
                    baseline -> {
                        // Apply ±delta only after we have a known starting balance.
                        balance  = if (txn.isDebit) {
                            (balance - txn.amount).coerceAtLeast(0.0)
                        } else {
                            balance + txn.amount
                        }
                        latestTs = txn.date
                    }
                    // else: no baseline yet — skip (can't infer starting balance from a debit alone)
                }
            }

            if (!baseline) return@forEach  // no explicit balance ever seen — nothing to write

            // Write the recomputed balance back.
            realm.executeTransaction { r ->
                val row = r.where(dev.octoshrimpy.quik.model.AccountBalance::class.java)
                    .equalTo("id", acct.id)
                    .findFirst() ?: return@executeTransaction
                Timber.d(
                    "ParseAllTransactionsWorker: balance rebuild ${acct.id}: " +
                    "₹${row.balance} → ₹$balance (latestTs=$latestTs)"
                )
                row.balance     = balance
                row.lastUpdated = latestTs
            }
        }

        Timber.d("ParseAllTransactionsWorker: phase 2 rebuild complete")
    }
}
