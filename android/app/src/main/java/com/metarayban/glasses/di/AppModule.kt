package com.metarayban.glasses.di

import android.content.Context
import com.metarayban.glasses.data.ble.MetaGlassesBleManager
import com.metarayban.glasses.data.wifi.GlassesWifiManager
import com.metarayban.glasses.data.wifi.MediaTransferClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideBleManager(@ApplicationContext context: Context): MetaGlassesBleManager {
        return MetaGlassesBleManager(context)
    }

    @Provides
    @Singleton
    fun provideWifiManager(@ApplicationContext context: Context): GlassesWifiManager {
        return GlassesWifiManager(context)
    }

    @Provides
    @Singleton
    fun provideMediaTransferClient(@ApplicationContext context: Context): MediaTransferClient {
        return MediaTransferClient(context)
    }
}
