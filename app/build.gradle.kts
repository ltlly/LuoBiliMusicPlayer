import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.bilimusicplayer"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.bilimusicplayer"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 12
        versionName = "1.5.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    val localSigning = Properties().apply {
        val file = rootProject.file("keystore/signing.properties")
        if (file.isFile) file.inputStream().use { load(it) }
    }
    val store = rootProject.file("keystore/bilimusic-release.keystore")
    val storePass = providers.environmentVariable("BILI_STORE_PASSWORD").orNull ?: localSigning.getProperty("storePassword")
    val alias = providers.environmentVariable("BILI_KEY_ALIAS").orNull ?: localSigning.getProperty("keyAlias")
    val fixedSigning = if (store.isFile && storePass != null && alias != null) {
        signingConfigs.create("fixed") {
            storeFile = store
            storePassword = storePass
            keyAlias = alias
            keyPassword = providers.environmentVariable("BILI_KEY_PASSWORD").orNull ?: localSigning.getProperty("keyPassword") ?: storePass
        }
    } else null

    buildTypes {
        debug {
            fixedSigning?.let { signingConfig = it }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = fixedSigning
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":shared"))

    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.window)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.datasource)
    implementation(libs.androidx.media3.database)

    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.okio)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.12.1")
    androidTestImplementation(libs.compose.material3)
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.12.1")
    debugImplementation(libs.compose.ui.tooling)
}