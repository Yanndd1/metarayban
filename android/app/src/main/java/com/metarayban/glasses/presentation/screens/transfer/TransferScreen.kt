package com.metarayban.glasses.presentation.screens.transfer

import androidx.compose.foundation.layout.*
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferScreen(
    viewModel: TransferViewModel = hiltViewModel(),
    onBack: () -> Unit = {},
) {
    val transferState by viewModel.transferState.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Importer les medias") },
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
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Step indicator
            StepIndicator(
                steps = listOf(
                    "BLE connecte" to (connectionState == com.metarayban.glasses.data.model.ConnectionState.CONNECTED),
                    "WiFi active" to transferState.wifiSsid.isNotEmpty(),
                    "Transfert" to transferState.isActive,
                    "Termine" to (transferState.completedFiles > 0 && !transferState.isActive),
                ),
            )

            Spacer(Modifier.height(24.dp))

            if (transferState.isActive) {
                // Transfer in progress
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "Importation en cours...",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )

                        Spacer(Modifier.height(12.dp))

                        LinearProgressIndicator(
                            progress = { transferState.fileProgressPercent },
                            modifier = Modifier.fillMaxWidth(),
                        )

                        Spacer(Modifier.height(8.dp))

                        Text(
                            text = "${transferState.completedFiles} / ${transferState.totalFiles} fichiers",
                            style = MaterialTheme.typography.bodyMedium,
                        )

                        if (transferState.currentFile.isNotEmpty()) {
                            Text(
                                text = transferState.currentFile,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        if (transferState.totalBytes > 0) {
                            val mb = transferState.bytesTransferred / (1024.0 * 1024.0)
                            val totalMb = transferState.totalBytes / (1024.0 * 1024.0)
                            Text(
                                text = "%.1f / %.1f MB".format(mb, totalMb),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            } else if (transferState.error != null) {
                // Error
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Erreur",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(text = transferState.error ?: "")
                    }
                }
            } else {
                // Ready to transfer
                Icon(
                    imageVector = Icons.Default.CloudDownload,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )

                Spacer(Modifier.height(16.dp))

                Text(
                    text = "Pret a importer",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    text = "L'import va activer le WiFi des lunettes via BLE, " +
                        "se connecter au hotspot, puis telecharger les photos et videos.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(24.dp))

                Button(
                    onClick = { viewModel.startTransfer() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = connectionState == com.metarayban.glasses.data.model.ConnectionState.CONNECTED,
                ) {
                    Icon(Icons.Default.Download, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Lancer l'import")
                }

                if (connectionState != com.metarayban.glasses.data.model.ConnectionState.CONNECTED) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Connectez d'abord les lunettes via BLE",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            // Transfer results
            if (transferState.completedFiles > 0 && !transferState.isActive) {
                Spacer(Modifier.height(24.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "${transferState.completedFiles} fichiers importes",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "Sauves dans DCIM/MetaRayBan/",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StepIndicator(steps: List<Pair<String, Boolean>>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        steps.forEachIndexed { index, (label, completed) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = if (completed) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (completed) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    },
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (completed) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    },
                )
            }
        }
    }
}
