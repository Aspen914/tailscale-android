// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.App
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.model.BUILTIN_SYSTEM_ID
import com.tailscale.ipn.ui.model.BUILTIN_TAILNET_ID
import com.tailscale.ipn.ui.model.ProfileDNS
import com.tailscale.ipn.ui.model.ResolverPreset
import com.tailscale.ipn.ui.model.ResolverStore
import com.tailscale.ipn.ui.model.builtInPresets
import com.tailscale.ipn.ui.model.defaultProfileDNS
import com.tailscale.ipn.util.TSLog
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

private const val PREF_KEY_RESOLVER_PRESETS = "resolver_presets"
private const val TAG = "ResolverPresetsVM"

/**
 * Applies the currently-active resolver preset (stored in shared prefs) to the
 * live Tailscale backend. Safe to call from any lifecycle; cheap if nothing
 * changed because the Go side dedupes identical selection updates.
 */
fun pushActiveResolverPresetToLibtailscale() {
  val nm = com.tailscale.ipn.ui.notifier.Notifier.netmap.value ?: return
  val userKey = nm.SelfNode.User.toString().takeIf { it.isNotBlank() } ?: return
  val prefs = App.get().getSharedPreferences("unencrypted", Context.MODE_PRIVATE)
  val raw = prefs.getString("resolver_presets", null) ?: "{}"
  val store =
      try {
        Json { encodeDefaults = true; ignoreUnknownKeys = true }
            .decodeFromString<ResolverStore>(raw)
      } catch (_: Exception) {
        ResolverStore()
      }
  val pd = store.profiles[userKey] ?: defaultProfileDNS()
  when (pd.activeId) {
    BUILTIN_TAILNET_ID -> setBackendModeDirect("tailscale", emptyList())
    BUILTIN_SYSTEM_ID -> setBackendModeDirect("system", emptyList())
    else -> {
      val p = pd.custom.find { it.id == pd.activeId }
      val addrs = p?.resolved ?: emptyList()
      if (p == null || addrs.isEmpty()) setBackendModeDirect("tailscale", emptyList())
      else setBackendModeDirect("custom", addrs)
    }
  }
}

private fun setBackendModeDirect(mode: String, addrs: List<String>) {
  try {
    val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    App.get().getLibtailscaleApp().setResolverMode(mode, json.encodeToString(addrs))
  } catch (e: Exception) {
    TSLog.e(TAG, "setResolverMode failed: $e")
  }
}

class ResolverPresetsViewModel : ViewModel() {
  private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

  private val _store = MutableStateFlow(ResolverStore())
  val store: StateFlow<ResolverStore> = _store.asStateFlow()

  private val _saving = MutableStateFlow(false)
  val saving: StateFlow<Boolean> = _saving.asStateFlow()

  private val _probe = MutableStateFlow<ProbeResult?>(null)
  val probe: StateFlow<ProbeResult?> = _probe.asStateFlow()

  private val profileKey = MutableStateFlow<String?>(null)

  init {
    viewModelScope.launch {
      Notifier.netmap
          .filterNotNull()
          .distinctUntilChanged { o, n -> o.SelfNode.User == n.SelfNode.User }
          .collect { nm ->
            val key = nm.SelfNode.User?.toString()?.takeIf { it.isNotBlank() }
            profileKey.value = key
            loadStore()
            applyActivePresetToBackend()
          }
    }
  }

  private fun profileDns(): ProfileDNS {
    val key = profileKey.value ?: return defaultProfileDNS()
    return _store.value.profiles[key] ?: defaultProfileDNS()
  }

  fun activeId(): String = profileDns().activeId

  fun customPresets(): List<ResolverPreset> = profileDns().custom

  fun allPresets(): List<ResolverPreset> = builtInPresets() + customPresets()

  fun setActive(id: String) {
    val key = profileKey.value ?: return
    val current = profileDns()
    if (current.activeId == id) return
    updateProfile(key) { current.copy(activeId = id) }
    applyActivePresetToBackend()
  }

  fun addPreset(preset: ResolverPreset) {
    val key = profileKey.value ?: return
    val current = profileDns()
    updateProfile(key) { current.copy(custom = current.custom + preset) }
  }

