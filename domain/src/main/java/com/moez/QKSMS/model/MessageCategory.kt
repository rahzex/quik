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
package dev.octoshrimpy.quik.model

/**
 * Represents the category of an SMS conversation or individual message.
 *
 * This enum is the single source of truth used across ALL layers of the app:
 *   - domain  : interface contracts, model fields
 *   - data    : Realm storage (stored as the enum name string, e.g. "TRANSACTIONS")
 *   - presentation : tab labels, badge colours in the UI
 *
 * WHY AN ENUM?
 * Enums are compile-safe (typos are caught at build time) and Kotlin makes it easy
 * to iterate them (MessageCategory.values()) for building the tab bar dynamically.
 *
 * STORAGE NOTE:
 * Realm does not have a native enum column type, so we store `categoryId: String`
 * containing the enum's `.name` (e.g. "TRANSACTIONS") and convert it back with
 * `MessageCategory.valueOf(categoryId)` when reading.
 */
enum class MessageCategory {

    /**
     * Dual-purpose value:
     *  1. DEFAULT stored on every new/unprocessed message in Realm.
     *     When the categorizer hasn't run yet (fresh install, historical messages),
     *     the conversation keeps ALL and stays visible in the "All" tab.
     *  2. UI filter meaning "show everything" — used in MainState.activeCategory
     *     when the user is on the "All" tab.
     *
     * This avoids needing a separate UNCATEGORIZED value; unprocessed messages are
     * simply not hidden from the user.
     */
    ALL,

    /** Regular 1-to-1 or group conversations with people in your contacts. */
    PERSONAL,

    /**
     * Bank alerts, UPI debits/credits, credit-card statements, EMI notices.
     * Detected by alphanumeric sender IDs (e.g. VM-HDFCBK) and ₹/Rs/INR keywords.
     */
    TRANSACTIONS,

    /**
     * One-Time Passwords for logins, payments, and verifications.
     * Has the highest detection priority because a mis-categorised OTP is frustrating.
     */
    OTP,

    /**
     * Service notifications: order dispatched, flight PNR, appointment reminders,
     * recharge confirmations, account statements.
     */
    UPDATES,

    /** Discount codes, sale announcements, marketing blasts from brands/services. */
    PROMOS,

    /** Likely unsolicited or fraudulent messages (prize scams, loan offers, etc.). */
    SPAM,

    /**
     * Bill due reminders and upcoming payment notices.
     *
     * Examples:
     *  - Credit card statements: "Total Amt Due Rs 5790; Min Amt Due Rs 290; Payable by 08/03/2024"
     *  - HDFC statement:         "Total due amt: Rs.1,18,546 ... Due by:02-12-2024"
     *  - ICICI reminder:         "Total of Rs 65489 or minimum of Rs 3280 is due by 30-APR-24"
     *  - ICICI nudge:            "Pay Total Due of Rs X or Min Due Rs Y by DATE"
     *  - SBI outstanding:        "outstanding of Rs. 43295 on your credit card is due on 06-APR-25"
     *  - OlaMoney postpaid:      "Your OlaMoney Postpaid bill of Rs. 699 is due. Pay before DATE"
     *  - HDFC "Amt Due" nudge:   "Amt Due Rs.X on HDFC Bank Card, Pay with PayZapp"
     *
     * Key distinction from TRANSACTIONS: these messages announce money *owed in the
     * future* — not a payment that already happened. Detecting them separately allows
     * the UI to show an "Upcoming" / "Bills" section.
     *
     * Detection priority: checked AFTER OTP and known UPDATE senders (Tata Play etc.)
     * but BEFORE generic transaction heuristics, so "Min Due" / "Total Due" keywords
     * are not swallowed by TXN_BODY_KEYWORDS.
     */
    BILL_REMINDER
}

