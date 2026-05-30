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

import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device, privacy-first SMS financial data extractor.
 *
 * ── STRATEGY ────────────────────────────────────────────────────────────────
 * Two-tier approach:
 *
 * Tier 1 — 13 STRUCTURAL patterns (dispatched in order, first match wins).
 *   Each pattern matches a well-known SMS sentence structure used across
 *   Indian banks, regardless of which bank sent it. No bank names appear
 *   in any regex. The patterns were derived empirically from real corpus
 *   data in sms_backup_categorized.json.
 *
 * Tier 2 — Universal GENERIC fallback.
 *   If no structural pattern matches, try to extract amount + direction
 *   using universal financial keywords. Returns null if direction is
 *   ambiguous (both debit AND credit keywords present, or neither).
 *
 * ── THREAD SAFETY ───────────────────────────────────────────────────────────
 * @Singleton — created once. All Regex objects are precompiled at class-load
 * time and are immutable, so concurrent calls from WorkManager threads are safe.
 *
 * ── WHY NO BANK NAMES IN REGEX? ─────────────────────────────────────────────
 * Hardcoding "HDFC" or "SBI" makes the parser brittle — any new bank breaks
 * extraction silently. Structural patterns match on sentence shape (keyword
 * order, field positions, delimiters) which is stable across Indian bank SMS
 * formats. The sender-ID filtering is already done by [SmsCategorizerImpl];
 * by the time [parse] is called the message is confirmed TRANSACTIONS category.
 */
@Singleton
class TransactionParserImpl @Inject constructor() : TransactionParser {

    companion object {

        private val FLAGS = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)

        // ── AMOUNT TOKENS (shared across patterns) ──────────────────────────
        // Matches: ₹4,200  |  Rs.4200.50  |  INR 1,13,964.00
        private const val AMT = """(?:Rs\.?|INR|₹)\s*([\d,]+(?:\.\d{1,2})?)"""
        // Balance capture group — same precise format, prevents capturing trailing periods
        private const val BAL = """([\d,]+(?:\.\d{1,2})?)"""

        // ── TIER 1: STRUCTURAL PATTERNS ─────────────────────────────────────

        /**
         * S1 — "Money Transfer:" header (UPI debit).
         * Corpus: "Money Transfer:Rs 200.00 from … A/c **7472 … to Add Money … UPI: 402162644471"
         * Captures: (1)=amount  (2)=acctLast4  (3)=merchant  (4)=UPI ref
         */
        private val S1_MONEY_TRANSFER = Regex(
            """Money Transfer:\s*$AMT.*?[Aa]\/[Cc]\s+\*+(\d{4}).*?\bto\s+(.*?)\s+UPI:\s*(\d+)""",
            FLAGS
        )

        /**
         * S2 — Multiline "Amt Sent" block (UPI debit, newer format).
         * Corpus: "Amt Sent Rs.340.00\nFrom … A/C *7472\nTo Momo Magic Cafe\nOn 16-02\nRef 404747962998"
         * Captures: (1)=amount  (2)=acctLast4  (3)=merchant  (4)=ref
         */
        private val S2_AMT_SENT = Regex(
            """Amt Sent\s+$AMT\s*\nFrom.*?[A\/][Cc]\s+\*(\d{4})\s*\nTo\s+(.*?)\s*\nOn.*?\nRef\s+(\d+)""",
            FLAGS
        )

        /**
         * S3 — Inline "debited from a/c … (UPI Ref No. …)" (UPI debit).
         * Corpus: "… Rs. 1000.00 debited from a/c **7472 … (UPI Ref No. 405449586737)"
         * Captures: (1)=amount  (2)=acctLast4  (3)=UPI ref
         */
        private val S3_DEBITED_UPI_REF = Regex(
            """$AMT\s*debited\s+from\s+[Aa]\/[Cc]\s+\*+(\d{4}).*?\(UPI\s+Ref\s+No\.\s*(\d+)\)""",
            FLAGS
        )

        /**
         * S4 — "withdrawn from … Card x{last4} at {merchant} … Avl bal:" (ATM withdrawal).
         * Corpus: "Rs.3000 withdrawn from … Card x4441 at KOCH BIHAR BRANCH … Avl bal: 262453.73"
         * Captures: (1)=amount  (2)=cardLast4  (3)=merchant  (4)=balance
         */
        private val S4_WITHDRAWN_ATM = Regex(
            """$AMT\s*withdrawn\s+from\b.*?\bCard\s+x(\d{4})\s+at\s+(.*?)\s+on.*?[Aa]vl?\s*[Bb]al[:\s]+$BAL""",
            FLAGS
        )

