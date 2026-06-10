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
package dev.octoshrimpy.quik.receiver

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.getSystemService
import dev.octoshrimpy.quik.R

/**
 * Handles the "Copy" tap in the OTP expanded notification banner.
 *
 * Extras expected in the incoming [Intent]:
 *  - [EXTRA_OTP_CODE]  (String) — the extracted OTP digits, e.g. "847291"
 *  - [EXTRA_THREAD_ID] (Long)   — the notification ID so we can dismiss it
 *
 * No Dagger injection needed — only system services are used.
 * Lives in the presentation module so it can reference presentation string resources.
 */
class CopyOtpReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val otpCode  = intent.getStringExtra(EXTRA_OTP_CODE) ?: return
        val threadId = intent.getLongExtra(EXTRA_THREAD_ID, -1L)

        // Copy to clipboard
        val clipboard = context.getSystemService<ClipboardManager>() ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("OTP", otpCode))

        // Brief toast feedback
        Toast.makeText(
            context,
            context.getString(R.string.notif_otp_copied),
            Toast.LENGTH_SHORT
        ).show()

        // Dismiss the notification
        if (threadId != -1L) {
            context.getSystemService<NotificationManager>()?.cancel(threadId.toInt())
        }
    }

    companion object {
        const val EXTRA_OTP_CODE  = "otpCode"
        const val EXTRA_THREAD_ID = "threadId"
    }
}

