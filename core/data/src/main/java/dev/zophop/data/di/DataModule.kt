package dev.zophop.data.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.zophop.data.AppDatabase
import dev.zophop.data.CredentialDao
import dev.zophop.data.CredentialRepository
import dev.zophop.data.DefaultWorkflowInitializer
import dev.zophop.data.EncryptedSettingsRepository
import dev.zophop.data.MessageDao
import dev.zophop.data.PreconditionDao
import dev.zophop.data.PreconditionRepository
import dev.zophop.data.RoomCredentialRepository
import dev.zophop.data.RoomPreconditionRepository
import dev.zophop.data.RoomWorkflowRepository
import dev.zophop.data.SettingsRepository
import dev.zophop.data.WorkflowDao
import dev.zophop.data.WorkflowRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context,
    ): AppDatabase = AppDatabase.getInstance(context)

    @Provides
    fun provideMessageDao(database: AppDatabase): MessageDao = database.messageDao()

    @Provides
    fun provideWorkflowDao(database: AppDatabase): WorkflowDao = database.workflowDao()

    @Provides
    fun provideCredentialDao(database: AppDatabase): CredentialDao = database.credentialDao()

    @Provides
    fun providePreconditionDao(database: AppDatabase): PreconditionDao = database.preconditionDao()

    @Provides
    @Singleton
    fun provideEncryptedSettingsRepository(
        @ApplicationContext context: Context,
    ): EncryptedSettingsRepository = EncryptedSettingsRepository(context)

    @Provides
    @Singleton
    fun provideSettingsRepository(impl: EncryptedSettingsRepository): SettingsRepository = impl

    @Provides
    @Singleton
    fun provideWorkflowRepository(
        workflowDao: WorkflowDao,
        preconditionDao: PreconditionDao,
    ): WorkflowRepository = RoomWorkflowRepository(workflowDao, preconditionDao)

    @Provides
    @Singleton
    fun provideCredentialRepository(credentialDao: CredentialDao): CredentialRepository =
        RoomCredentialRepository(credentialDao)

    @Provides
    @Singleton
    fun providePreconditionRepository(preconditionDao: PreconditionDao): PreconditionRepository =
        RoomPreconditionRepository(preconditionDao)

    @Provides
    @Singleton
    fun provideDefaultWorkflowInitializer(workflowRepository: WorkflowRepository): DefaultWorkflowInitializer =
        DefaultWorkflowInitializer(workflowRepository)
}
