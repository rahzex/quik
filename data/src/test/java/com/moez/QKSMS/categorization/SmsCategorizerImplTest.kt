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
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [SmsCategorizerImpl].
 *
 * ABOUT UNIT TESTS
 * ─────────────────
 * Unit tests verify small isolated pieces of logic without needing a device or emulator.
 * They run directly on your development machine (JVM) and are very fast — usually
 * milliseconds per test.
 *
 * Location: `data/src/test/` (NOT `androidTest/`) — this is a pure JVM test,
 * no Android framework needed because SmsCategorizerImpl has zero Android dependencies.
 *
 * To run: in Android Studio, right-click this file → "Run SmsCategorizerImplTest"
 * OR run from terminal: ./gradlew :data:test
 *
 * HOW TO READ THESE TESTS
 * ───────────────────────
 * Each `@Test` function is a single test case:
 *   1. Arrange: set up the inputs (SMS body + sender address)
 *   2. Act:     call categorizer.categorize(address, body)
 *   3. Assert:  verify the result matches the expected [MessageCategory]
 *
 * `assertEquals(expected, actual)` fails the test if the two values differ.
 * A PASSING test means the categorizer correctly identified the SMS type.
 * A FAILING test means the regex rules need to be adjusted.
 */
class SmsCategorizerImplTest {

    // Create the categorizer once for all tests (it's stateless and thread-safe)
    private val categorizer = SmsCategorizerImpl()