  fun updatePreset(id: String, transform: (ResolverPreset) -> ResolverPreset) {
    val key = profileKey.value ?: return
    val current = profileDns()
    val updated = current.custom.map { if (it.id == id) transform(it) else it }
    updateProfile(key) { current.copy(custom = updated) }
  }

  fun deletePreset(id: String) {
    val key = profileKey.value ?: return
    val current = profileDns()
    val nextCustom = current.custom.filterNot { it.id == id }
    val nextActive = if (current.activeId == id) BUILTIN_TAILNET_ID else current.activeId
    updateProfile(key) { ProfileDNS(activeId = nextActive, custom = nextCustom) }
    if (current.activeId == id) applyActivePresetToBackend()
  }

  private fun updateProfile(key: String, block: (ProfileDNS) -> ProfileDNS) {
    val s = _store.value
    val pd = block(s.profiles[key] ?: defaultProfileDNS())
    _store.value = s.copy(profiles = s.profiles + (key to pd))
    saveStore()
  }

  private fun loadStore() {
    try {
      val prefs = App.get().getSharedPreferences("unencrypted", Context.MODE_PRIVATE)
      val raw = prefs.getString(PREF_KEY_RESOLVER_PRESETS, null)
      if (raw.isNullOrEmpty()) {
        _store.value = ResolverStore()
        return
      }
      val parsed = json.decodeFromString<ResolverStore>(raw)
      _store.value = parsed
    } catch (e: Exception) {
      TSLog.w(TAG, "Failed to load resolver store; resetting: $e")
      _store.value = ResolverStore()
      saveStore()
    }
  }

  private fun saveStore() {
    viewModelScope.launch {
      _saving.value = true
      try {
        val prefs = App.get().getSharedPreferences("unencrypted", Context.MODE_PRIVATE)
        val raw = json.encodeToString(_store.value)
        prefs.edit().putString(PREF_KEY_RESOLVER_PRESETS, raw).commit()
      } catch (e: Exception) {
        TSLog.e(TAG, "Failed to save resolver store: $e")
      } finally {
        _saving.value = false
      }
    }
  }

  fun applyActivePresetToBackend() {
    val key = profileKey.value
    val pd = if (key == null) defaultProfileDNS() else (_store.value.profiles[key] ?: defaultProfileDNS())
    when (pd.activeId) {
      BUILTIN_TAILNET_ID -> {
        setBackendMode("tailscale", emptyList())
      }
      BUILTIN_SYSTEM_ID -> {
        setBackendMode("system", emptyList())
      }
      else -> {
        val p = pd.custom.find { it.id == pd.activeId }
        val addrs = p?.resolved ?: emptyList()
        // A custom preset that has never been resolved has nothing to install:
        // fall back to the tailnet's resolvers rather than pointing the TUN at
        // no nameservers.
        if (p == null || addrs.isEmpty()) {
          setBackendMode("tailscale", emptyList())
        } else {
          setBackendMode("custom", addrs)
        }
      }
    }
  }

  private fun setBackendMode(mode: String, addrs: List<String>) {
    try {
      val jsonAddrs = json.encodeToString(addrs)
      App.get().getLibtailscaleApp().setResolverMode(mode, jsonAddrs)
    } catch (e: Exception) {
      TSLog.e(TAG, "setResolverMode failed: $e")
    }
  }

  fun probeTarget(target: String, cached: List<String>) {
    viewModelScope.launch {
      try {
        val app = App.get().getLibtailscaleApp()
        val cachedJson = json.encodeToString(cached)
        val res = app.resolveResolverAddresses(target, cachedJson, 4000)
        val addrs = json.decodeFromString<List<String>>(res)
        _probe.value = ProbeResult.Success(target, addrs)
      } catch (e: Exception) {
        _probe.value = ProbeResult.Failure(target, e.message ?: "unknown error")
      }
    }
  }

  fun clearProbe() {
    _probe.value = null
  }
}

sealed class ProbeResult {
  data class Success(val target: String, val resolved: List<String>) : ProbeResult()
  data class Failure(val target: String, val message: String) : ProbeResult()
}