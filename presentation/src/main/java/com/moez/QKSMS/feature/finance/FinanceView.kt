/*
 * Copyright (C) 2026 QUIK
 *
 * This file is part of QUIK.
 */
package dev.octoshrimpy.quik.feature.finance

import dev.octoshrimpy.quik.common.base.QkViewContract
import io.reactivex.Observable

interface FinanceView : QkViewContract<FinanceState> {
    /** Emits (year, month) when the user taps a month pill. month is 1-based. */
    val monthSelectedIntent: Observable<Pair<Int, Int>>
}
