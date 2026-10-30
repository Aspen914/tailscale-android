// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import com.tailscale.ipn.App
import com.tailscale.ipn.UninitializedApp
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.tailscale.ipn.ui.viewModel.ResolverPresetsState

/** 2x1 home-screen widget: tap to cycle the active resolver preset. */
class ResolverWidgetProvider : AppWidgetProvider() {
  companion object {
    const val ACTION_CYCLE = "com.tailscale.ipn.RESOLVER_WIDGET_CYCLE"

    /** Refresh every placed instance of the resolver widget. */
    @JvmStatic
    fun updateAll(context: Context) {
      val mgr = AppWidgetManager.getInstance(context)
      val ids =
          mgr.getAppWidgetIds(
              android.content.ComponentName(context, ResolverWidgetProvider::class.java))
      for (id in ids) Companion.updateWidget(context, mgr, id)
    }

    @JvmStatic
    fun updateWidget(context: Context, mgr: AppWidgetManager, id: Int) {
      val views = RemoteViews(context.packageName, R.layout.widget_resolver)
      views.setTextViewText(R.id.widget_resolver_label, ResolverPresetsState.currentLabel())
      val pi =
          PendingIntent.getBroadcast(
              context,
              0,
              Intent(context, ResolverWidgetProvider::class.java).setAction(ACTION_CYCLE),
              PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
          )
      views.setOnClickPendingIntent(R.id.widget_resolver_root, pi)
      mgr.updateAppWidget(id, views)
    }
  }

  override fun onUpdate(
      context: Context,
      appWidgetManager: AppWidgetManager,
      appWidgetIds: IntArray,
  ) {
    for (id in appWidgetIds) Companion.updateWidget(context, appWidgetManager, id)
  }

  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action == ACTION_CYCLE) {
      // App process state is never reliable in a widget tap (the process is usually
      // cold), so read the last-known VPN state persisted by App's state collector.
      val vpnRunning = UninitializedApp.get().isVpnRunning()
      if (!vpnRunning) {
        // When Tailscale is down the widget doubles as a reconnect affordance.
        val uninit = UninitializedApp.get()
        val vpnPrepared =
            try {
              App.get().getAppScopedViewModel().vpnPrepared.value
            } catch (_: Exception) {
              false
            }
        if (uninit.isAbleToStartVPN() && vpnPrepared) {
          try {
            App.get().startVPN()
          } catch (_: Exception) {
            launchMainActivity(context)
          }
        } else {
          launchMainActivity(context)
        }
      } else {
        ResolverPresetsState.cycle()
      }
      val mgr = AppWidgetManager.getInstance(context)
      val ids =
          mgr.getAppWidgetIds(
              android.content.ComponentName(context, ResolverWidgetProvider::class.java))
      for (id in ids) Companion.updateWidget(context, mgr, id)
    }
    super.onReceive(context, intent)
  }

  private fun launchMainActivity(context: Context) {
    val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(launchIntent)
  }

}

