package com.sarmidev.imuflux.di

import com.sarmidev.imuflux.data.diagnostics.DiagnosticsConfig
import com.sarmidev.imuflux.data.diagnostics.DiagnosticsTelemetryRepository
import com.sarmidev.imuflux.data.diagnostics.FirestoreDiagnosticsTelemetryRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DiagnosticsModule {

    @Binds
    @Singleton
    abstract fun bindDiagnosticsTelemetryRepository(
        impl: FirestoreDiagnosticsTelemetryRepository,
    ): DiagnosticsTelemetryRepository

    companion object {
        /** Single, centralized source of diagnostics thresholds. */
        @Provides
        @Singleton
        fun provideDiagnosticsConfig(): DiagnosticsConfig = DiagnosticsConfig.DEFAULT
    }
}