        /**
         * S5 — "deposited in … A/c XX{last4} … Avl bal" (NEFT/salary credit).
         * Corpus: "INR 1,13,964.00 deposited in … A/c XX7472 … Avl bal INR 3,67,329.58"
         * Captures: (1)=amount  (2)=acctLast4  (3)=balance
         */
        private val S5_DEPOSITED = Regex(
            """$AMT\s*deposited\s+in\b.*?[Aa]\/[Cc]\s+XX(\d{4}).*?[Aa]vl?\s*[Bb]al\s+(?:INR|Rs\.?)?\s*$BAL""",
            FLAGS
        )

        /**
         * S6 — "a/c XX{last4} is credited for … Available Bal … (UPI Ref ID {ref})" (PNB-style credit).
         * Corpus: "Your a/c XX5095 is credited for INR 255.00 … Available Bal INR 398.25 (UPI Ref ID 402335323428)"
         * Captures: (1)=acctLast4  (2)=amount  (3)=balance  (4)=UPI ref
         */
        private val S6_CREDITED_AVAIL_BAL = Regex(
            """[Aa]\/[Cc]\s+XX(\d{4})\s+is\s+credited\s+for\s+(?:INR|Rs\.?|₹)\s*([\d,.]+).*?[Aa]vailable\s+[Bb]al\s+(?:INR|Rs\.?)?\s*$BAL\s*\(UPI\s+Ref\s+ID\s+(\d+)\)""",
            FLAGS
        )

        /**
         * S7 — "Ac X…{last4} Credited with Rs. … Aval Bal … (UPI Ref ID:{ref})" (PNB alt-credit).
         * Corpus: "Ac XXXXXXXX85095 Credited with Rs.1000.00 … Aval Bal Rs.2384.75 CR. (UPI Ref ID:405449586737)"
         * Captures: (1)=acctLast4  (2)=amount  (3)=balance  (4)=UPI ref
         */
        private val S7_AC_CREDITED = Regex(
            """Ac\s+[X*]+(\d{1,5})\s+Credited\s+with\s+(?:Rs\.?|INR|₹)\s*([\d,.]+).*?[Aa]val?\s+[Bb]al\s+(?:Rs\.?|INR)?\s*$BAL.*?\(UPI\s+Ref\s+ID:(\d+)\)""",
            FLAGS
        )

        /**
         * S8 — "A/c XX{last4} debited … thru UPI:{ref}.Bal …" (PNB-style UPI debit).
         * Corpus: "A/c XX5095 debited INR 300.00 … thru UPI:403340082552.Bal INR 5454.25"
         * Captures: (1)=acctLast4  (2)=amount  (3)=UPI ref  (4)=balance
         */
        private val S8_DEBITED_THRU_UPI = Regex(
            """[Aa]\/[Cc]\s+XX(\d{4})\s+debited\s+(?:INR|Rs\.?|₹)\s*([\d,.]+).*?thru\s+UPI:(\d+)\.[Bb]al\s+(?:INR|Rs\.?)?\s*$BAL""",
            FLAGS
        )

        /**
         * S9 — "spent on your … Credit Card ending {last4} at {merchant}" (credit card purchase).
         * Corpus: "Rs.3,052.28 spent on your SBI Credit Card ending 8987 at REL RETAIL…"
         * Captures: (1)=amount  (2)=cardLast4  (3)=merchant
         */
        private val S9_SPENT_CREDIT_CARD = Regex(
            """$AMT\s*spent\s+on\s+your\s+[\w\s]+Credit\s+Card\s+ending\s+(\d{4})\s+at\s+(.*?)\s+on\s+[\d/]+""",
            FLAGS
        )

        /**
         * S10 — "received payment of … via {method} … available limit is …" (card payment received).
         * Corpus: "We have received payment of Rs.9,075.00 via UPI … available limit is Rs.146,948.06"
         * Captures: (1)=amount  (2)=method  (3)=limit (used as availableBalance)
         */
        private val S10_RECEIVED_PAYMENT = Regex(
            """received\s+payment\s+of\s+$AMT\s*via\s+(\w+).*?available\s+limit\s+is\s+(?:Rs\.?|INR|₹)\s*$BAL""",
            FLAGS
        )

        /**
         * S11 — "payment of … for your … Credit Card has been successfully processed … ref no" (card payment confirmation).
         * Corpus: "payment of Rs. 9075.00 for your SBI Credit Card … processed. ref no : ZIC51722265287"
         * Captures: (1)=amount  (2)=ref
         */
        private val S11_PAYMENT_PROCESSED = Regex(
            """payment\s+of\s+$AMT\s*for\s+your\s+[\w\s]+Credit\s+Card\s+has\s+been\s+successfully\s+processed.*?ref\s+no\s*:\s*(\S+)""",
            FLAGS
        )

