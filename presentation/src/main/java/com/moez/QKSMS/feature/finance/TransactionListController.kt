/*
 * Copyright (C) 2026 QUIK
 *
 * This file is part of QUIK.
 */
package dev.octoshrimpy.quik.feature.finance

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bluelinelabs.conductor.archlifecycle.LifecycleController
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.common.util.extensions.getColorCompat
import dev.octoshrimpy.quik.databinding.ControllerTransactionListBinding
import dev.octoshrimpy.quik.injection.appComponent
import dev.octoshrimpy.quik.model.ParsedTransaction
import dev.octoshrimpy.quik.repository.FinanceRepository
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Shows the full list of debits or credits for a given month.
 * Launched from [FinanceController] when user taps Spent or Received card.
 *
 * @param isDebit   true = All Debits screen, false = All Credits screen
 * @param year      calendar year (e.g. 2026)
 * @param month     calendar month, 1-based (1=Jan … 12=Dec)
 */
class TransactionListController(args: Bundle) : LifecycleController(args) {

    companion object {
        private const val ARG_IS_DEBIT = "isDebit"
        private const val ARG_YEAR    = "year"
        private const val ARG_MONTH   = "month"

        fun newInstance(isDebit: Boolean, year: Int, month: Int): TransactionListController {
            val args = Bundle().apply {
                putBoolean(ARG_IS_DEBIT, isDebit)
                putInt(ARG_YEAR, year)
                putInt(ARG_MONTH, month)
            }
            return TransactionListController(args)
        }
    }

    @Inject lateinit var financeRepo: FinanceRepository

    private val isDebit get() = args.getBoolean(ARG_IS_DEBIT, true)
    private val year    get() = args.getInt(ARG_YEAR, Calendar.getInstance().get(Calendar.YEAR))
    private val month   get() = args.getInt(ARG_MONTH, Calendar.getInstance().get(Calendar.MONTH) + 1)

    private var _binding: ControllerTransactionListBinding? = null
    private val binding get() = requireNotNull(_binding)

    private val fmt = NumberFormat.getCurrencyInstance(Locale("en", "IN"))
        .apply { maximumFractionDigits = 0 }

