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

class BootReceiver : BroadcastReceiver() {
  companion object {
    private const val TAG = "BootReceiver"
  }

  override fun onReceive(context: Context, intent: Intent?) {
    val action = intent?.action
    TSLog.d(TAG, "Received broadcast: $action")
    val app = UninitializedApp.get()
    if (app.isVpnRunning() && app.isAbleToStartVPN()) {
      TSLog.d(TAG, "VPN marked running on boot/update ($action). Restoring VPN tunnel...")
      val req =
          OneTimeWorkRequest.Builder(StartVPNWorker::class.java)
              .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
              .addTag("ipn-connect-vpn")
              .build()
      WorkManager.getInstance(context)
          .enqueueUniqueWork("ipn-connect-vpn", ExistingWorkPolicy.REPLACE, req)
      VPNWatchdogManager.schedule(context)
    }
  }
}
