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
package dev.octoshrimpy.quik.feature.finance

import dev.octoshrimpy.quik.model.AccountBalance
import dev.octoshrimpy.quik.model.ParsedTransaction
import io.realm.RealmResults
import java.util.Calendar

data class FinanceState(
    val selectedYear: Int  = Calendar.getInstance().get(Calendar.YEAR),
    val selectedMonth: Int = Calendar.getInstance().get(Calendar.MONTH) + 1, // 1-based
    val totalSpent: Double    = 0.0,
    val totalReceived: Double = 0.0,
    val accounts: RealmResults<AccountBalance>? = null,
    val upcomingBills: List<ParsedTransaction> = emptyList(),
    val isLoading: Boolean = true
) {
    val netSaved: Double get() = totalReceived - totalSpent
    val spentPercent: Float get() {
        val total = totalSpent + totalReceived
        return if (total <= 0.0) 0f else (totalSpent / total).toFloat().coerceIn(0f, 1f)
    }
}

