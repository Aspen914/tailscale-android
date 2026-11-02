// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tailscale.ipn.util.TSLog

class VPNWatchdogWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

  private val TAG = "VPNWatchdogWorker"

  override suspend fun doWork(): Result {
    val app = UninitializedApp.get()
    if (app.isVpnRunning() && app.isAbleToStartVPN()) {
      if (!IPNService.isRunning) {
        TSLog.w(TAG, "Watchdog found VPN marked running but IPNService is dead. Reviving VPN...")
        val req =
            OneTimeWorkRequest.Builder(StartVPNWorker::class.java)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag("ipn-connect-vpn")
                .build()
        WorkManager.getInstance(applicationContext)
            .enqueueUniqueWork("ipn-connect-vpn", ExistingWorkPolicy.REPLACE, req)
      } else {
        TSLog.d(TAG, "Watchdog verified IPNService is alive.")
      }
    } else {
      TSLog.d(TAG, "VPN is not marked as running, cancelling watchdog.")
      VPNWatchdogManager.cancel(applicationContext)
    }
    return Result.success()
  }
}
