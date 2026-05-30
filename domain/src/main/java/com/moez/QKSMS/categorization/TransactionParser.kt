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
package dev.octoshrimpy.quik.categorization

/**
 * Extracted, structured data from a TRANSACTIONS-category SMS.
 *
 * All fields are plain Kotlin — this is NOT a Realm object.
 * [FinanceRepositoryImpl] maps this to the [ParsedTransaction] Realm model before saving.
 *
 * @param amount           The transaction amount in INR (always positive).
 * @param isDebit          true = money leaving the account (debit/spent/withdrawn).
 *                         false = money arriving (credit/deposited/refund).
 * @param merchant         Counterparty name (payee or payer), or "" if not found.
 * @param reference        UPI Ref ID / NEFT ref / other reference string, or "" if not found.
 * @param accountLast4     Last 4 digits of the account/card number, or "" if not found.
 * @param availableBalance Available balance AFTER the transaction, or -1.0 if not in the message.
 * @param method           Payment method string: "UPI", "NEFT", "IMPS", "RTGS", "ATM",
 *                         "Card", "Statement", or "Other".
 * @param bankName         Human-readable bank name extracted from SMS body ("HDFC Bank", "SBI", etc.)
 * @param accountType      "Savings A/C", "Debit Card", or "Credit Card"
 * @param dueDateMs        For bill reminders (method="Statement"): epoch-ms of the payment due date. 0 otherwise.
 * @param minDue           For bill reminders: minimum amount due. 0.0 if not specified.
 */
data class ParsedTransactionData(
    val amount: Double,
    val isDebit: Boolean,
    val merchant: String = "",
    val reference: String = "",
    val accountLast4: String = "",
    val availableBalance: Double = -1.0,
    val method: String = "Other",
    val bankName: String = "",
    val accountType: String = "Savings A/C",
    val dueDateMs: Long = 0,
    val minDue: Double = 0.0
)

/**
 * Contract for on-device SMS financial data extraction.
 *
 * Called only for messages already classified as [MessageCategory.TRANSACTIONS] by
 * [SmsCategorizer]. Implementations must be pure (no side effects) and thread-safe.
 *
 * The interface lives in `domain`; the implementation ([TransactionParserImpl]) lives in `data`.
 * Dagger 2 wires them together via [AppModule].
 */
interface TransactionParser {
    /**
     * Attempts to parse financial data from a TRANSACTIONS-category SMS.
     *
     * @param address Sender address (alphanumeric sender ID or phone number).
     * @param body    Complete SMS body text.
     * @return [ParsedTransactionData] if an amount could be extracted, null otherwise.
     *         Returning null means the message should not be saved to the finance database.
     */
    fun parse(address: String, body: String): ParsedTransactionData?
}

