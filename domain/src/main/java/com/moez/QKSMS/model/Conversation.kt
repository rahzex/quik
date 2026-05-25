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
package dev.octoshrimpy.quik.model

import io.realm.RealmList
import io.realm.RealmObject
import io.realm.annotations.Index
import io.realm.annotations.PrimaryKey

open class Conversation(
    @PrimaryKey var id: Long = 0,
    @Index var archived: Boolean = false,
    @Index var blocked: Boolean = false,
    @Index var pinned: Boolean = false,
    var recipients: RealmList<Recipient> = RealmList(),
    var lastMessage: Message? = null,
    var draft: String = "",
    var draftDate: Long = 0,

    var blockingClient: Int? = null,
    var blockReason: String? = null,

    var name: String = "", // custom title

    var sendAsGroup: Boolean = true,

    /**
     * Stores the SMS category for this conversation as the enum name string,
     * e.g. "TRANSACTIONS", "OTP", "PERSONAL".
     *
     * WHY STRING AND NOT ENUM?
     * Realm's database format doesn't support Kotlin enums directly. We store the
     * string representation (MessageCategory.name) and convert it back to the enum
     * via the computed `category` property below.
     *
     * DEFAULT = "ALL": unprocessed conversations stay in the "All" tab so they are
     * never hidden from the user while the background categorization worker runs.
     *
     * @Index tells Realm to build an index on this field. This makes tab-switching
     * fast — instead of scanning every row, Realm can jump straight to the right ones.
     */
    @Index var categoryId: String = MessageCategory.ALL.name,
) : RealmObject() {

    val date: Long get() = lastMessage?.date ?: if (draft.isNotEmpty()) draftDate else 0
    val snippet: String? get() = lastMessage?.getSummary()
    val unread: Boolean get() = lastMessage?.read == false
    val me: Boolean get() = lastMessage?.isMe() == true

    /**
     * Convenience property to get/set the category as the MessageCategory enum.
     *
     * Reading:  converts the stored "TRANSACTIONS" string back to MessageCategory.TRANSACTIONS
     * Writing:  (use categoryId = newCategory.name directly for Realm writes inside transactions)
     *
     * The try/catch handles any future cases where an old string value doesn't match
     * a known enum constant (e.g. after a rollback) — falls back to ALL gracefully.
     */
    val category: MessageCategory
        get() = try {
            MessageCategory.valueOf(categoryId)
        } catch (e: IllegalArgumentException) {
            MessageCategory.ALL
        }

    fun getTitle(): String {
        return name.takeIf { it.isNotBlank() } ?: recipients.joinToString { recipient -> recipient.getDisplayName() }
    }

}
