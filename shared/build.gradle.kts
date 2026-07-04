import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
        testRuns["test"].executionTask.configure {
            useJUnit()
        }
    }

    sourceSets {
        commonMain.dependencies {
            // Pure Kotlin logic only — no Android/Firebase deps in shared.
            // `suspend` is a language feature; kotlinx-coroutines not needed at runtime here.
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            // runBlocking (DiagnosticsToleranceTest) lives on JVM/Android only,
            // both of which are our targets so coroutines-core is safe in commonTest.
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}

android {
    namespace = "com.sarmidev.imuflux.shared"
    compileSdk = 36
    defaultConfig {
        minSdk = 24
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
