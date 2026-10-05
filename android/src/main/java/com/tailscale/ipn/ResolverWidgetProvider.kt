// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.tailscale.ipn.ui.viewModel.ResolverPresetsState

/** 2x1 home-screen widget: tap to cycle the active resolver preset. */
class ResolverWidgetProvider : AppWidgetProvider() {
  companion object {
    const val ACTION_CYCLE = "com.tailscale.ipn.RESOLVER_WIDGET_CYCLE"
  }

  override fun onUpdate(
      context: Context,
      appWidgetManager: AppWidgetManager,
      appWidgetIds: IntArray,
  ) {
    for (id in appWidgetIds) updateWidget(context, appWidgetManager, id)
  }

  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action == ACTION_CYCLE) {
      ResolverPresetsState.cycle()
      val mgr = AppWidgetManager.getInstance(context)
      val ids =
          mgr.getAppWidgetIds(
              android.content.ComponentName(context, ResolverWidgetProvider::class.java))
      for (id in ids) updateWidget(context, mgr, id)
    }
    super.onReceive(context, intent)
  }

  private fun updateWidget(context: Context, mgr: AppWidgetManager, id: Int) {
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
