package dev.zophop.di

import android.content.Context
import androidx.work.WorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.zophop.bridge.AppBackupBridge
import dev.zophop.data.MessageDao
import dev.zophop.data.SettingsRepository
import dev.zophop.feature.backup.BackupBridge
import dev.zophop.network.NtfyClient
import dev.zophop.network.OkHttpNtfyClient
import dev.zophop.network.PreCheckManager
import dev.zophop.worker.ForwardingManager
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    fun provideWorkManager(
        @ApplicationContext context: Context,
    ): WorkManager? = runCatching { WorkManager.getInstance(context) }.getOrNull()

    @Provides
    @Singleton
    fun provideNtfyClient(): NtfyClient = OkHttpNtfyClient()

    @Provides
    @Singleton
    fun providePreCheckManager(
        @ApplicationContext context: Context,
    ): PreCheckManager = PreCheckManager(context)

    @Provides
    @Singleton
    fun provideForwardingManager(
        messageDao: MessageDao,
        settingsRepository: SettingsRepository,
        ntfyClient: NtfyClient,
        workManager: WorkManager?,
        preCheckManager: PreCheckManager,
        @ApplicationContext context: Context,
    ): ForwardingManager =
        ForwardingManager(
            messageDao = messageDao,
            settingsRepository = settingsRepository,
            ntfyClient = ntfyClient,
            workManager = workManager,
            preCheckManager = preCheckManager,
            context = context,
        )

    @Provides
    @Singleton
    fun provideBackupBridge(impl: AppBackupBridge): BackupBridge = impl
}