    // ═══════════════════════════════════════════════════════════════════════════
    // OTP TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `SBI OTP format - OTP keyword first then digits`() {
        val result = categorizer.categorize(
            address = "VM-SBIINB",
            body    = "Your OTP for SBI NetBanking is 847291. Valid for 10 minutes. Do not share."
        )
        assertEquals(MessageCategory.OTP, result)
    }

    @Test
    fun `Paytm OTP format - digits first then is your OTP`() {
        val result = categorizer.categorize(
            address = "AM-PAYTMB",
            body    = "394821 is your OTP for Paytm login. OTP expires in 2 min. Do not share."
        )
        assertEquals(MessageCategory.OTP, result)
    }

    @Test
    fun `HDFC OTP format - your OTP is`() {
        val result = categorizer.categorize(
            address = "VM-HDFCBK",
            body    = "Dear Customer, your OTP is 293847 for HDFC Bank NetBanking. Valid for 30 seconds."
        )
        assertEquals(MessageCategory.OTP, result)
    }

    @Test
    fun `Generic verification code OTP`() {
        val result = categorizer.categorize(
            address = "BT-VERIFY",
            body    = "Your verification code: 558821. Use this to complete your sign-in."
        )
        assertEquals(MessageCategory.OTP, result)
    }

    @Test
    fun `One-time password format`() {
        val result = categorizer.categorize(
            address = "TM-GPAYMS",
            body    = "Use one-time password 192847 to verify your Google Pay transaction."
        )
        assertEquals(MessageCategory.OTP, result)
    }

    @Test
    fun `ICICI OTP - OTP detection beats transaction detection (bank sender)`() {
        // This is critical: the sender looks like a bank, but the body is an OTP.
        // OTP should win because it has higher priority in the rule chain.
        val result = categorizer.categorize(
            address = "AM-ICICIB",
            body    = "Your OTP is 481929 for ICICI Bank transaction. Do NOT share with anyone."
        )
        assertEquals(MessageCategory.OTP, result)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TRANSACTION TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `HDFC debit via UPI`() {
        val result = categorizer.categorize(
            address = "VM-HDFCBK",
            body    = "Acct XX4821 debited Rs.4,200.00 on 23-May-26 via UPI ref 428192811. Avl Bal Rs.38,421.00"
        )
        assertEquals(MessageCategory.TRANSACTIONS, result)
    }

    @Test
    fun `ICICI credit via NEFT`() {
        val result = categorizer.categorize(
            address = "AM-ICICIB",
            body    = "INR 12,000.00 credited to Acct XX9934 via NEFT. Avl Bal: INR 50,821.00. Ref: INFT28192."
        )
        assertEquals(MessageCategory.TRANSACTIONS, result)
    }

    @Test
    fun `SBI account alert - debit`() {
        val result = categorizer.categorize(
            address = "TM-SBIINB",
            body    = "Your SBI Acct XX1234 debited for Rs.850.00 on 22-May-26. Bal: Rs.12,000.50."
        )
        assertEquals(MessageCategory.TRANSACTIONS, result)
    }

    @Test
    fun `Axis Bank UPI payment`() {
        val result = categorizer.categorize(
            address = "BT-AXISBK",
            body    = "Rs.599.00 paid from A/c XX8812 via UPI on 22-May. Avl Bal Rs.9,200.00. -Axis Bank"
        )
        assertEquals(MessageCategory.TRANSACTIONS, result)
    }

    @Test
    fun `Amount and debited keyword without known sender`() {
        val result = categorizer.categorize(
            address = "VM-NEWBNK",
            body    = "Your account XX7734 debited ₹2,500 on 22-May-2026. Available balance: ₹8,700."
        )
        assertEquals(MessageCategory.TRANSACTIONS, result)
    }

    @Test
    fun `Salary credit transaction`() {
        val result = categorizer.categorize(
            address = "VM-KOTAKB",
            body    = "Salary of INR 45,000.00 credited to your Kotak A/c XX1122. Avl Bal: INR 67,421."
        )
        assertEquals(MessageCategory.TRANSACTIONS, result)
    }

    @Test
    fun `Credit card EMI`() {
        val result = categorizer.categorize(
            address = "BT-HDFCBK",
            body    = "EMI of Rs.3,450 due on 01-Jun-2026 for your HDFC Credit Card XX4321. Auto-debit scheduled."
        )
        assertEquals(MessageCategory.TRANSACTIONS, result)
    }

    @Test
    fun `Paytm payment success`() {
        val result = categorizer.categorize(
            address = "AM-PAYTMB",
            body    = "Payment of Rs.120 to Big Bazaar successful via Paytm Wallet. Ref 189273."
        )
        assertEquals(MessageCategory.TRANSACTIONS, result)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PROMO TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Swiggy discount promo`() {
        val result = categorizer.categorize(
            address = "AD-SWIGGY",
            body    = "Get 50% off your next Swiggy order. Use code SAVE50. Valid till midnight tonight!"
        )
        assertEquals(MessageCategory.PROMOS, result)
    }

    @Test
    fun `AD- sender prefix marks promotional`() {
        val result = categorizer.categorize(
            address = "AD-ZOMATO",
            body    = "Order now and save big. Flat 30% off on all restaurants. Limited time offer!"
        )
        assertEquals(MessageCategory.PROMOS, result)
    }

    @Test
    fun `Cashback offer`() {
        val result = categorizer.categorize(
            address = "DM-AMAZON",
            body    = "Exclusive offer: Get 10% cashback on your next Amazon Pay transaction. Apply code CASHBACK10."
        )
        assertEquals(MessageCategory.PROMOS, result)
    }

    @Test
    fun `Sale announcement`() {
        val result = categorizer.categorize(
            address = "VM-FLIPKT",
            body    = "Big Billion Days sale starts tomorrow! Flat 40% off on electronics. Hurry, limited stock!"
        )
        assertEquals(MessageCategory.PROMOS, result)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // UPDATE TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Flipkart order out for delivery`() {
        val result = categorizer.categorize(
            address = "VM-FKFLIP",
            body    = "Your Flipkart order #FKrt12345 is out for delivery. Expected: Today by 7 PM."
        )
        assertEquals(MessageCategory.UPDATES, result)
    }

    @Test
    fun `IndiGo flight PNR confirmation`() {
        val result = categorizer.categorize(
            address = "AD-INDIGO",
            body    = "Booking confirmed! PNR: 6E-ABC123. IndiGo flight 6E-441 DEL→BOM on 28-May-2026 06:45."
        )
        assertEquals(MessageCategory.UPDATES, result)
    }

    @Test
    fun `Amazon order shipped`() {
        val result = categorizer.categorize(
            address = "TM-AMAZON",
            body    = "Your Amazon order #405-1234567-8901234 has been shipped. Expected delivery: 25-May."
        )
        assertEquals(MessageCategory.UPDATES, result)
    }

    @Test
    fun `Appointment reminder`() {
        val result = categorizer.categorize(
            address = "VM-APOLLO",
            body    = "Your appointment with Dr. Sharma is confirmed for 26-May-2026 at 11:00 AM at Apollo Hospital."
        )
        assertEquals(MessageCategory.UPDATES, result)
    }

    @Test
    fun `Jio recharge confirmation`() {
        val result = categorizer.categorize(
            address = "JM-JIOMON",
            body    = "Your Jio recharge of Rs.599 is successful. Validity 84 days. Activated on 22-May-2026."
        )
        assertEquals(MessageCategory.UPDATES, result)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SPAM TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Prize scam message`() {
        val result = categorizer.categorize(
            address = "+919876543210",
            body    = "Congratulations! You have WON a brand new iPhone 15. Click here to claim your prize: http://bit.ly/fake"
        )
        assertEquals(MessageCategory.SPAM, result)
    }

    @Test
    fun `Loan approval spam`() {
        val result = categorizer.categorize(
            address = "VM-LOANSS",
            body    = "Pre-approved loan of Rs.5,00,000 is waiting for you! Get rich fast. Apply now with 0 documents."
        )
        assertEquals(MessageCategory.SPAM, result)
    }

    @Test
    fun `Lottery scam`() {
        val result = categorizer.categorize(
            address = "+918800112233",
            body    = "You have been selected as winner of our KBC lottery. Claim now Rs.25 Lakh prize!"
        )
        assertEquals(MessageCategory.SPAM, result)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PERSONAL TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Normal personal message from Indian number`() {
        val result = categorizer.categorize(
            address = "+919876543210",
            body    = "Bhai aaj aayega na? Let me know by 6 PM."
        )
        assertEquals(MessageCategory.PERSONAL, result)
    }

    @Test
    fun `Personal message without country code`() {
        val result = categorizer.categorize(
            address = "9876543210",
            body    = "Are you coming to the party tonight?"
        )
        assertEquals(MessageCategory.PERSONAL, result)
    }

    @Test
    fun `Personal message from mom`() {
        val result = categorizer.categorize(
            address = "+919811223344",
            body    = "Kha liya? Ghar kab aa raha hai?"
        )
        assertEquals(MessageCategory.PERSONAL, result)
    }

    @Test
    fun `Short personal message`() {
        val result = categorizer.categorize(
            address = "9700000001",
            body    = "Ok"
        )
        assertEquals(MessageCategory.PERSONAL, result)
    }

    @Test
    fun `Unknown sender with unmatched body defaults to personal`() {
        val result = categorizer.categorize(
            address = "TM-UNKNWN",
            body    = "Your account details have been updated as requested."
        )
        // "updated" isn't a keyword in our rules → should default to PERSONAL
        assertEquals(MessageCategory.PERSONAL, result)
    }
}

