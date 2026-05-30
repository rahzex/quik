/*
 * Copyright (C) 2026 QUIK
 *
 * This file is part of QUIK.
 */
package dev.octoshrimpy.quik.feature.finance

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.common.base.QkController
import dev.octoshrimpy.quik.databinding.ControllerFinanceBinding
import dev.octoshrimpy.quik.injection.appComponent
import dev.octoshrimpy.quik.model.AccountBalance
import dev.octoshrimpy.quik.model.ParsedTransaction
import io.reactivex.Observable
import io.reactivex.subjects.PublishSubject
import java.text.NumberFormat
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject

class FinanceController : QkController<
        ControllerFinanceBinding,
        FinanceView,
        FinanceState,
        FinancePresenter>(),
    FinanceView {

    @Inject override lateinit var presenter: FinancePresenter

    private val monthSubject = PublishSubject.create<Pair<Int, Int>>()
    override val monthSelectedIntent: Observable<Pair<Int, Int>> = monthSubject

    private val monthPills = mutableListOf<TextView>()
    private val months     = mutableListOf<Pair<Int, Int>>()

    // Track currently displayed month so click listeners can pass it to TransactionListController
    private var currentYear  = Calendar.getInstance().get(Calendar.YEAR)
    private var currentMonth = Calendar.getInstance().get(Calendar.MONTH) + 1

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup) =
        ControllerFinanceBinding.inflate(inflater, container, false)

    override fun onAttach(view: View) {
        appComponent.inject(this)
        super.onAttach(view)
        presenter.bindIntents(this)
    }

    override fun onViewCreated() {
        buildMonthPills()
        binding.accountsRecycler.layoutManager = LinearLayoutManager(activity)
        binding.accountsRecycler.adapter = AccountsAdapter()
        binding.upcomingRecycler.layoutManager = LinearLayoutManager(activity)
        binding.upcomingRecycler.adapter = UpcomingAdapter()

        binding.cardSpent.setOnClickListener {
            router.pushController(
                com.bluelinelabs.conductor.RouterTransaction.with(
                    TransactionListController.newInstance(true, currentYear, currentMonth)
                )
            )
        }
        binding.cardReceived.setOnClickListener {
            router.pushController(
                com.bluelinelabs.conductor.RouterTransaction.with(
                    TransactionListController.newInstance(false, currentYear, currentMonth)
                )
            )
        }
    }

    private fun buildMonthPills() {
        val monthNames = listOf("Jan","Feb","Mar","Apr","May","Jun",
            "Jul","Aug","Sep","Oct","Nov","Dec")
        months.clear(); monthPills.clear()
        binding.monthSwitcher.removeAllViews()
        for (offset in -2..0) {
            val cal = Calendar.getInstance().apply { add(Calendar.MONTH, offset) }
            val y = cal.get(Calendar.YEAR)
            val m = cal.get(Calendar.MONTH) + 1
            months.add(Pair(y, m))
            val pill = TextView(activity).apply {
                text = monthNames[m - 1]
                textSize = 11f
                setPaddingRelative(18, 6, 18, 6)
                background = ContextCompat.getDrawable(context, R.drawable.rounded_rectangle_8dp)
                setOnClickListener { monthSubject.onNext(Pair(y, m)) }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            monthPills.add(pill)
            binding.monthSwitcher.addView(pill)
        }
    }

    override fun render(state: FinanceState) {
        currentYear  = state.selectedYear
        currentMonth = state.selectedMonth

        val fmt = NumberFormat.getCurrencyInstance(Locale("en", "IN"))
            .apply { maximumFractionDigits = 0 }

        months.forEachIndexed { i, (y, m) ->
            val active = y == state.selectedYear && m == state.selectedMonth
            monthPills.getOrNull(i)?.setTextColor(
                ContextCompat.getColor(activity!!, if (active) R.color.tools_theme else android.R.color.darker_gray)
            )
        }

        binding.statSpent.text    = fmt.format(state.totalSpent)
        binding.statReceived.text = fmt.format(state.totalReceived)

        val prefix = if (state.netSaved >= 0) "+" else ""
        binding.netAmount.text = "$prefix${fmt.format(state.netSaved)}"
        binding.spentProgress.progress = (state.spentPercent * 100).toInt()
        val pct = (state.spentPercent * 100).toInt()
        binding.progressSpentLabel.text = "Spent $pct%"
        binding.progressSavedLabel.text = "Saved ${100 - pct}%"

        val accounts = state.accounts
        binding.accountsEmpty.isVisible    = accounts.isEmpty()
        binding.accountsRecycler.isVisible = accounts.isNotEmpty()
        (binding.accountsRecycler.adapter as? AccountsAdapter)?.update(accounts)

        val upcoming = state.upcomingBills
        binding.upcomingEmpty.isVisible    = upcoming.isEmpty()
        binding.upcomingRecycler.isVisible = upcoming.isNotEmpty()
        (binding.upcomingRecycler.adapter as? UpcomingAdapter)?.update(upcoming)
    }

    // ── Adapters ─────────────────────────────────────────────────────────────

    /** Derives a human-readable bank name from the sender ID (e.g. "JXHDFCBK" → "HDFC Bank"). */
    private fun senderToBankName(sender: String): String {
        val s = sender.uppercase()
        return when {
            "HDFCBK" in s  -> "HDFC Bank"
            "ICICIT" in s  -> "ICICI Bank"
            "SBICRD" in s || "SBISMS" in s -> "SBI"
            "PNBSMS" in s  -> "PNB"
            "BDNSMS" in s  -> "Bandhan Bank"
            "AXISBK" in s  -> "Axis Bank"
            "YESBNK" in s || "YESBKS" in s -> "Yes Bank"
            "PAYTMB" in s || "PPBL" in s   -> "Paytm Bank"
            "KOTAKB" in s  -> "Kotak Bank"
            "INDUSB" in s  -> "IndusInd Bank"
            else           -> sender
        }
    }

    inner class AccountsAdapter : RecyclerView.Adapter<AccountsAdapter.VH>() {
        private var items: List<AccountBalance> = emptyList()
        fun update(list: List<AccountBalance>) {
            items = list
            notifyDataSetChanged()
        }
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context)
                .inflate(R.layout.finance_account_list_item, parent, false))
        override fun onBindViewHolder(h: VH, pos: Int) = h.bind(items[pos])

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            fun bind(item: AccountBalance) {
                val fmt = NumberFormat.getCurrencyInstance(Locale("en", "IN"))
                    .apply { maximumFractionDigits = 0 }

                // Bank display name: prefer stored bankName, fallback to sender-derived name
                val displayName = item.bankName.ifBlank { senderToBankName(item.senderPattern) }
                val acctLabel   = when (item.accountType) {
                    "Credit Card" -> "Credit Card ···· ${item.accountLast4}"
                    "Debit Card"  -> "Debit Card ···· ${item.accountLast4}"
                    else          -> "A/C ···· ${item.accountLast4}"
                }

                itemView.findViewById<TextView>(R.id.accountInitials).text =
                    displayName.split(" ").map { it.firstOrNull()?.toString() ?: "" }
                        .joinToString("").take(2).uppercase()
                itemView.findViewById<TextView>(R.id.accountSender).text = displayName
                itemView.findViewById<TextView>(R.id.accountNumber).text = acctLabel
                itemView.findViewById<TextView>(R.id.accountBalance).text = fmt.format(item.balance)
            }
        }
    }

    inner class UpcomingAdapter : RecyclerView.Adapter<UpcomingAdapter.VH>() {
        private var items: List<ParsedTransaction> = emptyList()
        fun update(list: List<ParsedTransaction>) { items = list; notifyDataSetChanged() }
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context)
                .inflate(R.layout.finance_upcoming_list_item, parent, false))
        override fun onBindViewHolder(h: VH, pos: Int) = h.bind(items[pos])

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            fun bind(item: ParsedTransaction) {
                val fmt = NumberFormat.getCurrencyInstance(Locale("en", "IN"))
                    .apply { maximumFractionDigits = 0 }
                itemView.findViewById<TextView>(R.id.reminderTitle).text  =
                    item.merchant.ifBlank { "Credit card bill" }

                // Prefer the parsed due date epoch; fall back to the raw reference string
                val dueLabel = if (item.dueDateMs > 0) {
                    val cal = Calendar.getInstance()
                    cal.timeInMillis = item.dueDateMs
                    val day   = cal.get(Calendar.DAY_OF_MONTH)
                    val month = arrayOf("Jan","Feb","Mar","Apr","May","Jun",
                        "Jul","Aug","Sep","Oct","Nov","Dec")[cal.get(Calendar.MONTH)]
                    "$day $month"
                } else {
                    item.reference.ifBlank { "—" }
                }
                itemView.findViewById<TextView>(R.id.reminderMeta).text   = "Due $dueLabel"
                itemView.findViewById<TextView>(R.id.reminderAmount).text = fmt.format(item.amount)
            }
        }
    }
}

