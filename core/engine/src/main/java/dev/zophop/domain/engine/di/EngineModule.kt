package dev.zophop.domain.engine.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.zophop.data.CredentialRepository
import dev.zophop.domain.engine.NodeExecutorFactory
import dev.zophop.domain.engine.PreconditionEvaluator
import dev.zophop.domain.engine.WorkflowEngine
import dev.zophop.domain.engine.plugin.ExternalPluginRegistry
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object EngineModule {
    @Provides
    @Singleton
    fun providePreconditionEvaluator(
        @ApplicationContext context: Context,
        credentialRepository: CredentialRepository,
    ): PreconditionEvaluator =
        PreconditionEvaluator(
            context = context,
            credentialRepository = credentialRepository,
        )

    @Provides
    @Singleton
    fun provideExternalPluginRegistry(
        @ApplicationContext context: Context,
    ): ExternalPluginRegistry = ExternalPluginRegistry(context)

    @Provides
    @Singleton
    fun provideNodeExecutorFactory(
        credentialRepository: CredentialRepository,
        externalPluginRegistry: ExternalPluginRegistry,
    ): NodeExecutorFactory =
        NodeExecutorFactory(
            credentialRepository = credentialRepository,
            pluginRegistry = externalPluginRegistry,
        )

    @Provides
    @Singleton
    fun provideWorkflowEngine(
        preconditionEvaluator: PreconditionEvaluator,
        credentialRepository: CredentialRepository,
        executorFactory: NodeExecutorFactory,
    ): WorkflowEngine =
        WorkflowEngine(
            preconditionEvaluator = preconditionEvaluator,
            credentialRepository = credentialRepository,
            executorFactory = executorFactory,
        )
}
