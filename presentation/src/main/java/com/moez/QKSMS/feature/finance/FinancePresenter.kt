package dev.octoshrimpy.quik.feature.finance

import dev.octoshrimpy.quik.common.base.QkPresenter
import dev.octoshrimpy.quik.repository.FinanceRepository
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
import timber.log.Timber
import java.util.Calendar
import javax.inject.Inject

class FinancePresenter @Inject constructor(
    private val financeRepo: FinanceRepository
) : QkPresenter<FinanceView, FinanceState>(FinanceState()) {

    override fun bindIntents(view: FinanceView) {
        super.bindIntents(view)

        // Load current month immediately on whichever thread bindIntents is called from
        val now = Calendar.getInstance()
        loadMonth(now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1)

        view.monthSelectedIntent
            .observeOn(Schedulers.io())          // run queries off the main thread
            .subscribe({ (year, month) -> loadMonth(year, month) }, Timber::e)
            .also { disposables.add(it) }
    }

    private fun loadMonth(year: Int, month: Int) {
        newState { copy(selectedYear = year, selectedMonth = month, isLoading = true) }
        try {
            val spent    = financeRepo.getSpentTotal(year, month)
            val received = financeRepo.getReceivedTotal(year, month)
            val accounts = financeRepo.getAccounts()      // now synchronous — safe on any thread
            val upcoming = financeRepo.getUpcomingBills()
            newState {
                copy(
                    totalSpent    = spent,
                    totalReceived = received,
                    accounts      = accounts,
                    upcomingBills = upcoming,
                    isLoading     = false
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "FinancePresenter: error loading month $year-$month")
            newState { copy(isLoading = false) }
        }
    }
}

