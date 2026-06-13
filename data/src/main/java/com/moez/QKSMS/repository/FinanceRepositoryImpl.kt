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
import dev.octoshrimpy.quik.repository.FinanceRepository.Companion.SALARY_THRESHOLD
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

    override fun getTransactions(year: Int, month: Int): List<ParsedTransaction> =
        Realm.getDefaultInstance().use { realm ->
            realm.copyFromRealm(
                realm.where(ParsedTransaction::class.java)
                    .equalTo("year", year)
                    .equalTo("month", month)
                    .sort("date", Sort.DESCENDING)
                    .findAll()
            )
        }

    override fun getSpentTotal(year: Int, month: Int): Double =
        Realm.getDefaultInstance().use { realm ->
            realm.where(ParsedTransaction::class.java)
                .equalTo("year", year)
                .equalTo("month", month)
                .equalTo("isDebit", true)
                .findAll()
                .sumOf { it.amount }
        }

    override fun isSalaryCredit(txn: ParsedTransaction): Boolean {
        if (txn.isDebit) return false
        // Credit card bill payments are NOT salary income
        if (txn.method == "Card" || txn.method == "Statement") return false
        if (txn.amount < SALARY_THRESHOLD) return false
        val cal      = Calendar.getInstance().apply { timeInMillis = txn.date }
        val dayOfMonth = cal.get(Calendar.DAY_OF_MONTH)
        val lastDay  = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        return dayOfMonth >= lastDay - 2
    }

    /**
     * Sum of credits for [year]/[month] applying the salary-shift rule:
     * - Exclude salary credits that physically arrived THIS month (they belong to next month).
     * - Include salary credits that physically arrived the PREVIOUS month (they belong to THIS month).
     */
    override fun getReceivedTotal(year: Int, month: Int): Double =
        Realm.getDefaultInstance().use { realm ->
            // ── This month's credits, minus any end-of-month salary credits ──────────
            val thisMonthIncome = realm.where(ParsedTransaction::class.java)
                .equalTo("year", year)
                .equalTo("month", month)
                .equalTo("isDebit", false)
                .findAll()
                .filterNot { isSalaryCredit(it) }
                .sumOf { it.amount }

            // ── Previous month's salary credits that count as THIS month's income ───
            val (prevYear, prevMonth) = if (month == 1) Pair(year - 1, 12) else Pair(year, month - 1)
            val prevMonthSalary = realm.where(ParsedTransaction::class.java)
                .equalTo("year", prevYear)
                .equalTo("month", prevMonth)
                .equalTo("isDebit", false)
                .findAll()
                .filter { isSalaryCredit(it) }
                .sumOf { it.amount }

            thisMonthIncome + prevMonthSalary
        }

    override fun getAccounts(): List<AccountBalance> =
        Realm.getDefaultInstance().use { realm ->
            realm.copyFromRealm(
                realm.where(AccountBalance::class.java)
                    .sort("lastUpdated", Sort.DESCENDING)
                    .findAll()
            )
        }.let { all ->
            // Deduplicate: same bank + same last-4 = same physical account regardless of sender ID.
            // We MUST derive the bank name from senderPattern when bankName is blank, because
            // existing rows have bankName="" (set by Realm migration) and different sender IDs
            // (JXHDFCBK, ADHDFCBK, VMHDFCBK…) all refer to the same bank.
            val seen = mutableSetOf<String>()
            all.filter { acct ->
                val bankKey = deriveBankName(acct.senderPattern, acct.bankName)
                seen.add("$bankKey:${acct.accountLast4}")  // true = first time seen
            }
        }

    /**
     * Derives a normalised bank name from the SMS sender-ID pattern.
     * Falls back to [storedBankName] if already set (for newer entries that went through the parser).
     * This mapping mirrors [FinanceController.senderToBankName].
     */
    private fun deriveBankName(senderPattern: String, storedBankName: String): String {
        if (storedBankName.isNotBlank()) return storedBankName
        val s = senderPattern.uppercase()
        return when {
            "HDFCBK" in s                    -> "HDFC Bank"
            "ICICIT" in s                    -> "ICICI Bank"
            "SBICRD" in s || "SBISMS" in s   -> "SBI"
            "PNBSMS" in s                    -> "PNB"
            "BDNSMS" in s                    -> "Bandhan Bank"
            "AXISBK" in s                    -> "Axis Bank"
            "YESBNK" in s || "YESBKS" in s   -> "Yes Bank"
            "PAYTMB" in s || "PPBL"   in s   -> "Paytm Bank"
            "KOTAKB" in s                    -> "Kotak Bank"
            "INDUSB" in s                    -> "IndusInd Bank"
            else                             -> senderPattern   // unknown — keep raw sender
        }
    }

    override fun getUpcomingBills(): List<ParsedTransaction> {
        val nowMs         = System.currentTimeMillis()
        // Show bills whose due date is in the future OR within the last 7 days (recently due).
        // For bill reminders without a parsed due date (dueDateMs=0), fall back to SMS received
        // date within the last 30 days so they still surface in the Upcoming section.
        val sevenDaysMs   = 7L  * 24 * 60 * 60 * 1000
        val thirtyDaysMs  = 30L * 24 * 60 * 60 * 1000
        val ninetyDaysMs  = 90L * 24 * 60 * 60 * 1000
        return Realm.getDefaultInstance().use { realm ->
            realm.copyFromRealm(
                realm.where(ParsedTransaction::class.java)
                    .equalTo("method", "Statement")
                    .beginGroup()
                        // Has a parsed due date and it's within [-7 days, +90 days]
                        .beginGroup()
                            .greaterThan("dueDateMs", 0L)
                            .greaterThanOrEqualTo("dueDateMs", nowMs - sevenDaysMs)
                            .lessThanOrEqualTo("dueDateMs", nowMs + ninetyDaysMs)
                        .endGroup()
                        .or()
                        // No due date parsed — show if received in the last 30 days
                        .beginGroup()
                            .equalTo("dueDateMs", 0L)
                            .greaterThanOrEqualTo("date", nowMs - thirtyDaysMs)
                        .endGroup()
                    .endGroup()
                    .sort("dueDateMs", Sort.ASCENDING)
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
                    bankName         = data.bankName
                    dueDateMs        = data.dueDateMs
                    minDue           = data.minDue
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
        ts: Long,
        bankName: String,
        accountType: String
    ) {
        if (accountLast4.isBlank()) return
        // Use bankName as the key prefix so all sender IDs for the same bank+account
        // update the SAME row instead of creating duplicates.
        val keyPrefix   = bankName.ifBlank { senderAddress }
        val compositeId = "$keyPrefix:$accountLast4"
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction { r ->
                val existing = r.where(AccountBalance::class.java)
                    .equalTo("id", compositeId)
                    .findFirst()
                if (existing != null && existing.lastUpdated >= ts) return@executeTransaction
                val obj = AccountBalance().apply {
                    id               = compositeId
                    senderPattern    = senderAddress     // keep original sender for reference
                    this.accountLast4 = accountLast4
                    this.balance     = balance
                    this.bankName    = bankName.ifBlank { senderAddress }
                    this.accountType = accountType
                    lastUpdated      = ts
                }
                r.copyToRealmOrUpdate(obj)
            }
        }
        Timber.v("FinanceRepo: updateAccountBalance compositeId=$keyPrefix:$accountLast4 balance=$balance ts=$ts")
    }

    override fun applyTransactionDelta(
        senderAddress: String,
        accountLast4: String,
        amount: Double,
        isDebit: Boolean,
        ts: Long,
        bankName: String,
        accountType: String
    ) {
        if (accountLast4.isBlank()) return
        val keyPrefix   = bankName.ifBlank { senderAddress }
        val compositeId = "$keyPrefix:$accountLast4"
        Realm.getDefaultInstance().use { realm ->
            realm.executeTransaction { r ->
                val existing = r.where(AccountBalance::class.java)
                    .equalTo("id", compositeId)
                    .findFirst()
                // No-op: if no existing row, we have no baseline balance to delta from.
                if (existing == null) {
                    Timber.v("FinanceRepo: applyTransactionDelta — no existing row for $compositeId, skipping")
                    return@executeTransaction
                }
                // No-op: if this message is older than the last update, skip it.
                if (ts <= existing.lastUpdated) {
                    Timber.v("FinanceRepo: applyTransactionDelta — ts=$ts <= lastUpdated=${existing.lastUpdated} for $compositeId, skipping")
                    return@executeTransaction
                }
                val newBalance = if (isDebit) {
                    (existing.balance - amount).coerceAtLeast(0.0)
                } else {
                    existing.balance + amount
                }
                val updatedBankName = bankName.ifBlank { existing.bankName }.ifBlank { senderAddress }
                val updatedAcctType = accountType.ifBlank { existing.accountType }
                val obj = AccountBalance().apply {
                    id               = compositeId
                    senderPattern    = senderAddress
                    this.accountLast4 = accountLast4
                    this.balance     = newBalance
                    this.bankName    = updatedBankName
                    this.accountType = updatedAcctType
                    lastUpdated      = ts
                }
                r.copyToRealmOrUpdate(obj)
                Timber.v(
                    "FinanceRepo: applyTransactionDelta ${if (isDebit) "debit" else "credit"}" +
                    " ₹$amount → $compositeId ${existing.balance} → $newBalance"
                )
            }
        }
    }
}
