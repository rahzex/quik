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

import io.realm.RealmObject
import io.realm.annotations.Index
import io.realm.annotations.PrimaryKey

/**
 * Stores extracted financial data from a single TRANSACTIONS-category SMS.
 *
 * PRIMARY KEY: [id] = [Message.id] — one-to-one relationship. If parsing fails for a message
 * (no amount found), no row is created. The absence of a row is itself information.
 *
 * INDEXES: [threadId], [year], [month] are indexed for fast Finance Dashboard queries:
 *  - "All transactions this month" → filter by year + month
 *  - "Transactions for this thread" → filter by threadId (for the bubble card in thread view)
 *
 * This model is created by [TransactionParserImpl] + [FinanceRepositoryImpl] whenever:
 *  1. A new TRANSACTIONS SMS arrives (real-time, via [ReceiveSmsWorker])
 *  2. The backfill worker [ParseAllTransactionsWorker] runs on first launch (Phase 2)
 */
open class ParsedTransaction : RealmObject() {

    /** Same as the parent [Message.id] — serves as the primary key. */
    @PrimaryKey
    var id: Long = 0

    /** Thread ID of the parent conversation — indexed for per-thread queries. */
    @Index
    var threadId: Long = 0

    /** Message timestamp in milliseconds since epoch (copied from [Message.date]). */
    var date: Long = 0

    /** Transaction amount in INR (always positive; direction is in [isDebit]). */
    var amount: Double = 0.0

    /** true = debit (money leaving); false = credit (money arriving). */
    var isDebit: Boolean = true

    /** Counterparty name (merchant / payee / payer). Empty string if not found. */
    var merchant: String = ""

    /** Payment reference (UPI Ref ID, NEFT ref, etc.). Empty string if not found. */
    var reference: String = ""

    /** Last 4 digits of the account or card. Empty string if not found. */
    var accountLast4: String = ""

    /**
     * Available balance after the transaction, or -1.0 if the message did not include it.
     * The Finance Dashboard uses this to display the latest known account balance.
     */
    var availableBalance: Double = -1.0

    /**
     * Payment method string. One of: "UPI", "NEFT", "IMPS", "RTGS", "ATM",
     * "Card", "Statement", "Other".
     */
    var method: String = "Other"

    /**
     * Human-readable bank name extracted from the SMS body.
     * e.g. "HDFC Bank", "SBI", "ICICI Bank", "PNB", "Bandhan Bank".
     * Empty string if not determined.
     */
    var bankName: String = ""

    /**
     * Calendar year extracted from [date]. Indexed for fast month-filter queries.
     * Stored denormalized (instead of computing on every query) for Realm performance.
     */
    @Index
    var year: Int = 0

    /**
     * Calendar month (1-based: Jan=1 … Dec=12) extracted from [date].
     * Indexed for fast month-filter queries.
     */
    @Index
    var month: Int = 0
}

