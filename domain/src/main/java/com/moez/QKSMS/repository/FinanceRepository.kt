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
package dev.octoshrimpy.quik.repository

import dev.octoshrimpy.quik.categorization.ParsedTransactionData
import dev.octoshrimpy.quik.model.AccountBalance
import dev.octoshrimpy.quik.model.Message
import dev.octoshrimpy.quik.model.ParsedTransaction
import io.realm.RealmResults

/**
 * Contract for all Finance Dashboard data operations.
 *
 * Implemented by [FinanceRepositoryImpl] in the `data` module.
 * Bound in [AppModule] via @Provides.
 */
interface FinanceRepository {

    /**
     * Returns the [ParsedTransaction] for a specific message ID, or null if none was parsed.
     * Called on the main thread from [MessagesAdapter] to decide whether to show a parsed card.
     */
    fun getTransactionForMessage(messageId: Long): ParsedTransaction?

    /**
     * Returns all [ParsedTransaction] rows for the given calendar month, sorted by date descending.
     * The returned [RealmResults] is live — it auto-updates when new rows are inserted.
     */
    fun getTransactions(year: Int, month: Int): RealmResults<ParsedTransaction>

    /**
     * Returns the total amount debited (spent) in the given month, in INR.
     * Returns 0.0 if there are no debit transactions for that month.
     */
    fun getSpentTotal(year: Int, month: Int): Double

    /**
     * Returns the total amount credited (received) in the given month, in INR.
     * Returns 0.0 if there are no credit transactions for that month.
     */
    fun getReceivedTotal(year: Int, month: Int): Double

    /**
     * Returns all known [AccountBalance] rows, sorted by [AccountBalance.lastUpdated] descending
     * (most recently seen account first). Live Realm results.
     */
    fun getAccounts(): RealmResults<AccountBalance>

    /**
     * Returns upcoming credit card bill statements parsed from SMS bodies
     * (method == "Statement"). These are shown in the Finance Dashboard "Upcoming" section.
     */
    fun getUpcomingBills(): List<ParsedTransaction>

    /**
     * Persists a [ParsedTransactionData] extracted from [message] to Realm.
     * Sets [ParsedTransaction.year] and [ParsedTransaction.month] from [message.date].
     * If a row with the same [message.id] already exists, it is overwritten (upsert).
     */
    fun saveTransaction(data: ParsedTransactionData, message: Message)

    /**
     * Upserts an [AccountBalance] row identified by "[senderAddress]:[accountLast4]".
     * Only updates if [ts] > [AccountBalance.lastUpdated] (newer message wins).
     */
    fun updateAccountBalance(
        senderAddress: String,
        accountLast4: String,
        balance: Double,
        ts: Long
    )
}

