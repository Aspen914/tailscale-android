// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import android.content.Context
import com.tailscale.ipn.App
import com.tailscale.ipn.ResolverWidgetProvider
import com.tailscale.ipn.ui.model.BUILTIN_SYSTEM_ID
import com.tailscale.ipn.ui.model.BUILTIN_TAILNET_ID
import com.tailscale.ipn.ui.model.ResolverStore
import com.tailscale.ipn.ui.model.defaultProfileDNS
import com.tailscale.ipn.ui.notifier.Notifier
import kotlinx.serialization.json.Json

/**
 * Read/write access to the stored resolver preset selection, independent of any
 * ViewModel lifecycle. Used by the QS tile, home-screen widget, and deep-link
 * actions so all three treat the current preset the same way.
 */
object ResolverPresetsState {
  private const val PREF_KEY = "resolver_presets"
  private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

  private fun currentProfileKey(): String? {
    val user = Notifier.netmap.value?.SelfNode?.User?.toString()
    return user?.takeIf { it.isNotBlank() } ?: App.get().lastProfileUser()
  }

  fun loadStore(): ResolverStore {
    return try {
      val raw =
          App.get()
              .getSharedPreferences("unencrypted", Context.MODE_PRIVATE)
              .getString(PREF_KEY, null)
      if (raw.isNullOrEmpty()) ResolverStore()
      else json.decodeFromString<ResolverStore>(raw)
    } catch (_: Exception) {
      ResolverStore()
    }
  }

  private fun saveStore(s: ResolverStore) {
    App.get()
        .getSharedPreferences("unencrypted", Context.MODE_PRIVATE)
        .edit()
        .putString(PREF_KEY, json.encodeToString(ResolverStore.serializer(), s))
        .apply()
  }

  fun currentLabel(): String {
    val key = currentProfileKey() ?: return "Tailnet DNS"
    val pd = loadStore().profiles[key] ?: defaultProfileDNS()
    return when (pd.activeId) {
      BUILTIN_TAILNET_ID -> "Tailnet DNS"
      BUILTIN_SYSTEM_ID -> "System"
      else -> pd.custom.find { it.id == pd.activeId }?.label ?: "Custom"
    }
  }

  fun setActive(id: String) {
    val key = currentProfileKey() ?: return
    val s = loadStore()
    val pd = s.profiles[key] ?: defaultProfileDNS()
    saveStore(s.copy(profiles = s.profiles + (key to pd.copy(activeId = id))))
    pushActiveResolverPresetToLibtailscale()
    refreshWidgets()
    try {
      App.get().updateStatusNotification()
    } catch (_: Exception) {
    }
  }

  /** Ask the launcher to re-render every placed resolver widget. */
  @JvmStatic
  fun refreshWidgets() {
    try {
      ResolverWidgetProvider.updateAll(App.get())
    } catch (_: Exception) {
    }
  }

  /** Alias kept for callers that prefer an "update" verb. */
  @JvmStatic
  fun updateWidgets() = refreshWidgets()

  /** Alias matching the AppWidgetProvider's entry point. */
  @JvmStatic
  fun updateAllWidgets() = refreshWidgets()

  /** Cycles through built-in presets and then each custom preset, in definition order. */
  fun cycle() {
    val key = currentProfileKey() ?: return
    val pd = loadStore().profiles[key] ?: defaultProfileDNS()
    val ids =
        listOf(BUILTIN_TAILNET_ID, BUILTIN_SYSTEM_ID) + pd.custom.map { it.id }
    val next =
        if (ids.isEmpty()) BUILTIN_TAILNET_ID
        else ids[(ids.indexOf(pd.activeId).takeIf { it >= 0 }?.plus(1) ?: 0) % ids.size]
    setActive(next)
  }
}
