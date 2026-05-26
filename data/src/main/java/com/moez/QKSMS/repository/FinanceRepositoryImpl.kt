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
import io.realm.Realm
import io.realm.Sort
import timber.log.Timber
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FinanceRepositoryImpl @Inject constructor() : FinanceRepository {

    override fun getTransactionForMessage(messageId: Long): ParsedTransaction? {
        return Realm.getDefaultInstance().use { realm ->
            realm.where(ParsedTransaction::class.java)
                .equalTo("id", messageId)
                .findFirst()
                ?.let { realm.copyFromRealm(it) }
        }
    }

    override fun getTransactions(year: Int, month: Int) =
        Realm.getDefaultInstance()
            .where(ParsedTransaction::class.java)
            .equalTo("year", year)
            .equalTo("month", month)
            .sort("date", Sort.DESCENDING)
            .findAllAsync()

    override fun getSpentTotal(year: Int, month: Int): Double =
        Realm.getDefaultInstance().use { realm ->
            realm.where(ParsedTransaction::class.java)
                .equalTo("year", year)
                .equalTo("month", month)
                .equalTo("isDebit", true)
                .findAll()
                .sumOf { it.amount }
        }

    override fun getReceivedTotal(year: Int, month: Int): Double =
        Realm.getDefaultInstance().use { realm ->
            realm.where(ParsedTransaction::class.java)
                .equalTo("year", year)
                .equalTo("month", month)
                .equalTo("isDebit", false)
                .findAll()
                .sumOf { it.amount }
        }

    override fun getAccounts() =
        Realm.getDefaultInstance()
            .where(AccountBalance::class.java)
            .sort("lastUpdated", Sort.DESCENDING)
            .findAllAsync()

    override fun getUpcomingBills(): List<ParsedTransaction> {
        val nowMs        = System.currentTimeMillis()
        val thirtyDaysMs = 30L * 24 * 60 * 60 * 1000
        return Realm.getDefaultInstance().use { realm ->
            realm.copyFromRealm(
                realm.where(ParsedTransaction::class.java)
                    .equalTo("method", "Statement")
                    .greaterThanOrEqualTo("date", nowMs)
                    .lessThanOrEqualTo("date", nowMs + thirtyDaysMs)
                    .sort("date", Sort.ASCENDING)
                    .findAll()
            )
        }
    }

    override fun saveTransaction(data: ParsedTransactionData, message: Message) {
        val cal = Calendar.getInstance().apply { timeInMillis = message.date }
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction { r ->
                val obj = ParsedTransaction().apply {
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
                    year             = cal.get(Calendar.YEAR)
                    month            = cal.get(Calendar.MONTH) + 1
                }
                r.copyToRealmOrUpdate(obj)
            }
        }
        Timber.v("FinanceRepo: saved ParsedTransaction id=${message.id} amt=${data.amount} debit=${data.isDebit}")
    }

    override fun updateAccountBalance(
        senderAddress: String,
        accountLast4: String,
        balance: Double,
        ts: Long
    ) {
        if (accountLast4.isBlank()) return
        val compositeId = "$senderAddress:$accountLast4"
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction { r ->
                val existing = r.where(AccountBalance::class.java)
                    .equalTo("id", compositeId)
                    .findFirst()
                if (existing != null && existing.lastUpdated >= ts) return@executeTransaction
                val obj = AccountBalance().apply {
                    id             = compositeId
                    senderPattern  = senderAddress
                    this.accountLast4 = accountLast4
                    this.balance      = balance
                    lastUpdated    = ts
                }
                r.copyToRealmOrUpdate(obj)
            }
        }
    }
}
