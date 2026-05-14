package com.sarmidev.imuflux.di

import com.sarmidev.imuflux.data.repository.ExportRepositoryImpl
import com.sarmidev.imuflux.data.repository.MovementRepositoryImpl
import com.sarmidev.imuflux.data.repository.SensorRepositoryImpl
import com.sarmidev.imuflux.data.repository.AnalysisRemoteRepositoryImpl
import com.sarmidev.imuflux.domain.repository.AnalysisRemoteRepository
import com.sarmidev.imuflux.domain.repository.ExportRepository
import com.sarmidev.imuflux.domain.repository.MovementRepository
import com.sarmidev.imuflux.domain.repository.SensorRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    @Singleton
    abstract fun bindSensorRepository(
        sensorRepositoryImpl: SensorRepositoryImpl
    ): SensorRepository

    @Binds
    @Singleton
    abstract fun bindMovementRepository(
        movementRepositoryImpl: MovementRepositoryImpl
    ): MovementRepository

    @Binds
    @Singleton
    abstract fun bindExportRepository(
        exportRepositoryImpl: ExportRepositoryImpl
    ): ExportRepository

    @Binds
    @Singleton
    abstract fun bindAnalysisRemoteRepository(
        analysisRemoteRepositoryImpl: AnalysisRemoteRepositoryImpl
    ): AnalysisRemoteRepository
}