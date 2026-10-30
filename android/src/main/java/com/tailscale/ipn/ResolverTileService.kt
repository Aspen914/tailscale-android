// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.tailscale.ipn.ui.viewModel.ResolverPresetsState

/**
 * Quick Settings tile that cycles the active resolver preset. Shows the current
 * mode as the tile subtitle.
 */
class ResolverTileService : TileService() {
  private var currentTile: Tile? = null

  override fun onStartListening() {
    super.onStartListening()
    currentTile = getQsTile()
    refresh()
  }

  override fun onStopListening() {
    super.onStopListening()
    currentTile = null
  }

  override fun onClick() {
    unlockAndRun {
      ResolverPresetsState.cycle()
      refresh()
    }
  }

  private fun refresh() {
    val t = currentTile ?: return
    t.label = getString(R.string.resolver_tile_label)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      t.subtitle = ResolverPresetsState.currentLabel()
    }
    t.state = Tile.STATE_ACTIVE
    t.updateTile()
  }
}
