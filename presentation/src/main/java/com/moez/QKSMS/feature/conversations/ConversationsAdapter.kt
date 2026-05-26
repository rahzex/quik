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
package dev.octoshrimpy.quik.feature.conversations

import android.content.Context
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.text.buildSpannedString
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.common.Navigator
import dev.octoshrimpy.quik.common.base.QkBindingViewHolder
import dev.octoshrimpy.quik.common.base.QkRealmAdapter
import dev.octoshrimpy.quik.common.util.Colors
import dev.octoshrimpy.quik.common.util.DateFormatter
import dev.octoshrimpy.quik.common.util.extensions.resolveThemeColor
import dev.octoshrimpy.quik.common.util.extensions.setTint
import dev.octoshrimpy.quik.databinding.ConversationListItemBinding
import dev.octoshrimpy.quik.model.Conversation
import dev.octoshrimpy.quik.model.MessageCategory
import dev.octoshrimpy.quik.repository.ScheduledMessageRepository
import dev.octoshrimpy.quik.util.PhoneNumberUtils
import io.reactivex.Observable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import javax.inject.Inject

class ConversationsAdapter @Inject constructor(
    private val colors: Colors,
    private val context: Context,
    private val dateFormatter: DateFormatter,
    private val scheduledMessageRepo: ScheduledMessageRepository,
    private val navigator: Navigator,
    private val phoneNumberUtils: PhoneNumberUtils
) : QkRealmAdapter<Conversation, QkBindingViewHolder<ConversationListItemBinding>>() {
    private val disposables = CompositeDisposable()

    // Emits threadId when user long-presses and selects "Move to Category"
    private val _moveToCategoryRequest: Subject<Long> = PublishSubject.create()
    val moveToCategoryRequest: Observable<Long> = _moveToCategoryRequest

    init {
        // This is how we access the threadId for the swipe actions
        setHasStableIds(true)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QkBindingViewHolder<ConversationListItemBinding> {
        val layoutInflater = LayoutInflater.from(parent.context)
        val binding = ConversationListItemBinding.inflate(layoutInflater, parent, false)

        if (viewType == 1) {
            val textColorPrimary = parent.context.resolveThemeColor(android.R.attr.textColorPrimary)

            binding.title.setTypeface(binding.title.typeface, Typeface.BOLD)

            binding.snippet.setTypeface(binding.snippet.typeface, Typeface.BOLD)
            binding.snippet.setTextColor(textColorPrimary)
            binding.snippet.maxLines = 5

            binding.unread.isVisible = true

            binding.date.setTypeface(binding.date.typeface, Typeface.BOLD)
            binding.date.setTextColor(textColorPrimary)
        }

        return QkBindingViewHolder(binding).apply {
            binding.root.setOnClickListener {
                val conversation = getItem(adapterPosition) ?: return@setOnClickListener
                when (toggleSelection(conversation.id, false)) {
                    true -> binding.root.isActivated = isSelected(conversation.id)
                    false -> navigator.showConversation(conversation.id)
                }
            }
            binding.root.setOnLongClickListener {
                val conversation = getItem(adapterPosition) ?: return@setOnLongClickListener true
                toggleSelection(conversation.id)
                binding.root.isActivated = isSelected(conversation.id)
                // Offer "Move to Category" on first long-press (selection mode just started)
                _moveToCategoryRequest.onNext(conversation.id)
                true
            }
        }
    }

    override fun onBindViewHolder(holder: QkBindingViewHolder<ConversationListItemBinding>, position: Int) {
        val conversation = getItem(position) ?: return
        val binding = holder.binding

        // If the last message wasn't incoming, then the colour doesn't really matter anyway
        val lastMessage = conversation.lastMessage
        val recipient = when {
            conversation.recipients.size == 1 || lastMessage == null -> conversation.recipients.firstOrNull()
            else -> conversation.recipients.find { recipient ->
                phoneNumberUtils.compare(recipient.address, lastMessage.address)
            }
        }
        val theme = colors.theme(recipient).theme

        holder.itemView.isActivated = isSelected(conversation.id)

        binding.avatars.recipients = conversation.recipients
        binding.title.collapseEnabled = conversation.recipients.size > 1
        binding.title.text = buildSpannedString {
            append(conversation.getTitle())
        }
        binding.date.text = conversation.date.takeIf { it > 0 }?.let(dateFormatter::getConversationTimestamp)
        binding.snippet.text = when {
            conversation.draft.isNotEmpty() -> context.getString(R.string.main_sender_draft, conversation.draft)
            conversation.me -> context.getString(R.string.main_sender_you, conversation.snippet)
            else -> conversation.snippet
        }

        // Make the preview in italics if draft
        if (conversation.draft.isNotEmpty()) binding.snippet.setTypeface(null, Typeface.ITALIC)

        // Get Scheduled Messages
        val disposable = scheduledMessageRepo
            .getScheduledMessagesForConversation(conversation.id)
            .asFlowable()
            .toObservable()
            .subscribe { messages ->
                binding.scheduled.isVisible = messages.isNotEmpty()
            }
        disposables.add(disposable)

        binding.pinned.isVisible = conversation.pinned
        binding.unread.setTint(theme)

        // Category badge (matches HTML .msg-tag — between name row and preview)
        val category = conversation.category
        if (category != MessageCategory.ALL && category != MessageCategory.PERSONAL) {
            binding.categoryBadge.isVisible = true
            // Contextual label matching HTML mockup style
            binding.categoryBadge.text = when (category) {
                MessageCategory.TRANSACTIONS -> "Transaction"
                MessageCategory.OTP          -> "OTP"
                MessageCategory.UPDATES      -> "Update"
                MessageCategory.PROMOS       -> "Promo"
                MessageCategory.SPAM         -> "Spam"
                else                         -> category.name.lowercase().replaceFirstChar { it.uppercase() }
            }
            val (bgColor, textColor) = when (category) {
                MessageCategory.TRANSACTIONS -> Pair(0xFFF0FAF4.toInt(), 0xFF1A7F4B.toInt())
                MessageCategory.OTP          -> Pair(0xFFF3E5F5.toInt(), 0xFF7B1FA2.toInt())
                MessageCategory.UPDATES      -> Pair(0xFFE8F0FE.toInt(), 0xFF1A56DB.toInt())
                MessageCategory.PROMOS       -> Pair(0xFFFFFBEB.toInt(), 0xFFB45309.toInt())
                MessageCategory.SPAM         -> Pair(0xFFFFF0F0.toInt(), 0xFFC0392B.toInt())
                else                         -> Pair(0xFFEEEEEE.toInt(), 0xFF424242.toInt())
            }
            binding.categoryBadge.setBackgroundColor(bgColor)
            binding.categoryBadge.setTextColor(textColor)
        } else {
            binding.categoryBadge.isVisible = false
        }
    }

    override fun getItemId(position: Int): Long {
        return getItem(position)?.id ?: -1
    }

    override fun getItemViewType(position: Int): Int {
        return if (getItem(position)?.unread == false) 0 else 1
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        disposables.clear()
    }


}
