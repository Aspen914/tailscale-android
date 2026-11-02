// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.tailscale.ipn.util.TSLog

class VPNWatchdogReceiver : BroadcastReceiver() {
  companion object {
    const val ACTION_CHECK_VPN = "com.tailscale.ipn.ACTION_CHECK_VPN"
    private const val TAG = "VPNWatchdogReceiver"
  }

  override fun onReceive(context: Context, intent: Intent?) {
    if (intent?.action != ACTION_CHECK_VPN) return

    val app = UninitializedApp.get()
    if (app.isVpnRunning() && app.isAbleToStartVPN()) {
      if (!IPNService.isRunning) {
        TSLog.w(TAG, "Watchdog alarm detected dead IPNService. Reviving VPN...")
        val req =
            OneTimeWorkRequest.Builder(StartVPNWorker::class.java)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag("ipn-connect-vpn")
                .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork("ipn-connect-vpn", ExistingWorkPolicy.REPLACE, req)
      }
      VPNWatchdogManager.scheduleAlarm(context)
    } else {
      VPNWatchdogManager.cancel(context)
    }
  }
}
