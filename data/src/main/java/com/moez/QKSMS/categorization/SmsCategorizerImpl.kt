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

import dev.octoshrimpy.quik.model.MessageCategory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device, privacy-first SMS categorization engine.
 *
 * ── TRAI SENDER ID FORMAT (updated from real data) ──────────────────────────
 * India's TRAI mandates alphanumeric sender IDs for commercial SMSes. The format is:
 *
 *   [2-letter telecom prefix] [optional hyphen] [entity code] [optional suffix]
 *
 * Examples seen in real data:
 *   ADHDFCBK     → AD (Airtel?) + HDFCBK  (HDFC Bank, no hyphen — older/common format)
 *   VM-HDFCBK-S  → VM + hyphen + HDFCBK + -S suffix  (newer format with hyphen)
 *   VMHDFCBKS    → VM + HDFCBKS  (S-suffix without hyphen)
 *   JMHDFCBK     → JM + HDFCBK
 *   VMSBICRD     → VM + SBICRD  (SBI Credit Card)
 *   JKJIOPAY-S   → JK + JIOPAY + -S
 *
 * The key pattern is: `^[A-Z]{2}-?ENTITY_CODE(-[A-Z])?$`
 * where the 2-letter prefix and optional hyphen may or may not be present.
 *
 * ── RULE PRIORITY ──────────────────────────────────────────────────────────
 * 1. OTP
 * 2. Known telecom/delivery/govt UPDATE senders (before generic amount checks)
 * 3. Transactions (bank/payment sender IDs + amount/keyword)
 * 4. Promos
 * 5. Updates (body keywords)
 * 6. Spam
 * 7. Personal (default)
 *
 * ── WHY UPDATES BEFORE TRANSACTIONS (for some senders) ─────────────────────
 * Tata Play, Jio, and BSNL recharge messages contain rupee amounts but are
 * service updates, not bank transactions. Checking known telecom/delivery sender IDs
 * BEFORE the generic "amount + keyword" heuristic prevents mis-classification.
 *
 * @Singleton: Created once. Precompiled Regex objects stay in memory permanently,
 * which matters since categorize() is called for every message in the batch worker.
 */
@Singleton
class SmsCategorizerImpl @Inject constructor() : SmsCategorizer {

