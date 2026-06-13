/*
 * Copyright (C) 2017 Moez Bhatti <moez.bhatti@gmail.com>
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 */
package dev.octoshrimpy.quik.feature.main

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.common.Navigator
import dev.octoshrimpy.quik.common.base.QkAdapter
import dev.octoshrimpy.quik.common.base.QkBindingViewHolder
import dev.octoshrimpy.quik.common.util.Colors
import dev.octoshrimpy.quik.common.util.DateFormatter
import dev.octoshrimpy.quik.extensions.removeAccents
import dev.octoshrimpy.quik.model.MessageCategory
import dev.octoshrimpy.quik.model.SearchResult
import dev.octoshrimpy.quik.databinding.SearchListItemBinding
import javax.inject.Inject

class SearchAdapter @Inject constructor(
    colors: Colors,
    private val context: Context,
    private val dateFormatter: DateFormatter,
    private val navigator: Navigator
) : QkAdapter<SearchResult, QkBindingViewHolder<SearchListItemBinding>>() {

    private val highlightColor: Int by lazy { colors.theme().highlight }
    private val tagCornerRadius: Float by lazy { context.resources.displayMetrics.density * 4f }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QkBindingViewHolder<SearchListItemBinding> {
        val binding = SearchListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return QkBindingViewHolder(binding).apply {
            itemView.setOnClickListener {
                val result = getItem(adapterPosition)
                navigator.showConversation(result.conversation.id, result.query.takeIf { result.messages > 0 })
            }
        }
    }

    override fun onBindViewHolder(holder: QkBindingViewHolder<SearchListItemBinding>, position: Int) {
        val result = getItem(position)
        val query = result.query
        val conversationTitle = result.conversation.getTitle()

        // ── Initials avatar ──────────────────────────────────────────────────
        // Strip non-alpha chars, take first 4 letters uppercase (e.g. "VM-HDFCBK" → "VMHD")
        val initials = conversationTitle.replace(Regex("[^A-Za-z]"), "").take(4).uppercase()
            .ifEmpty { conversationTitle.take(2).uppercase() }
        holder.binding.avatarInitials.text = initials

        // ── Sender name with title highlight ────────────────────────────────
        val titleSpan = SpannableString(conversationTitle)
        var idx = titleSpan.toString().removeAccents().indexOf(query, ignoreCase = true)
        while (idx >= 0) {
            titleSpan.setSpan(BackgroundColorSpan(highlightColor), idx, idx + query.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            idx = titleSpan.toString().indexOf(query, idx + query.length, ignoreCase = true)
        }
        holder.binding.title.text = titleSpan

        // ── Date (always shown) ──────────────────────────────────────────────
        holder.binding.date.text = dateFormatter.getConversationTimestamp(result.conversation.date)

        // ── Category tag badge ───────────────────────────────────────────────
        // Use holder.itemView.context (Activity context) so day/night color variants
        // are resolved against the current UI configuration — not the injected
        // application context which may not carry the correct uiMode.
        val tagInfo = getCategoryTagInfo(result.conversation.category, holder.itemView.context)
        if (tagInfo != null) {
            val (bgColor, fgColor, label) = tagInfo
            val tagBg = GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = tagCornerRadius
            }
            holder.binding.categoryTag.background = tagBg
            holder.binding.categoryTag.setTextColor(fgColor)
            holder.binding.categoryTag.text = label
            holder.binding.categoryTag.visibility = View.VISIBLE
        } else {
            holder.binding.categoryTag.visibility = View.GONE
        }

        // ── Snippet / preview with highlight ────────────────────────────────
        // For body matches (messages > 0): use result.snippet which contains the actual matching
        // message text — this is what gets highlighted.
        // For title matches (messages == 0): use conversation.snippet (last message body).
        val rawSnippet: String = when {
            result.messages > 0 -> result.snippet.ifEmpty { result.conversation.snippet ?: "" }
            result.conversation.me -> context.getString(
                R.string.main_sender_you, result.snippet.ifEmpty { result.conversation.snippet ?: "" }
            )
            else -> result.snippet.ifEmpty { result.conversation.snippet ?: "" }
        }
        val snippetSpan = SpannableString(rawSnippet)
        var sIdx = snippetSpan.toString().indexOf(query, ignoreCase = true)
        while (sIdx >= 0) {
            snippetSpan.setSpan(BackgroundColorSpan(highlightColor), sIdx, sIdx + query.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sIdx = snippetSpan.toString().indexOf(query, sIdx + query.length, ignoreCase = true)
        }
        holder.binding.snippet.text = snippetSpan
    }

    /**
     * Returns Triple(bgColor, fgColor, label) for the category badge, or null to hide the badge.
     * @param ctx Must be the Activity/View context so night-mode colour variants resolve correctly.
     */
    private fun getCategoryTagInfo(category: MessageCategory, ctx: Context): Triple<Int, Int, String>? {
        return when (category) {
            MessageCategory.TRANSACTIONS -> Triple(
                ContextCompat.getColor(ctx, R.color.cat_transactions_badge_bg),
                ContextCompat.getColor(ctx, R.color.cat_transactions_badge_fg),
                "Transaction"
            )
            MessageCategory.OTP -> Triple(
                ContextCompat.getColor(ctx, R.color.cat_otp_bg),
                ContextCompat.getColor(ctx, R.color.cat_otp_fg),
                "OTP"
            )
            MessageCategory.PROMOS -> Triple(
                ContextCompat.getColor(ctx, R.color.cat_promos_bg),
                ContextCompat.getColor(ctx, R.color.cat_promos_fg),
                "Promo"
            )
            MessageCategory.SPAM -> Triple(
                ContextCompat.getColor(ctx, R.color.cat_spam_bg),
                ContextCompat.getColor(ctx, R.color.cat_spam_fg),
                "Spam"
            )
            MessageCategory.UPDATES -> Triple(
                ContextCompat.getColor(ctx, R.color.cat_updates_bg),
                ContextCompat.getColor(ctx, R.color.cat_updates_fg),
                "Update"
            )
            MessageCategory.PERSONAL -> Triple(
                ContextCompat.getColor(ctx, R.color.cat_personal_bg),
                ContextCompat.getColor(ctx, R.color.cat_personal_fg),
                "Personal"
            )
            else -> null  // ALL, BILL_REMINDER — no badge
        }
    }

    override fun areItemsTheSame(old: SearchResult, new: SearchResult): Boolean {
        return old.conversation.id == new.conversation.id && old.messages > 0 == new.messages > 0
    }

    override fun areContentsTheSame(old: SearchResult, new: SearchResult): Boolean {
        return old.query == new.query &&
                old.conversation.id == new.conversation.id &&
                old.messages == new.messages
    }
}
