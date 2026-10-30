// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.model

import kotlinx.serialization.Serializable
import java.util.UUID

const val BUILTIN_TAILNET_ID = "builtin:tailnet"
const val BUILTIN_SYSTEM_ID = "builtin:system"

@Serializable
data class ResolverPreset(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val target: String,
    val resolved: List<String> = emptyList()
)

@Serializable
data class ProfileDNS(
    val activeId: String = BUILTIN_TAILNET_ID,
    val custom: List<ResolverPreset> = emptyList()
)

@Serializable
data class ResolverStore(val profiles: Map<String, ProfileDNS> = emptyMap())

fun defaultProfileDNS(): ProfileDNS = ProfileDNS(activeId = BUILTIN_TAILNET_ID, custom = emptyList())

fun builtInPresets(): List<ResolverPreset> = listOf(
    ResolverPreset(id = BUILTIN_TAILNET_ID, label = "Use Tailscale DNS settings", target = "", resolved = emptyList()),
    ResolverPreset(id = BUILTIN_SYSTEM_ID, label = "Use system resolver", target = "", resolved = emptyList())
)