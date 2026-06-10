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
 * Pure, thread-safe utility for extracting OTP codes and expiry hints from SMS bodies.
 *
 * Designed to be called from NotificationManagerImpl when building an OTP notification banner.
 * No Android dependencies — fully testable on the JVM.
 */
object OtpExtractor {

    // ── Code extraction ──────────────────────────────────────────────────────

    /** "Your OTP is 847291" / "otp: 465061" — keyword BEFORE digits */
    private val CODE_KEYWORD_BEFORE = Regex(
        """(?:otp|one.?time.?pass(?:word)?|verification.?code|auth(?:entication)?.?code|passcode|code|pin)\s*(?:is|:|-|=)?\s*(\d{4,8})\b""",
        RegexOption.IGNORE_CASE
    )

    /** "847291 is your OTP" / "394821 is the code" — digits BEFORE keyword */
    private val CODE_DIGITS_BEFORE = Regex(
        """\b(\d{4,8})\b.{0,60}\bis\s+(?:your|the)\s+(?:otp|code|password|pin|verification)\b""",
        RegexOption.IGNORE_CASE
    )

    /** "Do not share 760440" */
    private val CODE_DO_NOT_SHARE = Regex(
        """do not share.{0,40}?(\d{4,8})\b""",
        RegexOption.IGNORE_CASE
    )

    /** "Secure Delivery Code is 371310" / "OTP 672378 for your shipment" */
    private val CODE_DELIVERY = Regex(
        """(?:(?:secure\s+)?delivery\s+(?:code|pin|otp)\s*(?:is|:)?\s*(\d{4,8})|\bOTP\s+(\d{4,8})\s+(?:for|will\s+be))""",
        RegexOption.IGNORE_CASE
    )

    /** Fallback: any standalone 4–8 digit sequence */
    private val CODE_FALLBACK = Regex("""\b(\d{4,8})\b""")

    /**
     * Attempts to extract the OTP code from [body].
     * Returns the bare digit string (e.g. "847291"), or null if none found.
     * Order: keyword-before → digits-before → do-not-share → delivery → fallback.
     */
    fun extractCode(body: String): String? {
        CODE_KEYWORD_BEFORE.find(body)?.groupValues?.getOrNull(1)
            ?.takeIf { it.isNotEmpty() }?.let { return it }

        CODE_DIGITS_BEFORE.find(body)?.groupValues?.getOrNull(1)
            ?.takeIf { it.isNotEmpty() }?.let { return it }

        CODE_DO_NOT_SHARE.find(body)?.groupValues?.getOrNull(1)
            ?.takeIf { it.isNotEmpty() }?.let { return it }

        CODE_DELIVERY.find(body)?.let { mr ->
            val g1 = mr.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() }
            val g2 = mr.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }
            (g1 ?: g2)?.let { return it }
        }

        // Last resort: first isolated digit group of 4–8 chars
        return CODE_FALLBACK.find(body)?.groupValues?.getOrNull(1)
    }

    // ── Expiry extraction ────────────────────────────────────────────────────

    /**
     * Matches common expiry phrasings:
     *  - "valid for 10 minutes" / "valid for 5 mins"
     *  - "expires in 10 min" / "expiry: 30 min"
     *  - "valid for next 5 minutes"
     *  - "OTP is valid for 10 minutes only"
     */
    private val EXPIRY_PATTERN = Regex(
        """(?:valid(?:ity)?\s+(?:for\s+)?(?:next\s+)?|expir(?:es?|y)\s*(?:in|:)\s*)(\d+)\s*(second|sec|minute|min|hour|hr)s?\b""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Returns a short human-readable expiry string like "10 min", "30 sec", "1 hour",
     * or null if no expiry phrase is found in [body].
     */
    fun extractExpiry(body: String): String? {
        val match = EXPIRY_PATTERN.find(body) ?: return null
        val amount = match.groupValues[1]
        val unit   = match.groupValues[2].lowercase()
        val normalised = when {
            unit.startsWith("sec") -> if (amount == "1") "sec" else "secs"
            unit.startsWith("min") -> if (amount == "1") "min" else "mins"
            else                   -> if (amount == "1") "hour" else "hours"
        }
        return "$amount $normalised"
    }
}

