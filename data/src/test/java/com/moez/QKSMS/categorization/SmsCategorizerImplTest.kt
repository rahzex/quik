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
    fun `Unknown alphanumeric sender with unmatched body returns ALL`() {
        // Alphanumeric senders that reach no rule are shown in the "All" tab only (not Personal).
        // Only real phone numbers (digits-only) return PERSONAL.
        val result = categorizer.categorize(
            address = "TM-UNKNWN",
            body    = "Your account details have been updated as requested."
        )
        assertEquals(MessageCategory.ALL, result)
    }

    @Test
    fun `Blue Dart delivery OTP is classified as OTP`() {
        val result = categorizer.categorize(
            address = "JMBLUDRT",
            body    = "Your Blue Dart Secure Delivery Code is 371310 and its valid for next 30 minutes."
        )
        assertEquals(MessageCategory.OTP, result)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // BILL REMINDER TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Test
    fun `SBI credit card e-statement with total due and payable by date`() {
        val result = categorizer.categorize(
            address = "VMSBICRD",
            body    = "E-statement of SBI Credit Card ending XX87 dated 17/02/2024 has been mailed. " +
                      "Total Amt Due Rs 5790; Min Amt Due Rs 290; Payable by 08/03/2024. Click https://sbicard.com/quickpay"
        )
        assertEquals(MessageCategory.BILL_REMINDER, result)
    }

    @Test
    fun `HDFC credit card statement with total due amt and due by date`() {
        val result = categorizer.categorize(
            address = "VAHDFCBK",
            body    = "HDFC Bank Credit Card XX2660 Statement: Total due amt: Rs.1,18,546.00 " +
                      "Min due amt: Rs.5,930.00 Due by:02-12-2024. View statement here: https://hdfcbk.io/abc"
        )
        assertEquals(MessageCategory.BILL_REMINDER, result)
    }

    @Test
    fun `HDFC new format statement with pay by date`() {
        val result = categorizer.categorize(
            address = "VMHDFCBKS",
            body    = "HDFC Bank Credit Card XX2660 Statement:\nTotal due: Rs.1,630.00\nMin.due: Rs.200.00\nPay by 02-10-2025\nView: https://1.hdfc.bank.in/abc"
        )
        assertEquals(MessageCategory.BILL_REMINDER, result)
    }

    @Test
    fun `ICICI credit card statement - total of Rs X or minimum is due by date`() {
        val result = categorizer.categorize(
            address = "JMICICIT",
            body    = "ICICI Bank Credit Card XX7004 Statement is sent to user@email.com. " +
                      "Total of Rs 65489.68 or minimum of Rs 3280 is due by 30-APR-24."
        )
        assertEquals(MessageCategory.BILL_REMINDER, result)
    }

    @Test
    fun `ICICI pay total due reminder with to be paid by date`() {
        val result = categorizer.categorize(
            address = "TMICICIT",
            body    = "Total Due INR 65489.68 & Min Due INR 3280 to be paid by 30-Apr-24 on ICICI Bank Credit Card XX7004. " +
                      "Non-payment of Min Due will be reported to credit bureaus"
        )
        assertEquals(MessageCategory.BILL_REMINDER, result)
    }

    @Test
    fun `ICICI Pay Total Due of Rs X by DATE reminder`() {
        val result = categorizer.categorize(
            address = "JMICICITS",
            body    = "Kindly pay total due of Rs 5999 or Min Due Rs 300 by 02-Mar-25 on ICICI Bank Credit Card XX7004 to avoid reporting to credit bureaus"
        )
        assertEquals(MessageCategory.BILL_REMINDER, result)
    }

    @Test
    fun `ICICI 2026 Pay Total Amount Due format`() {
        val result = categorizer.categorize(
            address = "AD-ICICIT-S",
            body    = "Pay Total Amount Due of Rs 6,167.80 or Minimum Amount Due of Rs 350.00 by 02-Mar-26 " +
                      "towards ICICI Bank Credit Card XX7004. Delay/Non-payment is reported to Credit Bureaus. Ignore if paid."
        )
        assertEquals(MessageCategory.BILL_REMINDER, result)
    }

    @Test
    fun `SBI outstanding credit card bill due on date`() {
        val result = categorizer.categorize(
            address = "CPSBICRD",
            body    = "Dear SBI Cardholder, outstanding of Rs. 43295.00, on your credit card ending 8987 " +
                      "is due on 06-APR-25. Min. Amount Due: Rs. 2724.00. Please ignore if already paid."
        )
        assertEquals(MessageCategory.BILL_REMINDER, result)
    }

    @Test
    fun `OlaMoney postpaid bill due with late fee warning`() {
        val result = categorizer.categorize(
            address = "VMOLACBS",
            body    = "Your OlaMoney Postpaid bill of Rs. 699.00 is due. Please pay before 14-December-2024 " +
                      "to avoid Rs. 100.0 late fee. Please proceed to OlaMoney section in the Ola app to clear the dues"
        )
        assertEquals(MessageCategory.BILL_REMINDER, result)
    }

    @Test
    fun `HDFC Amt Due nudge notification - bank-agnostic reminder`() {
        val result = categorizer.categorize(
            address = "VMHDFCBK",
            body    = "Dear Customer, Amt Due Rs.118546 on HDFC Bank Card X2660? Pay with PayZapp, your safe Bank zone."
        )
        assertEquals(MessageCategory.BILL_REMINDER, result)
    }

    @Test
    fun `HDFC Amount Due multi-line with Pay instantly by`() {
        val result = categorizer.categorize(
            address = "JX-HDFCBK-S",
            body    = "Amount Due\nRs.125017 on HDFC Bank Credit Card 2660. Pay instantly by 01/APR/2026 via PayZapp > Bill Pay > Credit Card: https://hdfcbk.io/abc"
        )
        assertEquals(MessageCategory.BILL_REMINDER, result)
    }

    @Test
    fun `Generic issuer-agnostic - total due with payable by (no known sender)`() {
        // A hypothetical bank with no known sender ID — BILL_REMINDER purely from body
        val result = categorizer.categorize(
            address = "VM-NEWCRD",
            body    = "Your credit card XX1234 statement. Total Amt Due Rs 8500; Min Amt Due Rs 425; Payable by 15/06/2026."
        )
        assertEquals(MessageCategory.BILL_REMINDER, result)
    }

    @Test
    fun `ICICI Payment received is TRANSACTIONS not BILL_REMINDER`() {
        // Past-tense guard: "has been received" → already paid → TRANSACTIONS
        val result = categorizer.categorize(
            address = "AD-ICICIT-S",
            body    = "Payment of Rs 1,153.02 has been received on your ICICI Bank Credit Card XX7004 " +
                      "through Bharat Bill Payment System on 01-OCT-25."
        )
        assertEquals(MessageCategory.TRANSACTIONS, result)
    }

    @Test
    fun `Tata Play next due date is UPDATES not BILL_REMINDER`() {
        // Tata Play recharge confirmation with "Next due date" — should stay UPDATES
        val result = categorizer.categorize(
            address = "TXTPPLAY",
            body    = "Tata Play ID 1363021658\nRecharge Amt Rs 200\nNew A/c balance Rs 207\nMonthly charges Rs 176\nNext due date 25-Feb-24"
        )
        assertEquals(MessageCategory.UPDATES, result)
    }

    @Test
    fun `HDFC UPI payment to WBSEDCL billdesk is TRANSACTIONS not BILL_REMINDER`() {
        // "debited" guard — actual payment made
        val result = categorizer.categorize(
            address = "VMHDFCBK",
            body    = "Amt Sent Rs.4398.00\nFrom HDFC Bank A/C *7472\nTo WBSEDCL BILLDESK\nOn 08-11\nRef 356099991810"
        )
        assertEquals(MessageCategory.TRANSACTIONS, result)
    }

    @Test
    fun `Delivery code digits after phrase`() {
        val result = categorizer.categorize(
            address = "DELIVERY",
            body    = "Your Secure Delivery Code is 482910. Use this to receive your parcel."
        )
        assertEquals(MessageCategory.OTP, result)
    }
}

