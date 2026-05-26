/*
 * Copyright (C) 2026 QUIK
 *
 * Unit tests for TransactionParserImpl.
 * All test bodies are real SMS samples from sms_backup_categorized.json.
 * No bank names appear in patterns — purely structural matching.
 */
package dev.octoshrimpy.quik.categorization

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TransactionParserImplTest {

    private lateinit var parser: TransactionParserImpl

    @Before
    fun setUp() {
        parser = TransactionParserImpl()
    }

    // ── S1: Money Transfer UPI ───────────────────────────────────────────────

    @Test
    fun `S1 money transfer UPI debit basic`() {
        val result = parser.parse(
            "JXHDFCBK",
            "Money Transfer:Rs 200.00 from HDFC Bank A/c **7472 on 21-01-24 to Add Money to Wallet UPI: 402162644471 Not you? Call 18002586161"
        )
        assertNotNull(result)
        assertEquals(200.0, result!!.amount, 0.01)
        assertTrue(result.isDebit)
        assertEquals("7472", result.accountLast4)
        assertEquals("402162644471", result.reference)
        assertEquals("UPI", result.method)
    }

    @Test
    fun `S1 money transfer UPI debit with named payee`() {
        val result = parser.parse(
            "ADHDFCBK",
            "Money Transfer:Rs 1023.00 from HDFC Bank A/c **7472 on 23-01-24 to INDRANIL DATTA UPI: 438930027128 Not you? Call 18002586161"
        )
        assertNotNull(result)
        assertEquals(1023.0, result!!.amount, 0.01)
        assertTrue(result.isDebit)
        assertEquals("INDRANIL DATTA", result.merchant)
        assertEquals("438930027128", result.reference)
    }

    @Test
    fun `S1 money transfer large amount`() {
        val result = parser.parse(
            "JDHDFCBK",
            "Money Transfer:Rs 9075.00 from HDFC Bank A/c **7472 on 02-02-24 to SBI cards and Payment services Pvt Ltd UPI: 403347616834 Not you? Call 18002586161"
        )
        assertNotNull(result)
        assertEquals(9075.0, result!!.amount, 0.01)
    }

    // ── S2: Amt Sent multiline ───────────────────────────────────────────────

    @Test
    fun `S2 amt sent multiline format`() {
        val result = parser.parse(
            "AXHDFCBK",
            "Amt Sent Rs.340.00\nFrom HDFC Bank A/C *7472\nTo Momo Magic Cafe\nOn 16-02\nRef 404747962998\nNot You? Call 18002586161/SMS BLOCK UPI to 7308080808"
        )
        assertNotNull(result)
        assertEquals(340.0, result!!.amount, 0.01)
        assertTrue(result.isDebit)
        assertEquals("7472", result.accountLast4)
        assertEquals("Momo Magic Cafe", result.merchant)
        assertEquals("404747962998", result.reference)
        assertEquals("UPI", result.method)
    }

    @Test
    fun `S2 amt sent to company`() {
        val result = parser.parse(
            "ADHDFCBN",
            "Amt Sent Rs.200.00\nFrom HDFC Bank A/C *7472\nTo TATA SKY LIMITED\nOn 25-02\nRef 405630795283\nNot You? Call 18002586161/SMS BLOCK UPI to 7308080808"
        )
        assertNotNull(result)
        assertEquals(200.0, result!!.amount, 0.01)
        assertEquals("TATA SKY LIMITED", result.merchant)
    }

    // ── S3: Debited from a/c (UPI Ref No.) ──────────────────────────────────

    @Test
    fun `S3 debited UPI Ref No format`() {
        val result = parser.parse(
            "JXHDFCBK",
            "HDFC Bank: Rs. 1000.00 debited from a/c **7472 on 23-02-24 to a/c **5095 (UPI Ref No. 405449586737). Not you? Call on 18002586161 to report"
        )
        assertNotNull(result)
        assertEquals(1000.0, result!!.amount, 0.01)
        assertTrue(result.isDebit)
        assertEquals("7472", result.accountLast4)
        assertEquals("405449586737", result.reference)
        assertEquals("UPI", result.method)
    }

    // ── S4: Withdrawn ATM ────────────────────────────────────────────────────

    @Test
    fun `S4 ATM withdrawal with balance`() {
        val result = parser.parse(
            "JMHDFCBK",
            "Rs.3000 withdrawn from HDFC Bank Card x4441 at KOCH BIHAR BRANCH on 2024-01-21:21:24:58 Avl bal: 262453.73.Not You? Call 18002586161/SMS BLOCK DC 4441 to 7308080808"
        )
        assertNotNull(result)
        assertEquals(3000.0, result!!.amount, 0.01)
        assertTrue(result.isDebit)
        assertEquals("4441", result.accountLast4)
        assertEquals(262453.73, result.availableBalance, 0.01)
        assertEquals("ATM", result.method)
    }

    @Test
    fun `S4 ATM withdrawal different branch`() {
        val result = parser.parse(
            "VMHDFCBK",
            "Rs.3000 withdrawn from HDFC Bank Card x4441 at KOCHBIHAR BRANCH on 2024-02-11:20:10:46 Avl bal: 342054.58.Not You? Call 18002586161/SMS BLOCK DC 4441 to 7308080808"
        )
        assertNotNull(result)
        assertEquals(3000.0, result!!.amount, 0.01)
        assertEquals(342054.58, result.availableBalance, 0.01)
    }

    // ── S5: Deposited NEFT ───────────────────────────────────────────────────

    @Test
    fun `S5 NEFT salary deposit with balance`() {
        val result = parser.parse(
            "BPHDFCBK",
            "Update! INR 1,13,964.00 deposited in HDFC Bank A/c XX7472 on 31-JAN-24 for NEFT Cr-CITI0000002-EPAM SYSTEMS INDIA PVT LTD GURG INR-SUBHAJIT PAL-CITIN24415914830.Avl bal INR 3,67,329.58. Cheque deposits in A/C are subject to clearing"
        )
        assertNotNull(result)
        assertEquals(113964.0, result!!.amount, 0.01)
        assertFalse(result.isDebit)
        assertEquals("7472", result.accountLast4)
        assertEquals(367329.58, result.availableBalance, 0.01)
        assertEquals("NEFT", result.method)
    }

    // ── S6: a/c XX credited UPI Ref ID ──────────────────────────────────────

    @Test
    fun `S6 PNB credited with balance and UPI ref`() {
        val result = parser.parse(
            "VKPNBSMS",
            "Your a/c XX5095 is credited for INR 255.00  on 23-01-24 21:19 through UPI.Available Bal INR 398.25 (UPI Ref ID 402335323428).Download PNB ONE-PNB"
        )
        assertNotNull(result)
        assertEquals(255.0, result!!.amount, 0.01)
        assertFalse(result.isDebit)
        assertEquals("5095", result.accountLast4)
        assertEquals(398.25, result.availableBalance, 0.01)
        assertEquals("402335323428", result.reference)
        assertEquals("UPI", result.method)
    }

    @Test
    fun `S6 PNB large credit amount`() {
        val result = parser.parse(
            "VMPNBSMS",
            "Your a/c XX5095 is credited for INR 7200.00  on 14-02-24 20:17 through UPI.Available Bal INR 10426.75 (UPI Ref ID 441198493576).Download PNB ONE-PNB"
        )
        assertNotNull(result)
        assertEquals(7200.0, result!!.amount, 0.01)
        assertFalse(result.isDebit)
    }

    // ── S7: Ac X+ Credited ──────────────────────────────────────────────────

    @Test
    fun `S7 Ac XXXXXXXX credited format`() {
        val result = parser.parse(
            "AXPNBSMS",
            "Ac XXXXXXXX85095 Credited with Rs.1000.00 23-02-2024 22:25:54 thru UPI . Aval Bal Rs.2384.75 CR. (UPI Ref ID:405449586737) Helpline 18001800/18002021-PNB"
        )
        assertNotNull(result)
        assertEquals(1000.0, result!!.amount, 0.01)
        assertFalse(result.isDebit)
        assertEquals("5095", result.accountLast4)
        assertEquals(2384.75, result.availableBalance, 0.01)
        assertEquals("405449586737", result.reference)
    }

    // ── S8: A/c XX debited thru UPI ─────────────────────────────────────────

    @Test
    fun `S8 PNB debited UPI with balance`() {
        val result = parser.parse(
            "ADPNBSMS",
            "A/c XX5095 debited INR 300.00 Dt 02-02-24 20:45 thru UPI:403340082552.Bal INR 5454.25 Not u?Fwd this SMS to 9264092640 to block UPI.Download PNB ONE-PNB"
        )
        assertNotNull(result)
        assertEquals(300.0, result!!.amount, 0.01)
        assertTrue(result.isDebit)
        assertEquals("5095", result.accountLast4)
        assertEquals("403340082552", result.reference)
        assertEquals(5454.25, result.availableBalance, 0.01)
    }

    // ── S9: Spent on Credit Card ─────────────────────────────────────────────

    @Test
    fun `S9 credit card purchase`() {
        val result = parser.parse(
            "VMSBICRD",
            "Rs.3,052.28 spent on your SBI Credit Card ending 8987 at REL RETAIL LTD-FRESH on 27/01/24. Trxn. not done by you? Report at https://sbicard.com/Dispute"
        )
        assertNotNull(result)
        assertEquals(3052.28, result!!.amount, 0.01)
        assertTrue(result.isDebit)
        assertEquals("8987", result.accountLast4)
        assertEquals("Card", result.method)
    }

    @Test
    fun `S9 credit card purchase utility`() {
        val result = parser.parse(
            "VMSBICRD",
            "Rs.2,738.00 spent on your SBI Credit Card ending 8987 at WBSEDCL on 03/02/24. Trxn. not done by you? Report at https://sbicard.com/Dispute"
        )
        assertNotNull(result)
        assertEquals(2738.0, result!!.amount, 0.01)
        assertEquals("WBSEDCL", result.merchant)
    }

    // ── S10: Received payment via UPI ────────────────────────────────────────

    @Test
    fun `S10 credit card payment received`() {
        val result = parser.parse(
            "VMSBICRD",
            "We have received payment of Rs.9,075.00 via UPI & the same has been credited to your SBI Credit Card. Your available limit is Rs.146,948.06."
        )
        assertNotNull(result)
        assertEquals(9075.0, result!!.amount, 0.01)
        assertFalse(result.isDebit)
        assertEquals("UPI", result.method)
        assertEquals(146948.06, result.availableBalance, 0.01)
    }

    // ── S11: Payment processed ref no ────────────────────────────────────────

    @Test
    fun `S11 payment processed with ref`() {
        val result = parser.parse(
            "JMSBICRD",
            "Dear SBI Cardholder, payment of Rs. 9075.00 for your SBI Credit Card has been successfully processed. ref no : ZIC51722265287."
        )
        assertNotNull(result)
        assertEquals(9075.0, result!!.amount, 0.01)
        assertFalse(result.isDebit)
        assertTrue(result.reference.startsWith("ZIC51722265287"))
    }

    // ── S12: Credit card bill statement ──────────────────────────────────────

    @Test
    fun `S12 bill statement due date`() {
        val result = parser.parse(
            "VKSBICRD",
            "E-statement of SBI Credit Card ending XX87 dated 17/02/2024 has been mailed. If not received, SMS ENRS to 5676791. Total Amt Due Rs 5790; Min Amt Due Rs 290; Payable by 08/03/2024. Click https://sbicard.com/quickpaynet to pay your bill"
        )
        assertNotNull(result)
        assertEquals(5790.0, result!!.amount, 0.01)
        assertEquals("Statement", result.method)
        assertEquals("08/03/2024", result.reference)
    }

    // ── S13: Collect request (Paytm / GoKwik) ────────────────────────────────

    @Test
    fun `S13 Paytm collect request`() {
        val result = parser.parse(
            "ADPAYTMB",
            "Amazon Pay Groceries has requested money on Paytm. On approving, Rs.693 will be debited from your account. http://m.p-y.tm/UPIpas :PPBL"
        )
        assertNotNull(result)
        assertEquals(693.0, result!!.amount, 0.01)
        assertTrue(result.isDebit)
        assertEquals("UPI", result.method)
    }

    @Test
    fun `S13 GoKwik payment request`() {
        val result = parser.parse(
            "TMGKKWIK",
            "Payment requested: Khadi Natural has requested a payment of INR 219.43 on Gokwik. Click here to complete the payment: https://kwik.pe/Z97FXD"
        )
        assertNotNull(result)
        assertEquals(219.43, result!!.amount, 0.01)
        assertTrue(result.isDebit)
    }

    // ── Generic fallback ─────────────────────────────────────────────────────

    @Test
    fun `generic fallback ICICI debit UPI`() {
        val result = parser.parse(
            "VMICICIB",
            "INR 500.00 debited from your ICICI Bank A/C XX1234 via UPI Ref 987654321012. Available Bal INR 12000.00"
        )
        assertNotNull(result)
        assertEquals(500.0, result!!.amount, 0.01)
        assertTrue(result.isDebit)
    }

    @Test
    fun `generic fallback credit with balance`() {
        val result = parser.parse(
            "VMAXISBK",
            "Rs.8000.00 credited to your Axis Bank account. Avl Bal Rs.25000.00"
        )
        assertNotNull(result)
        assertEquals(8000.0, result!!.amount, 0.01)
        assertFalse(result.isDebit)
        assertEquals(25000.0, result.availableBalance, 0.01)
    }

    // ── Null returns — should NOT parse ──────────────────────────────────────

    @Test
    fun `null for OTP message`() {
        val result = parser.parse(
            "ADiPaytm",
            "Paytm never calls you asking for OTP. Sharing it with anyone gives them full access to your Paytm Wallet. Your Login OTP is 465061."
        )
        assertNull(result)
    }

    @Test
    fun `null for promotional message`() {
        val result = parser.parse(
            "AD-SWIGGY",
            "Get 50% off your next order. Use code SAVE50. Valid till tonight!"
        )
        assertNull(result)
    }

    @Test
    fun `null for delivery update`() {
        val result = parser.parse(
            "ADXPBEES",
            "We have delivered your Printstop order:14948390095194 on 2024-01-26 to Subhajit P. Xpressbees"
        )
        assertNull(result)
    }

    @Test
    fun `null for bank advisory no amount`() {
        val result = parser.parse(
            "JMBDNSMS",
            "Beware of fraudsters posing as bank representatives and asking you to update your KYC through WhatsApp video calls."
        )
        assertNull(result)
    }

    @Test
    fun `null for personal message`() {
        val result = parser.parse(
            "918906949459",
            "Lft\nKft\nSuger random\nLipid profile\nTsh"
        )
        assertNull(result)
    }

    @Test
    fun `null for ambiguous body both debit and credit keywords`() {
        val result = parser.parse(
            "VMBANKXX",
            "Your account has been debited Rs.500 and credited Rs.500 in reconciliation."
        )
        assertNull(result)
    }

    @Test
    fun `null for EMI reminder no amount`() {
        val result = parser.parse(
            "VMICICIB",
            "Dear Customer, your EMI is due. Please ensure sufficient balance in your account."
        )
        assertNull(result)
    }
}