        /**
         * S12 — Credit card bill statement / reminder.
         * Covers 6 bank-specific patterns (SBI, HDFC, ICICI) identified from corpus.
         * Due date is stored in [ParsedTransactionData.dueDateMs]; min due in [ParsedTransactionData.minDue].
         * See [tryS12] for the full multi-pattern implementation.
         */
        // SBI E-statement: "Total Amt Due Rs 5790; Min Amt Due Rs 290; Payable by 08/03/2024"
        private val S12a_SBI_ESTATE = Regex(
            """Total\s+Amt\s+Due\s+Rs\s*([\d,]+);\s*Min\s+Amt\s+Due\s+Rs\s*([\d,]+).*?Payable\s+by\s+([\d/]+)""",
            FLAGS
        )
        // HDFC Statement: "Total due amt: Rs.1,18,546.00 Min due amt: Rs.5,930.00 Due by:02-12-2024"
        private val S12b_HDFC_STMT = Regex(
            """HDFC Bank Credit Card\s+XX(\d{4})\s+Statement:.*?Total due amt:\s*Rs\.([\d,.\-]+)\s+Min due amt:\s*Rs\.([\d,.]+)\s+Due by:([\d\-\w]+)""",
            FLAGS
        )
        // ICICI Statement: "Total of Rs 65489.68 or minimum of Rs 3280 is due by 30-APR-24"
        private val S12c_ICICI_STMT = Regex(
            """ICICI Bank Credit Card\s+XX(\d{4})\s+Statement.*?Total of\s+Rs\s*([\d,.]+)\s+or\s+minimum\s+of\s+Rs\s*([\d,.]+)\s+is due by\s+([\d\w\-]+)""",
            FLAGS
        )
        // ICICI Due reminder variant a: "Total Due INR X & Min Due INR Y to be paid by DATE on ICICI Bank Credit Card XXNNNN"
        private val S12d_ICICI_TOTALDUE = Regex(
            """Total\s+Due\s+(?:INR|Rs\.?)\s*([\d,.]+)\s+[&]\s+Min\s+Due\s+(?:INR|Rs\.?)\s*([\d,.]+)\s+to be paid by\s+([\d\w\-]+)\s+on\s+ICICI Bank Credit Card\s+XX(\d{4})""",
            FLAGS
        )
        // ICICI Due reminder variant b/c: "(Kindly pay|Pay) total due of Rs X or (Min|Minimum) Due Rs Y by DATE (on|for) ICICI..."
        private val S12e_ICICI_KINDLYPAY = Regex(
            """(?:Kindly pay|Pay)\s+[Tt]otal [Dd]ue of\s+Rs\s*([\d,.]+)\s+or\s+(?:Min(?:imum)?)\s+Due\s+Rs\s*([\d,.]+)\s+by\s+([\d\w\-]+)\s+(?:on|for)\s+ICICI Bank Credit Card\s+XX(\d{4})""",
            FLAGS
        )
        // SBI Outstanding: "outstanding of Rs. 43295.00, on your credit card ending 8987 is due on 06-APR-25. Min. Amount Due: Rs. 2724.00"
        private val S12f_SBI_OUTSTANDING = Regex(
            """outstanding of Rs\.\s*([\d,.]+),?\s+on your credit card ending\s+(\d{4})\s+is due on\s+([\d\w\-]+).*?Min\.\s+Amount Due:\s+Rs\.\s*([\d,.]+)""",
            FLAGS
        )
        // ICICI Standing Instruction: "Payment of INR 337.25 towards Merchant Amazon ... ICICI Bank Credit Card 7004... is due by 19/03/2026"
        private val S12g_ICICI_SI = Regex(
            """Payment of\s+(?:INR|Rs\.?)\s*([\d,.]+)\s+towards\s+Merchant\s+(\w+).*?ICICI Bank Credit Card\s+(\d{4}).*?is due by\s+([\d/\-\w]+)""",
            FLAGS
        )

        /**
         * S12h — HDFC new multi-line statement format.
         * Corpus: "HDFC Bank Credit Card XX2660 Statement:\nTotal due: Rs.1,630.00\nMin.due: Rs.200.00\nPay by 02-10-2025"
         * Captures: (1)=cardLast4  (2)=totalAmount  (3)=dueDate
         */
        private val S12h_HDFC_NEW = Regex(
            """HDFC Bank Credit Card\s+XX(\d{4})\s+Statement:.*?Total due:\s*Rs\.([\d,.\-]+).*?Pay by\s+([\d/\-\w]+)""",
            FLAGS
        )

        /**
         * S12i — HDFC "Amount Due\n Rs.X … Pay instantly by DATE".
         * Corpus: "Amount Due\nRs.125017 on HDFC Bank Credit Card 2660. Pay instantly by 01/APR/2026"
         * Captures: (1)=amount  (2)=dueDate
         */
        private val S12i_HDFC_AMTDUE = Regex(
            """Amount\s+Due\s*[\n\r]+(?:Rs\.?|INR|₹)\s*([\d,.\-]+)\s+on\s+HDFC.*?(?:[Pp]ay\s+instantly\s+by|by)\s+([\d/\-\w]+)""",
            FLAGS
        )

