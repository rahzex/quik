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

/**
 * Contract for the on-device SMS categorization engine.
 *
 * WHY AN INTERFACE?
 * Clean Architecture separates "what" (domain/interface) from "how" (data/implementation).
 * By depending on this interface rather than a concrete class, the rest of the app:
 *   - Doesn't know or care about regex internals
 *   - Can easily swap the implementation (e.g. ML-based engine later) without touching
 *     ViewModels or repositories
 *   - Can be unit-tested with a fake implementation (no real regex needed in tests)
 *
 * The interface lives in the `domain` module; the implementation lives in `data`.
 * Dagger 2 wires them together at runtime (see AppModule.kt).
 */
interface SmsCategorizer {

    /**
     * Analyzes a single SMS and returns its best-matching [MessageCategory].
     *
     * This function is called:
     *  - For every new incoming SMS inside [ReceiveSmsWorker] (real-time)
     *  - For all historical messages inside [CategorizeAllMessagesWorker] (first-run batch)
     *
     * The function must be PURE (no side effects) and THREAD-SAFE because WorkManager
     * can run multiple workers concurrently on background threads.
     *
     * @param address The raw sender field — can be a phone number (+91 98765 43210)
     *                OR an alphanumeric sender ID (VM-HDFCBK, AM-PAYTMB, AD-SWIGGY).
     * @param body    The complete SMS body text, exactly as received.
     * @return        The best-matching [MessageCategory]; never null.
     *                Returns [MessageCategory.PERSONAL] when no specific rule matches.
     */
    fun categorize(address: String, body: String): MessageCategory
}

