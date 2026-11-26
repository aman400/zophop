package dev.zophop.feature.backup.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.zophop.feature.backup.BackupService
import dev.zophop.feature.backup.DefaultBackupService
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
interface BackupModule {
    @Binds
    @Singleton
    fun bindBackupService(impl: DefaultBackupService): BackupService
}
