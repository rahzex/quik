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
package dev.octoshrimpy.quik.util

/**
 * Pure, thread-safe utility for extracting a formatted transaction preview string
 * (amount + balance) from a bank/payment SMS body.
 *
 * Used by NotificationManagerImpl to show "− ₹4,200 · Bal ₹38,421" in the
 * notification compact row for TRANSACTIONS messages, instead of the raw body.
 */
object TxnPreviewExtractor {

    // Currency amount pattern: ₹4,200 / Rs.4,200 / Rs 4200 / INR 12,000
    private val AMOUNT_PATTERN = Regex(
        """(?:₹|Rs\.?\s*|INR\s*)([\d,]+(?:\.\d{1,2})?)""",
        RegexOption.IGNORE_CASE
    )

    // Debit signal keywords (precede the debit amount)
    private val DEBIT_KEYWORDS = Regex(
        """\b(?:debited|spent|withdrawn|deducted|paid|sent|payment\s+of)\b""",
        RegexOption.IGNORE_CASE
    )

    // Credit signal keywords (precede the credit amount)
    private val CREDIT_KEYWORDS = Regex(
        """\b(?:credited|received|refund(?:ed)?|salary|deposited)\b""",
        RegexOption.IGNORE_CASE
    )

    // Balance/available keyword that precedes the balance amount
    private val BALANCE_PATTERN = Regex(
        """(?:(?:avl?|avail(?:able)?\.?|bal(?:ance)?|a/c\s+bal)\.?\s*(?:rs\.?\s*|₹|inr\s*)?([\d,]+(?:\.\d{1,2})?)""" +
        """|(?:₹|Rs\.?\s*|INR\s*)([\d,]+(?:\.\d{1,2})?)\s*(?:avl?|avail|bal(?:ance)?))\b""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Attempts to extract a "− ₹X · Bal ₹Y" or "+ ₹X · Bal ₹Y" summary from [body].
     *
     * Logic:
     *  1. Find the first occurrence of a debit/credit keyword + nearest currency amount
     *  2. Find the balance amount
     *  3. Combine into a single readable string
     *
     * Returns null if a meaningful amount cannot be found (caller falls back to getSummary()).
     */
    fun extractPreview(body: String): String? {
        val amounts = AMOUNT_PATTERN.findAll(body).toList()
        if (amounts.isEmpty()) return null

        // Determine debit vs credit from first keyword found in body
        val isDebit = DEBIT_KEYWORDS.containsMatchIn(body)
        val isCredit = CREDIT_KEYWORDS.containsMatchIn(body)

        if (!isDebit && !isCredit) return null

        val prefix = if (isDebit) "− ₹" else "+ ₹"

        // The primary amount is the first currency figure in the body
        val primaryAmount = amounts.firstOrNull()?.groupValues?.getOrNull(1) ?: return null

        // Try to extract balance
        val balMatch = BALANCE_PATTERN.find(body)
        val balAmount = balMatch?.let { mr ->
            mr.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() }
                ?: mr.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }
        }

        return buildString {
            append(prefix)
            append(primaryAmount)
            if (!balAmount.isNullOrEmpty() && balAmount != primaryAmount) {
                append(" · Bal ₹")
                append(balAmount)
            }
        }
    }
}