        /**
         * S12j — ICICI "Pay Total Amount Due of Rs X or Minimum Amount Due of Rs Y by DATE towards ICICI … XXNNNN".
         * Corpus (2026 format): "Pay Total Amount Due of Rs 6,167.80 or Minimum Amount Due of Rs 350.00 by 02-Mar-26 towards ICICI Bank Credit Card XX7004"
         * Captures: (1)=totalAmount  (2)=dueDate  (3)=cardLast4
         */
        private val S12j_ICICI_NEW = Regex(
            """Pay\s+Total\s+Amount\s+Due\s+of\s+(?:Rs\.?|INR|₹)\s*([\d,.]+).{0,100}?by\s+([\d/\-\w]+)\s+towards\s+ICICI Bank Credit Card\s+XX(\d{4})""",
            FLAGS
        )

        /**
         * S12k — Generic "Amt Due Rs.X on … Credit Card / Card X{last4}" nudge (no date).
         * Corpus: "Amt Due Rs.118546 on HDFC Bank Card X2660? Pay with PayZapp"
         *         "Amt Due: Rs 822 on HDFC Bank Credit Card 2660"
         * Captures: (1)=amount  (2)=cardLast4
         */
        private val S12k_AMT_DUE_NUDGE = Regex(
            """(?:Amt|Amount)\s+Due\s*[:\s]+(?:Rs\.?|INR|₹)\s*([\d,.\-]+)\s+on\b.*?(?:Credit\s+Card|Card)\s+(?:X{1,4})?(\d{4})""",
            FLAGS
        )

        /**
         * S13 — "has requested money/payment … will be debited / a payment of …" (UPI collect request).
         */
        private val S13_COLLECT_REQUEST = Regex(
            """([\w\s&'.,-]+?)\s+has\s+requested\s+(?:money\b.*?On\s+approving,\s+$AMT\s+will\s+be\s+debited|a\s+payment\s+of\s+(?:INR|Rs\.?|₹)\s*([\d,.]+))""",
            FLAGS
        )

        // ── BANK NAME EXTRACTION ─────────────────────────────────────────────
        // Ordered by specificity (more specific names first)
        private val BANK_NAMES = listOf(
            "HDFC Bank"    to Regex("""HDFC\s*Bank""", RegexOption.IGNORE_CASE),
            "ICICI Bank"   to Regex("""ICICI\s*Bank""", RegexOption.IGNORE_CASE),
            "Bandhan Bank" to Regex("""Bandhan\s*Bank""", RegexOption.IGNORE_CASE),
            "Axis Bank"    to Regex("""Axis\s*Bank""", RegexOption.IGNORE_CASE),
            "Yes Bank"     to Regex("""Yes\s*Bank|YESBNK""", RegexOption.IGNORE_CASE),
            "Kotak Bank"   to Regex("""Kotak\s*(Mahindra)?\s*Bank""", RegexOption.IGNORE_CASE),
            "SBI"          to Regex("""SBI\s+(?:Credit\s+Card|Bank|Cardholder)""", RegexOption.IGNORE_CASE),
            "PNB"          to Regex("""PNB|Punjab\s+National\s+Bank""", RegexOption.IGNORE_CASE),
            "Paytm Bank"   to Regex("""Paytm\s*(?:Payments?\s*)?Bank|PPBL""", RegexOption.IGNORE_CASE),
            "Indian Bank"  to Regex("""Indian\s*Bank""", RegexOption.IGNORE_CASE),
            "BOB"          to Regex("""Bank\s+of\s+Baroda""", RegexOption.IGNORE_CASE),
            "Canara Bank"  to Regex("""Canara\s*Bank""", RegexOption.IGNORE_CASE),
        )

        /** Derives account type from SMS body: "Savings A/C", "Debit Card", or "Credit Card". */
        fun extractAccountType(body: String, method: String): String {
            val b = body.uppercase()
            if (method == "ATM" || b.contains("DEBIT CARD") || b.contains("ATM WITHDRAWAL")) return "Debit Card"
            if (method == "Card" || method == "Statement"
                || b.contains("CREDIT CARD") || b.contains("CREDIT LIMIT")
            ) return "Credit Card"
            return "Savings A/C"
        }

        /** Extracts the human-readable bank name from the SMS body. */
        fun extractBankName(body: String): String {
            for ((name, regex) in BANK_NAMES) {
                if (regex.containsMatchIn(body)) return name
            }
            return ""
        }

