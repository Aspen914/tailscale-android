// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.ui.model.BUILTIN_SYSTEM_ID
import com.tailscale.ipn.ui.model.BUILTIN_TAILNET_ID
import com.tailscale.ipn.ui.model.ResolverPreset
import com.tailscale.ipn.ui.model.builtInPresets
import com.tailscale.ipn.ui.viewModel.ProbeResult
import com.tailscale.ipn.ui.viewModel.ResolverPresetsViewModel
import java.util.UUID

@Composable
fun ResolverPresetsView(viewModel: ResolverPresetsViewModel = viewModel()) {
  val store by viewModel.store.collectAsState()
  val saving by viewModel.saving.collectAsState()
  val probe by viewModel.probe.collectAsState()
  var editorOpen by remember { mutableStateOf(false) }
  var editing by remember { mutableStateOf<ResolverPreset?>(null) }
  val key = remember(store) { "" }
  val profileKeyState = remember { mutableStateOf<String?>(null) }
  val activeId = viewModel.activeId()
  val custom = viewModel.customPresets()
  val all = builtInPresets() + custom

  Scaffold(
      floatingActionButton = {
        FloatingActionButton(onClick = { editing = null; editorOpen = true }) {
          Icon(Icons.Default.Add, contentDescription = "Add preset")
        }
      }) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp)) {
          Text("Resolver presets", style = MaterialTheme.typography.titleLarge)
          Text(
              "Control which resolver answers non-MagicDNS queries for this profile.",
              style = MaterialTheme.typography.bodyMedium,
              modifier = Modifier.padding(bottom = 16.dp))
          LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(all) { p ->
              Card(
                  modifier =
                      Modifier.fillMaxWidth().clickable { viewModel.setActive(p.id) },
                  colors =
                      if (p.id == activeId)
                          CardDefaults.cardColors(
                              containerColor = MaterialTheme.colorScheme.primaryContainer)
                      else CardDefaults.cardColors()) {
                    Row(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                      Column {
                        Text(p.label, fontWeight = FontWeight.Medium)
                        if (p.target.isNotEmpty()) {
                          Text(p.target, style = MaterialTheme.typography.bodySmall)
                        } else {
                          Text(
                              if (p.id == BUILTIN_TAILNET_ID) "Use Quad-100 as provided"
                              else "Do not hand Android DNS",
                              style = MaterialTheme.typography.bodySmall)
                        }
                        if (p.resolved.isNotEmpty()) {
                          Text(
                              p.resolved.joinToString(", "),
                              style = MaterialTheme.typography.bodySmall)
                        }
                      }
                      Row {
                        if (p.id != BUILTIN_TAILNET_ID && p.id != BUILTIN_SYSTEM_ID) {
                          IconButton(onClick = { viewModel.deletePreset(p.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete")
                          }
                          IconButton(onClick = { editing = p; editorOpen = true }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Edit")
                          }
                        }
                      }
                    }
                  }
            }
          }
        }
      }

  if (editorOpen) {
    PresetEditor(
        initial = editing,
        onDismiss = { editorOpen = false; viewModel.clearProbe() },
        onSave = { label, target, resolved ->
          if (editing == null) {
            viewModel.addPreset(
                ResolverPreset(id = UUID.randomUUID().toString(), label = label, target = target, resolved = resolved))
          } else {
            viewModel.updatePreset(editing!!.id) { it.copy(label = label, target = target, resolved = resolved) }
          }
          editorOpen = false
          viewModel.clearProbe()
        },
        onProbe = { target, cached -> viewModel.probeTarget(target, cached) },
        probe = probe)
  }
}

@Composable
fun PresetEditor(
    initial: ResolverPreset?,
    onDismiss: () -> Unit,
    onSave: (String, String, List<String>) -> Unit,
    onProbe: (String, List<String>) -> Unit,
    probe: ProbeResult?
) {
  var label by remember { mutableStateOf(initial?.label ?: "") }
  var target by remember { mutableStateOf(initial?.target ?: "") }
  var resolved by remember { mutableStateOf(initial?.resolved ?: emptyList()) }
  var testResult by remember { mutableStateOf("") }

  LaunchedEffect(probe) {
    when (probe) {
      is ProbeResult.Success -> {
        resolved = probe.resolved
        testResult = "Resolved: " + probe.resolved.joinToString(", ")
      }
      is ProbeResult.Failure -> {
        testResult = "Failed: " + probe.message
      }
      null -> testResult = ""
    }
  }

  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text(if (initial == null) "Add preset" else "Edit preset") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
          OutlinedTextField(
              value = label,
              onValueChange = { label = it },
              label = { Text("Label") },
              singleLine = true,
              modifier = Modifier.fillMaxWidth())
          OutlinedTextField(
              value = target,
              onValueChange = { target = it },
              label = { Text("Target (IP, short name, or FQDN)") },
              singleLine = true,
              modifier = Modifier.fillMaxWidth())
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onProbe(target, resolved) }) { Text("Test") }
            if (testResult.isNotEmpty()) {
              Text(testResult, style = MaterialTheme.typography.bodySmall)
            }
          }
          if (resolved.isNotEmpty()) {
            Text("Resolved: ${resolved.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
          }
        }
      },
      confirmButton = {
        TextButton(onClick = { onSave(label, target, resolved) }, enabled = label.isNotBlank() && target.isNotBlank()) {
          Text("Save")
        }
      },
      dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}