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
import io.realm.annotations.PrimaryKey

/**
 * Stores the last-known available balance for a bank account detected from SMS.
 *
 * PRIMARY KEY: [id] is a composite string: "[senderPattern]:[accountLast4]"
 * e.g. "JXHDFCBK:7472" or "AXPNBSMS:5095". This provides natural deduplication —
 * if the same account sends multiple messages, the balance is upserted in place.
 *
 * Updated every time a [ParsedTransaction] is saved that carries an [availableBalance].
 * The Finance Dashboard reads this to show the current balance per account.
 */
open class AccountBalance : RealmObject() {

    /**
     * Composite primary key: "[senderPattern]:[accountLast4]".
     * e.g. "JXHDFCBK:7472".
     */
    @PrimaryKey
    var id: String = ""

    /**
     * The raw alphanumeric sender ID from the SMS (e.g. "JXHDFCBK").
     * Used to re-identify the bank for display purposes.
     */
    var senderPattern: String = ""

    /**
     * Last 4 digits of the account or card number (e.g. "7472").
     * Displayed as "·· 7472" in the Finance Dashboard account card.
     */
    var accountLast4: String = ""

    /**
     * Last known available balance, in INR.
     * Sourced from the "Avl bal" / "Aval Bal" / "Bal" field in the SMS body.
     */
    var balance: Double = 0.0

    /**
     * Timestamp (ms since epoch) of the most recent message that updated this balance.
     * Accounts are sorted by this field (newest first) in the Finance Dashboard.
     */
    var lastUpdated: Long = 0
}

