package com.example.scantest.di

import com.example.scantest.data.repository.ExportRepositoryImpl
import com.example.scantest.data.repository.MovementRepositoryImpl
import com.example.scantest.data.repository.SensorRepositoryImpl
import com.example.scantest.data.repository.AnalysisRemoteRepositoryImpl
import com.example.scantest.domain.repository.AnalysisRemoteRepository
import com.example.scantest.domain.repository.ExportRepository
import com.example.scantest.domain.repository.MovementRepository
import com.example.scantest.domain.repository.SensorRepository
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