        // ── TIER 2: GENERIC FALLBACK PATTERNS ───────────────────────────────
        private val GENERIC_AMOUNT    = Regex("""(?:Rs\.?|INR|₹)\s*([\d,]+(?:\.\d{1,2})?)""", RegexOption.IGNORE_CASE)
        private val GENERIC_DEBIT     = Regex("""\b(debited|spent|withdrawn|paid|deducted|sent)\b""", RegexOption.IGNORE_CASE)
        private val GENERIC_CREDIT    = Regex("""\b(credited|deposited|received|refund(?:ed)?)\b""", RegexOption.IGNORE_CASE)
        private val GENERIC_ACCT_LAST4= Regex("""(?:[Aa]\/[Cc]|[Aa]cct|A\/c)\s+[X*]{2,}(\d{4})""")
        private val GENERIC_UPI_REF   = Regex("""(?:UPI\s*[Rr]ef(?:\s*[Nn]o\.?)?\s*|UPI:\s*)(\d{8,})""")
        private val GENERIC_NEFT_METHOD = Regex("""\b(NEFT|IMPS|RTGS)\b""", RegexOption.IGNORE_CASE)
        private val GENERIC_AVL_BAL   = Regex("""(?:[Aa]vl?\.?\s*[Bb]al(?:ance)?|[Aa]val\s+[Bb]al|Bal)\s*(?:INR|Rs\.?)?\s*([\d,]+(?:\.\d{1,2})?)""")
        private val GENERIC_MERCHANT_AT = Regex("""\bat\s+([A-Z][A-Za-z0-9 &'.\-]{2,40})(?=\s+on\b|\s*\d|\.)""")
        private val GENERIC_MERCHANT_TO = Regex("""\bto\s+([A-Z][A-Za-z0-9 &'.\-]{2,40})(?=\s+UPI|\s+on\b|\s+Ref\b)""")
    }  // end companion object

    // ── PUBLIC API ───────────────────────────────────────────────────────────

    override fun parse(address: String, body: String): ParsedTransactionData? {
        val b = body.trim()
        val bankName = extractBankName(b)

        // Try all structural patterns first (highest precision).
        val result = tryS1(b) ?: tryS2(b) ?: tryS3(b) ?: tryS4(b) ?: tryS5(b)
            ?: tryS6(b) ?: tryS7(b) ?: tryS8(b) ?: tryS9(b) ?: tryS10(b)
            ?: tryS11(b) ?: tryS12(b) ?: tryS13(b)
            // Fall through to the generic extractor if no structural pattern matched.
            ?: tryGeneric(b)

        return result?.copy(
            bankName    = bankName.ifEmpty { result.bankName },
            accountType = extractAccountType(b, result.method)
        )
    }

    // ── TIER 1 DISPATCH FUNCTIONS ────────────────────────────────────────────

    private fun tryS1(body: String): ParsedTransactionData? {
        val m = S1_MONEY_TRANSFER.find(body) ?: return null
        return ParsedTransactionData(
            amount       = parseAmount(m.groupValues[1]) ?: return null,
            isDebit      = true,
            accountLast4 = m.groupValues[2],
            merchant     = m.groupValues[3].trim(),
            reference    = m.groupValues[4],
            method       = "UPI"
        )
    }

    private fun tryS2(body: String): ParsedTransactionData? {
        val m = S2_AMT_SENT.find(body) ?: return null
        return ParsedTransactionData(
            amount       = parseAmount(m.groupValues[1]) ?: return null,
            isDebit      = true,
            accountLast4 = m.groupValues[2],
            merchant     = m.groupValues[3].trim(),
            reference    = m.groupValues[4],
            method       = "UPI"
        )
    }

    private fun tryS3(body: String): ParsedTransactionData? {
        val m = S3_DEBITED_UPI_REF.find(body) ?: return null
        return ParsedTransactionData(
            amount       = parseAmount(m.groupValues[1]) ?: return null,
            isDebit      = true,
            accountLast4 = m.groupValues[2],
            reference    = m.groupValues[3],
            method       = "UPI"
        )
    }

    private fun tryS4(body: String): ParsedTransactionData? {
        val m = S4_WITHDRAWN_ATM.find(body) ?: return null
        return ParsedTransactionData(
            amount           = parseAmount(m.groupValues[1]) ?: return null,
            isDebit          = true,
            accountLast4     = m.groupValues[2],
            merchant         = m.groupValues[3].trim(),
            availableBalance = parseAmount(m.groupValues[4]) ?: -1.0,
            method           = "ATM"
        )
    }

    private fun tryS5(body: String): ParsedTransactionData? {
        val m = S5_DEPOSITED.find(body) ?: return null
        return ParsedTransactionData(
            amount           = parseAmount(m.groupValues[1]) ?: return null,
            isDebit          = false,
            accountLast4     = m.groupValues[2],
            availableBalance = parseAmount(m.groupValues[3]) ?: -1.0,
            method           = "NEFT"
        )
    }

    private fun tryS6(body: String): ParsedTransactionData? {
        val m = S6_CREDITED_AVAIL_BAL.find(body) ?: return null
        return ParsedTransactionData(
            amount           = parseAmount(m.groupValues[2]) ?: return null,
            isDebit          = false,
            accountLast4     = m.groupValues[1],
            availableBalance = parseAmount(m.groupValues[3]) ?: -1.0,
            reference        = m.groupValues[4],
            method           = "UPI"
        )
    }

    private fun tryS7(body: String): ParsedTransactionData? {
        val m = S7_AC_CREDITED.find(body) ?: return null
        // group 1 may have 1-5 digits (e.g. "85095"); keep last 4
        val rawAcct = m.groupValues[1]
        val acct = if (rawAcct.length > 4) rawAcct.takeLast(4) else rawAcct
        return ParsedTransactionData(
            amount           = parseAmount(m.groupValues[2]) ?: return null,
            isDebit          = false,
            accountLast4     = acct,
            availableBalance = parseAmount(m.groupValues[3]) ?: -1.0,
            reference        = m.groupValues[4],
            method           = "UPI"
        )
    }

    private fun tryS8(body: String): ParsedTransactionData? {
        val m = S8_DEBITED_THRU_UPI.find(body) ?: return null
        return ParsedTransactionData(
            amount           = parseAmount(m.groupValues[2]) ?: return null,
            isDebit          = true,
            accountLast4     = m.groupValues[1],
            reference        = m.groupValues[3],
            availableBalance = parseAmount(m.groupValues[4]) ?: -1.0,
            method           = "UPI"
        )
    }

    private fun tryS9(body: String): ParsedTransactionData? {
        val m = S9_SPENT_CREDIT_CARD.find(body) ?: return null
        return ParsedTransactionData(
            amount       = parseAmount(m.groupValues[1]) ?: return null,
            isDebit      = true,
            accountLast4 = m.groupValues[2],
            merchant     = m.groupValues[3].trim(),
            method       = "Card"
        )
    }

    private fun tryS10(body: String): ParsedTransactionData? {
        val m = S10_RECEIVED_PAYMENT.find(body) ?: return null
        val rawMethod = m.groupValues[2].uppercase()
        val method = when {
            rawMethod.contains("UPI")  -> "UPI"
            rawMethod.contains("NEFT") -> "NEFT"
            rawMethod.contains("IMPS") -> "IMPS"
            rawMethod.contains("RTGS") -> "RTGS"
            else                       -> rawMethod.ifBlank { "Other" }
        }
        return ParsedTransactionData(
            amount           = parseAmount(m.groupValues[1]) ?: return null,
            isDebit          = false,
            availableBalance = parseAmount(m.groupValues[3]) ?: -1.0,
            method           = method
        )
    }

    private fun tryS11(body: String): ParsedTransactionData? {
        val m = S11_PAYMENT_PROCESSED.find(body) ?: return null
        return ParsedTransactionData(
            amount    = parseAmount(m.groupValues[1]) ?: return null,
            isDebit   = false,
            reference = m.groupValues[2],
            method    = "Card"
        )
    }

    private fun tryS12(body: String): ParsedTransactionData? {
        // S12a: SBI "Total Amt Due Rs X; Min Amt Due Rs Y; Payable by DATE"
        S12a_SBI_ESTATE.find(body)?.let { m ->
            val amt    = parseAmount(m.groupValues[1]) ?: return@let
            val minAmt = parseAmount(m.groupValues[2]) ?: 0.0
            val dateStr = m.groupValues[3]
            return ParsedTransactionData(amount = amt, isDebit = true, reference = dateStr,
                method = "Statement", dueDateMs = parseDueDateMs(dateStr), minDue = minAmt)
        }
        // S12b: HDFC "HDFC Bank Credit Card XX{last4} Statement: Total due amt: Rs.X … Due by:DATE"
        S12b_HDFC_STMT.find(body)?.let { m ->
            val amt    = parseAmount(m.groupValues[2]) ?: return@let
            val minAmt = parseAmount(m.groupValues[3]) ?: 0.0
            val dateStr = m.groupValues[4]
            return ParsedTransactionData(amount = amt, isDebit = true, accountLast4 = m.groupValues[1],
                reference = dateStr, method = "Statement", dueDateMs = parseDueDateMs(dateStr), minDue = minAmt)
        }
        // S12h: HDFC new "HDFC Bank Credit Card XX{last4} Statement:\nTotal due: Rs.X … Pay by DATE"
        S12h_HDFC_NEW.find(body)?.let { m ->
            val amt    = parseAmount(m.groupValues[2]) ?: return@let
            val dateStr = m.groupValues[3]
            return ParsedTransactionData(amount = amt, isDebit = true, accountLast4 = m.groupValues[1],
                reference = dateStr, method = "Statement", dueDateMs = parseDueDateMs(dateStr))
        }
        // S12c: ICICI "Total of Rs X or minimum of Rs Y is due by DATE"
        S12c_ICICI_STMT.find(body)?.let { m ->
            val amt    = parseAmount(m.groupValues[2]) ?: return@let
            val minAmt = parseAmount(m.groupValues[3]) ?: 0.0
            val dateStr = m.groupValues[4]
            return ParsedTransactionData(amount = amt, isDebit = true, accountLast4 = m.groupValues[1],
                reference = dateStr, method = "Statement", dueDateMs = parseDueDateMs(dateStr), minDue = minAmt)
        }
        // S12d: ICICI "Total Due INR X & Min Due INR Y to be paid by DATE on ICICI Bank Credit Card XXNNNN"
        S12d_ICICI_TOTALDUE.find(body)?.let { m ->
            val amt    = parseAmount(m.groupValues[1]) ?: return@let
            val minAmt = parseAmount(m.groupValues[2]) ?: 0.0
            val dateStr = m.groupValues[3]
            return ParsedTransactionData(amount = amt, isDebit = true, accountLast4 = m.groupValues[4],
                reference = dateStr, method = "Statement", dueDateMs = parseDueDateMs(dateStr), minDue = minAmt)
        }
        // S12e: ICICI "(Kindly pay|Pay) total due of Rs X … by DATE on ICICI Bank Credit Card XXNNNN"
        S12e_ICICI_KINDLYPAY.find(body)?.let { m ->
            val amt    = parseAmount(m.groupValues[1]) ?: return@let
            val minAmt = parseAmount(m.groupValues[2]) ?: 0.0
            val dateStr = m.groupValues[3]
            return ParsedTransactionData(amount = amt, isDebit = true, accountLast4 = m.groupValues[4],
                reference = dateStr, method = "Statement", dueDateMs = parseDueDateMs(dateStr), minDue = minAmt)
        }
        // S12j: ICICI 2026 "Pay Total Amount Due of Rs X … by DATE towards ICICI … XXNNNN"
        S12j_ICICI_NEW.find(body)?.let { m ->
            val amt    = parseAmount(m.groupValues[1]) ?: return@let
            val dateStr = m.groupValues[2]
            return ParsedTransactionData(amount = amt, isDebit = true, accountLast4 = m.groupValues[3],
                reference = dateStr, method = "Statement", dueDateMs = parseDueDateMs(dateStr))
        }
        // S12f: SBI "outstanding of Rs. X on your credit card ending NNNN is due on DATE"
        S12f_SBI_OUTSTANDING.find(body)?.let { m ->
            val amt    = parseAmount(m.groupValues[1]) ?: return@let
            val minAmt = parseAmount(m.groupValues[4]) ?: 0.0
            val dateStr = m.groupValues[3]
            return ParsedTransactionData(amount = amt, isDebit = true, accountLast4 = m.groupValues[2],
                reference = dateStr, method = "Statement", dueDateMs = parseDueDateMs(dateStr), minDue = minAmt)
        }
        // S12g: ICICI SI "Payment of INR X towards Merchant Y … ICICI Bank Credit Card NNNN … is due by DATE"
        S12g_ICICI_SI.find(body)?.let { m ->
            val amt    = parseAmount(m.groupValues[1]) ?: return@let
            val dateStr = m.groupValues[4]
            return ParsedTransactionData(amount = amt, isDebit = true, merchant = m.groupValues[2],
                accountLast4 = m.groupValues[3], reference = dateStr,
                method = "Statement", dueDateMs = parseDueDateMs(dateStr))
        }
        // S12i: HDFC "Amount Due\nRs.X on HDFC … Pay instantly by DATE"
        S12i_HDFC_AMTDUE.find(body)?.let { m ->
            val amt    = parseAmount(m.groupValues[1]) ?: return@let
            val dateStr = m.groupValues[2]
            return ParsedTransactionData(amount = amt, isDebit = true, reference = dateStr,
                method = "Statement", dueDateMs = parseDueDateMs(dateStr))
        }
        // S12k: generic "Amt Due Rs.X on … Credit Card XNNNN" (nudge, no date)
        S12k_AMT_DUE_NUDGE.find(body)?.let { m ->
            val amt = parseAmount(m.groupValues[1]) ?: return@let
            return ParsedTransactionData(amount = amt, isDebit = true, accountLast4 = m.groupValues[2],
                method = "Statement")
        }
        return null
    }

    private fun tryS13(body: String): ParsedTransactionData? {
        val m = S13_COLLECT_REQUEST.find(body) ?: return null
        // group 2 = amount from "On approving, Rs.X will be debited" branch
        // group 3 = amount from "a payment of INR X" branch
        val rawAmt = m.groupValues[2].ifBlank { m.groupValues[3] }
        return ParsedTransactionData(
            amount   = parseAmount(rawAmt) ?: return null,
            isDebit  = true,
            merchant = m.groupValues[1].trim(),
            method   = "UPI"
        )
    }

    // ── TIER 2: GENERIC FALLBACK ─────────────────────────────────────────────

    private fun tryGeneric(body: String): ParsedTransactionData? {
        val amtMatch = GENERIC_AMOUNT.find(body) ?: return null
        val amount   = parseAmount(amtMatch.groupValues[1]) ?: return null

        val hasDebit  = GENERIC_DEBIT.containsMatchIn(body)
        val hasCredit = GENERIC_CREDIT.containsMatchIn(body)

        // Ambiguous or directionless — skip to avoid wrong sign in dashboard.
        if (hasDebit == hasCredit) return null

        val isDebit = hasDebit

        val accountLast4 = GENERIC_ACCT_LAST4.find(body)?.groupValues?.get(1) ?: ""
        val upiRef       = GENERIC_UPI_REF.find(body)?.groupValues?.get(1) ?: ""
        val neftMethod   = GENERIC_NEFT_METHOD.find(body)?.groupValues?.get(1)?.uppercase()
        val balance      = GENERIC_AVL_BAL.find(body)?.groupValues?.get(1)?.let { parseAmount(it) } ?: -1.0

        // Prefer MERCHANT_TO for debits (money sent TO someone),
        // MERCHANT_AT for card purchases (spent AT a merchant).
        val merchant = if (isDebit)
            GENERIC_MERCHANT_TO.find(body)?.groupValues?.get(1)?.trim()
                ?: GENERIC_MERCHANT_AT.find(body)?.groupValues?.get(1)?.trim() ?: ""
        else
            GENERIC_MERCHANT_AT.find(body)?.groupValues?.get(1)?.trim() ?: ""

        val method = when {
            upiRef.isNotEmpty()      -> "UPI"
            neftMethod != null       -> neftMethod
            else                     -> "Other"
        }

        return ParsedTransactionData(
            amount           = amount,
            isDebit          = isDebit,
            merchant         = merchant,
            reference        = upiRef,
            accountLast4     = accountLast4,
            availableBalance = balance,
            method           = method
        )
    }

    // ── HELPER ───────────────────────────────────────────────────────────────

    /**
     * Parses an amount string like "1,13,964.00" or "4200" to a Double.
     * Strips commas and whitespace before conversion.
     * Returns null if the string is blank or not a valid number.
     */
    private fun parseAmount(raw: String): Double? {
        if (raw.isBlank()) return null
        return raw.replace(",", "").trim().toDoubleOrNull()
    }

    /**
     * Parses an Indian-bank due-date string into epoch milliseconds (IST).
     *
     * Handles the formats found across real bank SMS corpora:
     *  - "08/03/2024"   → DD/MM/YYYY
     *  - "02-12-2024"   → DD-MM-YYYY
     *  - "30-APR-24"    → DD-MMM-YY  (2-digit year → 2000+yy)
     *  - "06-APR-2025"  → DD-MMM-YYYY
     *  - "01/APR/2026"  → DD/MMM/YYYY
     *
     * Returns 0L if the string cannot be parsed (treated as "no due date").
     */
    private fun parseDueDateMs(raw: String): Long {
        if (raw.isBlank()) return 0L
        val s = raw.trim().uppercase()

        val months = mapOf(
            "JAN" to 1,  "FEB" to 2,  "MAR" to 3,  "APR" to 4,
            "MAY" to 5,  "JUN" to 6,  "JUL" to 7,  "AUG" to 8,
            "SEP" to 9,  "OCT" to 10, "NOV" to 11, "DEC" to 12
        )

        // DD-MMM-YY / DD-MMM-YYYY / DD/MMM/YYYY  (e.g. "30-APR-24", "01/APR/2026")
        val alphaMonth = Regex("""^(\d{1,2})[/\-]([A-Z]{3})[/\-](\d{2,4})$""").matchEntire(s)
        if (alphaMonth != null) {
            val day   = alphaMonth.groupValues[1].toIntOrNull() ?: return 0L
            val mon   = months[alphaMonth.groupValues[2]] ?: return 0L
            val rawYr = alphaMonth.groupValues[3].toIntOrNull() ?: return 0L
            val year  = if (rawYr < 100) 2000 + rawYr else rawYr
            return toEpochMs(year, mon, day)
        }

        // DD/MM/YYYY or DD-MM-YYYY  (e.g. "08/03/2024", "02-12-2024")
        val numericDate = Regex("""^(\d{1,2})[/\-](\d{1,2})[/\-](\d{2,4})$""").matchEntire(s)
        if (numericDate != null) {
            val day   = numericDate.groupValues[1].toIntOrNull() ?: return 0L
            val mon   = numericDate.groupValues[2].toIntOrNull() ?: return 0L
            val rawYr = numericDate.groupValues[3].toIntOrNull() ?: return 0L
            val year  = if (rawYr < 100) 2000 + rawYr else rawYr
            return toEpochMs(year, mon, day)
        }

        return 0L
    }

    private fun toEpochMs(year: Int, month: Int, day: Int): Long {
        return try {
            val cal = java.util.Calendar.getInstance(
                java.util.TimeZone.getTimeZone("Asia/Kolkata")
            )
            cal.set(year, month - 1, day, 0, 0, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            cal.timeInMillis
        } catch (_: Exception) { 0L }
    }
}










