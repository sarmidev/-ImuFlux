import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    jvm()

    sourceSets {
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(compose.material3)
            implementation(project(":backofficeCore"))
            implementation(libs.kotlinx.coroutines.swing)
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.sarmidev.imuflux.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "ImuFluxDiagnostics"
            packageVersion = "1.0.0"
        }
    }
}

// PlatformFirebaseConfigLoader (JVM) resolves config files with paths relative to the
// root project dir ("desktopApp/local.properties", "app/google-services.json").
// Without this, the Gradle run task sets CWD to the subproject dir and the
// relative paths resolve one level too deep, causing "config missing" errors.
tasks.withType<JavaExec>().configureEach {
    workingDir = rootProject.projectDir
}
