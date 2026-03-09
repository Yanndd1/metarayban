package com.metarayban.glasses.presentation.screens.connect

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.metarayban.glasses.data.model.ConnectionState
import com.metarayban.glasses.data.model.GlassesDevice

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(
    viewModel: ConnectViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val discoveredDevices by viewModel.discoveredDevices.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connexion BLE") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour")
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            // Scan button
            Button(
                onClick = {
                    if (isScanning) viewModel.stopScan() else viewModel.startScan()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = if (isScanning) Icons.Default.Stop else Icons.Default.Search,
                    contentDescription = null,
                )
                Spacer(Modifier.width(8.dp))
                Text(if (isScanning) "Arreter le scan" else "Scanner")
            }

            if (isScanning) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Spacer(Modifier.height(16.dp))

            // Status
            if (connectionState != ConnectionState.DISCONNECTED &&
                connectionState != ConnectionState.SCANNING
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = when (connectionState) {
                            ConnectionState.CONNECTED -> MaterialTheme.colorScheme.primaryContainer
                            ConnectionState.ERROR -> MaterialTheme.colorScheme.errorContainer
                            else -> MaterialTheme.colorScheme.secondaryContainer
                        }
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        when (connectionState) {
                            ConnectionState.CONNECTING,
                            ConnectionState.BONDING,
                            ConnectionState.AUTHENTICATING -> {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    when (connectionState) {
                                        ConnectionState.CONNECTING -> "Connexion en cours..."
                                        ConnectionState.BONDING -> "Appairage en cours..."
                                        ConnectionState.AUTHENTICATING -> "Authentification..."
                                        else -> ""
                                    }
                                )
                            }
                            ConnectionState.CONNECTED -> {
                                Icon(Icons.Default.CheckCircle, null)
                                Spacer(Modifier.width(12.dp))
                                Text("Connecte!", fontWeight = FontWeight.Bold)
                            }
                            ConnectionState.ERROR -> {
                                Icon(Icons.Default.Error, null)
                                Spacer(Modifier.width(12.dp))
                                Text("Erreur de connexion")
                            }
                            else -> {}
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            // Discovered devices
            Text(
                text = "Appareils detectes (${discoveredDevices.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))

            if (discoveredDevices.isEmpty() && !isScanning) {
                Text(
                    text = "Aucun appareil trouve. Assurez-vous que les lunettes sont allumees et a proximite.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            LazyColumn {
                items(discoveredDevices) { device ->
                    DeviceCard(
                        device = device,
                        onConnect = { viewModel.connectToDevice(device) },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(
    device: GlassesDevice,
    onConnect: () -> Unit,
) {
    Card(
        onClick = onConnect,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Bluetooth,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "${device.address}  |  RSSI: ${device.rssi} dBm",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalButton(onClick = onConnect) {
                Text("Connecter")
            }
        }
    }
}
