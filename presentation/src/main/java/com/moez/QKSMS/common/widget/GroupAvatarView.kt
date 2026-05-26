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
package dev.octoshrimpy.quik.common.widget

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import dev.octoshrimpy.quik.common.util.extensions.getColorCompat
import dev.octoshrimpy.quik.common.util.extensions.resolveThemeColor
import dev.octoshrimpy.quik.common.util.extensions.setBackgroundTint
import dev.octoshrimpy.quik.databinding.GroupAvatarViewBinding
import dev.octoshrimpy.quik.model.MessageCategory
import dev.octoshrimpy.quik.model.Recipient

class GroupAvatarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : ConstraintLayout(context, attrs) {

    private var layout: GroupAvatarViewBinding =
        GroupAvatarViewBinding.inflate(LayoutInflater.from(context), this)

    var recipients: List<Recipient> = ArrayList()
        set(value) {
            field = value.sortedWith(compareByDescending { contact -> contact.contact?.lookupKey })
            updateView()
        }

    override fun onFinishInflate() {
        super.onFinishInflate()

        if (!isInEditMode) {
            updateView()
        }
    }

    private fun updateView() {
        layout.avatar1Frame.setBackgroundTint(when (recipients.size > 1) {
            true -> context.resolveThemeColor(android.R.attr.windowBackground)
            false -> context.getColorCompat(android.R.color.transparent)
        })
        layout.avatar1Frame.updateLayoutParams<LayoutParams> {
            matchConstraintPercentWidth = if (recipients.size > 1) 0.75f else 1.0f
        }
        layout.avatar2.isVisible = recipients.size > 1


        recipients.getOrNull(0).run(layout.avatar1::setRecipient)
        recipients.getOrNull(1).run(layout.avatar2::setRecipient)
    }

    fun setCategory(category: MessageCategory) {
        val (bg, fg) = when (category) {
            MessageCategory.PERSONAL     -> 0xFFE8F0FE.toInt() to 0xFF1A56DB.toInt()
            MessageCategory.TRANSACTIONS -> 0xFFF0F0EC.toInt() to 0xFF6B6B67.toInt()
            MessageCategory.OTP          -> 0xFFF3E5F5.toInt() to 0xFF7B1FA2.toInt()
            MessageCategory.UPDATES      -> 0xFFF0F0EC.toInt() to 0xFF6B6B67.toInt()
            MessageCategory.PROMOS       -> 0xFFFFFBEB.toInt() to 0xFFB45309.toInt()
            MessageCategory.SPAM         -> 0xFFFFF0F0.toInt() to 0xFFC0392B.toInt()
            else                         -> 0xFFF0F0EC.toInt() to 0xFF6B6B67.toInt()
        }
        layout.avatar1.applyCategoryStyle(bg, fg)
        layout.avatar2.applyCategoryStyle(bg, fg)
    }

}
