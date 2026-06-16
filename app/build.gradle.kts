import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt.android)
    id("kotlin-kapt")
}

if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

// Load signing properties from local.properties (not committed to VCS)
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}

android {
    namespace = "com.sarmidev.imuflux"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sarmidev.imuflux"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val path = localProps.getProperty("STORE_FILE")?.trim().orEmpty()
            val storePwd = localProps.getProperty("STORE_PASSWORD")
            val alias = localProps.getProperty("KEY_ALIAS")
            val keyPwd = localProps.getProperty("KEY_PASSWORD")
            // Path relative to project root (recommended: app/imuflux-release.jks)
            if (path.isNotEmpty() && storePwd != null && alias != null && keyPwd != null) {
                val keystore = rootProject.file(path)
                if (keystore.isFile) {
                    storeFile = keystore
                    storePassword = storePwd
                    keyAlias = alias
                    keyPassword = keyPwd
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Only sign when keystore exists; otherwise APK is unsigned and device install fails.
            val releaseCfg = signingConfigs.getByName("release")
            signingConfig = signingConfigs.getByName("debug")
            if (releaseCfg.storeFile != null) {
                signingConfig = releaseCfg
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.auth)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    implementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    
    // Hilt
    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.navigation.compose)
    kapt(libs.hilt.compiler)
}