    companion object {

        // ── OTP PATTERNS ──────────────────────────────────────────────────────

        /** "Your OTP is 847291" / "otp is 465061" */
        private val OTP_KEYWORD_FIRST = Regex(
            """(otp|one.?time.?pass(?:word)?|verification.?code|auth(?:entication)?.?code|passcode)\b.{0,100}\b\d{4,8}\b""",
            RegexOption.IGNORE_CASE
        )

        /** "394821 is your OTP" / "465061 is the code" */
        private val OTP_DIGITS_FIRST = Regex(
            """\b(\d{4,8})\b.{0,60}\bis\s+(?:your|the)\s+(?:otp|code|password|pin|verification)\b""",
            RegexOption.IGNORE_CASE
        )

        /** "your OTP is 847291" / "your code: 394821" */
        private val OTP_YOUR_CODE = Regex(
            """\byour\s+(?:otp|code|pin|pass(?:word)?)\s*(?:is|:)\s*\d{4,8}\b""",
            RegexOption.IGNORE_CASE
        )

        /**
         * "Do not share 760440" — used by BHIM, HDFC, many banks.
         * Distinct from OTP_KEYWORD_FIRST because this phrasing alone is a strong OTP signal.
         */
        private val OTP_DO_NOT_SHARE = Regex(
            """do not share.{0,40}\d{4,8}""",
            RegexOption.IGNORE_CASE
        )

        /**
         * Delivery-specific OTP codes: "Secure Delivery Code is 371310" (Blue Dart),
         * "OTP 672378 for your shipment" (Ekart).
         * These don't say "OTP" explicitly but are delivery PINs — functionally OTPs.
         */
        private val OTP_DELIVERY_CODE = Regex(
            """\b(?:(?:secure\s+)?delivery\s+(?:code|pin|otp)\b.{0,60}\b\d{4,8}\b|\b\d{4,8}\b.{0,60}(?:secure\s+)?delivery\s+(?:code|pin|otp)\b|OTP\s+\d{4,8}\s+(?:for|will\s+be)\s+(?:your\s+)?(?:shipment|delivery|parcel))\b""",
            RegexOption.IGNORE_CASE
        )

        // ── TRANSACTION SENDER IDs ─────────────────────────────────────────────
        //
        // Pattern: ^[A-Z]{2} (2-letter prefix) + -? (optional hyphen) + ENTITY + suffix?
        //
        // Entity codes validated against real corpus:
        //   HDFCBK / HDFCBKS — HDFC Bank
        //   SBICRD / SBICRDS / SBICRDP — SBI Credit Card
        //   SBIINB — SBI Internet Banking
        //   ICICIB / ICICIT — ICICI Bank (ICICIO = OTP-only, handled separately)
        //   AXISBK — Axis Bank
        //   PNBSMS — Punjab National Bank
        //   IDFCBK — IDFC First Bank
        //   KOTAKB — Kotak Mahindra Bank
        //   YESBNK — Yes Bank
        //   BDNSMS — Bandhan Bank (security messages filtered out separately)
        //   MPPOJA / VMPOJA — local Golpo wallet (regional payment service)

        private val BANK_SENDER_ID = Regex(
            """^[A-Z]{2}-?(?:HDFCBK[S]?|SBICRD[SP]?|SBIINB[S]?|ICICIB[S]?|ICICIT[S]?""" +
            """|AXISBK[S]?|PNBSMS[S]?|IDFCBK[S]?|KOTAKB[S]?|YESBNK[S]?|BDNSMS[S]?""" +
            """|RBLBNK[S]?|AUBANK[S]?|INDBNK[S]?|BOBIBN[S]?|FEDBKM[S]?|JKBANK[S]?""" +
            """|MPPOJA[S]?|VMPOJA[S]?)(?:-[A-Z])?$""",
            RegexOption.IGNORE_CASE
        )

        /**
         * ICICI OTP-dedicated sender (AD-ICICIO-T etc.).
         * Always OTP; never transaction. Checked in the OTP block.
         */
        private val ICICI_OTP_SENDER = Regex(
            """^[A-Z]{2}-?ICICIO(?:-[A-Z])?$""",
            RegexOption.IGNORE_CASE
        )

        /**
         * HDFC Bank notification/marketing senders (VMHDFCBN, ADHDFCBN, etc.).
         * These send eligibility offers, maintenance notices — not transaction alerts.
         * Separated from BANK_SENDER_ID to route them to UPDATES.
         */
        private val HDFC_NOTIF_SENDER = Regex(
            """^[A-Z]{2}-?HDFCBN(?:-[A-Z])?$""",
            RegexOption.IGNORE_CASE
        )

        /**
         * UPI/wallet payment app senders.
         * NOTE: PHONPE (not PHONEP) is the real TRAI entity code for PhonePe.
         *       Validated against corpus: JMPHONPE, TMPHONPE, JD-PHONPE-S.
         */
        private val PAYMENT_SENDER_ID = Regex(
            """^[A-Z]{2}-?(?:PAYTMB|GPAYMS|PHONPE[S]?|AMZNPAY|MOBIKWK|BHIMUPI|NSDLPB|JIOMNY)(?:-[A-Z])?$""",
            RegexOption.IGNORE_CASE
        )

        /**
         * Paytm notification senders (login alerts, device registration).
         * These match ADiPaytm, AXiPaytm pattern — NOT the bank-format PAYTMB.
         * Routed to UPDATES (security alerts), not TRANSACTIONS.
         */
        private val PAYTM_NOTIF_SENDER = Regex(
            """^[A-Z]{2}i?Paytm$""",
            RegexOption.IGNORE_CASE
        )

        /** Generic bank-like sender ("BNKQ", "FIN", "BANK" suffixes) for lesser-known banks. */
        private val GENERIC_BANK_SENDER = Regex(
            """^[A-Z]{2}-?[A-Z0-9]{2,8}(?:BK|BNK|FIN|BANK|BNKQ)(?:-[A-Z])?$""",
            RegexOption.IGNORE_CASE
        )

        /** Transaction keywords found in the body. "balance" removed — too broad (Tata Play recharge). */
        private val TXN_BODY_KEYWORDS = Regex(
            """\b(?:debited|credited|payment|transferred|withdrawn|deposited|spent|refund|emi|""" +
            """mandate|auto.?debit|salary|neft|rtgs|imps|upi\s*ref|money transfer|""" +
            """amt sent|amount\s+(?:sent|paid)|a/c.{0,5}debited|a/c.{0,5}credited)\b""",
            RegexOption.IGNORE_CASE
        )

        /** Currency amount: ₹4,200 / Rs.4200 / INR 12,000 */
        private val AMOUNT_PATTERN = Regex(
            """(?:₹|Rs\.?\s*|INR\s*)[\d,]+(?:\.\d{1,2})?""",
            RegexOption.IGNORE_CASE
        )

        /** Bandhan Bank security/advisory body (route to UPDATES not TRANSACTIONS). */
        private val BANDHAN_SECURITY_BODY = Regex(
            """\b(?:beware|fraud|unauthori[sz]|never share|do not share|advisory|maintenance|unavailable)\b""",
            RegexOption.IGNORE_CASE
        )

        // ── UPDATE SENDER IDs — checked BEFORE generic amount heuristics ────────
        //
        // IMPORTANT: These senders must be classified as UPDATES even when their
        // messages contain amounts (e.g. "Recharge Rs.200 successful").
        // Putting this check before the transaction heuristic prevents mis-classification.

        /**
         * Telecom / DTH service senders.
         * TPPLAY = Tata Play  |  JIOPAY = Jio payments  |  BSNLIN = BSNL  |  JIOVOC = Jio surveys
         * Real corpus: TXTPPLAY, TX-TPPLAY-S, JKJIOPAY-S, JK-JioPay-S, BVBSNLIN, BV-BSNLIN-S
         */
        private val UPDATE_TELECOM_SENDER = Regex(
            """^[A-Z]{2}-?(?:TPPLAY[S]?|JIOPAY[S]?|JIOVOC|BSNLIN[S]?|BSNLEZ|JIOBNK)(?:-[A-Z])?$""",
            RegexOption.IGNORE_CASE
        )

        /**
         * E-commerce delivery / logistics senders.
         * EKARTL = Ekart (Flipkart logistics)  |  FLPKRT = Flipkart  |  BLUDRT = Blue Dart  |  AJIOIN = AJIO
         * Note: Ekart messages can contain delivery OTPs — OTP check fires first.
         */
        private val UPDATE_DELIVERY_SENDER = Regex(
            """^[A-Z]{2}-?(?:EKARTL[S]?|FLPKRT[S]?|BLUDRT[S]?|AJIOIN[S]?)(?:-[A-Z])?$""",
            RegexOption.IGNORE_CASE
        )

        /** Government / regulatory senders: DOTGOI = Dept of Telecom, NDMAEW = NDMA weather, ISATHI = Sanchar Saathi */
        private val UPDATE_GOVT_SENDER = Regex(
            """^[A-Z]{2}-?(?:DOTGOI[G]?|NDMAEW|ISATHI[G]?|VELCTI)(?:-[A-Z])?$""",
            RegexOption.IGNORE_CASE
        )

        /** Financial services (non-bank): NSE/Zerodha brokerage, IRCTC. */
        private val UPDATE_FINSERV_SENDER = Regex(
            """^[A-Z]{2}-?(?:NSESMS|ZERODHA|IRCTCi|IRCTTC)(?:-[A-Z])?$""",
            RegexOption.IGNORE_CASE
        )

        /** Update body keywords: delivery, travel, telecom, account maintenance. */
        private val UPDATE_BODY_KEYWORDS = Regex(
            """\b(?:order|deliver(?:y|ed|ing)?|shipped|dispatch(?:ed)?|ofd|out\s+for\s+delivery|""" +
            """appointment|booking\s+confirm(?:ed)?|ticket|boarded|flight|pnr|""" +
            """account\s+update|statement|due\s+date|recharge|validity|subscription\b|""" +
            """renewed|activated|deactivated|incoming\s+call|missed\s+call|train|""" +
            """cancelled|pickup|track(?:ing)?|maintenance|unavailable|""" +
            """scheduled\s+maintenance|advisory|warning|expire|freebies|""" +
            """plan\s+expir|your\s+plan|data\s+(?:used|usage))\b""",
            RegexOption.IGNORE_CASE
        )

        // ── BILL REMINDER PATTERNS ───────────────────────────────────────────
        //
        // Covers bill reminders from ALL issuers — no bank names in any regex.
        // Signal: message announces money OWED in the future (due date / payable by).
        //
        // Corpus-validated patterns (derived from real SBI/HDFC/ICICI/OlaMoney SMSes):
        //   SBI:       "Total Amt Due Rs X; Min Amt Due Rs Y; Payable by DATE"
        //   HDFC old:  "Total due amt: Rs.X  Min due amt: Rs.Y  Due by:DATE"
        //   HDFC new:  "Total due: Rs.X  Min.due: Rs.Y  Pay by DATE"
        //   HDFC nudge:"Amount Due\nRs.X on HDFC … Pay instantly by DATE"
        //   HDFC nudge:"Amt Due Rs.X on … Card"  (no explicit date — amount-due notice)
        //   ICICI:     "Total of Rs X or minimum of Rs Y is due by DATE"
        //   ICICI:     "Pay Total Due of Rs X or Min Due Rs Y by DATE"
        //   ICICI:     "Pay Total Amount Due of Rs X or Minimum Amount Due of Rs Y by DATE"
        //   ICICI SI:  "Payment of INR X towards Merchant Y … is due by DATE"
        //   SBI:       "outstanding of Rs. X on your credit card ending N is due on DATE"
        //   OlaMoney:  "bill of Rs. X is due. Please pay before DATE to avoid late fee"
        //   Generic:   any "is due by DATE", "to be paid by DATE", "payable by DATE"

        /**
         * Strong bill reminder keywords — bank/issuer agnostic.
         * Any of these phrases in the body, combined with a currency amount and the
         * absence of a past-tense completion marker, signals a BILL_REMINDER.
         */
        private val BILL_REMINDER_STRONG = Regex(
            """\b(?:""" +
            """total\s+(?:amt\s+)?due\b|""" +                  // "Total Amt Due" / "Total due"
            """min(?:imum)?\s+(?:amt\s+)?due\b|""" +           // "Min Amt Due" / "Minimum Due"
            """payable\s+by\s+\d|""" +                         // "Payable by 08/03/2024"
            """outstanding\s+of\s+(?:Rs|INR|₹)|""" +           // "outstanding of Rs. 43295"
            """is\s+due\s+(?:by|on)\s+\d|""" +                 // "is due by 30-APR" / "is due on 06-APR"
            """to\s+be\s+paid\s+by\s+\d|""" +                  // "to be paid by 30-Apr"
            """pay\s+by\s+\d{2}[-/]|""" +                      // "Pay by 01-04-2026" (HDFC new format)
            """pay\s+instantly\s+by\s+\d|""" +                  // "Pay instantly by 04/MAR/2026"
            """pay\s+total\s+(?:amount\s+)?due\b|""" +          // "Pay Total Due" / "Pay Total Amount Due"
            """bill\s+(?:of\s+(?:Rs|INR|₹).{0,40}?)?\bis\s+due\b|""" +  // "bill of Rs.X is due"
            """amt\s+due\s*(?:Rs|INR|₹|:)|""" +                // "Amt Due Rs.X" / "Amt Due:"
            """amount\s+due\s*[\n\r]|""" +                     // "Amount Due\n" (HDFC multi-line)
            """payment\s+of\s+(?:Rs|INR|₹).{0,80}?is\s+due\s+by""" + // "Payment of Rs X … is due by DATE"
            """)""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )

        /**
         * Guards against already-completed payments being mis-classified as bill reminders.
         * If these past-tense markers are present, the message is a TRANSACTION confirmation,
         * not an upcoming bill notice — even if it also contains "due" language.
         */
        private val BILL_ALREADY_PAID = Regex(
            """\b(?:has\s+been\s+received|payment\s+received|debited|withdrawn|spent""" +
            """|credited\s+to\s+ICICI|received\s+on\s+your\s+ICICI)\b""",
            RegexOption.IGNORE_CASE
        )

        // ── PROMOTIONAL PATTERNS ──────────────────────────────────────────────

        /** Body keywords for promotional/marketing messages. */
        private val PROMO_BODY_KEYWORDS = Regex(
            """\b(?:off|discount|sale|offer|coupon|cashback|promo(?:tion)?|deal|flat\s+\d+\s*%|""" +
            """use\s+code|apply\s+code|hurry|limited.?time|expires?|valid\s+till|book\s+now|""" +
            """exclusive|win\s+a|get\s+\d+%|subscribe\s+now|special\s+offer|avail\s+now|""" +
            """upgrade\s+now|enjoy\s+\w+\s+free)\b""",
            RegexOption.IGNORE_CASE
        )

        /** Dedicated promo senders: BSNLWB = BSNL WiFi offers, JIOINF = Jio info/offers. */
        private val PROMO_SENDER = Regex(
            """^[A-Z]{2}-?(?:BSNLWB|JIOINF|655155P?)(?:-[A-Z])?$""",
            RegexOption.IGNORE_CASE
        )

        // ── SPAM PATTERNS ─────────────────────────────────────────────────────

        private val SPAM_BODY_KEYWORDS = Regex(
            """\b(?:you\s+have\s+(?:won|been\s+selected)|winner|prize|lottery|""" +
            """claim\s+now|free\s+gift|loan\s+approved|pre.?approved\s+loan|""" +
            """get\s+rich|earn\s+from\s+home|make\s+money|work\s+from\s+home\s+earn)\b""",
            RegexOption.IGNORE_CASE
        )

        /**
         * Matches addresses that look like a real phone number:
         *  - International E.164:  +919876543210
         *  - Local numeric:        9876543210
         *  - Formatted:            +1 (555) 123-4567
         * Alphanumeric sender IDs (e.g. VM-HDFCBK, AD-SWIGGY) will NOT match this.
         */
        private val PHONE_NUMBER = Regex("""^\+?[\d\s\-().]{7,20}$""")
    }

    override fun categorize(address: String, body: String): MessageCategory {
        val addr = address.trim()
        val bodyTrimmed = body.trim()

        // Pre-compute amount presence — used by both BILL_REMINDER and TRANSACTIONS checks.
        val hasAmount = AMOUNT_PATTERN.containsMatchIn(bodyTrimmed)

        // ── 1. OTP (highest priority) ──────────────────────────────────────
        if (ICICI_OTP_SENDER.containsMatchIn(addr)) return MessageCategory.OTP
        if (OTP_KEYWORD_FIRST.containsMatchIn(bodyTrimmed) ||
            OTP_DIGITS_FIRST.containsMatchIn(bodyTrimmed)  ||
            OTP_YOUR_CODE.containsMatchIn(bodyTrimmed)      ||
            OTP_DO_NOT_SHARE.containsMatchIn(bodyTrimmed)   ||
            OTP_DELIVERY_CODE.containsMatchIn(bodyTrimmed)) return MessageCategory.OTP

        // ── 2. Known UPDATE senders — checked BEFORE generic amount heuristics ──
        // These senders' messages may contain amounts (e.g. Tata Play recharge)
        // but are service updates, not financial transactions.
        if (UPDATE_TELECOM_SENDER.containsMatchIn(addr) ||
            UPDATE_DELIVERY_SENDER.containsMatchIn(addr) ||
            UPDATE_GOVT_SENDER.containsMatchIn(addr)     ||
            UPDATE_FINSERV_SENDER.containsMatchIn(addr)) return MessageCategory.UPDATES

        // Paytm notification senders (login alerts etc.)
        if (PAYTM_NOTIF_SENDER.containsMatchIn(addr)) return MessageCategory.UPDATES

        // ── 3. Bill Reminders — BEFORE generic transaction check ──────────
        // Bill reminders come from bank/service senders but announce money OWED
        // in the future (credit card due, EMI, postpaid bill). They must be checked
        // before step 4 because "emi" / "min due" are also in TXN_BODY_KEYWORDS.
        // Guard: past-tense completion markers (debited, received) → still TRANSACTIONS.
        if (BILL_REMINDER_STRONG.containsMatchIn(bodyTrimmed) && hasAmount
            && !BILL_ALREADY_PAID.containsMatchIn(bodyTrimmed)) {
            return MessageCategory.BILL_REMINDER
        }

        // ── 4. Transactions ────────────────────────────────────────────────
        val isBankSender    = BANK_SENDER_ID.containsMatchIn(addr)
        val isPaymentSender = PAYMENT_SENDER_ID.containsMatchIn(addr)
        val hasTxnKeyword   = TXN_BODY_KEYWORDS.containsMatchIn(bodyTrimmed)

        // HDFC notification senders → UPDATES (unless body is a real UPI transfer)
        if (HDFC_NOTIF_SENDER.containsMatchIn(addr)) {
            // "Amt Sent Rs." messages come from HDFCBN senders but are genuine transfers.
            // Treat them as TRANSACTIONS before routing to UPDATES.
            return if (bodyTrimmed.startsWith("Amt Sent", ignoreCase = true) || (hasAmount && hasTxnKeyword))
                MessageCategory.TRANSACTIONS
            else
                MessageCategory.UPDATES
        }

        // Bandhan Bank security/advisory messages → UPDATES
        if (Regex("""^[A-Z]{2}-?BDNSMS""", RegexOption.IGNORE_CASE).containsMatchIn(addr) &&
            BANDHAN_SECURITY_BODY.containsMatchIn(bodyTrimmed)) return MessageCategory.UPDATES

        if (isBankSender || isPaymentSender) {
            return if (hasAmount || hasTxnKeyword) MessageCategory.TRANSACTIONS
            else MessageCategory.UPDATES
        }

        if (hasAmount && hasTxnKeyword) return MessageCategory.TRANSACTIONS
        if (GENERIC_BANK_SENDER.containsMatchIn(addr) && hasAmount) return MessageCategory.TRANSACTIONS

        // ── 5. Promotions ──────────────────────────────────────────────────
        if (PROMO_SENDER.containsMatchIn(addr) || PROMO_BODY_KEYWORDS.containsMatchIn(bodyTrimmed))
            return MessageCategory.PROMOS

        // ── 6. Service Updates (body keywords) ────────────────────────────
        if (UPDATE_BODY_KEYWORDS.containsMatchIn(bodyTrimmed)) return MessageCategory.UPDATES

        // ── 7. Spam ────────────────────────────────────────────────────────
        if (SPAM_BODY_KEYWORDS.containsMatchIn(bodyTrimmed)) return MessageCategory.SPAM

        // ── 8. Personal — only real phone numbers ─────────────────────────
        // Alphanumeric sender IDs that reached here didn't match any known pattern.
        // Keep them in ALL (visible only in the All tab) rather than mis-labelling
        // them as personal conversations.
        if (PHONE_NUMBER.matches(addr)) return MessageCategory.PERSONAL

        // ── 9. Default: ALL ───────────────────────────────────────────────
        // Unknown alphanumeric sender — show in All tab only.
        return MessageCategory.ALL
    }
}




