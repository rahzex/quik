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
package dev.octoshrimpy.quik.common.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.app.TaskStackBuilder
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.common.util.extensions.toPerson
import dev.octoshrimpy.quik.feature.compose.ComposeActivity
import dev.octoshrimpy.quik.feature.qkreply.QkReplyActivity
import dev.octoshrimpy.quik.manager.PermissionManager
import dev.octoshrimpy.quik.manager.ShortcutManager
import dev.octoshrimpy.quik.model.MessageCategory
import dev.octoshrimpy.quik.receiver.BlockThreadReceiver
import dev.octoshrimpy.quik.receiver.CopyOtpReceiver
import dev.octoshrimpy.quik.receiver.DeleteMessagesReceiver
import dev.octoshrimpy.quik.receiver.MessageMarkReceiver
import dev.octoshrimpy.quik.receiver.RemoteMessagingReceiver
import dev.octoshrimpy.quik.receiver.ResendMessageReceiver
import dev.octoshrimpy.quik.receiver.SpeakThreadsReceiver
import dev.octoshrimpy.quik.repository.ContactRepository
import dev.octoshrimpy.quik.repository.ConversationRepository
import dev.octoshrimpy.quik.repository.MessageRepository
import dev.octoshrimpy.quik.util.OtpExtractor
import dev.octoshrimpy.quik.util.PhoneNumberUtils
import dev.octoshrimpy.quik.util.Preferences
import dev.octoshrimpy.quik.util.TxnPreviewExtractor
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationManagerImpl @Inject constructor(
    private val context: Context,
    private val colors: Colors,
    private val conversationRepo: ConversationRepository,
    private val prefs: Preferences,
    private val messageRepo: MessageRepository,
    private val permissions: PermissionManager,
    private val phoneNumberUtils: PhoneNumberUtils,
    private val contactRepo: ContactRepository,
    private val shortcutManager: ShortcutManager
) : dev.octoshrimpy.quik.manager.NotificationManager {

    companion object {
        const val DEFAULT_CHANNEL_ID = "notifications_default"
        const val BACKUP_RESTORE_CHANNEL_ID = "notifications_backup_restore"
        const val RECEIVING_WORKER_CHANNEL_ID = "notifications_receiving_worker"

        val MARK_TYPE_COUNT = MessageMarkReceiver.MarkType.values().size

        val VIBRATE_PATTERN = longArrayOf(0, 200, 0, 200)

        // Time formatter for notification banner
        private val TIME_FMT = SimpleDateFormat("h:mm", Locale.getDefault())

        /** Visual spec for each category used in RemoteViews population */
        data class CategorySpec(
            val iconRes: Int,
            val chipBgColorRes: Int,
            val chipFgColorRes: Int,
            val badgeBgColorRes: Int,
            val badgeFgColorRes: Int,
            val badgeLabelRes: Int,
            val showBadge: Boolean = true
        )
    }

    /** Maps [MessageCategory] → rendering spec. Colors reference values/colors.xml resources
     *  that have night-mode overrides in values-night/colors.xml. */
    private fun specForCategory(category: MessageCategory): CategorySpec = when (category) {
        MessageCategory.PERSONAL -> CategorySpec(
            iconRes           = R.drawable.ic_chat_bubble_outline_24dp,
            chipBgColorRes    = R.color.cat_personal_bg,
            chipFgColorRes    = R.color.cat_personal_fg,
            badgeBgColorRes   = R.color.cat_personal_bg,
            badgeFgColorRes   = R.color.cat_personal_fg,
            badgeLabelRes     = R.string.notif_badge_personal
        )
        MessageCategory.TRANSACTIONS -> CategorySpec(
            iconRes           = R.drawable.ic_trending_up_black_24dp,
            chipBgColorRes    = R.color.cat_transactions_badge_bg,
            chipFgColorRes    = R.color.cat_transactions_badge_fg,
            badgeBgColorRes   = R.color.cat_transactions_badge_bg,
            badgeFgColorRes   = R.color.cat_transactions_badge_fg,
            badgeLabelRes     = R.string.notif_badge_transactions
        )
        MessageCategory.OTP -> CategorySpec(
            iconRes           = R.drawable.ic_notif_lock,
            chipBgColorRes    = R.color.cat_otp_bg,
            chipFgColorRes    = R.color.cat_otp_fg,
            badgeBgColorRes   = R.color.cat_otp_bg,
            badgeFgColorRes   = R.color.cat_otp_fg,
            badgeLabelRes     = R.string.notif_badge_otp,
            showBadge         = false  // badge shown in title text instead ("SENDER · OTP")
        )
        MessageCategory.UPDATES -> CategorySpec(
            iconRes           = R.drawable.ic_notifications_black_24dp,
            chipBgColorRes    = R.color.cat_updates_bg,
            chipFgColorRes    = R.color.cat_updates_fg,
            badgeBgColorRes   = R.color.cat_updates_bg,
            badgeFgColorRes   = R.color.cat_updates_fg,
            badgeLabelRes     = R.string.notif_badge_updates
        )
        MessageCategory.PROMOS -> CategorySpec(
            iconRes           = R.drawable.ic_star_black_24dp,
            chipBgColorRes    = R.color.cat_promos_bg,
            chipFgColorRes    = R.color.cat_promos_fg,
            badgeBgColorRes   = R.color.cat_promos_bg,
            badgeFgColorRes   = R.color.cat_promos_fg,
            badgeLabelRes     = R.string.notif_badge_promos
        )
        MessageCategory.SPAM -> CategorySpec(
            iconRes           = R.drawable.ic_block_white_24dp,
            chipBgColorRes    = R.color.cat_spam_bg,
            chipFgColorRes    = R.color.cat_spam_fg,
            badgeBgColorRes   = R.color.cat_spam_bg,
            badgeFgColorRes   = R.color.cat_spam_fg,
            badgeLabelRes     = R.string.notif_badge_spam
        )
        MessageCategory.BILL_REMINDER -> CategorySpec(
            iconRes           = R.drawable.ic_credit_card_black_24dp,
            chipBgColorRes    = R.color.cat_transactions_bg,
            chipFgColorRes    = R.color.cat_transactions_fg,
            badgeBgColorRes   = R.color.cat_transactions_badge_bg,
            badgeFgColorRes   = R.color.cat_transactions_badge_fg,
            badgeLabelRes     = R.string.notif_badge_bill
        )
        else -> CategorySpec(
            iconRes           = R.drawable.ic_chat_bubble_outline_24dp,
            chipBgColorRes    = R.color.cat_default_bg,
            chipFgColorRes    = R.color.cat_default_fg,
            badgeBgColorRes   = R.color.cat_default_bg,
            badgeFgColorRes   = R.color.cat_default_fg,
            badgeLabelRes     = R.string.notif_badge_personal,
            showBadge         = false
        )
    }

    /**
     * Builds the compact [RemoteViews] used as [NotificationCompat.Builder.setCustomContentView].
     * Applies category-specific icon chip and badge pill.
     */
    private fun buildCompactView(
        senderName: String,
        timeStr: String,
        previewText: String,
        spec: CategorySpec,
        category: MessageCategory
    ): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.notification_compact_category)

        // Resolve text colours respecting the current night-mode configuration so that
        // the custom RemoteViews content is readable on both light and dark backgrounds.
        val isNight = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val textPrimaryColor = ContextCompat.getColor(
            context, if (isNight) R.color.textPrimaryDark else R.color.textPrimary)
        val textSecondaryColor = ContextCompat.getColor(
            context, if (isNight) R.color.textSecondaryDark else R.color.textSecondary)

        // Icon chip background color
        rv.setInt(
            R.id.notif_icon_chip, "setBackgroundColor",
            ContextCompat.getColor(context, spec.chipBgColorRes)
        )
        // Icon drawable tinted with fg color
        rv.setImageViewResource(R.id.notif_icon, spec.iconRes)
        rv.setInt(
            R.id.notif_icon, "setColorFilter",
            ContextCompat.getColor(context, spec.chipFgColorRes)
        )

        // Sender — for OTP append the category label in the sender field
        val displaySender = if (category == MessageCategory.OTP)
            "$senderName · ${context.getString(R.string.notif_badge_otp)}"
        else
            senderName
        rv.setTextViewText(R.id.notif_sender, displaySender)
        rv.setTextColor(R.id.notif_sender, textPrimaryColor)

        rv.setTextViewText(R.id.notif_time, timeStr)
        rv.setTextColor(R.id.notif_time, textSecondaryColor)

        rv.setTextViewText(R.id.notif_preview, previewText)
        rv.setTextColor(R.id.notif_preview, textSecondaryColor)

        // Badge pill
        if (spec.showBadge) {
            rv.setViewVisibility(R.id.notif_badge, View.VISIBLE)
            rv.setTextViewText(R.id.notif_badge, context.getString(spec.badgeLabelRes))
            rv.setInt(
                R.id.notif_badge, "setBackgroundColor",
                ContextCompat.getColor(context, spec.badgeBgColorRes)
            )
            rv.setTextColor(R.id.notif_badge, ContextCompat.getColor(context, spec.badgeFgColorRes))
        } else {
            rv.setViewVisibility(R.id.notif_badge, View.GONE)
        }

        return rv
    }

    /**
     * Builds the expanded [RemoteViews] for OTP notifications.
     * Shows individual digit pills + a Copy button wired to [CopyOtpReceiver].
     */
    private fun buildOtpExpandedView(
        senderName: String,
        timeStr: String,
        otpCode: String,
        expiry: String?,
        threadId: Long,
        spec: CategorySpec
    ): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.notification_otp_expanded)

        // Resolve text colours for dark/light mode
        val isNight = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val textPrimaryColor = ContextCompat.getColor(
            context, if (isNight) R.color.textPrimaryDark else R.color.textPrimary)
        val textSecondaryColor = ContextCompat.getColor(
            context, if (isNight) R.color.textSecondaryDark else R.color.textSecondary)

        // Icon chip
        rv.setInt(
            R.id.notif_otp_icon_chip, "setBackgroundColor",
            ContextCompat.getColor(context, spec.chipBgColorRes)
        )
        rv.setImageViewResource(R.id.notif_otp_icon, spec.iconRes)
        rv.setInt(
            R.id.notif_otp_icon, "setColorFilter",
            ContextCompat.getColor(context, spec.chipFgColorRes)
        )

        // Title: "SENDER · OTP"
        rv.setTextViewText(
            R.id.notif_otp_title,
            "$senderName · ${context.getString(R.string.notif_badge_otp)}"
        )
        rv.setTextColor(R.id.notif_otp_title, textPrimaryColor)

        rv.setTextViewText(R.id.notif_otp_time, timeStr)
        rv.setTextColor(R.id.notif_otp_time, textSecondaryColor)

        // Expiry
        if (!expiry.isNullOrEmpty()) {
            rv.setViewVisibility(R.id.notif_otp_expiry, View.VISIBLE)
            rv.setTextViewText(
                R.id.notif_otp_expiry,
                context.getString(R.string.notif_otp_expires_in, expiry)
            )
            rv.setTextColor(
                R.id.notif_otp_expiry,
                ContextCompat.getColor(context, spec.chipFgColorRes)
            )
        } else {
            rv.setViewVisibility(R.id.notif_otp_expiry, View.GONE)
        }

        // Digit views — show as many as the OTP has digits
        val digitViews = listOf(
            R.id.notif_otp_d1, R.id.notif_otp_d2, R.id.notif_otp_d3, R.id.notif_otp_d4,
            R.id.notif_otp_d5, R.id.notif_otp_d6, R.id.notif_otp_d7, R.id.notif_otp_d8
        )
        val chipBg  = ContextCompat.getColor(context, spec.chipBgColorRes)
        val chipFg  = ContextCompat.getColor(context, spec.chipFgColorRes)

        digitViews.forEachIndexed { index, viewId ->
            val ch = otpCode.getOrNull(index)
            if (ch != null) {
                rv.setViewVisibility(viewId, View.VISIBLE)
                rv.setTextViewText(viewId, ch.toString())
                rv.setInt(viewId, "setBackgroundColor", chipBg)
                rv.setTextColor(viewId, chipFg)
            } else {
                rv.setViewVisibility(viewId, View.GONE)
            }
        }

        // Copy button
        rv.setInt(R.id.notif_otp_copy_btn, "setBackgroundColor", chipBg)
        rv.setTextColor(R.id.notif_otp_copy_btn, chipFg)
        val copyIntent = Intent(context, CopyOtpReceiver::class.java).apply {
            putExtra(CopyOtpReceiver.EXTRA_OTP_CODE, otpCode)
            putExtra(CopyOtpReceiver.EXTRA_THREAD_ID, threadId)
        }
        val copyPI = PendingIntent.getBroadcast(
            context,
            threadId.toInt() + 500_000,
            copyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        rv.setOnClickPendingIntent(R.id.notif_otp_copy_btn, copyPI)

        return rv
    }

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        // Make sure the default channel has been initialized
        createNotificationChannel()
    }

    /**
     * In order to not have conflicting pending intents for the same receiver,
     * let's generate one for each type of marking action.
     */
    fun MessageMarkReceiver.MarkType.getRequestCode(threadId: Long): Int {
        return threadId.toInt() * MARK_TYPE_COUNT + ordinal
    }

    // Required for running workers on Android 12 and older
    override fun getForegroundNotificationForWorkersOnOlderAndroids() =
        NotificationCompat.Builder(context, RECEIVING_WORKER_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.notification_foreground_worker_title))
            .setContentText(context.getString(R.string.notification_foreground_worker_text))
            .setShowWhen(false)
            .setWhen(System.currentTimeMillis())
            .setSmallIcon(R.drawable.ic_notification_worker)
            .setColor(colors.theme().theme)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .build()

    /**
     * Updates the notification for a particular conversation
     */
    override fun update(threadId: Long) {
        // If notifications are disabled, don't do anything
        if (!prefs.notifications(threadId).get()) {
            return
        }

        if (!permissions.hasNotifications()) {
            Timber.w("Cannot update notification because we don't have the notification permission")
            return
        }

        val messages = messageRepo.getUnreadUnseenMessages(threadId)

        // If there are no messages to be displayed, make sure that the notification is dismissed
        if (messages.isEmpty()) {
            notificationManager.cancel(threadId.toInt())
            notificationManager.cancel(threadId.toInt() + 100000)
            return
        }

        val conversation = conversationRepo.getConversation(threadId) ?: return
        val lastMessage  = messages.last() ?: return
        val category     = lastMessage.category

        val lastRecipient = conversation.lastMessage?.let { lm ->
            conversation.recipients.find { r -> phoneNumberUtils.compare(r.address, lm.address) }
        } ?: conversation.recipients.firstOrNull()

        val contentIntent = Intent(context, ComposeActivity::class.java).putExtra("threadId", threadId)
        val taskStackBuilder = TaskStackBuilder.create(context)
                .addParentStack(ComposeActivity::class.java)
                .addNextIntent(contentIntent)
        val contentPI = taskStackBuilder.getPendingIntent(
            threadId.toInt(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val seenIntent = Intent(context, MessageMarkReceiver::class.java)
            .putExtra("threadId", threadId)
            .putExtra("type", MessageMarkReceiver.MarkType.Seen.ordinal)
        val seenPI = PendingIntent.getBroadcast(
            context,
            MessageMarkReceiver.MarkType.Seen.getRequestCode(threadId),
            seenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val ringtone = prefs.ringtone(threadId).get()
                .takeIf { it.isNotEmpty() }
                ?.let(Uri::parse)
                ?.also { uri ->
                    context.grantUriPermission(
                        "com.android.systemui", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }

        // ── Category-aware visual setup ───────────────────────────────────────
        val spec = specForCategory(category)

        // Sender name: for group conversations use the conversation title
        val senderName = if (conversation.recipients.size >= 2)
            conversation.getTitle()
        else
            lastRecipient?.getDisplayName() ?: conversation.getTitle()

        val timeStr = TIME_FMT.format(Date(lastMessage.date))

        // Preview text — category-specific extraction
        val previewText: String = when (category) {
            MessageCategory.TRANSACTIONS, MessageCategory.BILL_REMINDER ->
                TxnPreviewExtractor.extractPreview(lastMessage.getSummary())
                    ?: lastMessage.getSummary()
            else -> lastMessage.getSummary()
        }

        val compactRv = buildCompactView(senderName, timeStr, previewText, spec, category)

        // ── Notification builder ──────────────────────────────────────────────
        val notification = NotificationCompat.Builder(context, getChannelIdForNotification(threadId))
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setColor(colors.theme(lastRecipient).theme)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setSmallIcon(R.drawable.ic_notification)
                .setNumber(messages.size)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(contentPI)
                .setDeleteIntent(seenPI)
                .setLights(Color.WHITE, 500, 2000)
                .setWhen(lastMessage.date)
                .setVibrate(if (prefs.vibration(threadId).get()) VIBRATE_PATTERN else longArrayOf(0))
                .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                .setCustomContentView(compactRv)

        // Silent-if-not-contact preference
        if (prefs.silentNotContact.get() && run {
            val msgRecipientNumbers = conversation.recipients.map { it.address }
            !contactRepo
                .getUnmanagedAllContacts()
                .flatMap { it.numbers }
                .map { it.address }
                .any { cn -> msgRecipientNumbers.any { phoneNumberUtils.compare(cn, it) } }
        }) {
            notification.setSilent(true)
        } else {
            notification.setSound(ringtone)
        }

        // Allow/block system contextual actions based on settings
        notification.setAllowSystemGeneratedContextualActions(
            prefs.messageLinkHandling.get() == Preferences.MESSAGE_LINK_HANDLING_ALLOW
        )

        // ── OTP expanded big-content view ─────────────────────────────────────
        if (category == MessageCategory.OTP) {
            val body     = lastMessage.getSummary()
            val otpCode  = OtpExtractor.extractCode(body) ?: ""
            val expiry   = OtpExtractor.extractExpiry(body)

            if (otpCode.isNotEmpty()) {
                val expandedRv = buildOtpExpandedView(
                    senderName, timeStr, otpCode, expiry, threadId, spec
                )
                // Show OTP digits + Copy button in both the expanded shade view AND the
                // heads-up banner so the user sees them without having to pull down.
                notification.setCustomBigContentView(expandedRv)
                notification.setCustomHeadsUpContentView(expandedRv)
            }
        }

        // ── Notification preview mode fallback (PREVIEWS_NAME / PREVIEWS_NONE) ──
        when (prefs.notificationPreviews(threadId).get()) {
            Preferences.NOTIFICATION_PREVIEWS_ALL -> { /* custom view already set */ }

            Preferences.NOTIFICATION_PREVIEWS_NAME -> {
                // Override: drop message content but still show custom view with name only
                val nameOnlyRv = buildCompactView(
                    senderName, timeStr,
                    context.resources.getQuantityString(
                        R.plurals.notification_new_messages, messages.size, messages.size
                    ),
                    spec, category
                )
                notification.setCustomContentView(nameOnlyRv)
            }

            Preferences.NOTIFICATION_PREVIEWS_NONE -> {
                val noneRv = RemoteViews(context.packageName, R.layout.notification_compact_category)
                // Minimal — just app name + count, no sender/content
                noneRv.setTextViewText(R.id.notif_sender, context.getString(R.string.app_name))
                noneRv.setTextViewText(R.id.notif_time, timeStr)
                noneRv.setTextViewText(
                    R.id.notif_preview,
                    context.resources.getQuantityString(
                        R.plurals.notification_new_messages, messages.size, messages.size
                    )
                )
                noneRv.setViewVisibility(R.id.notif_badge, View.GONE)
                noneRv.setInt(R.id.notif_icon_chip, "setBackgroundColor",
                    ContextCompat.getColor(context, R.color.cat_default_bg))
                noneRv.setImageViewResource(R.id.notif_icon, R.drawable.ic_chat_bubble_outline_24dp)
                noneRv.setInt(R.id.notif_icon, "setColorFilter",
                    ContextCompat.getColor(context, R.color.cat_default_fg))
                // Override all views including big/heads-up so OTP content is hidden
                // when the user has opted out of content previews.
                notification.setCustomContentView(noneRv)
                notification.setCustomBigContentView(noneRv)
                notification.setCustomHeadsUpContentView(noneRv)
            }
        }

        // ── Action buttons ────────────────────────────────────────────────────
        val actionLabels = context.resources.getStringArray(R.array.notification_actions)
        listOf(prefs.notifAction1, prefs.notifAction2, prefs.notifAction3)
                .map { preference -> preference.get() }
                .distinct()
                .mapNotNull { action ->
                    when (action) {
                        Preferences.NOTIFICATION_ACTION_ARCHIVE -> {
                            val intent = Intent(context, MessageMarkReceiver::class.java)
                                .putExtra("threadId", threadId)
                                .putExtra("type", MessageMarkReceiver.MarkType.Archived.ordinal)
                            val pi = PendingIntent.getBroadcast(
                                context,
                                MessageMarkReceiver.MarkType.Archived.getRequestCode(threadId),
                                intent,
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                            NotificationCompat.Action.Builder(R.drawable.ic_archive_white_24dp, actionLabels[action], pi)
                                    .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_ARCHIVE).build()
                        }

                        Preferences.NOTIFICATION_ACTION_DELETE -> {
                            val messageIds = messages.map { it.id }.toLongArray()
                            val intent = Intent(context, DeleteMessagesReceiver::class.java)
                                    .putExtra("threadId", threadId)
                                    .putExtra("messageIds", messageIds)
                            val pi = PendingIntent.getBroadcast(context, threadId.toInt(), intent,
                                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                            NotificationCompat.Action.Builder(R.drawable.ic_delete_white_24dp, actionLabels[action], pi)
                                    .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_DELETE).build()
                        }

                        Preferences.NOTIFICATION_ACTION_BLOCK -> {
                            val intent = Intent(context, BlockThreadReceiver::class.java).putExtra("threadId", threadId)
                            val pi = PendingIntent.getBroadcast(context, threadId.toInt(), intent,
                                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                            NotificationCompat.Action.Builder(R.drawable.ic_block_white_24dp, actionLabels[action], pi)
                                    .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MUTE).build()
                        }

                        Preferences.NOTIFICATION_ACTION_READ -> {
                            val intent = Intent(context, MessageMarkReceiver::class.java)
                                .putExtra("threadId", threadId)
                                .putExtra("type", MessageMarkReceiver.MarkType.Read.ordinal)
                            val pi = PendingIntent.getBroadcast(
                                context,
                                MessageMarkReceiver.MarkType.Read.getRequestCode(threadId),
                                intent,
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                            NotificationCompat.Action.Builder(R.drawable.ic_check_white_24dp, actionLabels[action], pi)
                                    .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ).build()
                        }

                        Preferences.NOTIFICATION_ACTION_REPLY -> {
                            if (Build.VERSION.SDK_INT >= 24) {
                                getReplyAction(threadId)
                            } else {
                                val intent = Intent(context, QkReplyActivity::class.java).putExtra("threadId", threadId)
                                val pi = PendingIntent.getActivity(context, threadId.toInt(), intent,
                                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                                NotificationCompat.Action
                                        .Builder(R.drawable.ic_reply_white_24dp, actionLabels[action], pi)
                                        .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY).build()
                            }
                        }

                        Preferences.NOTIFICATION_ACTION_CALL -> {
                            val address = conversation.recipients[0]?.address
                            val intentAction = if (permissions.hasCalling()) Intent.ACTION_CALL else Intent.ACTION_DIAL
                            val intent = Intent(intentAction, "tel:$address".toUri())
                            val pi = PendingIntent.getActivity(context, threadId.toInt(), intent,
                                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                            NotificationCompat.Action.Builder(R.drawable.ic_call_white_24dp, actionLabels[action], pi)
                                    .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_CALL).build()
                        }

                        Preferences.NOTIFICATION_ACTION_SPEAK -> {
                            val intent = Intent(context, SpeakThreadsReceiver::class.java).putExtra("threadId", threadId)
                            val pi = PendingIntent.getBroadcast(context, 0, intent,
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                            NotificationCompat.Action.Builder(R.drawable.ic_speaker_black_24dp, actionLabels[action], pi)
                                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_NONE).build()
                        }

                        else -> null
                    }
                }
                .forEach { notification.addAction(it) }

        // ── People (bypass DND) ───────────────────────────────────────────────
        conversation.recipients.forEach { recipient ->
            notification.addPerson(recipient.toPerson(context, colors))
        }

        if (prefs.qkreply.get()) {
            notification.priority = NotificationCompat.PRIORITY_DEFAULT
            val intent = Intent(context, QkReplyActivity::class.java)
                    .putExtra("threadId", threadId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }

        val sc = shortcutManager.getOrCreateShortcut(threadId)
        notification.setShortcutInfo(sc)
        notificationManager.notify(threadId.toInt(), notification.build())

        // Wake screen
        if (prefs.wakeScreen(threadId).get()) {
            context.getSystemService<PowerManager>()?.let { powerManager ->
                if (!powerManager.isInteractive) {
                    val flags = PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP
                    val wakeLock = powerManager.newWakeLock(flags, context.packageName)
                    wakeLock.acquire(5000)
                }
            }
        }
    }

    override fun notifyFailed(msgId: Long) {
        val message = messageRepo.getMessage(msgId)

        if (message == null || !message.isFailedMessage()) {
            return
        }

        val conversation = conversationRepo.getConversation(message.threadId) ?: return
        val lastRecipient = conversation.lastMessage?.let { lastMessage ->
            conversation.recipients.find { recipient ->
                phoneNumberUtils.compare(recipient.address, lastMessage.address)
            }
        } ?: conversation.recipients.firstOrNull()

        val threadId = conversation.id

        val contentIntent = Intent(context, ComposeActivity::class.java).putExtra("threadId", threadId)
        val taskStackBuilder = TaskStackBuilder.create(context)
            .addParentStack(ComposeActivity::class.java)
            .addNextIntent(contentIntent)
        val contentPI = taskStackBuilder.getPendingIntent(threadId.toInt(), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        //Action for resending a failed message
        val resendIntent = Intent(context, ResendMessageReceiver::class.java).apply {
            putExtra("id", message.id)
        }
        val resendPendingIntent = PendingIntent.getBroadcast(
            context,
            message.id.toInt(),
            resendIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val resendAction = NotificationCompat.Action.Builder(
            R.drawable.ic_send_black_24dp,
            context.getString(R.string.notification_message_failed_action),
            resendPendingIntent
        )
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_NONE)
            .build()

        val notification = NotificationCompat.Builder(context, getChannelIdForNotification(threadId))
                .setContentTitle(context.getString(R.string.notification_message_failed_title))
                .setContentText(context.getString(R.string.notification_message_failed_text, conversation.getTitle()))
                .addAction(resendAction)
                .setColor(colors.theme(lastRecipient).theme)
                .setPriority(NotificationManagerCompat.IMPORTANCE_MAX)
                .setSmallIcon(R.drawable.ic_notification_failed)
                .setAutoCancel(true)
                .setContentIntent(contentPI)
                .setSound(Uri.parse(prefs.ringtone(threadId).get()))
                .setLights(Color.WHITE, 500, 2000)
                .setVibrate(if (prefs.vibration(threadId).get()) VIBRATE_PATTERN else longArrayOf(0))

        notificationManager.notify(threadId.toInt() + 100000, notification.build())
    }

    private fun getReplyAction(threadId: Long): NotificationCompat.Action {
        val replyIntent = Intent(context, RemoteMessagingReceiver::class.java).putExtra("threadId", threadId)
        val replyPI = PendingIntent.getBroadcast(context, threadId.toInt(), replyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)

        val title = context.resources.getStringArray(R.array.notification_actions)[
                Preferences.NOTIFICATION_ACTION_REPLY]
        val responseSet = context.resources.getStringArray(R.array.qk_responses)
        val remoteInput = RemoteInput.Builder("body")
                .setLabel(title)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            remoteInput.setChoices(responseSet)
        }

        return NotificationCompat.Action.Builder(R.drawable.ic_reply_white_24dp, title, replyPI)
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                .addRemoteInput(remoteInput.build())
                .build()
    }

    /**
     * Creates a notification channel for the given conversation
     */
    override fun createNotificationChannel(threadId: Long) {

        // Only proceed if the android version supports notification channels
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val channels: List<NotificationChannel> = when (threadId) {
            0L -> listOf(
                NotificationChannel(
                    DEFAULT_CHANNEL_ID,
                    "Default",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    enableLights(true)
                    lightColor = Color.WHITE
                    enableVibration(true)
                    vibrationPattern = VIBRATE_PATTERN
                },
                NotificationChannel(
                    RECEIVING_WORKER_CHANNEL_ID,
                    context.getString(R.string.notification_foreground_worker_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    enableLights(false)
                    enableVibration(false)
                },
            )

            else -> {
                if (getNotificationChannel(threadId) != null) return
                val conversation = conversationRepo.getConversation(threadId) ?: return
                val channelId = buildNotificationChannelId(threadId)
                val title = conversation.getTitle()
                listOf(
                    NotificationChannel(channelId, title, NotificationManager.IMPORTANCE_HIGH).apply {
                        enableLights(true)
                        lightColor = Color.WHITE
                        enableVibration(true)
                        vibrationPattern = VIBRATE_PATTERN
                        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                        setSound(prefs.ringtone().get().let(Uri::parse), AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build())
                    }
                )
            }
        }

        for (channel in channels)
            notificationManager.createNotificationChannel(channel)
    }

    /**
     * Returns the notification channel for the given conversation, or null if it doesn't exist
     */
    private fun getNotificationChannel(threadId: Long): NotificationChannel? {
        val channelId = buildNotificationChannelId(threadId)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return notificationManager.notificationChannels
                    .find { channel -> channel.id == channelId }
        }

        return null
    }

    /**
     * Returns the channel id that should be used for a notification based on the threadId
     *
     * If a notification channel for the conversation exists, use the id for that. Otherwise return
     * the default channel id
     */
    private fun getChannelIdForNotification(threadId: Long): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return getNotificationChannel(threadId)?.id ?: DEFAULT_CHANNEL_ID
        }

        return DEFAULT_CHANNEL_ID
    }

    /**
     * Formats a notification channel id for a given thread id, whether the channel exists or not
     */
    override fun buildNotificationChannelId(threadId: Long): String {
        return when (threadId) {
            0L -> DEFAULT_CHANNEL_ID
            else -> "notifications_$threadId"
        }
    }

    override fun getNotificationForBackup(): NotificationCompat.Builder {
        if (Build.VERSION.SDK_INT >= 26) {
            val name = context.getString(R.string.backup_notification_channel_name)
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(BACKUP_RESTORE_CHANNEL_ID, name, importance)
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(context, BACKUP_RESTORE_CHANNEL_ID)
                .setContentTitle(context.getString(R.string.backup_restoring))
                .setShowWhen(false)
                .setWhen(System.currentTimeMillis()) // Set this anyway in case it's shown
                .setSmallIcon(R.drawable.ic_file_download_black_24dp)
                .setColor(colors.theme().theme)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setProgress(0, 0, true)
                .setOngoing(true)
    }

    override fun cancel(i: Int) {
        notificationManager.cancel(i)
    }

}
