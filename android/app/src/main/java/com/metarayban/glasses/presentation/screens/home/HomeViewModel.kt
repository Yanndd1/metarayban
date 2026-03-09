package com.metarayban.glasses.presentation.screens.home

import androidx.lifecycle.ViewModel
import com.metarayban.glasses.data.ble.MetaGlassesBleManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val bleManager: MetaGlassesBleManager,
) : ViewModel() {

    val connectionState = bleManager.connectionState
    val glassesInfo = bleManager.glassesInfo
}
