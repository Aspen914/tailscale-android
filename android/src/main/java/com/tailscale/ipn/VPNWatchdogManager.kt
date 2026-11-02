// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.tailscale.ipn.util.TSLog
import java.util.concurrent.TimeUnit

object VPNWatchdogManager {
  private const val TAG = "VPNWatchdogManager"
  private const val PERIODIC_WORK_NAME = "vpn-watchdog-periodic"
  private const val ALARM_REQUEST_CODE = 9021
  private const val ALARM_INTERVAL_MINUTES = 5L

  fun schedule(context: Context) {
    schedulePeriodicWork(context)
    scheduleAlarm(context)
  }

  fun cancel(context: Context) {
    cancelPeriodicWork(context)
    cancelAlarm(context)
  }

  private fun schedulePeriodicWork(context: Context) {
    try {
      val constraints =
          Constraints.Builder()
              .setRequiredNetworkType(NetworkType.CONNECTED)
              .build()

      val periodicWork =
          PeriodicWorkRequestBuilder<VPNWatchdogWorker>(15, TimeUnit.MINUTES)
              .setConstraints(constraints)
              .build()

      WorkManager.getInstance(context)
          .enqueueUniquePeriodicWork(
              PERIODIC_WORK_NAME,
              ExistingPeriodicWorkPolicy.UPDATE,
              periodicWork,
          )
      TSLog.d(TAG, "Scheduled periodic VPN watchdog work")
    } catch (e: Exception) {
      TSLog.e(TAG, "Failed to schedule periodic watchdog: $e")
    }
  }

  private fun cancelPeriodicWork(context: Context) {
    try {
      WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
      TSLog.d(TAG, "Cancelled periodic VPN watchdog work")
    } catch (e: Exception) {
      TSLog.e(TAG, "Failed to cancel periodic watchdog: $e")
    }
  }

  fun scheduleAlarm(context: Context) {
    try {
      val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
      val intent =
          Intent(context, VPNWatchdogReceiver::class.java).apply {
            action = VPNWatchdogReceiver.ACTION_CHECK_VPN
          }
      val pendingIntent =
          PendingIntent.getBroadcast(
              context,
              ALARM_REQUEST_CODE,
              intent,
              PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
          )

      val triggerAtMillis =
          SystemClock.elapsedRealtime() + TimeUnit.MINUTES.toMillis(ALARM_INTERVAL_MINUTES)
      alarmManager.setAndAllowWhileIdle(
          AlarmManager.ELAPSED_REALTIME_WAKEUP,
          triggerAtMillis,
          pendingIntent,
      )
      TSLog.d(TAG, "Scheduled alarm watchdog in $ALARM_INTERVAL_MINUTES minutes")
    } catch (e: Exception) {
      TSLog.e(TAG, "Failed to schedule alarm watchdog: $e")
    }
  }

  fun cancelAlarm(context: Context) {
    try {
      val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
      val intent =
          Intent(context, VPNWatchdogReceiver::class.java).apply {
            action = VPNWatchdogReceiver.ACTION_CHECK_VPN
          }
      val pendingIntent =
          PendingIntent.getBroadcast(
              context,
              ALARM_REQUEST_CODE,
              intent,
              PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
          )
      alarmManager.cancel(pendingIntent)
      TSLog.d(TAG, "Cancelled alarm watchdog")
    } catch (e: Exception) {
      TSLog.e(TAG, "Failed to cancel alarm watchdog: $e")
    }
  }
}