    private val monthNames = listOf("Jan","Feb","Mar","Apr","May","Jun",
        "Jul","Aug","Sep","Oct","Nov","Dec")

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup): View {
        appComponent.inject(this)
        val vb = ControllerTransactionListBinding.inflate(inflater, container, false)
        _binding = vb

        // Header
        val monthLabel = "${monthNames.getOrElse(month - 1) { "?" }} $year"
        vb.txnListTitle.text    = if (isDebit) "All Debits" else "All Credits"
        vb.txnListSubtitle.text = monthLabel

        vb.btnBack.setOnClickListener { router.popCurrentController() }

        // Load transactions (synchronous, detached from Realm)
        val allTxns = financeRepo.getTransactions(year, month)
        val filtered = allTxns.filter { it.isDebit == isDebit }

        // Summary strip — use the salary-aware repo methods so totals match Finance Dashboard
        val total = if (isDebit) financeRepo.getSpentTotal(year, month)
                    else financeRepo.getReceivedTotal(year, month)
        val count = filtered.size
        val acctCount = filtered.map { it.accountLast4 }.filter { it.isNotEmpty() }.distinct().size

        if (isDebit) {
            vb.summaryLabel.text       = "Total spent"
            vb.summaryAmount.text      = fmt.format(total)
            vb.summaryAmount.setTextColor(vb.root.context.getColorCompat(R.color.finance_red_text))
            vb.summaryMeta.text        = "$count transactions"
            vb.summaryMeta.setTextColor(vb.root.context.getColorCompat(R.color.finance_red_text))
            vb.summaryRightLabel.text  = "Transactions"
            vb.summaryRightValue.text  = count.toString()
            vb.summaryRightMeta.text   = if (acctCount > 0) "across $acctCount account${if (acctCount > 1) "s" else ""}" else ""
        } else {
            val largest = filtered.maxByOrNull { it.amount }
            vb.summaryLabel.text       = "Total received"
            vb.summaryAmount.text      = fmt.format(total)
            vb.summaryAmount.setTextColor(vb.root.context.getColorCompat(R.color.finance_green_text))
            vb.summaryMeta.text        = "$count credit${if (count != 1) "s" else ""} this month"
            vb.summaryMeta.setTextColor(vb.root.context.getColorCompat(R.color.finance_green_text))
            vb.summaryRightLabel.text  = "Largest credit"
            vb.summaryRightValue.text  = if (largest != null) fmt.format(largest.amount) else "—"
            if (largest != null) {
                val cal = Calendar.getInstance().apply { timeInMillis = largest.date }
                val lm = monthNames.getOrElse(cal.get(Calendar.MONTH)) { "?" }
                val ld = cal.get(Calendar.DAY_OF_MONTH)
                val label = largest.merchant.ifBlank { "Credit" }
                vb.summaryRightMeta.text = "$label · $lm $ld"
            } else {
                vb.summaryRightMeta.text = ""
            }
        }

        // RecyclerView
        vb.txnRecycler.layoutManager = LinearLayoutManager(container.context)
        vb.txnRecycler.adapter = TxnAdapter(filtered, isDebit)

        return vb.root
    }

    override fun onDestroyView(view: View) {
        super.onDestroyView(view)
        _binding = null
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    private data class TxnRow(val isHeader: Boolean, val label: String = "", val txn: ParsedTransaction? = null)

    inner class TxnAdapter(
        private val items: List<ParsedTransaction>,
        private val showingDebits: Boolean
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val VIEW_DATE   = 0
        private val VIEW_ITEM   = 1

        // Build flat list: date-header items interleaved with transaction items
        private val rows: List<TxnRow> by lazy {
            val dayFmt = SimpleDateFormat("d MMM", Locale.getDefault())
            val result = mutableListOf<TxnRow>()
            var lastDay = ""
            for (txn in items.sortedByDescending { it.date }) {
                val dayStr = dayFmt.format(Date(txn.date))
                if (dayStr != lastDay) {
                    result.add(TxnRow(isHeader = true, label = dayStr))
                    lastDay = dayStr
                }
                result.add(TxnRow(isHeader = false, txn = txn))
            }
            result
        }

        override fun getItemViewType(position: Int) =
            if (rows[position].isHeader) VIEW_DATE else VIEW_ITEM

        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == VIEW_DATE) {
                DateVH(inflater.inflate(R.layout.transaction_list_date_header, parent, false))
            } else {
                ItemVH(inflater.inflate(R.layout.transaction_list_item, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val row = rows[position]
            if (holder is DateVH) {
                holder.bind(row.label)
            } else if (holder is ItemVH) {
                holder.bind(row.txn!!)
            }
        }

        inner class DateVH(v: View) : RecyclerView.ViewHolder(v) {
            fun bind(label: String) {
                itemView.findViewById<TextView>(R.id.dateHeaderText).text = label
            }
        }

        inner class ItemVH(v: View) : RecyclerView.ViewHolder(v) {
            fun bind(txn: ParsedTransaction) {
                val ctx = itemView.context

                // Icon container background color + icon tint
                val iconFrame   = itemView.findViewById<FrameLayout>(R.id.txnIcon)
                val iconImage   = itemView.findViewById<ImageView>(R.id.txnIconImage)
                val amountView  = itemView.findViewById<TextView>(R.id.txnAmount)

                // Check salary-shift: credit ≥ threshold in last 3 days of month
                val isSalary = !showingDebits && financeRepo.isSalaryCredit(txn)

                // Salary credits shown with amber tint (they're counted in next month)
                val (bgColor, fgColor) = when {
                    isSalary -> ctx.getColorCompat(R.color.cat_promos_bg) to ctx.getColorCompat(R.color.cat_promos_fg)
                    txn.isDebit -> ctx.getColorCompat(R.color.finance_red_bg) to ctx.getColorCompat(R.color.finance_red_text)
                    else -> ctx.getColorCompat(R.color.finance_green_bg) to ctx.getColorCompat(R.color.finance_green_text)
                }

                iconFrame.backgroundTintList = android.content.res.ColorStateList.valueOf(bgColor)
                iconImage.setImageResource(
                    if (txn.isDebit) R.drawable.ic_trending_up_black_24dp
                    else R.drawable.ic_trending_down_black_24dp
                )
                iconImage.imageTintList = android.content.res.ColorStateList.valueOf(fgColor)

                // Title: merchant or method
                val title = txn.merchant.ifBlank { txn.method.ifBlank { if (txn.isDebit) "Debit" else "Credit" } }
                itemView.findViewById<TextView>(R.id.txnTitle).text = title

                // Meta: bank + account + ref; salary credits get a note
                val metaParts = buildList {
                    val bankLabel = txn.bankName.ifBlank { "" }
                    if (bankLabel.isNotEmpty()) add(bankLabel)
                    if (txn.accountLast4.isNotEmpty()) add("···· ${txn.accountLast4}")
                    if (txn.method.isNotEmpty() && txn.method != "Other") add(txn.method)
                    if (txn.reference.isNotEmpty()) add("Ref ${txn.reference.takeLast(10)}")
                }
                val metaBase = metaParts.joinToString(" · ")
                val nextMonthNote = if (isSalary) {
                    val nextM = if (month == 12) 1 else month + 1
                    " · Counted in ${monthNames.getOrElse(nextM - 1) { "next month" }}"
                } else ""
                itemView.findViewById<TextView>(R.id.txnMeta).text = metaBase + nextMonthNote

                // Time
                val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
                itemView.findViewById<TextView>(R.id.txnTime).text = timeFmt.format(Date(txn.date))

                // Amount — salary credits shown dimmed
                val sign = if (txn.isDebit) "−" else "+"
                amountView.text = "$sign${fmt.format(txn.amount)}"
                amountView.setTextColor(fgColor)
                amountView.alpha = if (isSalary) 0.55f else 1.0f
            }
        }
    }
}









