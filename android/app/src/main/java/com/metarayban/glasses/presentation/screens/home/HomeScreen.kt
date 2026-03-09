package com.metarayban.glasses.presentation.screens.home

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.metarayban.glasses.data.model.ConnectionState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
    onNavigateToConnect: () -> Unit = {},
    onNavigateToTransfer: () -> Unit = {},
    onNavigateToGallery: () -> Unit = {},
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val glassesInfo by viewModel.glassesInfo.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MetaRayBan") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Connection status card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = when (connectionState) {
                        ConnectionState.CONNECTED -> MaterialTheme.colorScheme.primaryContainer
                        ConnectionState.ERROR -> MaterialTheme.colorScheme.errorContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = when (connectionState) {
                            ConnectionState.CONNECTED -> Icons.Default.BluetoothConnected
                            ConnectionState.SCANNING,
                            ConnectionState.CONNECTING,
                            ConnectionState.BONDING -> Icons.Default.BluetoothSearching
                            ConnectionState.ERROR -> Icons.Default.BluetoothDisabled
                            else -> Icons.Default.Bluetooth
                        },
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                    )

                    Spacer(Modifier.height(8.dp))

                    Text(
                        text = when (connectionState) {
                            ConnectionState.DISCONNECTED -> "Non connecte"
                            ConnectionState.SCANNING -> "Recherche..."
                            ConnectionState.CONNECTING -> "Connexion..."
                            ConnectionState.BONDING -> "Appairage..."
                            ConnectionState.AUTHENTICATING -> "Authentification..."
                            ConnectionState.CONNECTED -> glassesInfo.deviceName.ifEmpty { "Connecte" }
                            ConnectionState.ERROR -> "Erreur"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )

                    if (connectionState == ConnectionState.CONNECTED && glassesInfo.firmwareVersion.isNotEmpty()) {
                        Text(
                            text = "FW: ${glassesInfo.firmwareVersion}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // Action buttons
            ActionButton(
                icon = Icons.Default.BluetoothSearching,
                title = "Connecter",
                subtitle = "Scanner et appairer les lunettes",
                onClick = onNavigateToConnect,
            )

            Spacer(Modifier.height(12.dp))

            ActionButton(
                icon = Icons.Default.CloudDownload,
                title = "Importer",
                subtitle = "Recuperer photos et videos",
                onClick = onNavigateToTransfer,
                enabled = connectionState == ConnectionState.CONNECTED,
            )

            Spacer(Modifier.height(12.dp))

            ActionButton(
                icon = Icons.Default.PhotoLibrary,
                title = "Galerie",
                subtitle = "Photos et videos importees",
                onClick = onNavigateToGallery,
            )
        }
    }
}

@Composable
private fun ActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                },
            )
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
            )
        }
    }
}
