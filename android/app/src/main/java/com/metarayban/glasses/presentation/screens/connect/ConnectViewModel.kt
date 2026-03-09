package com.metarayban.glasses.presentation.screens.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metarayban.glasses.data.ble.MetaGlassesBleManager
import com.metarayban.glasses.data.model.GlassesDevice
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ConnectViewModel @Inject constructor(
    private val bleManager: MetaGlassesBleManager,
) : ViewModel() {

    val connectionState = bleManager.connectionState

    private val _discoveredDevices = MutableStateFlow<List<GlassesDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<GlassesDevice>> = _discoveredDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private var scanJob: Job? = null

    fun startScan() {
        if (_isScanning.value) return

        _discoveredDevices.value = emptyList()
        _isScanning.value = true

        scanJob = viewModelScope.launch {
            bleManager.scanForGlasses()
                .catch { e ->
                    _isScanning.value = false
                }
                .collect { device ->
                    val current = _discoveredDevices.value.toMutableList()
                    val existing = current.indexOfFirst { it.address == device.address }
                    if (existing >= 0) {
                        current[existing] = device
                    } else {
                        current.add(device)
                    }
                    _discoveredDevices.value = current
                }
        }

        // Auto-stop after 15s
        viewModelScope.launch {
            kotlinx.coroutines.delay(15_000)
            stopScan()
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _isScanning.value = false
    }

    fun connectToDevice(device: GlassesDevice) {
        stopScan()
        viewModelScope.launch {
            bleManager.connect(device)
        }
    }

    override fun onCleared() {
        stopScan()
        super.onCleared()
    }
}
